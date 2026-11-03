#!/usr/bin/env python3
"""Offline Android ART v1/v2/v3 method-trace attribution (including --sampling).

Android 11 primary format/writer:
https://android.googlesource.com/platform/art/+/refs/heads/android11-release/runtime/trace.cc
CompareAndUpdateStackTrace emits stack DIFFERENCES at each changed sample, not one
record per sample. Reconstruct stacks and charge the interval preceding each
change to the previous stack. Wall attribution includes blocking/preemption;
thread-CPU attribution uses the actual per-thread CPU clock, but method ownership
is still sample-derived, not exact instrumented execution time. JNI leaves do not
reveal C/C++ callees. ART sampling itself suspends threads and perturbs timing.
"""
from __future__ import annotations

import argparse
import csv
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import re
import struct


PRIMITIVES = dict(zip("VZBCSIJFD", ("void", "boolean", "byte", "char", "short", "int", "long", "float", "double")))
METHOD_LINE = re.compile(r"^(?:(\d+):(\d+):)?(\S+)\s+(.+?)\((.*?)\)(?::\d+(?::\d+)?)?$")


def descriptor_types(signature: str, classes: dict[str, str]) -> tuple[tuple[str, ...], str]:
    """Resolve a JVM method descriptor to original Java parameter/return types."""
    def one(index: int) -> tuple[str, int]:
        dimensions = 0
        while signature[index] == "[":
            dimensions += 1
            index += 1
        if signature[index] == "L":
            end = signature.index(";", index)
            name = signature[index + 1:end].replace("/", ".")
            value, index = classes.get(name, name), end + 1
        else:
            value, index = PRIMITIVES[signature[index]], index + 1
        return value + "[]" * dimensions, index
    if not signature.startswith("("):
        raise ValueError("Not a JVM method descriptor")
    args, index = [], 1
    while signature[index] != ")":
        value, index = one(index)
        args.append(value)
    result, index = one(index + 1)
    if index != len(signature):
        raise ValueError("Trailing descriptor bytes")
    return tuple(args), result


class Mapping:
    """Descriptor-aware R8/ProGuard labels; no PC/line means inlining stays ambiguous."""
    def __init__(self, path: Path | None):
        self.classes: dict[str, str] = {}
        self.methods: dict[tuple[str, str], list[dict]] = defaultdict(list)
        self.sha256 = None
        if path is None:
            return
        data = path.read_bytes()
        self.sha256 = hashlib.sha256(data).hexdigest()
        owner = original_owner = None
        previous = None
        for raw in data.decode("utf-8").splitlines():
            if raw and not raw.startswith((" ", "#")) and " -> " in raw and raw.endswith(":"):
                original_owner, owner = raw[:-1].split(" -> ", 1)
                self.classes[owner] = original_owner
                previous = None
            elif raw.lstrip().startswith("#"):
                comment = raw.lstrip()[1:].strip()
                if previous is not None and comment.startswith("{"):
                    info = json.loads(comment)
                    if info.get("id") == "com.android.tools.r8.residualsignature":
                        previous["residual"] = info.get("signature")
            elif owner and " -> " in raw:
                left, obfuscated = raw.strip().rsplit(" -> ", 1)
                match = METHOD_LINE.fullmatch(left)
                previous = None
                if match:
                    result, method, params = match.group(3, 4, 5)
                    if "." in method:
                        method_owner, name = method.rsplit(".", 1)
                    else:
                        method_owner, name = original_owner, method
                    previous = {"args": tuple(params.split(",")) if params else (), "return": result,
                                "label": f"{method_owner}.{name}({params}):{result}"}
                    self.methods[owner, obfuscated].append(previous)

    def labels(self, cls: str, method: str, signature: str) -> dict:
        cls = cls.replace("/", ".")
        original_cls = self.classes.get(cls, cls)
        rows = self.methods.get((cls, method), [])
        exact = []
        try:
            args, result = descriptor_types(signature, self.classes)
            exact = [row for row in rows if row.get("residual") == signature or
                     (not row.get("residual") and row["args"] == args and row["return"] == result)]
        except (ValueError, IndexError, KeyError):
            pass
        selected = exact or rows
        candidates = sorted({row["label"] for row in selected})
        if candidates:
            status = "descriptor_match" if exact else "name_only_candidates_signature_changed"
            if len(candidates) > 1:
                status += "_ambiguous_without_pc_line"
        else:
            candidates = [f"{original_cls}.{method}{signature}"]
            status = "class_only" if original_cls != cls else "unmapped_or_framework"
        return {"raw": f"{cls}.{method}{signature}", "deobfuscation": status,
                "candidates": candidates}


def parse_header(data: bytes) -> tuple[dict, dict[int, str], dict[int, dict], int, int, int, int]:
    end = re.search(rb"(?m)^\*end\r?\n", data)
    if end is None or end.end() > 16 * 1024 * 1024:
        raise ValueError("Expected non-streaming ART text *end header; streaming format is not supported")
    text = data[:end.end()].decode("utf-8", "replace")
    metadata, threads, methods = {}, {}, {}
    section = None
    for line in text.splitlines():
        if line.startswith("*"):
            section = line[1:]
        elif section == "threads":
            tid, name = line.split("\t", 1)
            threads[int(tid)] = name
        elif section == "methods":
            parts = line.split("\t")
            if len(parts) < 4:
                raise ValueError("Invalid ART method-table row")
            methods[int(parts[0], 0)] = {"class": parts[1], "method": parts[2], "descriptor": parts[3],
                                       "source": parts[4] if len(parts) > 4 else None}
        elif "=" in line:
            key, value = line.split("=", 1)
            metadata[key] = value
        elif section == "version" and line:
            metadata["text_version"] = int(line)
    base = end.end()
    if data[base:base + 4] != b"SLOW":
        raise ValueError("Missing binary SLOW magic after text header")
    version, offset, started = struct.unpack_from("<HHQ", data, base + 4)
    if version not in (1, 2, 3):
        raise ValueError(f"Unsupported ART binary version {version}; refusing to guess streaming/new format")
    if metadata.get("text_version") != version:
        raise ValueError("Text/binary trace version mismatch")
    record_size = 9 if version == 1 else 10 if version == 2 else struct.unpack_from("<H", data, base + 16)[0]
    expected = 14 if metadata.get("clock") == "dual" else 9 if version == 1 else 10
    if offset < (18 if version == 3 else 16) or record_size < expected:
        raise ValueError("Invalid ART binary offset/record size")
    if metadata.get("clock") not in ("wall", "thread-cpu", "dual"):
        raise ValueError("Trace must declare its clock; wall must not be mislabeled as CPU")
    return metadata, threads, methods, base + offset, record_size, version, started


def analyze(data: bytes, mapping: Mapping | None = None, thread: str = "main",
            start_us: float = 0, end_us: float | None = None, top: int = 40,
            windows_us: list[tuple[float, float]] | None = None) -> dict:
    metadata, names, methods, offset, stride, version, started = parse_header(data)
    mapping = mapping or Mapping(None)
    clock = metadata["clock"]
    has_wall, has_cpu = clock != "thread-cpu", clock != "wall"
    if start_us < 0 or (end_us is not None and end_us <= start_us):
        raise ValueError("Invalid relative wall window")
    if not has_wall and (start_us != 0 or end_us is not None):
        raise ValueError("A wall window cannot be applied to thread-cpu-only records")
    tids = {tid for tid, name in names.items() if thread == "*" or thread == str(tid) or name == thread}
    if not tids:
        raise ValueError(f"No thread {thread!r}; available: {names}")
    elapsed = int(metadata.get("elapsed-time-usec", "0"))
    end_us = end_us if end_us is not None else elapsed or 2**63 - 1
    windows = sorted(windows_us) if windows_us is not None else [(start_us, end_us)]
    if not has_wall and windows_us is not None:
        raise ValueError("Wall windows require a wall or dual-clock trace")
    if not windows or any(a < 0 or b <= a for a, b in windows):
        raise ValueError("Invalid window list")
    if any(windows[i][0] < windows[i-1][1] for i in range(1, len(windows))):
        raise ValueError("Overlapping windows would double-count attribution")
    if windows_us is not None:
        start_us, end_us = windows[0][0], windows[-1][1]
    errors = Counter()
    warnings = []
    body = memoryview(data)[offset:]
    records = len(body) // stride
    if len(body) % stride:
        warnings.append(f"Truncated trailing record: {len(body) % stride} bytes")
    if metadata.get("data-file-overflow") == "true":
        warnings.append("ART reports data-file-overflow=true; trace is incomplete")
    if "num-method-calls" in metadata and int(metadata["num-method-calls"]) != records:
        warnings.append("Header event count does not match parsed records")
    states = {tid: {"stack": [], "last": None, "first": None, "roll": [0, 0], "raw_last": [None, None],
                    "exclusive_wall": Counter(), "exclusive_cpu": Counter(),
                    "inclusive_wall": Counter(), "inclusive_cpu": Counter(), "enters": Counter(),
                    "paths_wall": Counter(), "paths_cpu": Counter(), "events": 0, "intervals": 0,
                    "covered_wall": 0, "covered_cpu": 0} for tid in tids}
    def unwrap(state: dict, raw: int | None, which: int) -> int | None:
        if raw is None:
            return None
        last = state["raw_last"][which]
        if last is not None and raw < last:
            if last - raw > 2**31:
                state["roll"][which] += 2**32
            else:
                raise ValueError("Per-thread trace clock moved backwards")
        state["raw_last"][which] = raw
        return raw + state["roll"][which]
    for at in range(0, records * stride, stride):
        tid = body[at] if version == 1 else struct.unpack_from("<H", body, at)[0]
        if tid not in tids:
            continue
        state = states[tid]
        payload = at + (1 if version == 1 else 2)
        encoded, first = struct.unpack_from("<II", body, payload)
        method, action = encoded & ~3, encoded & 3
        cpu_raw = first if has_cpu else None
        wall_raw = struct.unpack_from("<I", body, payload + 8)[0] if clock == "dual" else first if has_wall else None
        cpu, wall = unwrap(state, cpu_raw, 0), unwrap(state, wall_raw, 1)
        stamp = (cpu, wall)
        last = state["last"]
        if last is not None:
            d_cpu = cpu - last[0] if has_cpu else 0
            d_wall = wall - last[1] if has_wall else 0
            overlap = sum(max(0, min(wall, b) - max(last[1], a)) for a, b in windows) if has_wall else 0
            ratio = overlap / d_wall if d_wall else (1 if not has_wall else 0)
            weighted_cpu = round(d_cpu * ratio) if has_wall else d_cpu
            stack = state["stack"]
            if stack and (overlap or weighted_cpu):
                leaf, path = stack[-1], tuple(stack)
                state["exclusive_wall"][leaf] += overlap
                state["exclusive_cpu"][leaf] += weighted_cpu
                # Deduplicate recursive appearances for comparable inclusive percentages.
                for ancestor in set(stack):
                    state["inclusive_wall"][ancestor] += overlap
                    state["inclusive_cpu"][ancestor] += weighted_cpu
                state["paths_wall"][path] += overlap
                state["paths_cpu"][path] += weighted_cpu
                state["covered_wall"] += overlap
                state["covered_cpu"] += weighted_cpu
                state["intervals"] += 1
        else:
            state["first"] = stamp
        state["last"] = stamp
        state["events"] += 1
        stack = state["stack"]
        if method not in methods:
            errors["unknown_method_id"] += 1
        if action == 0:
            stack.append(method)
            if not has_wall or any(a <= wall < b for a, b in windows):
                state["enters"][method] += 1
        elif action in (1, 2):
            if stack and stack[-1] == method:
                stack.pop()
            elif method in stack:
                errors["non_top_exit_recovery"] += 1
                reverse_index = len(stack) - 1 - stack[::-1].index(method)
                del stack[reverse_index:]
            else:
                errors["unmatched_exit_stack_reset"] += 1
                stack.clear()
        else:
            errors["reserved_action_3"] += 1
    if errors:
        warnings.append("Stack/record anomalies found; inspect counts before using attribution")
    def label(mid: int) -> dict:
        row = methods.get(mid)
        return dict(mapping.labels(row["class"], row["method"], row["descriptor"]),
                    method_id=hex(mid), source_file=row["source"]) if row else {"method_id": hex(mid), "raw": "unknown", "candidates": []}
    outputs = []
    for tid, state in sorted(states.items()):
        def rows(inclusive: bool) -> list[dict]:
            wall_counts = state["inclusive_wall" if inclusive else "exclusive_wall"]
            cpu_counts = state["inclusive_cpu" if inclusive else "exclusive_cpu"]
            ranking = cpu_counts if has_cpu else wall_counts
            result = []
            for mid, _ in ranking.most_common(top):
                result.append(dict(label(mid), wall_ms=wall_counts[mid] / 1000 if has_wall else None,
                                   thread_cpu_ms=cpu_counts[mid] / 1000 if has_cpu else None,
                                   thread_cpu_percent=cpu_counts[mid] * 100 / state["covered_cpu"] if state["covered_cpu"] else None,
                                   synthesized_enters=state["enters"][mid]))
            return result
        path_ranking = state["paths_cpu"] if has_cpu else state["paths_wall"]
        paths = [{"wall_ms": state["paths_wall"][path] / 1000 if has_wall else None,
                  "thread_cpu_ms": state["paths_cpu"][path] / 1000 if has_cpu else None,
                  "frames_bottom_to_top": [label(mid) for mid in path]}
                 for path, _ in path_ranking.most_common(min(top, 20))]
        outputs.append({"tid": tid, "name": names.get(tid), "events": state["events"],
                        "changed_stack_intervals": state["intervals"],
                        "covered_wall_ms": state["covered_wall"] / 1000 if has_wall else None,
                        "covered_thread_cpu_ms": state["covered_cpu"] / 1000 if has_cpu else None,
                        "first_record_cpu_wall_us": state["first"], "last_record_cpu_wall_us": state["last"],
                        "unattributed_final_wall_tail_ms": sum(
                            max(0, min(elapsed, b) - max(a, state["last"][1])) for a, b in windows) / 1000
                            if has_wall and state["last"] else None,
                        "exclusive_leaf": rows(False), "inclusive_ancestor": rows(True), "top_stacks": paths})
    return {"schema_version": 1, "format_version": version, "record_size": stride, "binary_start_timestamp_us": started,
            "clock": clock, "metadata": metadata, "parsed_records": records, "method_table_size": len(methods),
            "trace_sha256": hashlib.sha256(data).hexdigest(), "mapping_sha256": mapping.sha256,
            "relative_wall_window_us": [start_us, end_us] if has_wall else None,
            "relative_wall_windows_us": windows if has_wall else None,
            "binary_start_clock": "CLOCK_MONOTONIC on Android 11 (ART MicroTime)",
            "status": "partial_or_anomalous" if warnings else "parsed_complete_records",
            "warnings": warnings, "anomalies": dict(errors), "threads": outputs,
            "interpretation": ["Wall-clock attribution includes blocking and preemption; it is NOT CPU running time.",
                "Thread-CPU clock durations are sampled-stack attribution, not exact per-method timing.",
                "Sampling emits stack diffs only; event/enter counts are NOT real calls or sample counts.",
                "First-record prefix and final unchanged-stack tail are not extrapolated.",
                "Inclusive counts deduplicate recursion; CPU for clipped wall intervals is proportionally estimated.",
                "JNI leaves do not expose native C/C++ callees. ART sampling suspends threads and perturbs scheduling.",
                "Tiny getter buckets are not production method costs; sampling/JIT/inlining and sparse observations bias attribution.",
                "R8 inline/overload ambiguity without PC/line is preserved as candidate lists."]}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace", type=Path)
    parser.add_argument("--mapping", type=Path)
    parser.add_argument("--thread", default="main", help="Exact thread name, numeric tid, or '*' for all")
    parser.add_argument("--start-us", type=float, default=0)
    parser.add_argument("--end-us", type=float)
    parser.add_argument("--windows-csv", type=Path,
                        help="Non-overlapping absolute CLOCK_MONOTONIC ns windows: start_mono_ns,end_mono_ns")
    parser.add_argument("--top", type=int, default=40)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not 1 <= args.top <= 500:
        parser.error("--top must be 1..500")
    data = args.trace.read_bytes()
    windows = None
    if args.windows_csv:
        if args.start_us != 0 or args.end_us is not None:
            parser.error("Use --windows-csv or --start-us/--end-us, not both")
        started = parse_header(data)[-1]
        with args.windows_csv.open() as stream:
            windows = [((int(row["start_mono_ns"]) - started * 1000) / 1000,
                        (int(row["end_mono_ns"]) - started * 1000) / 1000)
                       for row in csv.DictReader(stream)]
    result = analyze(data, Mapping(args.mapping), args.thread, args.start_us, args.end_us, args.top, windows)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(f"{result['status']}; version={result['format_version']} clock={result['clock']} records={result['parsed_records']}")
    for thread in result["threads"]:
        print(f"{thread['name']} tid={thread['tid']}: covered CPU={thread['covered_thread_cpu_ms']}ms wall={thread['covered_wall_ms']}ms")
        for row in thread["exclusive_leaf"]:
            print(f"  CPU={row['thread_cpu_ms']}ms wall={row['wall_ms']}ms | {' OR '.join(row['candidates'])}")
    for warning in result["warnings"]:
        print("WARNING:", warning)


if __name__ == "__main__":
    main()

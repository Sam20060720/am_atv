"""Bound service-global Perfetto counters without importing trace payloads.

Only the two info-level service counters below are eligible. Session buffer,
ftrace, invalid-packet and error/data_loss stats remain the caller's blockers.
This is evidence of counter scope, never a declaration that a trace is loss-free.
"""

import mmap
from pathlib import Path


SERVICE_SOURCE_URL = "https://github.com/google/perfetto/blob/v34.0/src/tracing/core/tracing_service_impl.cc"
WIRE_SOURCE_URL = "https://github.com/google/perfetto/blob/v34.0/protos/perfetto/trace/trace_packet.proto"
COUNTER_FIELDS = {"traced_chunks_discarded": 8, "traced_patches_discarded": 9}
BOOTTIME = 6
SERVICE_SEQUENCE = 1


class UnsupportedTrace(ValueError):
    pass


def _varint(data, pos, end):
    value = 0
    for shift in range(0, 70, 7):
        if pos >= end:
            raise UnsupportedTrace("truncated_varint")
        byte = data[pos]
        pos += 1
        if shift == 63 and byte > 1:
            raise UnsupportedTrace("varint_overflow")
        value |= (byte & 127) << shift
        if byte < 128:
            return value, pos
    raise UnsupportedTrace("varint_overflow")


def _fields(data, pos, end):
    count = 0
    while pos < end:
        count += 1
        if count > 10_000:
            raise UnsupportedTrace("message_field_limit")
        tag, pos = _varint(data, pos, end)
        number, wire = tag >> 3, tag & 7
        if not number:
            raise UnsupportedTrace("invalid_field_number")
        if wire == 0:
            value, pos = _varint(data, pos, end)
        elif wire == 2:
            size, pos = _varint(data, pos, end)
            value = (pos, pos + size)
            pos += size
        elif wire in (1, 5):
            size = 8 if wire == 1 else 4
            value = (pos, pos + size)
            pos += size
        else:
            raise UnsupportedTrace("unsupported_wire_type")
        if pos > end:
            raise UnsupportedTrace("truncated_message")
        yield number, wire, value


def inspect_service_global_counters(path, *, max_packets=2_000_000,
                                    max_trace_bytes=4 * 1024**3,
                                    max_packet_bytes=64 * 1024**2):
    """Return small JSON-serializable observations; mmap skips producer payloads.

    v34.0 ReadBuffers emits its read-complete event then stats on the service
    thread (source lines 2274-2297). The next read-complete event therefore
    bounds the preceding stats sample from above. Event timestamps explicitly
    use GetBootTimeNs (line 3054). Ordinary producer packet order is NOT a clock.
    A primary BOOTTIME declaration or explicit BOOTTIME timestamp clock is
    required. Compressed packets, malformed input and limits fail closed.
    """
    result = {"status": "unknown", "clock": "BOOTTIME", "observations": [],
              "packet_count": 0, "errors": [], "source_url": SERVICE_SOURCE_URL,
              "wire_source_url": WIRE_SOURCE_URL,
              "scope": "tracing_service_lifetime_not_session"}
    primary_clock = None
    previous_read = None
    pending = []
    last_values = None
    try:
        path = Path(path)
        size = path.stat().st_size
        if not size or size > max_trace_bytes:
            raise UnsupportedTrace("empty_or_trace_size_limit")
        with path.open("rb") as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as data:
            pos = 0
            while pos < size:
                index = result["packet_count"]
                if index >= max_packets:
                    raise UnsupportedTrace("packet_count_limit")
                tag, pos = _varint(data, pos, size)
                if tag != 10:  # Trace.packet = 1, length-delimited.
                    raise UnsupportedTrace("unsupported_trace_envelope")
                length, pos = _varint(data, pos, size)
                end = pos + length
                if end > size:
                    raise UnsupportedTrace("truncated_packet")
                if length > max_packet_bytes:
                    raise UnsupportedTrace("packet_size_limit")
                fields = {n: (w, value) for n, w, value in _fields(data, pos, end)}
                if 50 in fields:
                    raise UnsupportedTrace("compressed_packets_unsupported")
                if 6 in fields and fields[6][0] == 2:
                    clock = {n: value for n, w, value in _fields(data, *fields[6][1]) if w == 0}.get(2)
                    if clock is not None:
                        if primary_clock is not None and primary_clock != clock:
                            raise UnsupportedTrace("primary_clock_changed")
                        primary_clock = clock
                sequence = fields.get(10, (0, None))[1]
                if sequence == SERVICE_SEQUENCE and 69 in fields and fields[69][0] == 2:
                    event = {n: value for n, w, value in _fields(data, *fields[69][1]) if w == 0}
                    if event.get(4) == 1:  # TracingServiceEvent.read_tracing_buffers_completed.
                        clock = fields.get(58, (0, primary_clock))[1]
                        stamp = fields.get(8, (0, None))[1] if clock == BOOTTIME else None
                        if stamp is not None and previous_read is not None and stamp < previous_read:
                            raise UnsupportedTrace("service_read_clock_regressed")
                        for observation in pending:
                            lower = observation["sample_lower_ns"]
                            if stamp is not None and lower is not None and stamp >= lower:
                                observation["sample_upper_ns"] = stamp
                                observation["upper_read_packet_index"] = index
                        pending.clear()
                        previous_read = stamp
                if sequence == SERVICE_SEQUENCE and 35 in fields and fields[35][0] == 2:
                    stats = {n: value for n, w, value in _fields(data, *fields[35][1]) if w == 0}
                    values = {name: stats.get(number, 0) for name, number in COUNTER_FIELDS.items()}
                    if last_values is not None and any(values[name] < last_values[name] for name in values):
                        raise UnsupportedTrace("service_counter_regressed")
                    observation = {"packet_index": index, "file_offset": pos,
                                   "sample_lower_ns": previous_read, "sample_upper_ns": None,
                                   "counters": values}
                    result["observations"].append(observation)
                    pending.append(observation)
                    last_values = values
                pos = end
                result["packet_count"] += 1
        if not result["observations"]:
            raise UnsupportedTrace("no_service_counter_observations")
        result["status"] = "complete"
        result["declared_primary_clock_id"] = primary_clock
    except (OSError, ValueError) as error:
        result["errors"].append(str(error))
    return result


def classify_service_global_health(evidence, start_ns, end_ns, stats):
    """Classify one BOOTTIME window, retaining unknown/overlapping increments.

    A before-window sample must have an upper bound <= start; an after-window
    sample must have a lower bound >= end. Equal monotonic counter values at
    these samples prove no service-counter increase during this window. Absolute
    nonzero baseline values remain visible, with no guessed session attribution.
    """
    eligible = [row for row in stats if row.get("severity") == "info" and row.get("name") in COUNTER_FIELDS]
    result = {"classification": "not_applicable", "safe_to_separate_from_session_loss": False,
              "window_covered": False, "eligible_stats": eligible, "counters": [],
              "warnings": [], "clock": "BOOTTIME", "start_ns": start_ns, "end_ns": end_ns}
    if not eligible:
        return result
    result["classification"] = "unknown_or_overlapping"
    result["warnings"].append("Service-global counters cannot identify the affected tracing session; this is not a loss-free capture claim.")
    if evidence.get("status") != "complete" or not isinstance(start_ns, int) or not isinstance(end_ns, int) or end_ns <= start_ns:
        result["reason"] = "incomplete_evidence_or_invalid_window"
        return result
    observations = evidence.get("observations", [])
    before = [row for row in observations if row.get("sample_upper_ns") is not None and row["sample_upper_ns"] <= start_ns]
    after = [row for row in observations if row.get("sample_lower_ns") is not None and row["sample_lower_ns"] >= end_ns]
    if not before or not after:
        result["reason"] = "window_not_bracketed_by_service_samples"
        return result
    baseline, following = before[-1], after[0]
    result["window_covered"] = True
    safe = True
    for raw in eligible:
        name = raw["name"]
        values = [row["counters"][name] for row in observations]
        counter = {"name": name, "raw_value": raw.get("value"),
                   "baseline": baseline, "after_window": following,
                   "possible_increment_intervals": []}
        for previous, current in zip(observations, observations[1:]):
            delta = current["counters"][name] - previous["counters"][name]
            if delta:
                lo, hi = previous.get("sample_lower_ns"), current.get("sample_upper_ns")
                outside = (hi is not None and hi <= start_ns) or (lo is not None and lo >= end_ns)
                counter["possible_increment_intervals"].append({"delta": delta,
                    "lower_ns": lo, "upper_ns": hi, "outside_window": outside})
        matching_raw = raw.get("value") == max(values)
        unchanged = baseline["counters"][name] == following["counters"][name]
        counter["safe_outside_window"] = matching_raw and unchanged
        safe = safe and counter["safe_outside_window"]
        result["counters"].append(counter)
    if safe:
        result["classification"] = "safe_outside_window"
        result["safe_to_separate_from_session_loss"] = True
        result["reason"] = "service_counter_unchanged_across_bracketed_window"
    else:
        result["reason"] = "counter_increment_may_overlap_window_or_raw_value_mismatch"
    return result

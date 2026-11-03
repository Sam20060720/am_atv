import importlib.util
from pathlib import Path
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location("trace_health_scope", Path(__file__).parents[1] / "trace_health_scope.py")
health = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(health)


def varint(value):
    result = bytearray()
    while value > 127:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def scalar(number, value):
    return varint(number << 3) + varint(value)


def message(number, payload):
    return varint(number << 3 | 2) + varint(len(payload)) + payload


def packet(payload):
    return message(1, payload)


def clock(value=6):
    return packet(message(6, scalar(2, value)))


def read(stamp, sequence=1):
    return packet(scalar(8, stamp) + scalar(10, sequence) + message(69, scalar(4, 1)))


def counters(chunks, patches=0):
    return packet(scalar(10, 1) + message(35, scalar(8, chunks) + scalar(9, patches)))


RAW = [{"name": "traced_chunks_discarded", "severity": "info", "value": 4}]


class ServiceCounterScopeTests(unittest.TestCase):
    def inspect(self, wire, **kwargs):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "tiny.pftrace"
            path.write_bytes(wire)
            return health.inspect_service_global_counters(path, **kwargs)

    def test_startup_increment_is_outside_bracketed_window(self):
        evidence = self.inspect(clock() + read(100) + counters(3) + read(200) + counters(4)
                                + read(300) + read(900) + counters(4) + read(1000))
        self.assertEqual(evidence["status"], "complete")
        result = health.classify_service_global_health(evidence, 400, 800, RAW)
        self.assertEqual(result["classification"], "safe_outside_window")
        self.assertTrue(result["window_covered"])
        self.assertEqual(result["counters"][0]["baseline"]["counters"]["traced_chunks_discarded"], 4)
        self.assertTrue(result["counters"][0]["possible_increment_intervals"][0]["outside_window"])
        self.assertTrue(result["warnings"])

    def test_increment_interval_overlaps_measurement(self):
        evidence = self.inspect(clock() + read(100) + counters(3) + read(200)
                                + read(500) + counters(4) + read(600)
                                + read(900) + counters(4) + read(1000))
        result = health.classify_service_global_health(evidence, 400, 800, RAW)
        self.assertEqual(result["classification"], "unknown_or_overlapping")
        self.assertFalse(result["safe_to_separate_from_session_loss"])

    def test_missing_read_bounds_and_uncovered_window_fail_closed(self):
        evidence = self.inspect(clock() + counters(4) + counters(4))
        result = health.classify_service_global_health(evidence, 400, 800, RAW)
        self.assertFalse(result["window_covered"])
        self.assertEqual(result["classification"], "unknown_or_overlapping")
        evidence = self.inspect(clock() + read(100) + counters(4) + read(200))
        self.assertFalse(health.classify_service_global_health(evidence, 400, 800, RAW)["window_covered"])

    def test_unknown_and_non_boottime_clocks_cannot_supply_bounds(self):
        for prefix in [b"", clock(3)]:
            evidence = self.inspect(prefix + read(100) + counters(4) + read(200)
                                    + read(900) + counters(4) + read(1000))
            self.assertFalse(health.classify_service_global_health(evidence, 400, 800, RAW)["window_covered"])

    def test_only_exact_info_service_names_are_eligible(self):
        for row in [{"name": "traced_chunks_discarded", "severity": "data_loss", "value": 4},
                    {"name": "traced_chunks_discarded", "severity": "error", "value": 4},
                    {"name": "traced_invalid_packets", "severity": "info", "value": 4},
                    {"name": "ftrace_cpu_overrun", "severity": "info", "value": 4}]:
            self.assertEqual(health.classify_service_global_health({}, 1, 2, [row])["classification"], "not_applicable")

    def test_truncation_compression_and_limits_fail_closed(self):
        good = clock() + read(100) + counters(4) + read(200)
        for wire, options in [(good[:-1], {}), (good + packet(message(50, b"not expanded")), {}),
                              (good, {"max_packets": 2}), (good, {"max_packet_bytes": 1})]:
            evidence = self.inspect(wire, **options)
            self.assertEqual(evidence["status"], "unknown")
            self.assertTrue(evidence["errors"])
            self.assertFalse(health.classify_service_global_health(evidence, 110, 120, RAW)["safe_to_separate_from_session_loss"])

    def test_counter_regression_and_raw_mismatch_fail_closed(self):
        evidence = self.inspect(clock() + read(100) + counters(4) + read(200) + counters(3))
        self.assertEqual(evidence["status"], "unknown")
        evidence = self.inspect(clock() + read(100) + counters(3) + read(200)
                                + read(900) + counters(3) + read(1000))
        self.assertFalse(health.classify_service_global_health(evidence, 400, 800, RAW)["safe_to_separate_from_session_loss"])

    def test_producer_timestamps_do_not_bound_service_samples(self):
        evidence = self.inspect(clock() + read(100, sequence=2) + counters(4)
                                + read(200, sequence=2) + read(900, sequence=2) + counters(4))
        self.assertFalse(health.classify_service_global_health(evidence, 400, 800, RAW)["window_covered"])


if __name__ == "__main__":
    unittest.main()

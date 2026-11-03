#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
device_serial=${1:-${ANDROID_SERIAL:-}}
if [ -z "$device_serial" ]; then
    echo "Usage: $0 DEVICE_SERIAL [TEST_CLASS_OR_COMMA_SEPARATED_CLASSES]" >&2
    exit 2
fi
allow_player_playback=${AMATV_ALLOW_PLAYER_PLAYBACK:-0}
allow_playback_seed=${AMATV_ALLOW_PLAYBACK_SEED:-0}
case "$allow_player_playback:$allow_playback_seed" in
    0:0|1:0|1:1) ;;
    *)
        echo "AMATV_ALLOW_PLAYER_PLAYBACK and AMATV_ALLOW_PLAYBACK_SEED must be 0 or 1; seed also requires playback." >&2
        exit 2
        ;;
esac
case "${2:-all}" in
    all) test_classes=dev.amatv.benchmark.TvNavigationTest,dev.amatv.benchmark.TvProductJourneyTest,dev.amatv.benchmark.KaraokeGradientRenderingTest,dev.amatv.benchmark.TvNavigationBenchmark,dev.amatv.benchmark.PlayerRenderingBenchmark ;;
    functional) test_classes=dev.amatv.benchmark.TvNavigationTest,dev.amatv.benchmark.TvProductJourneyTest,dev.amatv.benchmark.KaraokeGradientRenderingTest ;;
    performance) test_classes=dev.amatv.benchmark.TvNavigationBenchmark,dev.amatv.benchmark.PlayerRenderingBenchmark ;;
    *) test_classes=$2 ;;
esac
run_id=$(TZ=Asia/Taipei date +%Y%m%d-%H%M%S)
output_dir="$project_dir/app/build/verification/tv-ui/$run_id"
mkdir -p "$output_dir"
printf '%s\n' "UI test artifacts: $output_dir"
test_status=0
ANDROID_SERIAL="$device_serial" ./gradlew :benchmark:connectedBenchmarkAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=$test_classes" \
    "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.compilation.enabled=false" \
    "-Pandroid.testInstrumentationRunnerArguments.benchmarkIterations=${AMATV_BENCHMARK_ITERATIONS:-3}" \
    "-Pandroid.testInstrumentationRunnerArguments.allow_player_playback=$allow_player_playback" \
    "-Pandroid.testInstrumentationRunnerArguments.allow_playback_seed=$allow_playback_seed" \
    > "$output_dir/gradle.log" 2>&1 || test_status=$?
for artifact in androidTest-results connected_android_test_additional_output; do
    if [ -d "benchmark/build/outputs/$artifact" ]; then
        cp -R "benchmark/build/outputs/$artifact" "$output_dir/"
    fi
done
python3 tools/summarize-tv-benchmarks.py "$output_dir" > "$output_dir/summary.json"
printf '%s\n' "Exit status: $test_status"
exit "$test_status"

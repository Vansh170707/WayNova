#!/usr/bin/env bash
# Explicit emulator-only instrumentation. No uninstall, clear-data, or physical targets.
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
adb_bin="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
test_serial="${1:?Pass an explicit emulator serial, for example emulator-5554}"
test_class="${2:-org.neuronavx.logger.RouteGuidanceTest,org.neuronavx.logger.DriveEvidenceTest,org.neuronavx.logger.SavedRideTest,org.neuronavx.logger.RoundTwoTest,org.neuronavx.logger.GlassUiTest}"
case "$test_serial" in emulator-[0-9]*) ;; *) echo "Refusing a non-emulator target" >&2; exit 2 ;; esac
[[ "$test_serial" =~ ^emulator-[0-9]+$ ]] || exit 2
[[ "$test_class" =~ ^[a-zA-Z0-9_.,\#]+$ ]] || { echo "Invalid test class" >&2; exit 2; }
[[ "$("$adb_bin" -s "$test_serial" shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]] || {
    echo "Target is not a verified emulator; stopping" >&2; exit 2;
}
(cd "$repo_dir/mobile" && ./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest)
"$adb_bin" -s "$test_serial" install -r "$repo_dir/mobile/app/build/outputs/apk/debug/app-debug.apk"
"$adb_bin" -s "$test_serial" install -r "$repo_dir/mobile/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
if [[ "$test_class" == "org.neuronavx.logger.FieldLogReplayTest" ]]; then
    fixture_dir="${3:?Supply a local directory containing the backed-up field fixtures}"
    [[ -d "$fixture_dir" ]] || exit 2
    remote_dir="files/phase16-replay"
    "$adb_bin" -s "$test_serial" shell run-as org.neuronavx.logger mkdir -p "$remote_dir"
    shopt -s nullglob
    fixtures=("$fixture_dir"/nav_summary_*.json "$fixture_dir"/nav_drive_*.csv "$fixture_dir"/nav_diagnostics_*.csv)
    ((${#fixtures[@]} > 0)) || { echo "No fixtures found" >&2; exit 2; }
    for fixture in "${fixtures[@]}"; do
        [[ "$(basename "$fixture")" =~ ^nav_(summary|drive|diagnostics)_[0-9]+\.(json|csv)$ ]] || exit 2
        "$adb_bin" -s "$test_serial" shell -T run-as org.neuronavx.logger tee "$remote_dir/$(basename "$fixture")" < "$fixture" > /dev/null
    done
    session_ids=""
    for summary in "$fixture_dir"/nav_summary_*.json; do
        session_id="$(basename "$summary" .json)"; session_id="${session_id#nav_summary_}"
        session_ids="${session_ids:+$session_ids,}$session_id"
    done
    result_log="$(mktemp -t waynova-instrumentation)"
    "$adb_bin" -s "$test_serial" shell am instrument -w -r -e class "$test_class" -e fieldReplay true -e fieldSessionIds "$session_ids" org.neuronavx.logger.test/androidx.test.runner.AndroidJUnitRunner | tee "$result_log"
elif [[ "$test_class" == "org.neuronavx.logger.RoadHeadingReplayTest" ]]; then
    fixture_dir="${3:?Supply a local directory containing backed-up nav_drive_*.csv files}"
    [[ -d "$fixture_dir" ]] || exit 2
    remote_dir="files/road-heading-replay"
    "$adb_bin" -s "$test_serial" shell run-as org.neuronavx.logger mkdir -p "$remote_dir"
    shopt -s nullglob
    fixtures=("$fixture_dir"/nav_drive_*.csv)
    ((${#fixtures[@]} > 0)) || { echo "No fixtures found" >&2; exit 2; }
    for fixture in "${fixtures[@]}"; do
        [[ "$(basename "$fixture")" =~ ^nav_drive_[0-9]+\.csv$ ]] || exit 2
        "$adb_bin" -s "$test_serial" shell -T run-as org.neuronavx.logger tee "$remote_dir/$(basename "$fixture")" < "$fixture" > /dev/null
    done
    result_log="$(mktemp -t waynova-instrumentation)"
    "$adb_bin" -s "$test_serial" shell am instrument -w -r -e class "$test_class" -e roadHeadingReplay true -e traceOnly "${ROAD_REPLAY_TRACE_ONLY:-false}" org.neuronavx.logger.test/androidx.test.runner.AndroidJUnitRunner | tee "$result_log"
    "$adb_bin" -s "$test_serial" exec-out run-as org.neuronavx.logger cat "$remote_dir/road_heading_results.json" > "$fixture_dir/road_heading_results.json" || true
    "$adb_bin" -s "$test_serial" exec-out run-as org.neuronavx.logger cat "$remote_dir/speed_trace.csv" > "$fixture_dir/speed_trace.csv" 2>/dev/null || true
else
    result_log="$(mktemp -t waynova-instrumentation)"
    "$adb_bin" -s "$test_serial" shell am instrument -w -r -e class "$test_class" org.neuronavx.logger.test/androidx.test.runner.AndroidJUnitRunner | tee "$result_log"
fi
echo "Instrumentation transcript: $result_log"
grep -Eq '^OK \([0-9]+ tests?\)' "$result_log" || { echo "Instrumentation did not report success" >&2; exit 1; }

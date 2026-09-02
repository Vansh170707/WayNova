#!/usr/bin/env bash
# Pull every recorded drive off the phone and run the same quality audit the IO-VNBD
# sessions get, so an unusable log is caught now rather than after it is trained on.
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
REMOTE="/sdcard/Android/data/org.neuronavx.logger/files"
LOCAL="$REPO/data/raw/own_drives"

mkdir -p "$LOCAL"
"$ADB" wait-for-device

FILES=$("$ADB" shell ls "$REMOTE" 2>/dev/null | tr -d '\r' | grep -E '\.(csv|json)$' || true)
[ -n "$FILES" ] || { echo "No drives found on the phone."; exit 0; }

for f in $FILES; do
    echo "pulling $f"
    "$ADB" pull "$REMOTE/$f" "$LOCAL/$f" >/dev/null
done

echo
echo "=== quality audit ==="
"$REPO/envs/bin/python" - "$LOCAL" <<'PY'
import glob, sys
from neuronav.data.own_drive import load_own_drive, summarise

segments = []
raw_paths = sorted(
    glob.glob(f"{sys.argv[1]}/drive_*.csv") +
    glob.glob(f"{sys.argv[1]}/nav_drive_*.csv")
)
for path in raw_paths:
    try:
        segments.extend(load_own_drive(path))
    except Exception as exc:
        print(f"  {path}: FAILED {type(exc).__name__}: {exc}")

if not segments:
    raise SystemExit("no usable segments")

report = summarise(segments)
print(report.to_string(index=False))
print()
# The thresholds that made most of IO-VNBD unusable -- see docs/data_audit.md
for _, r in report.iterrows():
    problems = []
    if r["imu_hz"] < 40:
        problems.append(f"IMU only {r['imu_hz']:.0f} Hz")
    if r["gnss_hz"] < 0.5:
        problems.append(f"GNSS only {r['gnss_hz']:.2f} Hz")
    if r["bearing_hz"] < 0.5:
        problems.append(f"bearing only {r['bearing_hz']:.2f} Hz")
    if r["nan_in_features"]:
        problems.append(f"{int(r['nan_in_features'])} NaNs in model features")
    if r["accel_p99"] < 1.5:
        problems.append(f"accelerometer looks filtered (p99 {r['accel_p99']:.2f} m/s^2)")
    if r["duration_min"] < 20:
        problems.append(f"short ({r['duration_min']:.0f} min)")
    verdict = "OK" if not problems else "CHECK: " + "; ".join(problems)
    print(f"  {r['route_id']:28s} {verdict}")
PY

echo
echo "=== calibration + physics evidence ==="
shopt -s nullglob
RAW_FILES=("$LOCAL"/drive_*.csv "$LOCAL"/nav_drive_*.csv)
for path in "${RAW_FILES[@]}"; do
    if ! "$REPO/envs/bin/python" "$REPO/scripts/analyze_own_drive.py" "$path"; then
        echo "  $path: analysis failed; keep the raw log and inspect the error above"
    fi
done

echo
echo "=== controlled field blackout evidence ==="
SUMMARY_FILES=("$LOCAL"/nav_summary_*.json)
if [ ${#SUMMARY_FILES[@]} -eq 0 ]; then
    echo "  no live navigation summaries found"
else
    "$REPO/envs/bin/python" "$REPO/scripts/analyze_live_blackout.py" "${SUMMARY_FILES[@]}"
fi

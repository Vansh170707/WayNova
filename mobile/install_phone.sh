#!/usr/bin/env bash
# Build (if needed), install on a connected phone, grant permissions, and launch.
#
# Runtime permissions are granted here rather than left to the in-app prompt because a
# denied-then-forgotten permission produces an IMU-only log that looks fine until the
# GNSS column turns out to be empty an hour later.
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
APK="$(dirname "$0")/app/build/outputs/apk/debug/app-debug.apk"
PKG="org.neuronavx.logger"

[ -x "$ADB" ] || { echo "adb not found at $ADB"; exit 1; }

echo "Waiting for a device… (plug in USB, unlock the phone, accept 'Allow USB debugging')"
"$ADB" wait-for-device

MODEL=$("$ADB" shell getprop ro.product.model | tr -d '\r')
ANDROID=$("$ADB" shell getprop ro.build.version.release | tr -d '\r')
ABI=$("$ADB" shell getprop ro.product.cpu.abi | tr -d '\r')
echo "Found: $MODEL  (Android $ANDROID, $ABI)"

if [ ! -f "$APK" ]; then
    echo "Building debug APK…"
    ( cd "$(dirname "$0")" && JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home)}" \
        ANDROID_HOME="$SDK" ./gradlew --no-daemon assembleDebug -q )
fi

echo "Installing…"
"$ADB" install -r "$APK"

# Location is a runtime permission; grant it up front so the first drive is not wasted.
"$ADB" shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
"$ADB" shell pm grant "$PKG" android.permission.ACCESS_COARSE_LOCATION 2>/dev/null || true

"$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null
echo
echo "Installed and launched on $MODEL."
echo
echo "Next:"
echo "  1. Tap 'Benchmark full navigation loop' -> record the physical-device result"
echo "  2. Mount rigidly; in Navigation tap 'Start live navigation' while parked"
echo "  3. Tap 'Arm 60s test' while parked; it starts automatically after calibration"
echo "  4. Stop only after parking, then pull and score the drive with:"
echo "       ./pull_drives.sh"

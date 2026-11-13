#!/usr/bin/env bash
# ==============================================================================
# CleanYourCar - Automotive TPMS Automated Deployment Script
# Target: UNISOC UIS7870 (FYT 6318) Android 13 Head Unit
# ==============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APK_PATH="$REPO_DIR/TpmsApp/build/TpmsApp.apk"

echo "========================================================"
echo "   🚗 CleanYourCar - TPMS App Deployment Tool"
echo "========================================================"

# 1. Resolve target ADB device
TARGET_DEVICE="${1:-${CAR_DEVICE_IP:-""}}"

if [ -n "$TARGET_DEVICE" ]; then
    echo "[*] Connecting to specified device: $TARGET_DEVICE..."
    adb connect "$TARGET_DEVICE" || true
    ADB_CMD="adb -s $TARGET_DEVICE"
else
    # Auto-detect from connected devices
    CONNECTED_DEVICES=($(adb devices | grep -w "device" | awk '{print $1}'))
    if [ ${#CONNECTED_DEVICES[@]} -eq 0 ]; then
        echo "[-] Error: No ADB device connected."
        echo "    Usage: $0 <CAR_HEAD_UNIT_IP:PORT>"
        echo "    Example: $0 192.168.1.100:5555"
        exit 1
    elif [ ${#CONNECTED_DEVICES[@]} -eq 1 ]; then
        TARGET_DEVICE="${CONNECTED_DEVICES[0]}"
        echo "[+] Auto-detected ADB device: $TARGET_DEVICE"
        ADB_CMD="adb -s $TARGET_DEVICE"
    else
        echo "[-] Multiple devices detected: ${CONNECTED_DEVICES[*]}"
        echo "    Please specify device IP: $0 <DEVICE_IP>"
        exit 1
    fi
fi

# 2. Check or build APK
if [ ! -f "$APK_PATH" ]; then
    echo "[*] APK not found at $APK_PATH, building now..."
    python3 "$REPO_DIR/TpmsApp/build_apk.py"
fi

echo "[1/4] Pushing & Installing TpmsApp.apk..."
$ADB_CMD push "$APK_PATH" /data/local/tmp/TpmsApp.apk
$ADB_CMD shell pm install -r -d -t -g /data/local/tmp/TpmsApp.apk
$ADB_CMD shell rm -f /data/local/tmp/TpmsApp.apk

echo "[2/4] Granting permissions..."
# Overlay permission for floating HUD capsule
$ADB_CMD shell appops set com.car.tpms SYSTEM_ALERT_WINDOW allow
# Notification permission for Android 13 ForegroundService
$ADB_CMD shell pm grant com.car.tpms android.permission.POST_NOTIFICATIONS 2>/dev/null || true

echo "[3/4] Configuring Launcher embedded slot..."
# Replace default Google Maps widget with TPMS card
$ADB_CMD shell setprop persist.launcher.embedded_pkg com.car.tpms

echo "[4/4] Starting TPMS service & launching interface..."
$ADB_CMD shell am start -n com.car.tpms/.MainActivity
sleep 1
$ADB_CMD shell input keyevent KEYCODE_HOME

echo "========================================================"
echo "✔ Deployment completed successfully!"
echo "👉 The TPMS widget is now active on the Launcher right slot."
echo "👉 Tap '開啟懸浮' in the card to enable the floating HUD capsule."
echo "========================================================"

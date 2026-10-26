#!/usr/bin/env bash
# Runs inside the emulator: install the debug APK, open each tab, screenshot it, and fail if
# Muninn crashed anywhere.
set -u
PKG=io.github.ravenor907.muninn
mkdir -p screens
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
for p in BLUETOOTH_ADVERTISE BLUETOOTH_CONNECT BLUETOOTH_SCAN NEARBY_WIFI_DEVICES ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do adb shell pm grant $PKG android.permission.$p 2>/dev/null || true; done
adb shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true
adb shell mkdir -p /sdcard/Download/roms
for f in cachyos-handheld.iso steamdeck-repair.img.bz2 notes.txt; do adb shell "echo test > /sdcard/Download/$f"; done
adb logcat -c
n=0
crashed() {
  if [ -z "$(adb shell pidof $PKG | tr -d '\r')" ]; then
    msg=$(adb logcat -d | grep -A20 "FATAL EXCEPTION" | head -30 | sed 's/%/%25/g' | sed ':a;N;$!ba;s/\n/%0A/g')
    echo "::error title=Muninn crashed on '$1'::$msg"
    exit 1
  fi
}
for screen in chat send files usb settings; do
  n=$((n+1))
  adb shell am force-stop $PKG
  adb shell am start -W -n $PKG/.MainActivity --ez ci true --es screen $screen >/dev/null
  sleep 5
  adb exec-out screencap -p > "screens/$(printf %02d $n)-$screen.png"
  crashed "$screen"
done
# A conversation (top bar, bubbles, composer), the chat list's + menu, the lock screen and Settings › Contacts.
for extra in "conversation:--es chat demo" "new-chat-menu:--es screen chat --ez menu true" "lock:--es screen lock" "contacts:--es screen contacts"; do
  n=$((n+1)); name=${extra%%:*}; args=${extra#*:}
  adb shell am force-stop $PKG
  adb shell am start -W -n $PKG/.MainActivity --ez ci true $args >/dev/null
  sleep 5
  adb exec-out screencap -p > "screens/$(printf %02d $n)-$name.png"
  crashed "$name"
done
# A fresh install opens on "Protect Muninn" (a lock is required).
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.MainActivity >/dev/null
sleep 5
adb exec-out screencap -p > "screens/$(printf %02d $((n+1)))-first-lock.png"
if adb logcat -d | grep -q "FATAL EXCEPTION"; then
  msg=$(adb logcat -d | grep -A20 "FATAL EXCEPTION" | head -30 | sed 's/%/%25/g' | sed ':a;N;$!ba;s/\n/%0A/g')
  echo "::error title=Crash in logcat::$msg"
  exit 1
fi
echo "All Muninn screens rendered without crashing"

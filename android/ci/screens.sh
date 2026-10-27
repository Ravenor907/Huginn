#!/usr/bin/env bash
# Runs inside the emulator: install the debug APK, open each tab, screenshot it, and fail if
# Muninn crashed anywhere. Then the same on a tablet-size display for the rail layout.
set -u
PKG=io.github.ravenor907.muninn
mkdir -p screens
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
for p in BLUETOOTH_ADVERTISE BLUETOOTH_CONNECT BLUETOOTH_SCAN NEARBY_WIFI_DEVICES ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do adb shell pm grant $PKG android.permission.$p 2>/dev/null || true; done
adb logcat -c
n=0
crashed() {
  if [ -z "$(adb shell pidof $PKG | tr -d '\r')" ]; then
    msg=$(adb logcat -d | grep -A20 "FATAL EXCEPTION" | head -30 | sed 's/%/%25/g' | sed ':a;N;$!ba;s/\n/%0A/g')
    echo "::error title=Muninn crashed on '$1'::$msg"
    exit 1
  fi
}
# shot NAME ARGS...: open Muninn with those extras and screenshot it.
shot() {
  local name=$1; shift
  n=$((n+1))
  adb shell am force-stop $PKG
  adb shell am start -W -n $PKG/.MainActivity --ez ci true "$@" >/dev/null
  sleep 5
  adb exec-out screencap -p > "screens/$(printf %02d $n)-$name.png"
  crashed "$name"
}
# The four tabs and Settings (the header gear).
for screen in chats calls files contacts settings; do
  s=$screen; [ "$s" = chats ] && s=chat
  shot "$screen" --es screen "$s"
done
# A conversation (top bar, bubbles, composer), the chat list's + menu and the lock screen.
shot conversation --es chat demo
shot new-chat-menu --es screen chat --ez menu true
# Groups: a group conversation (channel chips, sender names) and its settings (members, channels, folders).
shot group --ez group true
shot group-settings --ez group true --ez gsettings true
shot lock --es screen lock
# The Kids' TV voice processing library (echo canceller, RNNoise) loads and runs on Android.
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.MainActivity --ez ci true --ez tvvoice true >/dev/null
sleep 6
tvv=$(adb logcat -d -s Muninn:I | grep "TvVoice self-check" | tail -1)
case "$tvv" in
  *"self-check: ok"*) echo "$tvv" ;;
  *) echo "::error title=Kids' TV voice library::${tvv:-no self-check line in logcat}"; exit 1 ;;
esac
crashed tvvoice
# Tablet size (1280x800 at density 160, so 1280dp wide): the rail, chat list and conversation.
adb shell wm size 1280x800
adb shell wm density 160
sleep 2
shot tablet-chats --es screen chat
shot tablet-conversation --es chat demo
# The rail's group button, the channel column (TEXT, VOICE) and the group in the main column.
shot tablet-group --ez group true
shot tablet-files --es screen files
shot tablet-settings --es screen settings
adb shell wm size reset
adb shell wm density reset
sleep 2
# A fresh install opens on "Protect Muninn" (a lock is required).
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.MainActivity >/dev/null
sleep 5
adb exec-out screencap -p > "screens/$(printf %02d $((n+1)))-first-lock.png"
crashed first-lock
if adb logcat -d | grep -q "FATAL EXCEPTION"; then
  msg=$(adb logcat -d | grep -A20 "FATAL EXCEPTION" | head -30 | sed 's/%/%25/g' | sed ':a;N;$!ba;s/\n/%0A/g')
  echo "::error title=Crash in logcat::$msg"
  exit 1
fi
echo "All Muninn screens rendered without crashing"

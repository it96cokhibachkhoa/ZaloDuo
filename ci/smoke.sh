#!/bin/bash
# Chạy trên máy ảo Android trong GitHub Actions: cài app, mở, chụp màn hình, lấy log.
# Kết quả nằm trong ci-out/ và được đẩy lên nhánh ci/api-<level> để đọc lại.
set -x
PKG=app.zaloduo.client
OUT=ci-out
mkdir -p "$OUT"

{
  echo "android=$(adb shell getprop ro.build.version.release) api=$(adb shell getprop ro.build.version.sdk)"
  adb shell dumpsys webviewupdate | grep -iE "current webview package|version" | head -5
} > "$OUT/device.txt" 2>&1

adb install -r ZaloDuo*.apk > "$OUT/install.txt" 2>&1
adb logcat -c

# Lần mở đầu tiên (giống người dùng vừa cài).
adb shell am start -W -n $PKG/.MainActivity > "$OUT/start1.txt" 2>&1
sleep 15
adb exec-out screencap -p > "$OUT/1-first-launch.png"
adb shell pidof $PKG > "$OUT/pid-1.txt" 2>&1 || echo "NOT RUNNING" > "$OUT/pid-1.txt"

# Cấp quyền thông báo như khi người dùng bấm "Cho phép", rồi mở lại.
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS > /dev/null 2>&1
adb shell am start -W -n $PKG/.MainActivity > "$OUT/start2.txt" 2>&1
sleep 30
adb exec-out screencap -p > "$OUT/2-zalo-page.png"
adb shell pidof $PKG > "$OUT/pid-2.txt" 2>&1 || echo "NOT RUNNING" > "$OUT/pid-2.txt"
adb shell dumpsys activity services $PKG > "$OUT/services.txt" 2>&1

# Về màn hình chính: app phải còn sống nhờ dịch vụ chạy nền.
adb shell input keyevent KEYCODE_HOME
sleep 20
adb shell pidof $PKG > "$OUT/pid-after-home.txt" 2>&1 || echo "NOT RUNNING" > "$OUT/pid-after-home.txt"
adb shell dumpsys notification --noredact > "$OUT/notifications-full.txt" 2>&1
grep -n -A6 "pkg=$PKG" "$OUT/notifications-full.txt" > "$OUT/notifications.txt" 2>&1
rm -f "$OUT/notifications-full.txt"

# Mở lại lần nữa: nếu lần trước văng, màn hình báo lỗi sẽ hiện log.
adb shell am start -W -n $PKG/.MainActivity > "$OUT/start3.txt" 2>&1
sleep 8
adb exec-out screencap -p > "$OUT/3-relaunch.png"
adb shell run-as $PKG cat files/last_crash.txt > "$OUT/last_crash.txt" 2>&1

adb logcat -d -v time > "$OUT/logcat-full.txt" 2>&1
grep -E "AndroidRuntime|FATAL|ZaloDuo|zaloduo|ForegroundService|chromium.*(Error|error)|cr_" "$OUT/logcat-full.txt" > "$OUT/logcat-app.txt"
exit 0

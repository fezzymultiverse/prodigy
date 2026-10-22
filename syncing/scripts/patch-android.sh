#!/usr/bin/env bash
# S.O.I · Bojack Security: adds the native playback layer to the generated Capacitor project
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIR="$ROOT/android/app/src/main/java/com/fezzy/prodigy"
MAN="$ROOT/android/app/src/main/AndroidManifest.xml"
[ -d "$DIR" ] || { echo "❌ $DIR missing (did 'cap add android' fail?)"; exit 1; }
[ -f "$MAN" ] || { echo "❌ manifest missing: $MAN"; exit 1; }
cp -v "$ROOT"/native/*.java "$DIR/"
python3 - "$MAN" <<'PY'
import sys
p=sys.argv[1]; x=open(p,encoding='utf-8').read()
if 'PlaybackService' in x:
    print('manifest already patched'); sys.exit(0)
perms=['WAKE_LOCK','FOREGROUND_SERVICE','FOREGROUND_SERVICE_MEDIA_PLAYBACK','POST_NOTIFICATIONS','REQUEST_IGNORE_BATTERY_OPTIMIZATIONS','READ_MEDIA_AUDIO','RECORD_AUDIO','MODIFY_AUDIO_SETTINGS','FLASHLIGHT']
add=''.join('    <uses-permission android:name="android.permission.%s" />\n'%n for n in perms)
add+='    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />\n'
add+='    <uses-feature android:name="android.hardware.microphone" android:required="false" />\n'
add+='    <uses-feature android:name="android.hardware.camera.flash" android:required="false" />\n'
add+='    <queries><package android:name="com.termux" /><package android:name="com.whatsapp" /><package android:name="com.whatsapp.w4b" /><package android:name="com.facebook.katana" /><package android:name="com.waze" /></queries>\n\n    '
i=x.index('<application'); x=x[:i]+add+x[i:]
j=x.rindex('</application>')
x=x[:j]+'    <service android:name=".PlaybackService" android:exported="false" android:foregroundServiceType="mediaPlayback" />\n    '+x[j:]
for k in ('PlaybackService','WAKE_LOCK','FOREGROUND_SERVICE_MEDIA_PLAYBACK','READ_MEDIA_AUDIO','READ_EXTERNAL_STORAGE','RECORD_AUDIO','MODIFY_AUDIO_SETTINGS'):
    assert k in x,k
open(p,'w',encoding='utf-8').write(x); print('✅ manifest patched')
PY
python3 "$ROOT/scripts/gradle_newpipe.py" "$ROOT/android/build.gradle" "$ROOT/android/app/build.gradle"
STY="$ROOT/android/app/src/main/res/values/styles.xml"
if [ -f "$STY" ] && grep -q "@drawable/splash" "$STY"; then sed -i 's#@drawable/splash#@android:color/black#g' "$STY"; echo "✅ black launch splash"; fi
grep -q usesCleartextTraffic "$MAN" || sed -i '0,/<application/s##<application android:usesCleartextTraffic="true"#' "$MAN"
for P in ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION; do grep -q "$P" "$MAN" || sed -i "0,/<application/s##<uses-permission android:name=\"android.permission.$P\" />\n    <application#" "$MAN"; done
RES="$ROOT/android/app/src/main/res"
for D in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
  mkdir -p "$RES/mipmap-$D"
  cp "$ROOT/native/icons/ic_launcher-$D.png" "$RES/mipmap-$D/ic_launcher.png"
  cp "$ROOT/native/icons/ic_launcher-$D.png" "$RES/mipmap-$D/ic_launcher_round.png"
  cp "$ROOT/native/icons/ic_launcher_foreground-$D.png" "$RES/mipmap-$D/ic_launcher_foreground.png"
done
BG="$RES/values/ic_launcher_background.xml"
[ -f "$BG" ] && sed -i 's#<color name="ic_launcher_background">[^<]*#<color name="ic_launcher_background">\#0A0814#' "$BG"
echo "✅ cup icon installed"

# V75: every build gets a higher versionCode so a new APK/AAB installs over the old one as an UPDATE (needs the same signing key every time)
APPG="$ROOT/android/app/build.gradle"
VC="${VERSION_CODE:-${GITHUB_RUN_NUMBER:-1}}"; VN="${VERSION_NAME:-90.0.$VC}"
if [ -f "$APPG" ]; then
  sed -i -E "s/versionCode [0-9]+/versionCode $VC/; s/versionName \"[^\"]*\"/versionName \"$VN\"/" "$APPG"
  grep -E "versionCode|versionName" "$APPG" || true
  echo "✅ version $VN (code $VC)"
fi
echo "✅ native layer ready"

#!/usr/bin/env bash
# Builds agentbench-client.apk without Gradle: aidl (from AndyClaw's own app/src/main/aidl, so
# the client can never drift from the service it drives) -> javac -> d8 -> aapt2 -> sign.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
OUT="${OUT:-$here/../out/agentbench-client.apk}"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)"
JAR="$SDK/platforms/android-35/android.jar"
AIDL_ROOT="$repo/app/src/main/aidl"

work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
mkdir -p "$work/gen" "$work/classes" "$(dirname "$OUT")"

for f in ILauncherService ILauncherCallback IExecSummaryCallback; do
    "$BT/aidl" -I"$AIDL_ROOT" -p"$SDK/platforms/android-35/framework.aidl" -o"$work/gen" \
        "$AIDL_ROOT/org/ethereumphone/andyclaw/ipc/$f.aidl"
done
javac -nowarn -encoding UTF-8 -source 17 -target 17 -cp "$JAR" -d "$work/classes" \
    $(find "$work/gen" "$here/src" -name '*.java')
"$BT/d8" --min-api 30 --release --lib "$JAR" --output "$work" $(find "$work/classes" -name '*.class')
"$BT/aapt2" link -o "$work/base.apk" -I "$JAR" --manifest "$here/AndroidManifest.xml" \
    --min-sdk-version 30 --target-sdk-version 35
(cd "$work" && zip -q base.apk classes.dex)
"$BT/zipalign" -f -p 4 "$work/base.apk" "$work/aligned.apk"
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android --out "$OUT" "$work/aligned.apk"
echo "built $OUT"

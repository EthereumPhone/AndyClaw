#!/usr/bin/env bash
# Builds agentdisplayd.jar: the ethOS tree's AgentDisplayService.java, unmodified, plus the
# app_process entry point in src/. Compiled against the framework classes of the tree's last
# system build (out_sys), so it sees the same hidden APIs system_server does.
#
#   ETHOS_TREE   the ethOS AOSP tree (default ~/dgen1/v0mp1_test/git-V0MP1)
#   OUT          output jar (default ../out/agentdisplayd.jar)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
ETHOS_TREE="${ETHOS_TREE:-$HOME/dgen1/v0mp1_test/git-V0MP1}"
OUT="${OUT:-$here/../out/agentdisplayd.jar}"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
D8="$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)d8"

inter="$ETHOS_TREE/out_sys/soong/.intermediates"
fw="$(ls "$inter"/frameworks/base/framework-minus-apex/android_common/*/combined/framework.jar | head -1)"
core="$inter/libcore/core-all/android_common/turbine-combined/core-all.jar"
svc="$ETHOS_TREE/frameworks/base/services/java/com/android/server/AgentDisplayService.java"
for f in "$fw" "$core" "$svc"; do [[ -f "$f" ]] || { echo "missing: $f (build the tree's system image once)" >&2; exit 1; }; done

work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes" "$(dirname "$OUT")"

javac -nowarn -encoding UTF-8 -source 17 -target 17 -proc:none \
    -cp "$fw:$core" -d "$work/classes" \
    "$svc" "$here"/src/org/ethereumphone/agentbench/*.java 2>&1 | grep -v "^Note:" || true
[[ -f "$work/classes/com/android/server/AgentDisplayService.class" ]] || { echo "javac failed" >&2; exit 1; }

# The AIDL stubs exist only in ethOS's framework.jar; a stock image has none of them.
(cd "$work/classes" && unzip -q -o "$fw" 'android/os/IAgent*')

(cd "$work/classes" && find . -name '*.class' > ../list)
(cd "$work/classes" && "$D8" --min-api 34 --release --output "$work/dex.zip" \
    --classpath "$fw" --lib "$core" $(cat ../list) 2>&1 | grep -v "^Warning" || true)
cp "$work/dex.zip" "$OUT"
echo "built $OUT ($(stat -c %s "$OUT") bytes) from $(git -C "$ETHOS_TREE" log -1 --format='%h %s' -- "$svc")"

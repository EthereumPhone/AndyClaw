#!/usr/bin/env bash
# Writes out/dgen1-wallet-signin.sh: dgen1-wallet-signin.sh with WalletSignIn prebuilt and embedded,
# so it runs on any machine with adb (no ethOS tree, SDK build-tools or JDK needed).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
ETHOS_TREE="${ETHOS_TREE:-$HOME/dgen1/v0mp1_test/git-V0MP1}"
BT="$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)"
fw="$(ls "$ETHOS_TREE"/out_sys/soong/.intermediates/frameworks/base/framework-minus-apex/android_common/*/combined/framework.jar | head -1)"
out="$here/../out/dgen1-wallet-signin.sh"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT

javac -nowarn -source 17 -target 17 -cp "$fw" -d "$work" "$here/walletsign/WalletSignIn.java"
"$BT/d8" --min-api 34 --release --classpath "$fw" --output "$work" $(find "$work" -name '*.class') 2>/dev/null
sha="$(sha256sum "$work/classes.dex" | cut -d' ' -f1)"
rev="$(git -C "$here" rev-parse --short HEAD)"
mkdir -p "$(dirname "$out")"

{
cat <<EOF
#!/usr/bin/env bash
# agentbench wallet sign-in, self-contained: needs only adb (on PATH or under \$ANDROID_HOME).
# WalletSignIn.java from AndyClaw agentbench/tools/walletsign (AndyClaw $rev), dex sha256 $sha.
#
# Asks the connected dgen1's own 'wallet' service for its address and a signature of
# "Signing into AndyClaw" (chainId 8453, personal_sign), the call AndyClaw's onboarding makes.
# You approve it on the terminal screen; the key never leaves the phone.
#
#   ./dgen1-wallet-signin.sh [-s <serial>]
EOF
cat <<'EOF'
set -euo pipefail
ADB="$(command -v adb || echo "${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb")"
[[ -x "$ADB" ]] || { echo "adb not found (install platform-tools or set ANDROID_HOME)" >&2; exit 1; }
[[ "${1:-}" == "-s" ]] && { export ANDROID_SERIAL="$2"; shift 2; }
if [[ -z "${ANDROID_SERIAL:-}" ]]; then
    devs=($("$ADB" devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ {print $1}'))
    [[ ${#devs[@]} -eq 1 ]] || { echo "connect exactly one dgen1 (or pass -s <serial>); found: ${devs[*]:-none}" >&2; exit 1; }
    export ANDROID_SERIAL="${devs[0]}"
fi
echo "device: $ANDROID_SERIAL ($("$ADB" shell getprop ro.build.display.id | tr -d '\r'))" >&2
"$ADB" shell service check wallet | grep -q ": found" || { echo "no 'wallet' service on this device: not ethOS?" >&2; exit 1; }

tmp="$(mktemp)"; trap 'rm -f "$tmp"' EXIT
base64 -d > "$tmp" <<'DEX'
EOF
base64 -w0 "$work/classes.dex"; echo
echo "DEX"
echo "echo \"$sha  \$tmp\" | sha256sum -c --quiet - || { echo \"embedded program is corrupt (bad copy?)\" >&2; exit 1; }"
cat <<'EOF'
"$ADB" push "$tmp" /data/local/tmp/walletsignin.dex >/dev/null
"$ADB" shell chmod 444 /data/local/tmp/walletsignin.dex   # ART won't load a dex its process can write

"$ADB" shell input keyevent KEYCODE_WAKEUP
echo "asking the wallet: approve on the terminal screen (3 min timeout)" >&2
# `|| true`: under set -e a failing program would end the script here without a word.
out="$("$ADB" shell app_process -Djava.class.path=/data/local/tmp/walletsignin.dex /system/bin \
    org.ethereumphone.agentbench.WalletSignIn 2>&1 | tr -d '\r' || true)"
"$ADB" shell rm -f /data/local/tmp/walletsignin.dex || true
json="$(printf '%s\n' "$out" | grep '"walletSignature":"0x' || true)"
if [[ -z "$json" ]]; then
    echo "no signature. Everything the program printed:" >&2
    printf '%s\n' "$out" >&2
    exit 1
fi
echo "$json"
echo "^ this goes in ~/.config/agentbench/secrets.json on the machine that runs agentbench" >&2
EOF
} > "$out"
chmod +x "$out"
bash -n "$out"
echo "wrote $out ($(stat -c %s "$out") bytes)"

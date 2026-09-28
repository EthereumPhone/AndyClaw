#!/usr/bin/env bash
# Gets agentbench its wallet sign-in from a real dgen1: the address and its signature of
# "Signing into AndyClaw" (chainId 8453) — the same pair AndyClaw's onboarding stores.
#
#   1. Connect the dgen1 over adb (USB debugging on, screen unlocked).
#   2. agentbench/tools/dgen1-wallet-signin.sh [-s <serial>]
#   3. Approve the request on the terminal screen.
#
# The result is printed and written to ~/.config/agentbench/secrets.json (mode 600), where
# `agentbench up` picks it up. The key never leaves the phone: the phone signs, this reads the result.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
ETHOS_TREE="${ETHOS_TREE:-$HOME/dgen1/v0mp1_test/git-V0MP1}"
[[ "${1:-}" == "-s" ]] && { export ANDROID_SERIAL="$2"; shift 2; }

if [[ -z "${ANDROID_SERIAL:-}" ]]; then
    mapfile -t devs < <("$ADB" devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ {print $1}')
    [[ ${#devs[@]} -eq 1 ]] || { echo "connect exactly one dgen1 (or pass -s <serial>); found: ${devs[*]:-none}" >&2; exit 1; }
    export ANDROID_SERIAL="${devs[0]}"
fi
echo "device: $ANDROID_SERIAL ($("$ADB" shell getprop ro.product.model | tr -d '\r'), $("$ADB" shell getprop ro.build.display.id | tr -d '\r'))" >&2
"$ADB" shell service check wallet | grep -q ": found" || { echo "this device has no 'wallet' service: not ethOS?" >&2; exit 1; }

BT="$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)"
fw="$(ls "$ETHOS_TREE"/out_sys/soong/.intermediates/frameworks/base/framework-minus-apex/android_common/*/combined/framework.jar | head -1)"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
javac -nowarn -source 17 -target 17 -cp "$fw" -d "$work" "$here/walletsign/WalletSignIn.java"
"$BT/d8" --min-api 34 --release --classpath "$fw" --output "$work" $(find "$work" -name '*.class') 2>/dev/null
"$ADB" push "$work/classes.dex" /data/local/tmp/walletsignin.dex >/dev/null
# ART refuses to load a dex its process can write to (Android 14+).
"$ADB" shell chmod 444 /data/local/tmp/walletsignin.dex

"$ADB" shell input keyevent KEYCODE_WAKEUP
echo "asking the wallet — approve on the terminal screen (3 min timeout)…" >&2
out="$("$ADB" shell app_process -Djava.class.path=/data/local/tmp/walletsignin.dex /system/bin \
    org.ethereumphone.agentbench.WalletSignIn 2>/dev/null | tr -d '\r' | grep '^{' || true)"
"$ADB" shell rm -f /data/local/tmp/walletsignin.dex
if [[ "$out" != *'"walletSignature":"0x'* ]]; then
    echo "no signature (declined, timed out, or screen went off). Details: adb logcat -d | grep -i wallet" >&2
    exit 1
fi

mkdir -p "$HOME/.config/agentbench"
secrets="$HOME/.config/agentbench/secrets.json"
python3 - "$secrets" "$out" <<'PY'
import json, os, sys
path, new = sys.argv[1], json.loads(sys.argv[2])
cur = json.load(open(path)) if os.path.exists(path) else {}
cur.update(new)
fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, "w") as f:
    json.dump(cur, f, indent=2)
PY
echo "$out"
echo "saved to $secrets — now run: agentbench/agentbench up" >&2

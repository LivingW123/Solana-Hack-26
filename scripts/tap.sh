#!/usr/bin/env bash
# Fake NFC tap: deliver a Solana Pay release URL to the buyer emulator as a
# `solana:` deep link (TapTrade's intent filter → DeepLinkTapTransport).
#
#   scripts/tap.sh                      # tap the seller's last published URL (read from its logcat)
#   scripts/tap.sh <ESCROW> <NONCE>     # build the URL from parts (BACKEND below)
#   scripts/tap.sh --url '<solana:…>'   # deliver any URL as-is (e.g. a tampered one, Phase 6)
#
# Env: ADB (default: adb), SELLER (emulator-5554), BUYER (emulator-5556),
#      BACKEND (http://10.0.2.2:3000; the emulator's alias for the host machine).
set -euo pipefail
export MSYS_NO_PATHCONV=1 # Git Bash on Windows: don't rewrite the URL argument as a path

ADB="${ADB:-adb}"
SELLER="${SELLER:-emulator-5554}"
BUYER="${BUYER:-emulator-5556}"
BACKEND="${BACKEND:-http://10.0.2.2:3000}"

usage() { sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }

case "${1:-}" in
  -h|--help) usage ;;
  --url)
    [ $# -eq 2 ] || usage
    URL="$2" ;;
  "")
    URL="$("$ADB" -s "$SELLER" logcat -d -s DebugTap:I | grep 'publish: ' | tail -n 1 | sed 's/.*publish: //' | tr -d '\r')"
    if [ -z "$URL" ]; then
      echo "No published URL in $SELLER's log. On the seller: Ready for handoff → Publish for tap." >&2
      exit 1
    fi ;;
  *)
    [ $# -eq 2 ] || usage
    URL="solana:${BACKEND%/}/tx/release?escrow=$1&nonce=$2" ;;
esac

case "$URL" in
  solana:*) ;;
  *) echo "Not a solana: URL: $URL" >&2; exit 1 ;;
esac
case "$URL" in
  *"'"*) echo "URL must not contain single quotes" >&2; exit 1 ;;
esac

echo "tap → $BUYER: $URL"
# One string so the device shell keeps `&` inside the quotes; -p skips any app chooser.
"$ADB" -s "$BUYER" shell "am start -W -a android.intent.action.VIEW -p app.taptrade -d '$URL'"

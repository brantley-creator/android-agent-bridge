#!/usr/bin/env bash
# Drive a paired emulator or adb phone through the safety cases.
# This environment has no Android emulator. If adb or a device is missing the
# script prints the manual steps and exits 2.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_ID="dev.brantley.agentbridge"
HARNESS_ID="dev.brantley.agentbridge.harness"
A11Y_SERVICE="${APP_ID}/dev.brantley.agentbridge.a11y.BridgeAccessibilityService"
RELAY_URL="${ABRIDGE_RELAY_URL:-http://127.0.0.1:8787}"
PHONE_RELAY="${ABRIDGE_PHONE_RELAY:-}"

manual() {
  cat <<'EOF'
This machine cannot run the emulator flow (adb or a device is missing).
Do these steps on your computer and phone. Do not deploy the relay.

1. Build the debug APKs from the repo root (JDK 17, Android SDK 36):
     echo "sdk.dir=$ANDROID_HOME" > local.properties
     ./gradlew :app:assembleDebug :harness:assembleDebug
   APKs:
     app/build/outputs/apk/debug/app-debug.apk
     harness/build/outputs/apk/debug/harness-debug.apk

2. Sideload. On Android 13 and newer a normal file install shows
   "Allow restricted settings" on the app's App info screen before the
   accessibility service can be enabled. Installing with adb skips that gate:
     adb install -r app/build/outputs/apk/debug/app-debug.apk
     adb install -r harness/build/outputs/apk/debug/harness-debug.apk
   Then Settings → Accessibility → Agent Bridge → on.
   The service description says this is an automation tool.

3. Start the relay locally. Copy relay/.dev.vars.example to relay/.dev.vars
   and set RELAY_ADMIN_TOKEN to a long random string. Do not commit that file.
     cd relay && npm ci && npm run dev
   Leave it on http://127.0.0.1:8787.

4. Point the phone at that port without opening cleartext to the LAN.
   Debug builds allow cleartext only for localhost, 127.0.0.1, and 10.0.2.2.
   Physical phone:
     adb reverse tcp:8787 tcp:8787
     use relay URL http://127.0.0.1:8787
   Emulator: the box still calls http://127.0.0.1:8787, but the deep link the
   phone opens must use http://10.0.2.2:8787. Rewrite only the link.

5. Pair from the box (the admin token is not printed and is not in the link):
     cd box && npm ci
     ABRIDGE_ADMIN_TOKEN='your-token' npx tsx src/cli.ts pair --relay http://127.0.0.1:8787
   Open the printed agentbridge://pair link on the phone:
     adb shell am start -a android.intent.action.VIEW -d '<link>'
   The screen shows the relay host and room. Tap Pair. The phone stays disarmed.

6. Grant notifications and arm:
     adb shell pm grant dev.brantley.agentbridge android.permission.POST_NOTIFICATIONS
   Open Agent Bridge and tap Arm session. An ongoing notification
   "Agent Bridge armed" appears, with Stop.

7. From the box, with the config pair just wrote (~/.config/abridge/config.json, mode 0600):
     npx tsx src/cli.ts snapshot '{}'
   Launch Settings (always confirms, even in Writes mode):
     npx tsx src/cli.ts launch '{"package":"com.android.settings"}'
     npx tsx src/cli.ts snapshot '{}'
   On the phone tap Approve on the confirm overlay, then:
     npx tsx src/cli.ts tap '{"ref":"<ref from that snapshot>"}'
   Repeat the same ref. Expect STALE_REF.
   Send another tap and tap Deny. Expect DENIED_BY_USER.

8. Kill switch. Tap Stop on the notification, or:
     adb shell am start-foreground-service -a dev.brantley.agentbridge.STOP \
       -n dev.brantley.agentbridge/dev.brantley.agentbridge.safety.ArmedService
   The next call returns PAUSED:
     npx tsx src/cli.ts status '{}'

9. Arm again. Launch the bridge package itself (it is on the floor denylist):
     npx tsx src/cli.ts launch '{"package":"dev.brantley.agentbridge"}'
   Expect DENIED_APP and no tree. Launch the harness instead:
     npx tsx src/cli.ts launch '{"package":"dev.brantley.agentbridge.harness"}'
     npx tsx src/cli.ts snapshot '{}'
   The password field text is "[redacted]". Type into that ref:
     npx tsx src/cli.ts type '{"ref":"<password ref>","text":"not-stored"}'
   Expect PASSWORD_FIELD. Open Audit log in the app. The row shows a typed
   length and does not show the characters.

10. Disarm from the notification, the Quick Settings tile, or Disarm in the app.
    Settings → Unpair and clear the audit log drops the token and the log.
EOF
}

tap_label() {
  local label="$1"
  local dump
  dump="$(mktemp)"
  adb shell uiautomator dump /sdcard/abridge-ui.xml >/dev/null
  adb pull /sdcard/abridge-ui.xml "$dump" >/dev/null
  local point
  point="$(python3 - "$dump" "$label" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
want = sys.argv[2]
for node in re.findall(r"<node\b[^>]*>", xml):
    text = re.search(r'\btext="([^"]*)"', node)
    desc = re.search(r'\bcontent-desc="([^"]*)"', node)
    bounds = re.search(r'\bbounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    label = (text.group(1) if text else "") or (desc.group(1) if desc else "")
    if label == want and bounds:
        l, t, r, b = map(int, bounds.groups())
        print((l + r) // 2, (t + b) // 2)
        break
PY
)"
  rm -f "$dump"
  if [[ -z "$point" ]]; then
    echo "Could not find '$label' on screen" >&2
    return 1
  fi
  # shellcheck disable=SC2086
  adb shell input tap $point
}

first_ref() {
  python3 -c 'import json,sys
data=json.load(sys.stdin)
found=[]
def walk(node):
    if isinstance(node, dict):
        if isinstance(node.get("ref"), str):
            found.append(node["ref"])
        for value in node.values():
            walk(value)
    elif isinstance(node, list):
        for item in node:
            walk(item)
walk(data)
print(found[0] if found else "")'
}

if ! command -v adb >/dev/null 2>&1; then
  manual
  exit 2
fi

if ! adb get-state >/dev/null 2>&1; then
  manual
  exit 2
fi

if [[ -z "${ABRIDGE_ADMIN_TOKEN:-}" ]]; then
  echo "Set ABRIDGE_ADMIN_TOKEN to the value in relay/.dev.vars. It is not printed." >&2
  exit 2
fi

if [[ -z "$PHONE_RELAY" ]]; then
  if adb shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r' | grep -q '^1$'; then
    PHONE_RELAY="${RELAY_URL/127.0.0.1/10.0.2.2}"
    PHONE_RELAY="${PHONE_RELAY/localhost/10.0.2.2}"
  else
    adb reverse tcp:8787 tcp:8787 || true
    PHONE_RELAY="$RELAY_URL"
  fi
fi

APP_APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
HARNESS_APK="$ROOT/harness/build/outputs/apk/debug/harness-debug.apk"
if [[ ! -f "$APP_APK" || ! -f "$HARNESS_APK" ]]; then
  (cd "$ROOT" && ./gradlew :app:assembleDebug :harness:assembleDebug)
fi

adb install -r "$APP_APK"
adb install -r "$HARNESS_APK"
adb shell pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS || true

PAIR_OUT="$(cd "$ROOT/box" && npx tsx src/cli.ts pair --relay "$RELAY_URL")"
LINK="$(printf '%s\n' "$PAIR_OUT" | head -n 1)"
PHONE_LINK="$(python3 - "$LINK" "$RELAY_URL" "$PHONE_RELAY" <<'PY'
import sys, urllib.parse
link, src, dst = sys.argv[1:]
parts = urllib.parse.urlsplit(link)
query = urllib.parse.parse_qsl(parts.query, keep_blank_values=True)
query = [(k, dst if k == "relay" and v == src else v) for k, v in query]
print(urllib.parse.urlunsplit((parts.scheme, parts.netloc, parts.path, urllib.parse.urlencode(query), parts.fragment)))
PY
)"

adb shell am start -a android.intent.action.VIEW -d "$PHONE_LINK"
sleep 2
tap_label "Pair"
sleep 2

adb shell settings put secure enabled_accessibility_services "$A11Y_SERVICE"
adb shell settings put secure accessibility_enabled 1
sleep 1

adb shell am start -n "$APP_ID/.ui.MainActivity"
sleep 1
tap_label "Arm session"
sleep 2

cli() {
  (cd "$ROOT/box" && npx tsx src/cli.ts "$@")
}

echo "--- snapshot ---"
cli snapshot '{}'
echo "--- launch settings ---"
cli launch '{"package":"com.android.settings"}' || true
sleep 1
SNAP="$(cli snapshot '{}')"
printf '%s\n' "$SNAP"
REF="$(printf '%s' "$SNAP" | first_ref)"
if [[ -z "$REF" ]]; then
  echo "No ref in the Settings snapshot. Approve may still be waiting, or Settings is blocked." >&2
  exit 1
fi
echo "--- tap $REF after Approve ---"
cli tap "{\"ref\":\"$REF\"}" > /tmp/abridge-tap.json &
sleep 1
tap_label "Approve"
wait || true
cat /tmp/abridge-tap.json || true
echo "--- stale ref ---"
cli tap "{\"ref\":\"$REF\"}" || true
echo "--- deny ---"
cli tap "{\"ref\":\"$REF\"}" > /tmp/abridge-deny.json &
sleep 1
tap_label "Deny" || true
wait || true
cat /tmp/abridge-deny.json || true
echo "--- kill ---"
adb shell am start-foreground-service -a dev.brantley.agentbridge.STOP \
  -n "$APP_ID/dev.brantley.agentbridge.safety.ArmedService" \
  || adb shell am startservice -a dev.brantley.agentbridge.STOP \
       -n "$APP_ID/dev.brantley.agentbridge.safety.ArmedService"
sleep 1
cli status '{}' || true

echo "--- re-arm ---"
adb shell am start -n "$APP_ID/.ui.MainActivity"
sleep 1
tap_label "Arm session"
sleep 2
echo "--- denylist ---"
cli launch "{\"package\":\"$APP_ID\"}" || true
echo "--- harness password ---"
cli launch "{\"package\":\"$HARNESS_ID\"}" || true
sleep 1
HARNESS_SNAP="$(cli snapshot '{}' || true)"
printf '%s\n' "$HARNESS_SNAP"
PREF="$(printf '%s' "$HARNESS_SNAP" | first_ref)"
if [[ -n "$PREF" ]]; then
  cli type "{\"ref\":\"$PREF\",\"text\":\"not-stored\"}" || true
fi
echo "Open Audit log in the app. Typed text is stored as a length only."

#!/usr/bin/env bash
# Drive a paired emulator or a USB phone through the safety cases.
# If adb or a device is missing, the script prints the manual steps and exits 2.
#
# A physical device (ro.kernel.qemu is not 1) is not an unattended emulator.
# This script does not rewrite enabled_accessibility_services there, and it
# does not inject taps. It prints a MANUAL: line and waits for Enter so a
# person can tap Approve, Deny, Stop, the Quick Settings tile, or Disarm.
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

is_emulator() {
  adb shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r' | grep -q '^1$'
}

cli() {
  (cd "$ROOT/box" && npx tsx src/cli.ts "$@")
}

# Quote the link on the device. An unquoted & is a shell command separator,
# so the room and pair code never reach the activity.
open_pair_link() {
  local link="$1"
  adb shell am start -a android.intent.action.VIEW -d "'$link'"
}

require_admin_token() {
  if [[ -z "${ABRIDGE_ADMIN_TOKEN:-}" ]]; then
    echo "Set ABRIDGE_ADMIN_TOKEN to the value in relay/.dev.vars. It is not printed." >&2
    exit 2
  fi
}

run_emulator() {
require_admin_token

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

open_pair_link "$PHONE_LINK"
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
}

wait_manual() {
  printf 'MANUAL: %s\n' "$1"
  echo "MANUAL_WAIT"
  local ack
  if ! IFS= read -r ack; then
    echo "Manual step was not acknowledged. Refusing to continue." >&2
    exit 2
  fi
}

note() {
  local status="$1" id="$2" detail="$3"
  printf '%s\t%s\t%s\n' "$status" "$id" "$detail" | tee -a "$RESULTS_DIR/checks.tsv"
  if [[ "$status" != "PASS" ]]; then
    FAILURES=$((FAILURES + 1))
  fi
}

shot() {
  local name="$1"
  adb exec-out screencap -p > "$RESULTS_DIR/${name}.png"
}

save_cli() {
  local dest="$1"
  shift
  set +e
  cli "$@" > "$dest"
  set -e
}

envelope_field() {
  local file="$1" kind="$2"
  python3 - "$file" "$kind" <<'PY'
import json, re, sys
path, kind = sys.argv[1], sys.argv[2]
raw = open(path, encoding="utf-8", errors="replace").read().strip()
line = raw.splitlines()[-1] if raw else "{}"
try:
    data = json.loads(line)
except json.JSONDecodeError:
    print("BAD_JSON")
    raise SystemExit
if kind == "code":
    if data.get("ok") is True:
        print("OK")
    else:
        print((data.get("error") or {}).get("code") or "UNKNOWN")
elif kind == "refs":
    print("yes" if re.search(r"@[A-Za-z0-9_-]+:e[1-9][0-9]*", json.dumps(data)) else "no")
elif kind == "tree":
    blob = json.dumps(data.get("data") or {})
    print("yes" if '"tree"' in blob else "no")
elif kind == "first_ref":
    found = []
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
    print(found[0] if found else "")
PY
}

package_installed() {
  adb shell pm path "$1" >/dev/null 2>&1
}

service_enabled() {
  local enabled
  enabled="$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"
  [[ "$enabled" == *"$A11Y_SERVICE"* ]]
}

physical_setup() {
  adb reverse tcp:8787 tcp:8787 || true
  PHONE_RELAY="$RELAY_URL"
  if package_installed "$APP_ID" && package_installed "$HARNESS_ID"; then
    echo "Apps already installed. Not reinstalling."
  else
    local app_apk="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
    local harness_apk="$ROOT/harness/build/outputs/apk/debug/harness-debug.apk"
    if [[ ! -f "$app_apk" || ! -f "$harness_apk" ]]; then
      (cd "$ROOT" && ./gradlew :app:assembleDebug :harness:assembleDebug)
    fi
    adb install -r "$app_apk"
    adb install -r "$harness_apk"
  fi
  adb shell pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS || true
  if [[ -f "$HOME/.config/abridge/config.json" ]]; then
    echo "Pair config already present. Not starting a new pair."
  else
    require_admin_token
    local pair_out link
    pair_out="$(cd "$ROOT/box" && npx tsx src/cli.ts pair --relay "$RELAY_URL")"
    link="$(printf '%s\n' "$pair_out" | head -n 1)"
    echo "Open this pair link on the phone. The admin token is not in it."
    printf '%s\n' "$link"
    open_pair_link "$link"
    wait_manual "Check the host is 127.0.0.1 and the room matches the link, then tap Pair."
  fi
  if service_enabled; then
    echo "Accessibility service is already enabled. Leaving that setting alone."
  else
    wait_manual "Settings > Accessibility > Installed apps > Agent Bridge > On. If the switch is greyed out, open the app's App info menu and choose Allow restricted settings, then turn the service on."
    if ! service_enabled; then
      note FAIL setup "Accessibility service is still off."
      return 1
    fi
  fi
}

begin_confirm() {
  local instruction="$1"
  local shot_name="$2"
  local dest="$3"
  shift 3
  wait_manual "$instruction"
  set +e
  cli "$@" > "$dest" &
  local pid=$!
  set -e
  sleep 2
  if [[ -n "$shot_name" ]]; then
    shot "$shot_name"
  fi
  set +e
  wait "$pid"
  set -e
}

run_physical() {
  RESULTS_DIR="$ROOT/test-results"
  mkdir -p "$RESULTS_DIR"
  : > "$RESULTS_DIR/checks.tsv"
  FAILURES=0
  physical_setup || return 1

  wait_manual "Press Home so Agent Bridge is not the foreground app. A snapshot of this app itself is denied."
  save_cli "$RESULTS_DIR/a-snapshot.json" snapshot '{}'
  local code refs
  code="$(envelope_field "$RESULTS_DIR/a-snapshot.json" code)"
  refs="$(envelope_field "$RESULTS_DIR/a-snapshot.json" refs)"
  if [[ "$code" == "OK" && "$refs" == "yes" ]]; then
    note PASS a "Snapshot returned a tree with @snap refs."
  else
    note FAIL a "snapshot code=$code refs=$refs"
  fi
  local old_ref
  old_ref="$(envelope_field "$RESULTS_DIR/a-snapshot.json" first_ref)"

  begin_confirm "Look at the phone. After you acknowledge, a Settings launch overlay appears. Tap Approve within 20 seconds." "b-approve" "$RESULTS_DIR/b-launch.json" launch '{"package":"com.android.settings"}'
  code="$(envelope_field "$RESULTS_DIR/b-launch.json" code)"
  if [[ "$code" == "OK" ]]; then
    note PASS b "Settings launch was approved."
  else
    note FAIL b "launch code=$code"
  fi

  if [[ -z "$old_ref" ]]; then
    note FAIL c "No ref from the pre-launch snapshot to reuse."
  else
    save_cli "$RESULTS_DIR/c-stale.json" tap "{\"ref\":\"$old_ref\"}"
    code="$(envelope_field "$RESULTS_DIR/c-stale.json" code)"
    if [[ "$code" == "STALE_REF" ]]; then
      note PASS c "Reused $old_ref after the screen change."
    else
      note FAIL c "tap of $old_ref returned $code"
    fi
  fi

  begin_confirm "A confirm overlay is about to appear. Tap Deny within 20 seconds." "d-deny" "$RESULTS_DIR/d-deny.json" tap '{"x":200,"y":400}'
  code="$(envelope_field "$RESULTS_DIR/d-deny.json" code)"
  if [[ "$code" == "DENIED_BY_USER" ]]; then
    note PASS d "Deny returned DENIED_BY_USER."
  else
    note FAIL d "deny code=$code"
  fi

  wait_manual "A confirm overlay is about to appear. Do not tap it. Acknowledge only when you are looking at the phone and will leave it alone for 20 seconds."
  save_cli "$RESULTS_DIR/e-timeout.json" tap '{"x":220,"y":420}'
  code="$(envelope_field "$RESULTS_DIR/e-timeout.json" code)"
  if [[ "$code" == "CONFIRM_TIMEOUT" ]]; then
    note PASS e "No answer returned CONFIRM_TIMEOUT, which is recorded as a denial."
  else
    note FAIL e "timeout code=$code"
  fi

  save_cli "$RESULTS_DIR/f-denylist.json" launch "{\"package\":\"$APP_ID\"}"
  code="$(envelope_field "$RESULTS_DIR/f-denylist.json" code)"
  local has_tree
  has_tree="$(envelope_field "$RESULTS_DIR/f-denylist.json" tree)"
  if [[ "$code" == "DENIED_APP" && "$has_tree" == "no" ]]; then
    note PASS f "Launching the bridge package returned DENIED_APP with no tree."
  else
    note FAIL f "denylist code=$code tree=$has_tree"
  fi

  begin_confirm "A confirm overlay is about to appear for the harness app. Tap Approve within 20 seconds." "" "$RESULTS_DIR/g-launch.json" launch "{\"package\":\"$HARNESS_ID\"}"
  code="$(envelope_field "$RESULTS_DIR/g-launch.json" code)"
  if [[ "$code" != "OK" ]]; then
    note FAIL g "Harness launch code=$code"
  else
    sleep 1
    save_cli "$RESULTS_DIR/g-snapshot.json" snapshot '{}'
    shot "g-harness"
    python3 - "$RESULTS_DIR/g-snapshot.json" "$RESULTS_DIR/g-password-ref.txt" <<'PY'
import json, sys
data = json.loads(open(sys.argv[1], encoding="utf-8").read().splitlines()[-1])
found = None
def walk(node):
    global found
    if found is not None or not isinstance(node, dict):
        if isinstance(node, list):
            for item in node:
                walk(item)
        return
    states = node.get("states") or {}
    if states.get("password") is True and node.get("text") == "[redacted]" and isinstance(node.get("ref"), str):
        found = node["ref"]
        return
    for value in node.values():
        walk(value)
walk(data)
open(sys.argv[2], "w").write(found or "")
print("password_ref=" + (found or ""))
PY
    local pref
    pref="$(cat "$RESULTS_DIR/g-password-ref.txt")"
    if [[ -z "$pref" ]]; then
      note FAIL g "Password field was not [redacted] with a ref."
    else
      save_cli "$RESULTS_DIR/g-type.json" type "{\"ref\":\"$pref\",\"text\":\"not-stored\"}"
      code="$(envelope_field "$RESULTS_DIR/g-type.json" code)"
      if adb exec-out run-as "$APP_ID" cat files/audit.jsonl > "$RESULTS_DIR/g-audit.jsonl"; then
        :
      else
        echo "run-as could not read the audit log" >&2
        : > "$RESULTS_DIR/g-audit.jsonl"
      fi
      if python3 - "$RESULTS_DIR/g-audit.jsonl" <<'PY'
import json, sys
secret = "not-stored"
text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
if secret in text:
    raise SystemExit("secret leaked")
found = False
for line in text.splitlines():
    if not line.strip():
        continue
    row = json.loads(line)
    if row.get("result") == "PASSWORD_FIELD" and row.get("typedLength") == 10:
        found = True
if not found:
    raise SystemExit("missing typedLength 10")
PY
      then
        if [[ "$code" == "PASSWORD_FIELD" ]]; then
          note PASS g "Password field was [redacted], type returned PASSWORD_FIELD, audit stored length 10."
        else
          note FAIL g "type code=$code but the audit row looked right"
        fi
      else
        note FAIL g "type code=$code and the audit log did not store only a length"
      fi
    fi
  fi
  wait_manual "Open Agent Bridge and tap Audit log. Leave that screen up so it can be captured."
  shot "g-audit"

  wait_manual "In Agent Bridge, open Settings and turn on Allow screenshots. Then press Home so this app is not in front. Leave the switch on only for this capture."
  save_cli "$RESULTS_DIR/h-screenshot.json" screenshot '{}'
  code="$(envelope_field "$RESULTS_DIR/h-screenshot.json" code)"
  if [[ "$code" == "OK" ]] && python3 - "$RESULTS_DIR/h-screenshot.json" "$RESULTS_DIR/h-screenshot.png" <<'PY'
import base64, json, sys
data = json.loads(open(sys.argv[1], encoding="utf-8").read().splitlines()[-1])
payload = data.get("data") or {}
raw = base64.b64decode(payload.get("base64") or "")
if payload.get("mime") != "image/png" or not raw.startswith(b"\x89PNG"):
    raise SystemExit(1)
open(sys.argv[2], "wb").write(raw)
PY
  then
    note PASS h "Screenshot returned a PNG."
  else
    note FAIL h "screenshot code=$code"
  fi

  wait_manual "Pull down the notification shade and tap Stop on Agent Bridge armed. Leave the shade open if you can."
  shot "i-notification"
  save_cli "$RESULTS_DIR/i-notification.json" status '{}'
  code="$(envelope_field "$RESULTS_DIR/i-notification.json" code)"
  if python3 - "$RESULTS_DIR/i-notification.json" <<'PY'
import json, sys
data = json.loads(open(sys.argv[1], encoding="utf-8").read().splitlines()[-1])
err = (data.get("error") or {}).get("code")
armed = (data.get("data") or {}).get("armed")
raise SystemExit(0 if err == "PAUSED" or armed is False else 1)
PY
  then
    note PASS i-notification "Stop on the notification paused the session."
  else
    note FAIL i-notification "status code=$code"
  fi

  wait_manual "Open Agent Bridge and tap Arm session."
  wait_manual "Pull down Quick Settings and tap the Agent Bridge tile."
  shot "i-tile"
  save_cli "$RESULTS_DIR/i-tile.json" status '{}'
  code="$(envelope_field "$RESULTS_DIR/i-tile.json" code)"
  if python3 - "$RESULTS_DIR/i-tile.json" <<'PY'
import json, sys
data = json.loads(open(sys.argv[1], encoding="utf-8").read().splitlines()[-1])
err = (data.get("error") or {}).get("code")
armed = (data.get("data") or {}).get("armed")
raise SystemExit(0 if err == "PAUSED" or armed is False else 1)
PY
  then
    note PASS i-tile "The Quick Settings tile paused the session."
  else
    note FAIL i-tile "status code=$code"
  fi

  wait_manual "Open Agent Bridge and tap Arm session again."
  wait_manual "In Agent Bridge, tap Disarm."
  shot "i-disarm"
  save_cli "$RESULTS_DIR/i-disarm.json" status '{}'
  code="$(envelope_field "$RESULTS_DIR/i-disarm.json" code)"
  if python3 - "$RESULTS_DIR/i-disarm.json" <<'PY'
import json, sys
data = json.loads(open(sys.argv[1], encoding="utf-8").read().splitlines()[-1])
err = (data.get("error") or {}).get("code")
armed = (data.get("data") or {}).get("armed")
raise SystemExit(0 if err == "PAUSED" or armed is False else 1)
PY
  then
    note PASS i-disarm "Disarm paused the session."
  else
    note FAIL i-disarm "status code=$code"
  fi

  wait_manual "Open Agent Bridge and tap Arm session, then press Home. The MCP snapshot needs the session armed and this app in the background."
  if ROOT="$ROOT" MCP_OUT="$RESULTS_DIR/j-mcp.json" python3 - <<'PY'
import json, os, subprocess, sys
root = os.environ["ROOT"]
proc = subprocess.Popen(
    ["npx", "tsx", "src/cli.ts", "mcp"],
    cwd=os.path.join(root, "box"),
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    text=True,
)
def send(msg):
    proc.stdin.write(json.dumps(msg) + "\n")
    proc.stdin.flush()
def recv():
    line = proc.stdout.readline()
    if not line:
        err = proc.stderr.read()
        raise SystemExit("mcp closed stdout: " + err[-500:])
    return json.loads(line)
send({
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
        "protocolVersion": "2025-11-25",
        "capabilities": {},
        "clientInfo": {"name": "e2e", "version": "0"},
    },
})
recv()
send({"jsonrpc": "2.0", "method": "notifications/initialized"})
send({"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}})
listed = recv()
tools = [tool.get("name") for tool in listed.get("result", {}).get("tools", [])]
expected = [
    "phone_status", "phone_snapshot", "phone_tap", "phone_type", "phone_swipe",
    "phone_scroll", "phone_key", "phone_launch", "phone_list_apps", "phone_wait",
    "phone_screenshot",
]
missing = [name for name in expected if name not in tools]
send({
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {"name": "phone_snapshot", "arguments": {}},
})
called = recv()
proc.stdin.close()
proc.wait(timeout=10)
content = (called.get("result") or {}).get("content") or []
text = content[0].get("text") if content else ""
envelope = json.loads(text) if text else {}
blob = json.dumps(envelope)
out = {
    "missing": missing,
    "snapshot_ok": envelope.get("ok") is True,
    "has_ref": "@" in blob and ":e" in blob,
    "error": (envelope.get("error") or {}).get("code"),
}
open(os.environ["MCP_OUT"], "w").write(json.dumps(out))
print(json.dumps(out))
if missing or not out["snapshot_ok"] or not out["has_ref"]:
    sys.exit(1)
PY
  then
    note PASS j "abridge mcp listed the phone_* tools and phone_snapshot returned a tree."
  else
    note FAIL j "MCP tools/list or phone_snapshot failed. See test-results/j-mcp.json."
  fi

  local pid
  pid="$(adb shell pidof "$APP_ID" | tr -d '\r' || true)"
  if [[ -n "$pid" ]]; then
    adb logcat -d --pid="$pid" > "$RESULTS_DIR/logcat.txt" || true
  else
    adb logcat -d | grep -F "$APP_ID" > "$RESULTS_DIR/logcat.txt" || true
  fi
  echo "Checks recorded in $RESULTS_DIR/checks.tsv"
  if [[ "$FAILURES" -ne 0 ]]; then
    echo "$FAILURES check(s) failed." >&2
    return 1
  fi
}

if is_emulator; then
  run_emulator
else
  run_physical
fi

# Android Agent Bridge

A private sideload tool. An Android app dials out to a small relay. A CLI and MCP server on a Linux box talk to that relay. The agent can read the screen as an element tree with refs, then tap, type, swipe, press keys, and launch apps.

The phone is the only safety boundary. The relay checks tokens and forwards frames. Nothing on the box or the relay can arm the phone, skip a confirm, or turn the kill switch off.

## Build

You need JDK 17, the Android SDK (compile SDK 36, build-tools 35), and Node 20 or newer.

```sh
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug :harness:assembleDebug :app:testDebugUnitTest
cd relay && npm ci && npm test
cd ../box && npm ci && npm test
```

`local.properties` is gitignored. Debug APKs land in `app/build/outputs/apk/debug/` and `harness/build/outputs/apk/debug/`. The harness app is only a password-field fixture for the emulator script. It is not a control path.

The relay runs locally:

```sh
cp relay/.dev.vars.example relay/.dev.vars
# set RELAY_ADMIN_TOKEN to a long random string
cd relay && npm run dev
```

`.dev.vars` is gitignored. Do not put a live token, a personal relay URL, or a device name into the repo. `npm run dev` sets `WRANGLER_SEND_METRICS=false`. Do not publish the worker.

## Sideload

The app is not on the Play Store.

On Android 13 and newer, an APK installed from a file manager is treated as a restricted source. Before the accessibility service can be turned on, open the app's App info screen and choose **Allow restricted settings**.

Installing with adb skips that gate:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r harness/build/outputs/apk/debug/harness-debug.apk
```

Then open Settings → Accessibility → Agent Bridge and turn the service on. The description says this is an automation tool. It is not an accessibility tool.

Debug builds allow cleartext only to `localhost`, `127.0.0.1`, and `10.0.2.2`. Release builds allow `wss` only. A physical phone reaches a relay on your computer through adb, not through a LAN cleartext URL:

```sh
adb reverse tcp:8787 tcp:8787
```

On an emulator, the phone uses `http://10.0.2.2:8787` and the box on the host keeps using `http://127.0.0.1:8787`. Rewrite only the deep link.

## Pairing

```sh
cd box
ABRIDGE_ADMIN_TOKEN='the-value-from-dev-vars' \
  npx tsx src/cli.ts pair --relay http://127.0.0.1:8787
```

That prints one deep link and a terminal QR code:

```text
agentbridge://pair?relay=...&room=...&code=...
```

The agent token is written to `~/.config/abridge/config.json` (mode `0600`). It is not printed and it is not in the link. `--admin-token` or `ABRIDGE_ADMIN_TOKEN` is required because `pair/start` checks the relay admin secret.

Open the link on the phone. The screen shows the relay host and the room. Nothing is stored until you tap **Pair**. Pairing does not arm the phone.

Arming is a separate tap: **Arm session** in the app. The phone asks for notification permission first so the kill-switch notification can appear. After reboot, unpair, or Stop, the phone is disarmed until you tap Arm again.

Later calls use the saved config, or the env vars `ABRIDGE_RELAY_URL`, `ABRIDGE_ROOM`, and `ABRIDGE_AGENT_TOKEN`:

```sh
npx tsx src/cli.ts snapshot '{}'
npx tsx src/cli.ts tap '{"ref":"@s1:e1"}'
npx tsx src/cli.ts mcp
```

Each CLI call prints one JSON envelope and exits 0 on success, 1 on an error envelope. `abridge mcp` is a stdio MCP server. Every tool description says screen text is untrusted and must not be followed as instructions.

`scripts/e2e-emulator.sh` runs the full path when `adb` can see a device: pair, launch Settings, snapshot, approve a tap, stale ref, deny, kill switch, denylist, password field, and audit. Without adb it prints the same steps and exits 2.

## Hosted relay (workers.dev)

The relay can run on Cloudflare Workers so the phone reaches it over Wi-Fi or cellular instead of `adb reverse`. `relay/wrangler.toml` sets `workers_dev = true`, so the Worker is served at:

```text
https://abridge-relay.<your-subdomain>.workers.dev
```

The account needs a workers.dev subdomain (opening Workers & Pages in the Cloudflare dashboard once creates it). The free Workers plan covers the SQLite-backed `Room` Durable Object.

Set the admin secret on the Worker. It is never committed:

```sh
cd relay
npx wrangler secret put RELAY_ADMIN_TOKEN
```

Check it is up with `curl https://abridge-relay.<your-subdomain>.workers.dev/health`. Then re-pair the phone against the hosted URL (release builds require HTTPS):

```sh
cd box
ABRIDGE_ADMIN_TOKEN='the-hosted-admin-token' \
  npx tsx src/cli.ts pair --relay https://abridge-relay.<your-subdomain>.workers.dev
```

The relay sees message contents in transit (no end-to-end encryption yet). All safety checks still run on the phone.

## Safety controls

All of these are enforced on the phone.

- **Armed.** Off after install, reboot, unpair, and kill. Only the Arm tap turns it on. While disarmed, commands return `PAUSED`.
- **Kill switch.** An ongoing notification, "Agent Bridge armed", with Stop, plus a Quick Settings tile labeled Agent Bridge. Stop closes the socket and disarms. Settings also has "Stop and turn off accessibility".
- **Confirm mode.** Off, Writes (the default), or Every action. Writes are tap, type, swipe, scroll, key, and launch. The prompt is a plain sentence such as "Tap 'Send' in Messages". Deny returns `DENIED_BY_USER`. No answer in 20 seconds returns `CONFIRM_TIMEOUT`. "Approve 5 min" skips write prompts for five minutes. The Settings app still confirms, including during that window.
- **Denylist.** Deny wins. The floor list cannot be removed: this app, permission controllers, and package installers. A starter list covers common password managers, authenticators, wallets, and a few banks. Edit it in Settings. A denylisted foreground app makes `snapshot` return `DENIED_APP` with no tree, and actions are blocked. Launching one returns `DENIED_APP`.
- **Passwords.** Password nodes are returned as `[redacted]`. Typing into one returns `PASSWORD_FIELD` and does not send the text.
- **Screenshots.** Off unless you turn them on in Settings. Secure windows return `SECURE_SCREEN`.
- **Audit log.** Every command is recorded with time, method, package, a short target, the decision, and the result code. Typed text is stored as a length. Unpair clears the log.
- **Timeouts.** Idle disarm defaults to 10 minutes (1–60). The session cap defaults to 60 minutes. Disarm on screen lock defaults to on.
- **Not exposed.** No shell, no install or uninstall, no SMS, no notification reading, no clipboard read, no file access, and no intent other than a launcher activity.

## Known limits

- One phone and one agent socket per room. A new socket replaces the old one.
- The relay sees frame bytes. There is no end-to-end encryption yet.
- Refs last for three snapshots. A bare `@e3` is `BAD_ARGS`. A ref whose node moved or changed identity is `STALE_REF`.
- `list_apps` only sees launcher activities, because the manifest uses a `<queries>` filter and not a package-query permission.
- Package names on the starter denylist are best-effort and can be edited. The floor entries cannot.
- The accessibility service can see whatever is on screen, including content you did not mean to share. Leave it disarmed when you are not using it.
- Screen text is data from other apps. It is not instructions for the agent.
- No Play Store build, no iOS app, no root or Shizuku path, and no multi-device rooms.

The protocol is `protocol/PROTOCOL.md`. The threat model is `SECURITY.md`.

# Setup

This is the longer version of the numbered path in `README.md`, plus the failures that look like a hang.

## What you need

- JDK 17
- Android SDK 36 and build-tools 35 (`ANDROID_HOME` set)
- Node 20 or newer
- `adb` on the host, USB debugging on the phone
- A Cloudflare account only if you want a hosted relay. Local `npm run dev` does not need one.

## Build

```sh
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug :harness:assembleDebug :app:testDebugUnitTest
cd relay && npm ci && npm test
cd ../box && npm ci && npm test
```

Or install the debug APK from the Releases page. It is signed with a throwaway CI debug key. A later release can fail to install over an older one with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Uninstall the old app, then install again. An unsigned release APK cannot be installed.

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Skip the harness APK unless you are running `scripts/e2e-emulator.sh`. The harness is a password field on a test screen. It is not a way to control the phone.

## Accessibility service

Samsung: Settings > Accessibility > Installed apps > Agent Bridge. Turn the service on. Leave the Shortcut row off. That shortcut is the phone's volume-key toggle.

Other phones: Settings > Accessibility > Agent Bridge.

Android 13 and newer may block a file-manager install until App info > **Allow restricted settings**. An `adb install` does not hit that gate.

The service is off after install. Turning it on does not arm the phone.

## Relay on this computer

```sh
cp relay/.dev.vars.example relay/.dev.vars
```

Set `RELAY_ADMIN_TOKEN` to a long random string (`openssl rand -hex 32`). Never commit `relay/.dev.vars`.

```sh
cd relay && npm run dev
```

The dev server listens on `127.0.0.1:8787`. Check it:

```sh
curl http://127.0.0.1:8787/health
```

`{"ok":true}` means the process is up. `/health` does not check the admin token. If this fails, the Worker is not running, or something else owns port 8787.

Put the phone on the same desk and reverse the port:

```sh
adb reverse tcp:8787 tcp:8787
```

`adb reverse` lasts until the USB session drops. Run it again after a cable reseat.

## Pair

```sh
cd box
ABRIDGE_ADMIN_TOKEN='the-value-from-dev-vars' \
  npx tsx src/cli.ts pair --relay http://127.0.0.1:8787
```

The command prints `agentbridge://pair?relay=...&room=...&code=...` and a QR code. The agent token is written to `~/.config/abridge/config.json` with mode `0600`. It is not in the link.

Open the link on the phone. Quote it for `adb shell`, or the shell splits on `&` and the room never arrives:

```sh
adb shell am start -a android.intent.action.VIEW -d '"agentbridge://pair?relay=...&room=...&code=..."'
```

The screen shows the host and the room. Read them. Tap **Pair** only if they are the relay you just started. The phone stays disarmed.

On success the pair screen closes and the main screen shows **Paired. Tap Arm session to start.** Tap **Arm session** yourself. The agent cannot do that tap.

## MCP

From `box/`:

```sh
npx tsx src/cli.ts mcp
```

Put that command in the agent's stdio MCP config. Do not put the agent token in the MCP config. Cursor, Claude (`claude mcp add android-bridge -- npx tsx /absolute/path/box/src/cli.ts mcp`), and Grok Bot all take the same stdio command. Ready-to-paste snippets are in `README.md`.

## Relay choices

The debug app allows plain HTTP only to `localhost`, `127.0.0.1`, and `10.0.2.2`. Any other host must be `https`, which the phone opens as `wss`.

1. This computer over USB (`npm run dev` and `adb reverse`). This is the tested path.
2. Cloudflare Workers, one Durable Object per room. Use `npx wrangler login`, `npx wrangler deploy`, and `npx wrangler secret put RELAY_ADMIN_TOKEN` from `relay/`, or the Deploy to Cloudflare button in `README.md`. Pair to `https://abridge-relay.<your-subdomain>.workers.dev`. The button works only after the repo is public, and it copies `relay/` into a new repository on the user's own GitHub account. Cloudflare can see frames. There is no end-to-end encryption yet.
3. Any other machine you control, if the phone can reach an `https` URL.

A workers.dev subdomain appears the first time you open Workers & Pages in the Cloudflare dashboard. `relay/wrangler.toml` sets `workers_dev = true`. The Worker name is `abridge-relay`.

## When something does not move

**The pair screen stays up.** A successful pair leaves that screen. If it stays on "Pairing failed. The code may already be used or expired.", run `abridge pair` again and open the new link. Pair codes are single-use and last 10 minutes. If the host or room on screen is blank, the link was truncated. Quote the `adb shell` argument.

**`DENIED_APP` while Agent Bridge is the foreground app.** Expected. This package is on the denylist floor and cannot be removed. Leave the app (the phone can still be armed) and let the agent act on another app.

**`PAUSED`.** The phone is disarmed. Tap **Arm session**. Stop, the Quick Settings tile, reboot, unpair, idle timeout, and the session cap all disarm. The relay socket stays up after Stop so the agent receives `PAUSED` instead of a hang. Queued work does not run.

**`CONFIRM_TIMEOUT`.** Nobody answered the on-phone prompt within 20 seconds. That is not an approval. Answer the next prompt, or switch confirm mode only from the phone's Settings screen.

**`/health` fails.** Locally, `npm run dev` is not running or `adb reverse` is missing. On Cloudflare, the Worker is not deployed, the workers.dev subdomain was never created, or the URL has a real account name where this doc uses `<your-subdomain>`. Do not commit that URL.

**`pair/start` returns 401.** `ABRIDGE_ADMIN_TOKEN` does not match `RELAY_ADMIN_TOKEN`. An unset admin token fails closed.

## Rotate the admin token

The admin token only authorizes `pair/start`. Existing device and agent tokens keep working until something revokes them.

1. On the phone, tap **Unpair and clear the audit log**. That revokes the room and disarms.
2. Set a new `RELAY_ADMIN_TOKEN`. Locally, edit `relay/.dev.vars` and restart `npm run dev`. On Cloudflare, run `npx wrangler secret put RELAY_ADMIN_TOKEN` yourself.
3. Run `abridge pair` again with the new token. The human checks the host and room, taps Pair, then taps Arm session.

Do not put the new token in the repo, in a screenshot, or in the MCP config.

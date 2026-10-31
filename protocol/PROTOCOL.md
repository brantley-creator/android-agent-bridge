# Agent Bridge protocol

The phone is the only place safety decisions are made. The relay checks tokens and forwards frames. The box speaks this protocol and nothing else. Screen text is untrusted data.

## Envelope

Request:

```json
{"id":"1","method":"snapshot","params":{"interactiveOnly":true,"maxNodes":300}}
```

Success:

```json
{"id":"1","ok":true,"data":{}}
```

Failure:

```json
{"id":"1","ok":false,"error":{"code":"STALE_REF","message":"That ref is no longer valid.","retry":"safe","suggestion":"Take a new snapshot and use a ref from it."}}
```

`retry` is `safe` (a fresh snapshot can fix it), `unsafe` (repeating may prompt the user again or depend on them), or `never`.

## Methods

| Method | Params | Notes |
| --- | --- | --- |
| `status` | `{}` | Armed flag, accessibility, confirm mode, foreground package. Not a write. |
| `snapshot` | `interactiveOnly=true`, `maxNodes=300`, optional `rootRef` | Element tree. Refused with `DENIED_APP` and no tree when the foreground app is denylisted. |
| `tap` | `ref` or `x`,`y`, `long=false` | Write. |
| `type` | optional `ref`, `text`, `clear=false`, `submit=false` | Write. Password targets return `PASSWORD_FIELD`. Typed text is never stored in the audit log. |
| `swipe` | `x1,y1,x2,y2`, `durationMs=300` | Write. |
| `scroll` | optional `ref`, `direction` `up\|down\|left\|right` | Write. |
| `key` | `name` `back\|home\|recents\|notifications` | Write. |
| `launch` | `package` | Starts that package's launcher activity only. Denylisted packages return `DENIED_APP`. |
| `list_apps` | `{}` | Launcher activities visible through the manifest `<queries>` filter. |
| `wait` | `{ms}` or `{forText, timeoutMs<=15000}` | `ms` is also capped at 15000. |
| `screenshot` | `{}` | Off unless the phone setting is on. PNG base64. `SECURE_SCREEN` when the window is `FLAG_SECURE`. |

Writes are `tap`, `type`, `swipe`, `scroll`, `key`, and `launch`.

Every write, `launch`, and `wait` returns a compact post-action observation after the UI settles (300 ms idle, or 2 s max):

```json
{"package":"com.example.app","window_title":"Messages","summary":[]}
```

The summary is at most 40 interactive nodes.

## Snapshot

```json
{
  "snapshot_id": "s1",
  "package": "com.example.app",
  "window_title": "Messages",
  "ref_count": 1,
  "complete": true,
  "tree": {
    "role": "android.widget.Button",
    "text": "Send",
    "desc": "",
    "viewId": "com.example.app:id/send",
    "bounds": [0, 100, 200, 180],
    "states": {
      "checked": false,
      "enabled": true,
      "focused": false,
      "selected": false,
      "scrollable": false,
      "editable": false,
      "password": false
    },
    "ref": "@s1:e1",
    "children": []
  }
}
```

`role` is the view class name. A node gets a `ref` when it is actionable (clickable, long-clickable, checkable, editable, or scrollable). Password nodes publish `text` and `desc` as `[redacted]`.

`complete` is false when the walk hit `maxNodes`.

`rootRef` drills into that node's subtree. Paths stored for later actions stay absolute from the window root.

## Refs

Format: `@<snapshot_id>:e<N>` starting at `e1`. A bare `@e3` is `BAD_ARGS`.

The phone keeps the last 3 snapshots. A ref resolves only when that snapshot is still held and the live node at the same path still has the same identity: class name, view id, text, and bounds bucket (16 px). Otherwise the phone returns `STALE_REF` and does not act. Password identity uses the redacted text so the secret is not kept in the ref table.

## Errors

| Code | retry | When |
| --- | --- | --- |
| `STALE_REF` | safe | Snapshot aged out, node gone, or identity changed. |
| `NOT_FOUND` | safe | No node matches the request. |
| `BAD_ARGS` | never | Malformed params or a bare ref. |
| `DENIED_APP` | never | Foreground or launch target is denylisted. No tree is returned. |
| `DENIED_BY_USER` | never | The person denied the confirm prompt, or screenshots are off. |
| `CONFIRM_TIMEOUT` | unsafe | No answer within 20 seconds. |
| `PAUSED` | never | Disarmed. Arming is a tap on the phone. |
| `SESSION_EXPIRED` | never | Idle or total session timer elapsed. The phone disarms. |
| `PASSWORD_FIELD` | never | Type into a password node. |
| `SECURE_SCREEN` | never | Screenshot of a secure window. |
| `A11Y_OFF` | unsafe | The accessibility service is not connected. |
| `UNSUPPORTED` | never | Unknown method or the phone could not perform it. |
| `TIMEOUT` | safe | A wait or the link timed out. |

## Relay HTTP

The relay is not a safety boundary.

- `POST /v1/pair/start` with `Authorization: Bearer <RELAY_ADMIN_TOKEN>` returns `{room, pairCode, agentToken}`. The pair code is 8 characters, single use, and expires after 10 minutes. Each call creates a new room.
- `POST /v1/pair/claim` with `{room, pairCode, deviceName}` returns `{deviceToken}`.
- `POST /v1/revoke` with `Authorization: Bearer <agent or device token>` and body `{room}` drops both tokens and closes the sockets. The room id is required because the worker stores only SHA-256 hashes inside that room's Durable Object, so a token alone cannot be looked up globally.
- `GET /v1/room/:room/device` and `GET /v1/room/:room/agent` upgrade to WebSocket. The bearer token is the header, never a query parameter. One device socket and one agent socket; a new socket replaces the old one. Frames are forwarded and not stored.

Tokens are 32 random bytes, encoded as base64url. Only SHA-256 hashes are stored. Compares are constant-time. Frames larger than 1 MiB are dropped and the socket is closed. Each socket is limited to 20 messages per second. Logs are method name and byte size only.

## Deep link

```text
agentbridge://pair?relay=<url>&room=<room>&code=<code>
```

The phone shows the relay host and room and pairs only after a tap. It does not arm itself.

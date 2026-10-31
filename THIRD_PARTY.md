# Third-party code, binaries, and ideas

This tree is original except where noted. It is meant to be scrubbed and relicensed later, so anything that is not ours is listed here. No code was copied from droidrun/mobilerun-portal, aura-android-use, or Magi-Labs/mobilemcp.

## Same author, reused on purpose

Patterns from [brantley-creator/meta-glasses-companion](https://github.com/brantley-creator/meta-glasses-companion) (Copyright 2026 Brantley Brumley). Audio, Bluetooth, Meta DAT, and camera code were not brought over.

| Here | There | What was kept |
| --- | --- | --- |
| `app/src/main/java/dev/brantley/agentbridge/net/RelaySocket.kt` | `agent/AgentSocket.kt` | OkHttp client with a 20 s ping, bearer header (never the query string), refusal to connect with an empty URL or token, `onStatus` callbacks. JSON-RPC frames replace audio messages. Reconnect is capped exponential backoff with jitter and continues while the phone is armed. The 5-try stop and the 5 s session-end wait were not kept: Stop closes the socket immediately. |
| `app/src/main/java/dev/brantley/agentbridge/safety/ArmedService.kt` | `session/SessionService.kt` | Notification channel, `ACTION_STOP` pending intent, and `stopForegroundAndSelf`. |
| `app/src/main/java/dev/brantley/agentbridge/data/SettingsStore.kt` | `data/SettingsStore.kt` | DataStore preferences pattern. Tokens are not stored here. |
| `app/src/debug/res/xml/network_security_config.xml` | `res/xml/network_security_config.xml` | Cleartext only for `localhost`, `127.0.0.1`, and `10.0.2.2`. |
| `relay/test/room.test.ts` | `adapters/tests/test_server_auth.py` | Missing bearer, wrong bearer, and matching bearer. |
| `docs/security.md` rules | same | Empty defaults, no token baked into the APK, server rejects a missing token. |

The companion debug overlay that set `cleartextTrafficPermitted="true"` on the base config was not copied. Release builds allow `wss` only.

The Gradle wrapper scripts and `gradle/wrapper/gradle-wrapper.jar` are the official Gradle 8.14.3 wrapper (Apache-2.0), the same wrapper version that repo already uses.

## Ideas, no code copied

- [vercel-labs/agent-desktop](https://github.com/vercel-labs/agent-desktop) concepts: a snapshot id, element refs tied to that snapshot, identity checks before acting, and an error envelope that says whether a retry is safe. The vocabulary in `protocol/PROTOCOL.md` follows this brief. No agent-desktop source was copied.
- [Magi-Labs/mobilemcp](https://github.com/Magi-Labs/mobilemcp) (MIT) was not used as a code source. No file from that repository was read into this tree and no notice is required.

## Direct dependencies

Build and test only. None of these are analytics or crash-reporting SDKs. Wrangler can send anonymous metrics; scripts set `WRANGLER_SEND_METRICS=false`. Do not run `wrangler deploy` or `wrangler login`.

| Component | Packages | Role |
| --- | --- | --- |
| Android app | AndroidX, Jetpack Compose BOM 2025.01.01, Kotlin 2.1.10, OkHttp 4.12.0, kotlinx-serialization 1.8.0, kotlinx-coroutines 1.10.1, DataStore 1.1.2 | UI, socket, JSON, preferences. Apache-2.0. |
| Android tests | JUnit 4.13.2 (EPL-1.0), OkHttp mockwebserver 4.12.0 (Apache-2.0), kotlinx-coroutines-test | JVM tests. MockWebServer binds localhost only. |
| Relay | wrangler, TypeScript, vitest, `@cloudflare/vitest-pool-workers` | Local worker and tests. No runtime npm dependency. |
| Box | `ws` (MIT), `@modelcontextprotocol/sdk` (MIT), `qrcode-terminal` (MIT), `zod` (MIT), vitest, tsx, TypeScript | CLI, MCP, tests. |

Registries contacted at build time are Google Maven, Maven Central, npm, and `services.gradle.org`. The running app does not call them.

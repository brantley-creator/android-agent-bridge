# Third-party code, binaries, and ideas

This tree is original except where noted. Anything that is not ours is listed here. No code was copied from droidrun/mobilerun-portal, aura-android-use, or Magi-Labs/mobilemcp. The project is Apache-2.0 (see `LICENSE` and `NOTICE`).

## Same author, reused on purpose

Patterns from [brantley-creator/meta-glasses-companion](https://github.com/brantley-creator/meta-glasses-companion) (Copyright 2026 Brantley Brumley). Audio, Bluetooth, Meta DAT, and camera code were not brought over.

| Here | There | What was kept |
| --- | --- | --- |
| `app/src/main/java/dev/brantley/agentbridge/net/RelaySocket.kt` | `agent/AgentSocket.kt` | OkHttp client with a 20 s ping, bearer header (never the query string), refusal to connect with an empty URL or token, `onStatus` callbacks. JSON-RPC frames replace audio messages. Reconnect is capped exponential backoff with jitter. Since 6ac63dd, Stop, the Quick Settings tile, and the kill notification disarm the phone and leave the relay socket connected so later commands can return `PAUSED`. Unpair is what closes the socket. The 5-try stop and the 5 s session-end wait were not kept. |
| `app/src/main/java/dev/brantley/agentbridge/safety/ArmedService.kt` | `session/SessionService.kt` | Notification channel, `ACTION_STOP` pending intent, and `stopForegroundAndSelf`. |
| `app/src/main/java/dev/brantley/agentbridge/data/SettingsStore.kt` | `data/SettingsStore.kt` | DataStore preferences pattern. Tokens are not stored here. |
| `app/src/debug/res/xml/network_security_config.xml` | `res/xml/network_security_config.xml` | Cleartext only for `localhost`, `127.0.0.1`, and `10.0.2.2`. |
| `relay/test/room.test.ts` | `adapters/tests/test_server_auth.py` | Missing bearer, wrong bearer, and matching bearer. |
| [docs/security.md](https://github.com/brantley-creator/meta-glasses-companion/blob/main/docs/security.md) rules | same | Empty defaults, no token baked into the APK, server rejects a missing token. That repository is public and MIT licensed. |

The companion debug overlay that set `cleartextTrafficPermitted="true"` on the base config was not copied. Release builds allow `wss` only.

The Gradle wrapper scripts and `gradle/wrapper/gradle-wrapper.jar` are the official Gradle 8.14.3 wrapper (Apache-2.0), the same wrapper version that repo already uses.

## Ideas, no code copied

- [vercel-labs/agent-desktop](https://github.com/vercel-labs/agent-desktop) concepts: a snapshot id, element refs tied to that snapshot, identity checks before acting, and an error envelope that says whether a retry is safe. The vocabulary in `protocol/PROTOCOL.md` follows this brief. No agent-desktop source was copied.
- [Magi-Labs/mobilemcp](https://github.com/Magi-Labs/mobilemcp) (MIT) was not used as a code source. No file from that repository was read into this tree and no notice is required.

## Direct dependencies

Build and test only. None of these are analytics or crash-reporting SDKs. Wrangler can send anonymous metrics; scripts set `WRANGLER_SEND_METRICS=false`. Deploying the relay is the user's own action on their own Cloudflare account.

Licenses below were checked against the installed package `license` field (npm) or the published coordinates in `gradle/libs.versions.toml` (Android). `qrcode-terminal` has no `license` field; its legacy `licenses` array and the `LICENSE` file shipped in the package are Apache-2.0.

| Component | Packages | Role |
| --- | --- | --- |
| Android app | core-ktx 1.15.0, activity-compose 1.10.0, activity-ktx 1.10.0, lifecycle-runtime-ktx 2.8.7, lifecycle-viewmodel-compose 2.8.7, lifecycle-runtime-compose 2.8.7, navigation-compose 2.8.7, datastore-preferences 1.1.2, Compose BOM 2025.01.01 (ui, ui-graphics, ui-tooling, ui-tooling-preview, material3, material-icons-extended), OkHttp 4.12.0, kotlinx-coroutines-android 1.10.1, kotlinx-serialization-json 1.8.0, Kotlin 2.1.10, Android Gradle Plugin 8.9.1 | UI, socket, JSON, preferences. All Apache-2.0. |
| Harness | core-ktx 1.15.0 | Password-field fixture only. Apache-2.0. |
| Android tests | JUnit 4.13.2 (EPL-1.0), OkHttp mockwebserver 4.12.0 (Apache-2.0), kotlinx-coroutines-test 1.10.1 (Apache-2.0) | JVM tests. MockWebServer binds localhost only. |
| Relay dev | wrangler 4.161.0 (MIT OR Apache-2.0), `@cloudflare/workers-types` 5.20261113.1 (MIT OR Apache-2.0), `@cloudflare/vitest-plugin` 1.8.1 (MIT), vitest 4.1.12 (MIT), typescript 5.9.3 (Apache-2.0) | Local worker and tests. No runtime npm dependency. |
| Box runtime | `@modelcontextprotocol/sdk` 1.32.2 (MIT, manifest `^1.20.2`), `ws` 8.22.1 (MIT, manifest `^8.18.3`), `zod` 4.6.7 (MIT), `qrcode-terminal` 0.12.0 (Apache-2.0) | CLI and MCP. |
| Box dev | tsx 4.23.15 (MIT), vitest 4.1.12 (MIT), `@types/node` 24.19.2 (MIT), `@types/qrcode-terminal` 0.12.2 (MIT), `@types/ws` 8.18.2 (MIT), typescript 5.9.3 (Apache-2.0) | Tests and the TypeScript runner. |

Registries contacted at build time are Google Maven, Maven Central, npm, and `services.gradle.org`. The running app does not call them.

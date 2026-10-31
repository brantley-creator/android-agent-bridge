import { chmodSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join } from "node:path";

export interface BridgeConfig {
  relayUrl: string;
  room: string;
  agentToken: string;
}

export function configFilePath(): string {
  return join(homedir(), ".config", "abridge", "config.json");
}

export function readConfigFile(): Partial<BridgeConfig> {
  try {
    const parsed = JSON.parse(readFileSync(configFilePath(), "utf8")) as Partial<BridgeConfig>;
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch {
    return {};
  }
}

export function loadConfig(): BridgeConfig | null {
  const file = readConfigFile();
  const relayUrl = process.env.ABRIDGE_RELAY_URL || file.relayUrl || "";
  const room = process.env.ABRIDGE_ROOM || file.room || "";
  const agentToken = process.env.ABRIDGE_AGENT_TOKEN || file.agentToken || "";
  if (!relayUrl || !room || !agentToken) return null;
  return { relayUrl, room, agentToken };
}

export function writeConfig(config: BridgeConfig): void {
  const path = configFilePath();
  mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
  writeFileSync(path, JSON.stringify(config, null, 2) + "\n", { mode: 0o600 });
  chmodSync(path, 0o600);
}

export function httpOrigin(relay: string): string {
  const trimmed = relay.replace(/\/$/, "");
  if (trimmed.startsWith("wss://")) return "https://" + trimmed.slice("wss://".length);
  if (trimmed.startsWith("ws://")) return "http://" + trimmed.slice("ws://".length);
  return trimmed;
}

export function agentWebSocketUrl(relay: string, room: string): string {
  const trimmed = relay.replace(/\/$/, "");
  let base = trimmed;
  if (base.startsWith("https://")) base = "wss://" + base.slice("https://".length);
  else if (base.startsWith("http://")) base = "ws://" + base.slice("http://".length);
  return `${base}/v1/room/${encodeURIComponent(room)}/agent`;
}

export function tokenInUrl(relay: string): boolean {
  return /[?&](token|access_token|code|authorization)=/i.test(relay);
}

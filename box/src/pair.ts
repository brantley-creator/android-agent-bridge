import qrcode from "qrcode-terminal";
import { httpOrigin, writeConfig } from "./config.js";

export interface PairResult {
  link: string;
  room: string;
}

export async function startPair(relay: string, adminToken: string): Promise<PairResult> {
  const response = await fetch(`${httpOrigin(relay)}/v1/pair/start`, {
    method: "POST",
    headers: { Authorization: `Bearer ${adminToken}` },
  });
  if (!response.ok) {
    throw new Error(`pair/start failed (${response.status})`);
  }
  const body = (await response.json()) as { room?: string; pairCode?: string; agentToken?: string };
  if (!body.room || !body.pairCode || !body.agentToken) {
    throw new Error("pair/start returned an incomplete body");
  }
  writeConfig({ relayUrl: relay, room: body.room, agentToken: body.agentToken });
  const link =
    "agentbridge://pair?relay=" +
    encodeURIComponent(relay) +
    "&room=" +
    encodeURIComponent(body.room) +
    "&code=" +
    encodeURIComponent(body.pairCode);
  return { link, room: body.room };
}

export function renderQr(text: string): Promise<string> {
  return new Promise((resolve) => {
    qrcode.generate(text, { small: true }, (qr: string) => resolve(qr));
  });
}

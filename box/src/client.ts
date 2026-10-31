import WebSocket from "ws";
import { agentWebSocketUrl, tokenInUrl, type BridgeConfig } from "./config.js";

export interface RpcError {
  code: string;
  message: string;
  retry: "safe" | "unsafe" | "never" | string;
  suggestion: string;
}

export interface Envelope {
  id: string;
  ok: boolean;
  data?: unknown;
  error?: RpcError;
}

export function errorEnvelope(id: string, code: string, message: string, retry: RpcError["retry"], suggestion: string): Envelope {
  return { id, ok: false, error: { code, message, retry, suggestion } };
}

export async function callPhone(config: BridgeConfig, method: string, params: unknown, timeoutMs = 30_000): Promise<Envelope> {
  const id = crypto.randomUUID();
  if (tokenInUrl(config.relayUrl)) {
    return errorEnvelope(
      id,
      "BAD_ARGS",
      "Refusing a relay URL that carries a secret in the query string.",
      "never",
      "Pass the token with ABRIDGE_AGENT_TOKEN or the config file.",
    );
  }
  const url = agentWebSocketUrl(config.relayUrl, config.room);
  const socket = new WebSocket(url, {
    headers: { Authorization: `Bearer ${config.agentToken}` },
  });
  try {
    await waitOpen(socket, Math.min(timeoutMs, 10_000));
    const pending = waitForId(socket, id, timeoutMs);
    socket.send(JSON.stringify({ id, method, params: params ?? {} }));
    return await pending;
  } catch {
    return errorEnvelope(
      id,
      "TIMEOUT",
      "The relay did not return a response.",
      "safe",
      "Confirm the relay is up and the phone is armed.",
    );
  } finally {
    socket.close();
  }
}

function waitOpen(socket: WebSocket, timeoutMs: number): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("open timeout")), timeoutMs);
    socket.once("open", () => {
      clearTimeout(timer);
      resolve();
    });
    socket.once("error", () => {
      clearTimeout(timer);
      reject(new Error("socket error"));
    });
    socket.once("unexpected-response", () => {
      clearTimeout(timer);
      reject(new Error("rejected"));
    });
  });
}

function waitForId(socket: WebSocket, id: string, timeoutMs: number): Promise<Envelope> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      cleanup();
      reject(new Error("response timeout"));
    }, timeoutMs);
    const onMessage = (data: WebSocket.RawData) => {
      try {
        const parsed = JSON.parse(data.toString()) as Envelope;
        if (parsed.id !== id) return;
        cleanup();
        resolve(parsed);
      } catch {
        cleanup();
        reject(new Error("bad frame"));
      }
    };
    const onClose = () => {
      cleanup();
      reject(new Error("closed"));
    };
    const cleanup = () => {
      clearTimeout(timer);
      socket.off("message", onMessage);
      socket.off("close", onClose);
    };
    socket.on("message", onMessage);
    socket.on("close", onClose);
  });
}

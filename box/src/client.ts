import type { ClientRequest, IncomingMessage } from "node:http";
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

class ConnectFailure extends Error {
  constructor(readonly kind: "unauthorized" | "unreachable" | "replaced") {
    super(kind);
    this.name = "ConnectFailure";
  }
}

export function errorEnvelope(id: string, code: string, message: string, retry: RpcError["retry"], suggestion: string): Envelope {
  return { id, ok: false, error: { code, message, retry, suggestion } };
}

export function timeoutEnvelope(id: string): Envelope {
  return errorEnvelope(id, "TIMEOUT", "The relay did not return a response.", "safe", "Confirm the relay is up and the phone is armed.");
}

export function unauthorizedEnvelope(id: string): Envelope {
  return errorEnvelope(id, "UNAUTHORIZED", "The relay rejected the agent token.", "never", "Re-pair or check ABRIDGE_AGENT_TOKEN.");
}

export function unreachableEnvelope(id: string): Envelope {
  return errorEnvelope(
    id,
    "RELAY_UNREACHABLE",
    "The relay could not be reached.",
    "safe",
    "Check ABRIDGE_RELAY_URL and that your relay is deployed; /health should answer.",
  );
}

export function replacedEnvelope(id: string): Envelope {
  return errorEnvelope(
    id,
    "REPLACED",
    "Another agent connected to this room.",
    "never",
    "Another agent session took this room; only one agent socket per room.",
  );
}

export function envelopeForConnectFailure(id: string, error: unknown): Envelope {
  if (error instanceof ConnectFailure) {
    if (error.kind === "unauthorized") return unauthorizedEnvelope(id);
    if (error.kind === "replaced") return replacedEnvelope(id);
    return unreachableEnvelope(id);
  }
  const status = statusFromMessage(error instanceof Error ? error.message : "");
  if (status === 401 || status === 403) return unauthorizedEnvelope(id);
  return unreachableEnvelope(id);
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
    await awaitSocketOpen(socket, Math.min(timeoutMs, 10_000));
  } catch (error) {
    return envelopeForConnectFailure(id, error);
  }
  try {
    const pending = waitForId(socket, id, timeoutMs);
    socket.send(JSON.stringify({ id, method, params: params ?? {} }));
    return await pending;
  } catch (error) {
    if (error instanceof ConnectFailure && error.kind === "replaced") return replacedEnvelope(id);
    return timeoutEnvelope(id);
  } finally {
    socket.on("error", () => {});
    socket.close();
  }
}

export function awaitSocketOpen(socket: WebSocket, timeoutMs: number, onOpen?: () => void): Promise<void> {
  return new Promise((resolve, reject) => {
    let settled = false;
    let httpStatus = 0;
    const timer = setTimeout(() => fail(new ConnectFailure("unreachable")), timeoutMs);

    function fail(error: Error): void {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      socket.off("open", handleOpen);
      socket.off("error", handleError);
      socket.off("unexpected-response", handleUnexpected);
      socket.on("error", () => {});
      socket.terminate();
      reject(error);
    }

    function handleOpen(): void {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      socket.off("error", handleError);
      socket.off("unexpected-response", handleUnexpected);
      try {
        onOpen?.();
      } catch (error) {
        socket.on("error", () => {});
        socket.terminate();
        reject(error instanceof Error ? error : new Error("open failed"));
        return;
      }
      resolve();
    }

    function handleError(error: Error): void {
      const status = httpStatus || statusFromMessage(error.message) || 0;
      if (status === 401 || status === 403) fail(new ConnectFailure("unauthorized"));
      else fail(new ConnectFailure("unreachable"));
    }

    function handleUnexpected(req: ClientRequest, res: IncomingMessage): void {
      httpStatus = res.statusCode ?? 0;
      res.resume();
      req.destroy();
      if (httpStatus === 401 || httpStatus === 403) fail(new ConnectFailure("unauthorized"));
      else fail(new ConnectFailure("unreachable"));
    }

    socket.on("open", handleOpen);
    socket.on("error", handleError);
    socket.on("unexpected-response", handleUnexpected);
  });
}

function statusFromMessage(message: string): number | null {
  const match = /Unexpected server response:\s*(\d+)/.exec(message);
  if (!match) return null;
  return Number(match[1]);
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
    const onClose = (code: number) => {
      cleanup();
      if (code === 4001) reject(new ConnectFailure("replaced"));
      else reject(new Error("closed"));
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

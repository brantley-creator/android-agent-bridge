import WebSocket from "ws";
import { errorEnvelope, type Envelope } from "./client.js";
import { agentWebSocketUrl, tokenInUrl, type BridgeConfig } from "./config.js";

type Waiter = {
  resolve: (envelope: Envelope) => void;
  timer: ReturnType<typeof setTimeout>;
  socket: WebSocket;
};

/**
 * One agent socket for the life of `abridge mcp`.
 * Calls share it and are matched by JSON-RPC id. A closed socket reconnects on the next call.
 * One-shot CLI commands stay on `callPhone` and still open a socket per invocation.
 */
export class AgentSession {
  private socket: WebSocket | null = null;
  private opening: Promise<WebSocket> | null = null;
  private readonly pending = new Map<string, Waiter>();
  private stopped = false;

  constructor(
    private readonly load: () => BridgeConfig | null,
    private readonly defaultTimeoutMs = 30_000,
  ) {}

  async call(method: string, params: unknown, timeoutMs = this.defaultTimeoutMs): Promise<Envelope> {
    const id = crypto.randomUUID();
    const config = this.load();
    if (!config) {
      return errorEnvelope(
        id,
        "BAD_ARGS",
        "Missing relay config.",
        "never",
        "Set ABRIDGE_RELAY_URL, ABRIDGE_ROOM, and ABRIDGE_AGENT_TOKEN or run abridge pair.",
      );
    }
    if (tokenInUrl(config.relayUrl)) {
      return errorEnvelope(
        id,
        "BAD_ARGS",
        "Refusing a relay URL that carries a secret in the query string.",
        "never",
        "Pass the token with ABRIDGE_AGENT_TOKEN or the config file.",
      );
    }
    let socket: WebSocket;
    try {
      socket = await this.ensureOpen(config, Math.min(timeoutMs, 10_000));
    } catch {
      return errorEnvelope(id, "TIMEOUT", "The relay did not return a response.", "safe", "Confirm the relay is up and the phone is armed.");
    }
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        resolve(errorEnvelope(id, "TIMEOUT", "The relay did not return a response.", "safe", "Confirm the relay is up and the phone is armed."));
      }, timeoutMs);
      this.pending.set(id, { resolve, timer, socket });
      try {
        socket.send(JSON.stringify({ id, method, params: params ?? {} }));
      } catch {
        clearTimeout(timer);
        this.pending.delete(id);
        this.socket = null;
        resolve(errorEnvelope(id, "TIMEOUT", "The relay did not return a response.", "safe", "Confirm the relay is up and the phone is armed."));
      }
    });
  }

  close(): void {
    this.stopped = true;
    this.failSocket(this.socket);
    const current = this.socket;
    this.socket = null;
    current?.close();
  }

  private ensureOpen(config: BridgeConfig, openTimeoutMs: number): Promise<WebSocket> {
    if (this.stopped) return Promise.reject(new Error("stopped"));
    if (this.socket && this.socket.readyState === WebSocket.OPEN) return Promise.resolve(this.socket);
    if (this.opening) return this.opening;
    const pending = this.open(config, openTimeoutMs);
    this.opening = pending;
    return pending.finally(() => {
      if (this.opening === pending) this.opening = null;
    });
  }

  private open(config: BridgeConfig, openTimeoutMs: number): Promise<WebSocket> {
    const url = agentWebSocketUrl(config.relayUrl, config.room);
    const socket = new WebSocket(url, { headers: { Authorization: `Bearer ${config.agentToken}` } });
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        socket.terminate();
        reject(new Error("open timeout"));
      }, openTimeoutMs);
      const fail = () => {
        clearTimeout(timer);
        if (this.socket === socket) this.socket = null;
        reject(new Error("socket error"));
      };
      socket.once("open", () => {
        clearTimeout(timer);
        this.socket = socket;
        socket.on("message", (data) => this.onMessage(data));
        socket.on("close", () => {
          if (this.socket === socket) this.socket = null;
          this.failSocket(socket);
        });
        socket.on("error", () => {
          if (this.socket === socket) this.socket = null;
        });
        resolve(socket);
      });
      socket.once("error", fail);
      socket.once("unexpected-response", fail);
    });
  }

  private onMessage(data: WebSocket.RawData): void {
    let parsed: Envelope;
    try {
      parsed = JSON.parse(data.toString()) as Envelope;
    } catch {
      return;
    }
    if (!parsed.id) return;
    const waiter = this.pending.get(parsed.id);
    if (!waiter) return;
    clearTimeout(waiter.timer);
    this.pending.delete(parsed.id);
    waiter.resolve(parsed);
  }

  private failSocket(socket: WebSocket | null): void {
    if (!socket) return;
    for (const [id, waiter] of this.pending) {
      if (waiter.socket !== socket) continue;
      clearTimeout(waiter.timer);
      this.pending.delete(id);
      waiter.resolve(
        errorEnvelope(id, "TIMEOUT", "The relay did not return a response.", "safe", "Confirm the relay is up and the phone is armed."),
      );
    }
  }
}

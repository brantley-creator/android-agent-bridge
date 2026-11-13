import WebSocket from "ws";
import {
  awaitSocketOpen,
  envelopeForConnectFailure,
  errorEnvelope,
  replacedEnvelope,
  timeoutEnvelope,
  type Envelope,
} from "./client.js";
import { agentWebSocketUrl, tokenInUrl, type BridgeConfig } from "./config.js";

type Waiter = {
  resolve: (envelope: Envelope) => void;
  timer: ReturnType<typeof setTimeout>;
  socket: WebSocket;
};

export class AgentSession {
  private socket: WebSocket | null = null;
  private opening: Promise<WebSocket> | null = null;
  private readonly pending = new Map<string, Waiter>();
  private readonly closeCodes = new WeakMap<WebSocket, number>();
  private readonly killed = new WeakSet<WebSocket>();
  private heartbeat: ReturnType<typeof setInterval> | null = null;
  private pongTimer: ReturnType<typeof setTimeout> | null = null;
  private heartbeatSocket: WebSocket | null = null;
  private awaitingPong = false;
  private replaced = false;
  private stopped = false;

  constructor(
    private readonly load: () => BridgeConfig | null,
    private readonly defaultTimeoutMs = 30_000,
    private readonly pingIntervalMs = 20_000,
    private readonly pongDeadlineMs = 20_000,
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
    this.replaced = false;
    return this.dispatch(config, id, method, params ?? {}, timeoutMs, true);
  }

  close(): void {
    this.stopped = true;
    this.clearHeartbeat();
    const current = this.socket;
    this.socket = null;
    this.failSocket(current, 1006);
    if (!current) return;
    current.on("error", () => {});
    current.close();
  }

  private async dispatch(
    config: BridgeConfig,
    id: string,
    method: string,
    params: unknown,
    timeoutMs: number,
    retry: boolean,
  ): Promise<Envelope> {
    if (this.stopped) return timeoutEnvelope(id);
    let socket: WebSocket;
    try {
      socket = await this.ensureOpen(config, Math.min(timeoutMs, 10_000));
    } catch (error) {
      if (this.stopped) return timeoutEnvelope(id);
      return envelopeForConnectFailure(id, error);
    }
    if (socket.readyState !== WebSocket.OPEN) return this.recover(config, id, method, params, timeoutMs, retry, socket);
    const payload = JSON.stringify({ id, method, params });
    return new Promise((resolve) => {
      let done = false;
      const timer = setTimeout(() => succeed(timeoutEnvelope(id)), timeoutMs);
      const succeed = (envelope: Envelope) => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        this.pending.delete(id);
        resolve(envelope);
      };
      const failSend = () => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        this.pending.delete(id);
        void this.recover(config, id, method, params, timeoutMs, retry, socket).then(resolve, () => resolve(timeoutEnvelope(id)));
      };
      this.pending.set(id, { resolve: succeed, timer, socket });
      try {
        socket.send(payload, (error) => {
          if (error) failSend();
        });
      } catch {
        failSend();
      }
    });
  }

  private recover(
    config: BridgeConfig,
    id: string,
    method: string,
    params: unknown,
    timeoutMs: number,
    retry: boolean,
    socket: WebSocket,
  ): Promise<Envelope> {
    const replaced = this.replaced || this.closeCodes.get(socket) === 4001;
    this.detach(socket);
    if (replaced) return Promise.resolve(replacedEnvelope(id));
    if (!retry) return Promise.resolve(timeoutEnvelope(id));
    return this.dispatch(config, id, method, params, timeoutMs, false);
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
    return awaitSocketOpen(socket, openTimeoutMs, () => this.attach(socket)).then(() => {
      if (this.socket !== socket) throw new Error("stopped");
      return socket;
    });
  }

  private attach(socket: WebSocket): void {
    if (this.stopped) {
      socket.on("error", () => {});
      socket.close();
      return;
    }
    this.socket = socket;
    this.startHeartbeat(socket);
    socket.on("message", (data) => this.onMessage(data));
    socket.on("pong", () => {
      if (this.heartbeatSocket !== socket) return;
      this.awaitingPong = false;
      if (this.pongTimer) {
        clearTimeout(this.pongTimer);
        this.pongTimer = null;
      }
    });
    socket.on("close", (code: number) => {
      this.closeCodes.set(socket, code);
      const current = this.socket === socket;
      if (code === 4001 && current) this.replaced = true;
      if (this.heartbeatSocket === socket) this.clearHeartbeat();
      if (current) this.socket = null;
      this.failSocket(socket, code);
    });
    socket.on("error", () => {
      if (this.socket === socket) this.socket = null;
    });
  }

  private startHeartbeat(socket: WebSocket): void {
    this.clearHeartbeat();
    this.heartbeatSocket = socket;
    this.awaitingPong = false;
    this.heartbeat = setInterval(() => {
      if (this.socket !== socket || socket.readyState !== WebSocket.OPEN) {
        this.killQuiet(socket);
        return;
      }
      if (this.awaitingPong) {
        this.killQuiet(socket);
        return;
      }
      this.awaitingPong = true;
      if (this.pongTimer) clearTimeout(this.pongTimer);
      this.pongTimer = setTimeout(() => {
        if (this.socket === socket && this.awaitingPong) this.killQuiet(socket);
      }, this.pongDeadlineMs);
      try {
        socket.ping((error?: Error) => {
          if (error) this.killQuiet(socket);
        });
      } catch {
        this.killQuiet(socket);
      }
    }, this.pingIntervalMs);
  }

  private clearHeartbeat(socket?: WebSocket): void {
    if (socket && this.heartbeatSocket && this.heartbeatSocket !== socket) return;
    if (this.heartbeat) clearInterval(this.heartbeat);
    this.heartbeat = null;
    if (this.pongTimer) clearTimeout(this.pongTimer);
    this.pongTimer = null;
    this.awaitingPong = false;
    this.heartbeatSocket = null;
  }

  private killQuiet(socket: WebSocket): void {
    if (this.killed.has(socket)) return;
    this.killed.add(socket);
    if (this.heartbeatSocket === socket) this.clearHeartbeat();
    if (this.socket === socket) this.socket = null;
    this.failSocket(socket, 1006);
    if (socket.readyState === WebSocket.CLOSED) return;
    socket.on("error", () => {});
    socket.terminate();
  }

  private detach(socket: WebSocket): void {
    if (this.heartbeatSocket === socket) this.clearHeartbeat();
    if (this.socket === socket) this.socket = null;
    if (socket.readyState === WebSocket.CLOSED) return;
    socket.on("error", () => {});
    socket.terminate();
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

  private failSocket(socket: WebSocket | null, code: number): void {
    if (!socket) return;
    for (const [id, waiter] of this.pending) {
      if (waiter.socket !== socket) continue;
      clearTimeout(waiter.timer);
      this.pending.delete(id);
      waiter.resolve(code === 4001 ? replacedEnvelope(id) : timeoutEnvelope(id));
    }
  }
}

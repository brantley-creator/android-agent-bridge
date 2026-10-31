import { DurableObject } from "cloudflare:workers";
import {
  MAX_FRAME_BYTES,
  MAX_MESSAGES_PER_SECOND,
  PAIR_TTL_MS,
  bearerToken,
  randomBase64Url,
  randomPairCode,
  sha256Hex,
  timingSafeEqual,
  unauthorized,
} from "./crypto";
import type { Env } from "./env";

interface Attachment {
  role: "device" | "agent";
  windowStart: number;
  count: number;
}

interface TokenRow {
  role: string;
  hash: string;
  [key: string]: SqlStorageValue;
}

interface PairRow {
  code_hash: string;
  expires_at: number;
  used: number;
  [key: string]: SqlStorageValue;
}

export class Room extends DurableObject<Env> {
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.blockConcurrencyWhile(async () => {
      this.ctx.storage.sql.exec(`
        CREATE TABLE IF NOT EXISTS token (
          role TEXT PRIMARY KEY,
          hash TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS pair (
          code_hash TEXT PRIMARY KEY,
          expires_at INTEGER NOT NULL,
          used INTEGER NOT NULL
        );
      `);
    });
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;
    if (request.method === "POST" && path.endsWith("/pair/start")) {
      return this.pairStart(request);
    }
    if (request.method === "POST" && path.endsWith("/pair/claim")) {
      return this.pairClaim(request);
    }
    if (request.method === "POST" && path.endsWith("/revoke")) {
      return this.revoke(request);
    }
    if (request.method === "GET" && (path.endsWith("/device") || path.endsWith("/agent"))) {
      const role = path.endsWith("/device") ? "device" : "agent";
      return this.upgrade(request, role);
    }
    return new Response("not found", { status: 404 });
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const bytes = messageByteLength(message);
    if (bytes > MAX_FRAME_BYTES) {
      console.log(JSON.stringify({ event: "drop", reason: "frame", bytes }));
      ws.close(1009, "frame too large");
      return;
    }
    const attachment = (ws.deserializeAttachment() as Attachment | null) ?? {
      role: "agent" as const,
      windowStart: 0,
      count: 0,
    };
    const now = Date.now();
    if (now - attachment.windowStart >= 1000) {
      attachment.windowStart = now;
      attachment.count = 0;
    }
    attachment.count += 1;
    ws.serializeAttachment(attachment);
    if (attachment.count > MAX_MESSAGES_PER_SECOND) {
      console.log(JSON.stringify({ event: "drop", reason: "rate", bytes, method: "" }));
      return;
    }
    const text = typeof message === "string" ? message : new TextDecoder().decode(message);
    const method = methodName(text);
    console.log(JSON.stringify({ event: "fwd", method, bytes }));
    const other = attachment.role === "device" ? "agent" : "device";
    for (const peer of this.ctx.getWebSockets(other)) {
      try {
        peer.send(text);
      } catch {
        console.log(JSON.stringify({ event: "drop", reason: "closed", bytes, method }));
      }
    }
  }

  async webSocketClose(ws: WebSocket, code: number, reason: string, wasClean: boolean): Promise<void> {
    console.log(JSON.stringify({ event: "close", code, clean: wasClean, reason: reason.slice(0, 32) }));
    ws.close(code, reason);
  }

  async webSocketError(_ws: WebSocket, _error: unknown): Promise<void> {
    console.log(JSON.stringify({ event: "ws_error" }));
  }

  private async pairStart(request: Request): Promise<Response> {
    const room = request.headers.get("x-room-id") ?? "";
    if (!room) return Response.json({ error: "bad_request" }, { status: 400 });
    const agentToken = randomBase64Url(32);
    const pairCode = randomPairCode();
    const agentHash = await sha256Hex(agentToken);
    const codeHash = await sha256Hex(pairCode);
    this.ctx.storage.sql.exec("DELETE FROM token");
    this.ctx.storage.sql.exec("DELETE FROM pair");
    this.ctx.storage.sql.exec("INSERT INTO token (role, hash) VALUES (?, ?)", "agent", agentHash);
    this.ctx.storage.sql.exec(
      "INSERT INTO pair (code_hash, expires_at, used) VALUES (?, ?, 0)",
      codeHash,
      Date.now() + PAIR_TTL_MS,
    );
    return Response.json({ room, pairCode, agentToken });
  }

  private async pairClaim(request: Request): Promise<Response> {
    let body: { pairCode?: unknown; deviceName?: unknown };
    try {
      body = (await request.json()) as { pairCode?: unknown; deviceName?: unknown };
    } catch {
      return Response.json({ error: "bad_request" }, { status: 400 });
    }
    if (typeof body.pairCode !== "string" || body.pairCode.length === 0) {
      return unauthorized();
    }
    if (body.deviceName !== undefined && typeof body.deviceName !== "string") {
      return Response.json({ error: "bad_request" }, { status: 400 });
    }
    const codeHash = await sha256Hex(body.pairCode);
    const rows = this.ctx.storage.sql
      .exec<PairRow>("SELECT code_hash, expires_at, used FROM pair WHERE code_hash = ?", codeHash)
      .toArray();
    if (rows.length === 0) return unauthorized();
    const row = rows[0];
    if (Number(row.used) !== 0) return Response.json({ ok: false, error: "used" }, { status: 401 });
    if (Number(row.expires_at) < Date.now()) {
      return Response.json({ ok: false, error: "expired" }, { status: 401 });
    }
    this.ctx.storage.sql.exec("UPDATE pair SET used = 1 WHERE code_hash = ?", codeHash);
    const deviceToken = randomBase64Url(32);
    const deviceHash = await sha256Hex(deviceToken);
    this.ctx.storage.sql.exec(
      "INSERT OR REPLACE INTO token (role, hash) VALUES (?, ?)",
      "device",
      deviceHash,
    );
    return Response.json({ deviceToken });
  }

  private async revoke(request: Request): Promise<Response> {
    const token = bearerToken(request);
    if (!token) return unauthorized();
    const hash = await sha256Hex(token);
    const rows = this.ctx.storage.sql.exec<TokenRow>("SELECT role, hash FROM token").toArray();
    const match = rows.find((row) => timingSafeEqual(String(row.hash), hash));
    if (!match) return unauthorized();
    this.ctx.storage.sql.exec("DELETE FROM token");
    this.ctx.storage.sql.exec("DELETE FROM pair");
    for (const socket of this.ctx.getWebSockets()) socket.close(4000, "revoked");
    return Response.json({ ok: true });
  }

  private async upgrade(request: Request, role: "device" | "agent"): Promise<Response> {
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("expected websocket", { status: 426 });
    }
    const token = bearerToken(request);
    if (!token) return unauthorized();
    const presented = await sha256Hex(token);
    const rows = this.ctx.storage.sql
      .exec<{ hash: string }>("SELECT hash FROM token WHERE role = ?", role)
      .toArray();
    if (rows.length === 0 || !timingSafeEqual(String(rows[0].hash), presented)) return unauthorized();
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    const previous = this.ctx.getWebSockets(role);
    this.ctx.acceptWebSocket(server, [role]);
    const attachment: Attachment = { role, windowStart: 0, count: 0 };
    server.serializeAttachment(attachment);
    for (const existing of previous) existing.close(4001, "replaced");
    return new Response(null, { status: 101, webSocket: client });
  }
}

function messageByteLength(message: string | ArrayBuffer): number {
  if (typeof message === "string") return new TextEncoder().encode(message).byteLength;
  return message.byteLength;
}

function methodName(text: string): string {
  try {
    const parsed = JSON.parse(text) as { method?: unknown };
    if (typeof parsed.method === "string") return parsed.method.slice(0, 64);
  } catch {
    return "";
  }
  return "";
}

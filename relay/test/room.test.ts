import { env, runInDurableObject, SELF } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { adminOk } from "../src/index";
import type { Env } from "../src/env";

const ADMIN = "test-admin-token";

interface Started {
  room: string;
  pairCode: string;
  agentToken: string;
}

async function pairStart(token: string | null): Promise<Response> {
  const headers = new Headers();
  if (token !== null) headers.set("Authorization", `Bearer ${token}`);
  return SELF.fetch("http://relay.test/v1/pair/start", { method: "POST", headers });
}

async function startOk(): Promise<Started> {
  const response = await pairStart(ADMIN);
  expect(response.status).toBe(200);
  return (await response.json()) as Started;
}

async function claim(room: string, pairCode: string, deviceName = "pixel"): Promise<Response> {
  return SELF.fetch("http://relay.test/v1/pair/claim", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ room, pairCode, deviceName }),
  });
}

async function openSocket(room: string, role: "device" | "agent", token: string | null): Promise<Response> {
  const headers = new Headers({ Upgrade: "websocket", Connection: "Upgrade" });
  if (token !== null) headers.set("Authorization", `Bearer ${token}`);
  return SELF.fetch(`http://relay.test/v1/room/${room}/${role}`, { headers });
}

function accept(response: Response): WebSocket {
  const socket = response.webSocket;
  if (!socket) throw new Error(`no websocket, status ${response.status}`);
  socket.accept();
  return socket;
}

function nextMessage(socket: WebSocket): Promise<string> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("message timeout")), 2000);
    socket.addEventListener(
      "message",
      (event) => {
        clearTimeout(timer);
        const data = event.data;
        resolve(typeof data === "string" ? data : new TextDecoder().decode(data as ArrayBuffer));
      },
      { once: true },
    );
  });
}

describe("admin secret fails closed", () => {
  it("rejects pair/start when RELAY_ADMIN_TOKEN is unset or empty", async () => {
    const request = new Request("http://relay.test/v1/pair/start", {
      method: "POST",
      headers: { Authorization: "Bearer test-admin-token" },
    });
    const unset = { ROOM: env.ROOM } as Env;
    const empty = { ROOM: env.ROOM, RELAY_ADMIN_TOKEN: "" } as Env;
    expect(await adminOk(request, unset)).toBe(false);
    expect(await adminOk(request, empty)).toBe(false);
    const noBearer = new Request("http://relay.test/v1/pair/start", { method: "POST" });
    expect(await adminOk(noBearer, env)).toBe(false);
  });
});

describe("relay auth, ported from meta-glasses-companion test_server_auth.py", () => {
  it("rejects a missing bearer on pair/start", async () => {
    const response = await pairStart(null);
    expect(response.status).toBe(401);
  });

  it("rejects a wrong bearer on pair/start", async () => {
    const response = await pairStart("nope");
    expect(response.status).toBe(401);
  });

  it("accepts a matching admin bearer", async () => {
    const body = await startOk();
    expect(body.room).toMatch(/^[A-Z0-9]{12}$/);
    expect(body.pairCode).toMatch(/^[A-Z0-9]{8}$/);
    expect(body.agentToken.length).toBeGreaterThanOrEqual(42);
  });

  it("rejects a missing or wrong token on the device socket", async () => {
    const started = await startOk();
    const claimed = await claim(started.room, started.pairCode);
    const deviceToken = ((await claimed.json()) as { deviceToken: string }).deviceToken;
    const missing = await openSocket(started.room, "device", null);
    expect(missing.status).toBe(401);
    const wrong = await openSocket(started.room, "device", "wrong-token");
    expect(wrong.status).toBe(401);
    const queryOnly = await SELF.fetch(
      `http://relay.test/v1/room/${started.room}/device?token=${deviceToken}`,
      { headers: { Upgrade: "websocket" } },
    );
    expect(queryOnly.status).toBe(401);
    const ok = await openSocket(started.room, "device", deviceToken);
    expect(ok.status).toBe(101);
    accept(ok).close(1000, "done");
  });
});

describe("pairing", () => {
  it("uses a pair code once", async () => {
    const started = await startOk();
    const first = await claim(started.room, started.pairCode);
    expect(first.status).toBe(200);
    const body = (await first.json()) as { deviceToken: string };
    expect(body.deviceToken.length).toBeGreaterThanOrEqual(42);
    const second = await claim(started.room, started.pairCode);
    expect(second.status).toBe(401);
  });

  it("rejects an expired pair code", async () => {
    const started = await startOk();
    const id = env.ROOM.idFromName(started.room);
    const stub = env.ROOM.get(id);
    await runInDurableObject(stub, async (_instance, state) => {
      state.storage.sql.exec("UPDATE pair SET expires_at = ?", Date.now() - 1000);
    });
    const response = await claim(started.room, started.pairCode);
    expect(response.status).toBe(401);
    const body = (await response.json()) as { error: string };
    expect(body.error).toBe("expired");
  });

  it("stores only hashes", async () => {
    const started = await startOk();
    const claimed = await claim(started.room, started.pairCode, "pixel");
    const deviceToken = ((await claimed.json()) as { deviceToken: string }).deviceToken;
    const stub = env.ROOM.get(env.ROOM.idFromName(started.room));
    await runInDurableObject(stub, async (_instance, state) => {
      const tokens = state.storage.sql.exec("SELECT role, hash FROM token").toArray();
      const pairs = state.storage.sql.exec("SELECT code_hash, expires_at, used FROM pair").toArray();
      const dumped = JSON.stringify({ tokens, pairs });
      expect(dumped).not.toContain(started.agentToken);
      expect(dumped).not.toContain(started.pairCode);
      expect(dumped).not.toContain(deviceToken);
      expect(tokens).toHaveLength(2);
      for (const row of tokens) {
        expect(String(row.hash)).toMatch(/^[a-f0-9]{64}$/);
        expect(String(row.hash)).not.toBe(started.agentToken);
      }
      expect(String(pairs[0].code_hash)).toMatch(/^[a-f0-9]{64}$/);
    });
  });
});

describe("sockets", () => {
  it("replaces the previous device socket and forwards frames both ways", async () => {
    const started = await startOk();
    const claimed = await claim(started.room, started.pairCode);
    const deviceToken = ((await claimed.json()) as { deviceToken: string }).deviceToken;

    const firstResponse = await openSocket(started.room, "device", deviceToken);
    const first = accept(firstResponse);
    const closed = new Promise<CloseEvent>((resolve) => {
      first.addEventListener("close", (event) => resolve(event), { once: true });
    });

    const secondResponse = await openSocket(started.room, "device", deviceToken);
    const second = accept(secondResponse);
    const closeEvent = await closed;
    expect(closeEvent.code).toBe(4001);

    const agentResponse = await openSocket(started.room, "agent", started.agentToken);
    expect(agentResponse.status).toBe(101);
    const agent = accept(agentResponse);

    const toDevice = nextMessage(second);
    const request = JSON.stringify({ id: "1", method: "status", params: {} });
    agent.send(request);
    expect(JSON.parse(await toDevice)).toEqual({ id: "1", method: "status", params: {} });

    const toAgent = nextMessage(agent);
    const response = JSON.stringify({ id: "1", ok: true, data: { armed: false } });
    second.send(response);
    expect(JSON.parse(await toAgent)).toEqual({ id: "1", ok: true, data: { armed: false } });

    agent.close(1000, "done");
    second.close(1000, "done");
  });

  it("does not forward a frame over 1 MiB", async () => {
    const started = await startOk();
    const claimed = await claim(started.room, started.pairCode);
    const deviceToken = ((await claimed.json()) as { deviceToken: string }).deviceToken;
    const device = accept((await openSocket(started.room, "device", deviceToken)));
    const agent = accept((await openSocket(started.room, "agent", started.agentToken)));
    const closed = new Promise<number>((resolve) => {
      agent.addEventListener("close", (event) => resolve(event.code), { once: true });
    });
    let delivered = false;
    device.addEventListener("message", () => {
      delivered = true;
    });
    agent.send("x".repeat(MAX_PLUS));
    expect(await closed).toBe(1009);
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(delivered).toBe(false);
  });
});

const MAX_PLUS = 1024 * 1024 + 8;

import { createServer } from "node:http";
import type { AddressInfo } from "node:net";
import WebSocket from "ws";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { callPhone, errorEnvelope } from "../src/client.js";
import { toolContent } from "../src/mcp.js";
import { AgentSession } from "../src/session.js";
import { startFakeRelay, type FakeRelay } from "./fake-relay.js";

let relay: FakeRelay;

function load() {
  return { relayUrl: relay.url, room: relay.room, agentToken: relay.agentToken };
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function closedPortUrl(): Promise<string> {
  const server = createServer();
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  const { port } = server.address() as AddressInfo;
  await new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())));
  return `http://127.0.0.1:${port}`;
}

beforeAll(async () => {
  relay = await startFakeRelay();
});

afterAll(async () => {
  await relay.close();
});

describe("agent session", () => {
  it("runs two calls at once on one socket and reconnects after close", async () => {
    const session = new AgentSession(() => ({
      relayUrl: relay.url,
      room: relay.room,
      agentToken: relay.agentToken,
    }));
    const before = relay.agentSockets;
    const [snapshot, status] = await Promise.all([session.call("snapshot", {}), session.call("status", {})]);
    expect(snapshot.ok).toBe(true);
    expect((snapshot.data as { snapshot_id: string }).snapshot_id).toBe("s1");
    expect(status.ok).toBe(true);
    expect((status.data as { armed: boolean }).armed).toBe(true);
    expect(relay.agentSockets).toBe(before + 1);
    await relay.dropAgents();
    await new Promise((resolve) => setTimeout(resolve, 50));
    const again = await session.call("status", {});
    expect(again.ok).toBe(true);
    expect(relay.agentSockets).toBe(before + 2);
    session.close();
  });

  it("replaces a socket that stops answering pings", async () => {
    const session = new AgentSession(load, 5_000, 40, 40);
    try {
      const before = relay.agentSockets;
      const first = await session.call("status", {});
      expect(first.ok).toBe(true);
      expect(relay.agentSockets).toBe(before + 1);
      relay.pauseAgent();
      await delay(300);
      const again = await session.call("status", {}, 5_000);
      expect(again.ok).toBe(true);
      expect(relay.agentSockets).toBe(before + 2);
    } finally {
      session.close();
    }
  });

  it("returns REPLACED when another agent connects and reconnects on the next call", async () => {
    const session = new AgentSession(load);
    try {
      const opened = await session.call("status", {});
      expect(opened.ok).toBe(true);
      const before = relay.agentSockets;
      relay.holdDevice();
      const pending = session.call("status", {}, 5_000);
      await relay.connectAgent();
      const replaced = await pending;
      expect(replaced.ok).toBe(false);
      expect(replaced.error?.code).toBe("REPLACED");
      expect(replaced.error?.retry).toBe("never");
      relay.releaseDevice();
      const again = await session.call("status", {});
      expect(again.ok).toBe(true);
      expect(relay.agentSockets).toBe(before + 2);
    } finally {
      relay.releaseDevice();
      session.close();
    }
  });

  it("returns UNAUTHORIZED when the upgrade is rejected", async () => {
    const session = new AgentSession(() => ({ ...load(), agentToken: "wrong-token" }));
    try {
      const result = await session.call("status", {}, 5_000);
      expect(result.ok).toBe(false);
      expect(result.error?.code).toBe("UNAUTHORIZED");
      expect(result.error?.retry).toBe("never");
      const once = await callPhone({ ...load(), agentToken: "wrong-token" }, "status", {}, 5_000);
      expect(once.error?.code).toBe("UNAUTHORIZED");
      expect(once.error?.retry).toBe("never");
    } finally {
      session.close();
    }
  });

  it("returns RELAY_UNREACHABLE for a closed port", async () => {
    const relayUrl = await closedPortUrl();
    const session = new AgentSession(() => ({ ...load(), relayUrl }), 10_000);
    try {
      const started = Date.now();
      const result = await session.call("status", {}, 10_000);
      expect(Date.now() - started).toBeLessThan(2_000);
      expect(result.ok).toBe(false);
      expect(result.error?.code).toBe("RELAY_UNREACHABLE");
      expect(result.error?.retry).toBe("safe");
      const onceStarted = Date.now();
      const once = await callPhone({ ...load(), relayUrl }, "status", {}, 10_000);
      expect(Date.now() - onceStarted).toBeLessThan(2_000);
      expect(once.error?.code).toBe("RELAY_UNREACHABLE");
    } finally {
      session.close();
    }
  });

  it("retries a reused socket once when send throws", async () => {
    const session = new AgentSession(load);
    const original = WebSocket.prototype.send;
    try {
      const first = await session.call("status", {});
      expect(first.ok).toBe(true);
      const before = relay.agentSockets;
      let thrown = false;
      WebSocket.prototype.send = function send(this: WebSocket, ...args) {
        if (!thrown) {
          thrown = true;
          throw new Error("dead socket");
        }
        return original.apply(this, args as never);
      } as typeof WebSocket.prototype.send;
      const again = await session.call("status", {}, 5_000);
      expect(again.ok).toBe(true);
      expect(relay.agentSockets).toBe(before + 1);
    } finally {
      WebSocket.prototype.send = original;
      session.close();
    }
  });

  it("clears the heartbeat when closed", async () => {
    const intervals = new Set<ReturnType<typeof setInterval>>();
    const originalSet = globalThis.setInterval;
    const originalClear = globalThis.clearInterval;
    globalThis.setInterval = ((handler: TimerHandler, timeout?: number, ...args: unknown[]) => {
      const timer = originalSet(handler as () => void, timeout, ...(args as []));
      intervals.add(timer);
      return timer;
    }) as typeof setInterval;
    globalThis.clearInterval = ((timer: ReturnType<typeof setInterval>) => {
      intervals.delete(timer);
      originalClear(timer);
    }) as typeof clearInterval;
    const session = new AgentSession(load, 5_000, 30, 30);
    try {
      await session.call("status", {});
      expect(intervals.size).toBe(1);
      session.close();
      expect(intervals.size).toBe(0);
    } finally {
      session.close();
      globalThis.setInterval = originalSet;
      globalThis.clearInterval = originalClear;
    }
  });
});

describe("screenshot tool content", () => {
  it("returns image content for a png and text for an error", () => {
    const png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
    const image = toolContent("screenshot", { id: "1", ok: true, data: { mime: "image/png", base64: png } });
    expect(image.isError).toBe(false);
    expect(image.content).toEqual([{ type: "image", data: png, mimeType: "image/png" }]);
    const error = toolContent(
      "screenshot",
      errorEnvelope("2", "DENIED_BY_USER", "Screenshots are turned off on the phone.", "never", "Enable screenshots in Agent Bridge settings."),
    );
    expect(error.isError).toBe(true);
    expect(error.content[0].type).toBe("text");
    expect(error.content[0].text).toContain("DENIED_BY_USER");
    expect(error.content[0].text).not.toContain("iVBORw0KGgo");
  });
});

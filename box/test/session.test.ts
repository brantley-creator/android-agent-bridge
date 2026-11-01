import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { errorEnvelope } from "../src/client.js";
import { toolContent } from "../src/mcp.js";
import { AgentSession } from "../src/session.js";
import { startFakeRelay, type FakeRelay } from "./fake-relay.js";

let relay: FakeRelay;

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

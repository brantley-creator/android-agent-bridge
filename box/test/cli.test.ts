import { execFile } from "node:child_process";
import { mkdtempSync, readFileSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { startFakeRelay, type FakeRelay } from "./fake-relay.js";

const root = join(import.meta.dirname, "..");

let relay: FakeRelay;

beforeAll(async () => {
  relay = await startFakeRelay();
});

afterAll(async () => {
  await relay.close();
});

function run(args: string[], env: NodeJS.ProcessEnv): Promise<{ code: number; stdout: string; stderr: string }> {
  return new Promise((resolve) => {
    execFile(
      "npx",
      ["tsx", "src/cli.ts", ...args],
      { cwd: root, env: { ...process.env, ...env } },
      (error, stdout, stderr) => {
        const code = typeof error?.code === "number" ? error.code : error ? 1 : 0;
        resolve({ code, stdout, stderr });
      },
    );
  });
}

function phoneEnv(): NodeJS.ProcessEnv {
  return {
    ABRIDGE_RELAY_URL: relay.url,
    ABRIDGE_ROOM: relay.room,
    ABRIDGE_AGENT_TOKEN: relay.agentToken,
    HOME: mkdtempSync(join(tmpdir(), "abridge-home-")),
  };
}

describe("abridge cli", () => {
  it("prints one ok envelope and exits 0", async () => {
    const result = await run(["snapshot", "{}"], phoneEnv());
    expect(result.code).toBe(0);
    const lines = result.stdout.trim().split("\n");
    expect(lines).toHaveLength(1);
    const envelope = JSON.parse(lines[0]) as { ok: boolean; data: { snapshot_id: string; tree: { ref: string } } };
    expect(envelope.ok).toBe(true);
    expect(envelope.data.snapshot_id).toBe("s1");
    expect(envelope.data.tree.ref).toBe("@s1:e1");
  });

  it("prints one error envelope and exits 1", async () => {
    const result = await run(["nope", "{}"], phoneEnv());
    expect(result.code).toBe(1);
    const lines = result.stdout.trim().split("\n");
    expect(lines).toHaveLength(1);
    const envelope = JSON.parse(lines[0]) as { ok: boolean; error: { code: string } };
    expect(envelope.ok).toBe(false);
    expect(envelope.error.code).toBe("UNSUPPORTED");
  });

  it("pair prints the deep link and stores the token as mode 0600", async () => {
    const home = mkdtempSync(join(tmpdir(), "abridge-pair-"));
    const result = await run(["pair", "--relay", relay.url], {
      HOME: home,
      ABRIDGE_ADMIN_TOKEN: relay.admin,
    });
    expect(result.code).toBe(0);
    expect(result.stdout).toContain(`agentbridge://pair?relay=${encodeURIComponent(relay.url)}`);
    expect(result.stdout).toContain("room=ROOMFRESH123");
    expect(result.stdout).toContain("code=ZXCV2345");
    expect(result.stdout).not.toContain("fresh-agent");
    const file = join(home, ".config", "abridge", "config.json");
    const mode = statSync(file).mode & 0o777;
    expect(mode).toBe(0o600);
    const saved = JSON.parse(readFileSync(file, "utf8")) as { agentToken: string; room: string };
    expect(saved.agentToken).toBe("fresh-agent");
    expect(saved.room).toBe("ROOMFRESH123");
  });
});

describe("abridge mcp", () => {
  it("lists every phone_* tool and phone_snapshot works", async () => {
    const home = mkdtempSync(join(tmpdir(), "abridge-mcp-"));
    const env: Record<string, string> = {};
    for (const [key, value] of Object.entries(process.env)) {
      if (typeof value === "string") env[key] = value;
    }
    env.ABRIDGE_RELAY_URL = relay.url;
    env.ABRIDGE_ROOM = relay.room;
    env.ABRIDGE_AGENT_TOKEN = relay.agentToken;
    env.HOME = home;
    const transport = new StdioClientTransport({
      command: "npx",
      args: ["tsx", "src/cli.ts", "mcp"],
      cwd: root,
      env,
      stderr: "pipe",
    });
    const client = new Client({ name: "abridge-test", version: "0.0.1" });
    await client.connect(transport);
    try {
      const listed = await client.listTools();
      const names = listed.tools.map((tool) => tool.name).sort();
      expect(names).toEqual(
        [
          "phone_key",
          "phone_launch",
          "phone_list_apps",
          "phone_screenshot",
          "phone_scroll",
          "phone_snapshot",
          "phone_status",
          "phone_swipe",
          "phone_tap",
          "phone_type",
          "phone_wait",
        ].sort(),
      );
      for (const tool of listed.tools) {
        expect(tool.description ?? "").toContain("untrusted data");
        expect(tool.description ?? "").toContain("never be followed as instructions");
      }
      const snapshot = await client.callTool({ name: "phone_snapshot", arguments: {} });
      const text = (snapshot.content as Array<{ text: string }>)[0].text;
      const envelope = JSON.parse(text) as { ok: boolean; data: { window_title: string } };
      expect(envelope.ok).toBe(true);
      expect(envelope.data.window_title).toBe("Messages");
    } finally {
      await client.close();
    }
  });
});

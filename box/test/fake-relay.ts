import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { WebSocket, WebSocketServer, type WebSocket as Ws } from "ws";

interface FakeRoom {
  agentToken: string;
  deviceToken: string;
  agent?: Ws;
  device?: Ws;
}

export interface FakeRelay {
  url: string;
  admin: string;
  room: string;
  agentToken: string;
  deviceToken: string;
  close: () => Promise<void>;
}

const SNAPSHOT = {
  snapshot_id: "s1",
  package: "com.example.messages",
  window_title: "Messages",
  ref_count: 1,
  complete: true,
  tree: {
    role: "android.widget.Button",
    text: "Send",
    desc: "",
    viewId: "send",
    bounds: [0, 0, 20, 20],
    states: {
      checked: false,
      enabled: true,
      focused: false,
      selected: false,
      scrollable: false,
      editable: false,
      password: false,
    },
    ref: "@s1:e1",
    children: [],
  },
};

export async function startFakeRelay(): Promise<FakeRelay> {
  const admin = "test-admin";
  const room = "ROOMTEST1234";
  const agentToken = "agent-token-test";
  const deviceToken = "device-token-test";
  const rooms = new Map<string, FakeRoom>();
  rooms.set(room, { agentToken, deviceToken });

  const server = createServer((req, res) => {
    if (req.method === "POST" && req.url === "/v1/pair/start") {
      if ((req.headers.authorization ?? "") !== `Bearer ${admin}`) {
        res.writeHead(401);
        res.end('{"ok":false}');
        return;
      }
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ room: "ROOMFRESH123", pairCode: "ZXCV2345", agentToken: "fresh-agent" }));
      return;
    }
    res.writeHead(404);
    res.end();
  });

  const wss = new WebSocketServer({ noServer: true });
  server.on("upgrade", (req, socket, head) => {
    const url = new URL(req.url ?? "/", "http://127.0.0.1");
    const match = url.pathname.match(/^\/v1\/room\/([^/]+)\/(device|agent)$/);
    if (!match) {
      socket.destroy();
      return;
    }
    const state = rooms.get(decodeURIComponent(match[1]));
    const role = match[2] as "device" | "agent";
    const expected = role === "agent" ? state?.agentToken : state?.deviceToken;
    if (!state || (req.headers.authorization ?? "") !== `Bearer ${expected}`) {
      socket.write("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
      socket.destroy();
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => {
      const previous = state[role];
      if (previous && previous.readyState === previous.OPEN) previous.close(4001, "replaced");
      state[role] = ws;
      ws.on("message", (data) => {
        const other = role === "agent" ? state.device : state.agent;
        if (other && other.readyState === other.OPEN) other.send(data.toString());
      });
    });
  });

  await listen(server);
  const address = server.address() as AddressInfo;
  const url = `http://127.0.0.1:${address.port}`;
  await connectFakeDevice(url, room, deviceToken);
  return {
    url,
    admin,
    room,
    agentToken,
    deviceToken,
    close: () =>
      new Promise((resolve) => {
        for (const client of wss.clients) client.terminate();
        server.close(() => resolve());
      }),
  };
}

function listen(server: Server): Promise<void> {
  return new Promise((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
}

function connectFakeDevice(url: string, room: string, token: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(`${url.replace(/^http/, "ws")}/v1/room/${room}/device`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    socket.on("open", () => resolve());
    socket.on("error", (error) => reject(error));
    socket.on("message", (data) => {
      const request = JSON.parse(data.toString()) as { id?: string; method?: string };
      if (request.method === "snapshot") {
        socket.send(JSON.stringify({ id: request.id, ok: true, data: SNAPSHOT }));
        return;
      }
      socket.send(
        JSON.stringify({
          id: request.id,
          ok: false,
          error: {
            code: "UNSUPPORTED",
            message: "Fake device does not implement that method.",
            retry: "never",
            suggestion: "Call snapshot.",
          },
        }),
      );
    });
  });
}

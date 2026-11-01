import { bearerToken, randomRoomId, sha256Hex, timingSafeEqual, unauthorized } from "./crypto";
import type { Env } from "./env";

export { Room } from "./room";

const ROOM_ID = /^[A-Za-z0-9]{8,32}$/;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    try {
      if (request.method === "GET" && url.pathname === "/health") {
        return Response.json({ ok: true });
      }
      if (request.method === "POST" && url.pathname === "/v1/pair/start") {
        if (!(await adminOk(request, env))) return unauthorized();
        const room = randomRoomId();
        const stub = env.ROOM.get(env.ROOM.idFromName(room));
        return stub.fetch(
          new Request("https://room.internal/pair/start", {
            method: "POST",
            headers: { "x-room-id": room },
          }),
        );
      }
      if (request.method === "POST" && url.pathname === "/v1/pair/claim") {
        const text = await request.text();
        const room = roomFromJson(text);
        if (!room) return Response.json({ ok: false, error: "bad_request" }, { status: 400 });
        const stub = env.ROOM.get(env.ROOM.idFromName(room));
        return stub.fetch(
          new Request("https://room.internal/pair/claim", {
            method: "POST",
            headers: { "content-type": "application/json" },
            body: text,
          }),
        );
      }
      if (request.method === "POST" && url.pathname === "/v1/revoke") {
        const text = await request.text();
        const room = roomFromJson(text);
        if (!room) return Response.json({ ok: false, error: "bad_request" }, { status: 400 });
        const headers = new Headers({ "content-type": "application/json" });
        const authorization = request.headers.get("Authorization");
        if (authorization) headers.set("Authorization", authorization);
        const stub = env.ROOM.get(env.ROOM.idFromName(room));
        return stub.fetch(new Request("https://room.internal/revoke", { method: "POST", headers }));
      }
      const socket = url.pathname.match(/^\/v1\/room\/([^/]+)\/(device|agent)$/);
      if (socket && request.method === "GET") {
        const room = decodeURIComponent(socket[1]);
        if (!ROOM_ID.test(room)) return new Response("not found", { status: 404 });
        const stub = env.ROOM.get(env.ROOM.idFromName(room));
        return stub.fetch(request);
      }
      return new Response("not found", { status: 404 });
    } catch {
      console.log(JSON.stringify({ event: "error" }));
      return new Response("error", { status: 500 });
    }
  },
};

export async function adminOk(request: Request, env: Env): Promise<boolean> {
  const expected = env.RELAY_ADMIN_TOKEN ?? "";
  if (!expected) return false;
  const presented = bearerToken(request);
  if (!presented) return false;
  const a = await sha256Hex(presented);
  const b = await sha256Hex(expected);
  return timingSafeEqual(a, b);
}

function roomFromJson(text: string): string | null {
  try {
    const parsed = JSON.parse(text) as { room?: unknown };
    if (typeof parsed.room !== "string" || !ROOM_ID.test(parsed.room)) return null;
    return parsed.room;
  } catch {
    return null;
  }
}

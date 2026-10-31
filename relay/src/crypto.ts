const PAIR_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

export async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

export function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

export function randomBase64Url(bytes: number): string {
  const buf = new Uint8Array(bytes);
  crypto.getRandomValues(buf);
  let binary = "";
  for (const byte of buf) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

export function randomPairCode(): string {
  let out = "";
  while (out.length < 8) {
    const buf = new Uint8Array(8);
    crypto.getRandomValues(buf);
    for (const byte of buf) {
      if (byte >= 256 - (256 % PAIR_ALPHABET.length)) continue;
      out += PAIR_ALPHABET[byte % PAIR_ALPHABET.length];
      if (out.length === 8) break;
    }
  }
  return out;
}

export function randomRoomId(): string {
  return randomPairCode() + randomPairCode().slice(0, 4);
}

export function bearerToken(request: Request): string | null {
  const header = request.headers.get("Authorization") ?? "";
  const match = /^Bearer ([A-Za-z0-9._~+/-]+=*)$/.exec(header);
  if (!match) return null;
  const token = match[1].trim();
  return token.length > 0 ? token : null;
}

export function unauthorized(): Response {
  return Response.json({ ok: false, error: "unauthorized" }, { status: 401 });
}

export const PAIR_TTL_MS = 10 * 60 * 1000;
export const MAX_FRAME_BYTES = 1024 * 1024;
export const MAX_MESSAGES_PER_SECOND = 20;

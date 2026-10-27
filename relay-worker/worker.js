const TTL = 7200;
const SKEW = 300;
const PER_ID_DAILY = 30;
const GLOBAL_DAILY = 3000;
const MAX_BODY = 1024;
const HEX64 = /^[0-9a-f]{64}$/;

function json(status, data) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json", "cache-control": "no-store" },
  });
}

function hexToBytes(hex) {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.substr(i * 2, 2), 16);
  return out;
}

async function verifySig(secret, id, t, sig) {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey(
    "raw",
    enc.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["verify"],
  );
  return crypto.subtle.verify("HMAC", key, hexToBytes(sig), enc.encode(id + "." + t));
}

async function readBody(request) {
  const text = await request.text();
  if (text.length > MAX_BODY) return null;
  try {
    const body = JSON.parse(text);
    return body && typeof body === "object" ? body : null;
  } catch {
    return null;
  }
}

async function takeQuota(kv, id, nowSec) {
  const day = new Date(nowSec * 1000).toISOString().slice(0, 10);
  const gKey = "g:" + day;
  const uKey = "u:" + day + ":" + id;
  const [gRaw, uRaw] = await Promise.all([kv.get(gKey), kv.get(uKey)]);
  const g = parseInt(gRaw || "0", 10) || 0;
  const u = parseInt(uRaw || "0", 10) || 0;
  if (g >= GLOBAL_DAILY) return "global";
  if (u >= PER_ID_DAILY) return "id";
  const opts = { expirationTtl: 172800 };
  await Promise.all([kv.put(gKey, String(g + 1), opts), kv.put(uKey, String(u + 1), opts)]);
  return "ok";
}

async function handleTurn(request, env, nowSec) {
  if (!env.TURN_KEY_ID || !env.TURN_KEY_API_TOKEN || !env.APP_SECRET) {
    return json(503, { error: "not_configured" });
  }

  const body = await readBody(request);
  if (!body) return json(400, { error: "bad_request" });
  const { id, t, sig } = body;
  if (typeof id !== "string" || !HEX64.test(id)) return json(400, { error: "bad_request" });
  if (typeof sig !== "string" || !HEX64.test(sig)) return json(400, { error: "bad_request" });
  if (!Number.isInteger(t)) return json(400, { error: "bad_request" });
  if (Math.abs(nowSec - t) > SKEW) return json(401, { error: "stale" });
  if (!(await verifySig(env.APP_SECRET, id, t, sig))) return json(401, { error: "bad_sig" });

  // Without the KV store the limits can't be kept (memory resets whenever Cloudflare restarts the Worker),
  // so nothing is handed out: the daily caps are the only thing guarding the TURN account.
  if (!env.LIMITS) return json(503, { error: "limits_unavailable" });
  let quota;
  try {
    quota = await takeQuota(env.LIMITS, id, nowSec);
  } catch {
    return json(503, { error: "limits_unavailable" });
  }
  if (quota !== "ok") return json(429, { error: quota === "global" ? "global_limit" : "id_limit" });

  let upstream;
  try {
    upstream = await fetch(
      "https://rtc.live.cloudflare.com/v1/turn/keys/" +
        encodeURIComponent(env.TURN_KEY_ID) +
        "/credentials/generate-ice-servers",
      {
        method: "POST",
        headers: {
          Authorization: "Bearer " + env.TURN_KEY_API_TOKEN,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ ttl: TTL }),
      },
    );
  } catch {
    return json(502, { error: "upstream" });
  }
  if (upstream.status !== 201 && upstream.status !== 200) return json(502, { error: "upstream" });

  let data;
  try {
    data = await upstream.json();
  } catch {
    return json(502, { error: "upstream" });
  }

  const servers = Array.isArray(data && data.iceServers) ? data.iceServers : [];
  const urls = [];
  let username = null;
  let credential = null;
  for (const s of servers) {
    const list = Array.isArray(s.urls) ? s.urls : [s.urls];
    const turn = list.filter((u) => typeof u === "string" && /^turns?:/.test(u));
    if (turn.length && s.username && s.credential && (!username || s.username === username)) {
      urls.push(...turn);
      username = username || s.username;
      credential = credential || s.credential;
    }
  }
  if (!urls.length || !username || !credential) return json(502, { error: "upstream" });

  return json(200, { urls, username, credential, ttl: TTL });
}

export async function handle(request, env, nowSec = Math.floor(Date.now() / 1000)) {
  const { pathname } = new URL(request.url);
  if (pathname === "/health") {
    return request.method === "GET" ? json(200, { ok: true }) : json(405, { error: "method" });
  }
  if (pathname === "/turn") {
    return request.method === "POST" ? handleTurn(request, env, nowSec) : json(405, { error: "method" });
  }
  return json(404, { error: "not_found" });
}

export default {
  fetch(request, env) {
    return handle(request, env);
  },
};

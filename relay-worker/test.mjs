import { createHmac } from "node:crypto";
import assert from "node:assert/strict";
import worker, { handle } from "./worker.js";

const SECRET = "test-app-secret";
const ID = "a".repeat(64);
const NOW = 1_800_000_000;

class FakeKV {
  constructor() { this.map = new Map(); this.fail = false; }
  async get(k) { if (this.fail) throw new Error("kv down"); return this.map.has(k) ? this.map.get(k) : null; }
  async put(k, v) { if (this.fail) throw new Error("kv down"); this.map.set(k, v); }
}

const upstreamCalls = [];
globalThis.fetch = async (url, init) => {
  upstreamCalls.push({ url, init });
  return new Response(JSON.stringify({
    iceServers: [
      { urls: ["stun:stun.cloudflare.com:3478", "stun:stun.cloudflare.com:53"] },
      {
        urls: [
          "turn:turn.cloudflare.com:3478?transport=udp",
          "turn:turn.cloudflare.com:3478?transport=tcp",
          "turns:turn.cloudflare.com:5349?transport=tcp",
          "turns:turn.cloudflare.com:443?transport=tcp",
        ],
        username: "user123",
        credential: "cred456",
      },
    ],
  }), { status: 201, headers: { "content-type": "application/json" } });
};

function env(kv = new FakeKV()) {
  return { TURN_KEY_ID: "kid", TURN_KEY_API_TOKEN: "tok", APP_SECRET: SECRET, LIMITS: kv };
}

function sign(id, t, secret = SECRET) {
  return createHmac("sha256", secret).update(id + "." + t).digest("hex");
}

function req(body, method = "POST", path = "/turn") {
  return new Request("https://relay.example.workers.dev" + path, {
    method,
    body: method === "POST" ? (typeof body === "string" ? body : JSON.stringify(body)) : undefined,
  });
}

async function call(e, body, opts = {}) {
  const res = await handle(req(body, opts.method, opts.path), e, opts.now ?? NOW);
  return { status: res.status, data: await res.json() };
}

let passed = 0;
async function test(name, fn) { await fn(); passed++; console.log("ok  " + name); }

await test("health", async () => {
  const res = await worker.fetch(new Request("https://x/health"), {});
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { ok: true });
});

await test("unknown path is 404, wrong method is 405", async () => {
  assert.equal((await call(env(), {}, { path: "/nope" })).status, 404);
  assert.equal((await call(env(), null, { method: "GET" })).status, 405);
});

await test("valid request returns relay-only credentials", async () => {
  upstreamCalls.length = 0;
  const r = await call(env(), { id: ID, t: NOW, sig: sign(ID, NOW) });
  assert.equal(r.status, 200);
  assert.equal(r.data.username, "user123");
  assert.equal(r.data.credential, "cred456");
  assert.equal(r.data.ttl, 7200);
  assert.equal(r.data.urls.length, 4);
  assert.ok(r.data.urls.every((u) => /^turns?:/.test(u)));
  assert.equal(upstreamCalls.length, 1);
  const c = upstreamCalls[0];
  assert.equal(c.url, "https://rtc.live.cloudflare.com/v1/turn/keys/kid/credentials/generate-ice-servers");
  assert.equal(c.init.headers.Authorization, "Bearer tok");
  assert.deepEqual(JSON.parse(c.init.body), { ttl: 7200 });
});

await test("bad signature is 401", async () => {
  const r = await call(env(), { id: ID, t: NOW, sig: sign(ID, NOW, "wrong") });
  assert.equal(r.status, 401);
});

await test("timestamp outside 300 s is 401, inside is ok", async () => {
  assert.equal((await call(env(), { id: ID, t: NOW - 301, sig: sign(ID, NOW - 301) })).status, 401);
  assert.equal((await call(env(), { id: ID, t: NOW + 301, sig: sign(ID, NOW + 301) })).status, 401);
  assert.equal((await call(env(), { id: ID, t: NOW - 300, sig: sign(ID, NOW - 300) })).status, 200);
});

await test("malformed bodies are 400", async () => {
  assert.equal((await call(env(), "not json")).status, 400);
  assert.equal((await call(env(), { id: "xyz", t: NOW, sig: sign("xyz", NOW) })).status, 400);
  assert.equal((await call(env(), { id: ID, t: String(NOW), sig: sign(ID, NOW) })).status, 400);
  assert.equal((await call(env(), "x".repeat(2000))).status, 400);
});

await test("per id cap of 30 per day", async () => {
  const e = env();
  for (let i = 0; i < 30; i++) {
    assert.equal((await call(e, { id: ID, t: NOW, sig: sign(ID, NOW) })).status, 200);
  }
  const r = await call(e, { id: ID, t: NOW, sig: sign(ID, NOW) });
  assert.equal(r.status, 429);
  assert.equal(r.data.error, "id_limit");
  const other = "b".repeat(64);
  assert.equal((await call(e, { id: other, t: NOW, sig: sign(other, NOW) })).status, 200);
  const tomorrow = NOW + 86400;
  assert.equal((await call(e, { id: ID, t: tomorrow, sig: sign(ID, tomorrow) }, { now: tomorrow })).status, 200);
});

await test("global cap of 3000 per day", async () => {
  const kv = new FakeKV();
  const day = new Date(NOW * 1000).toISOString().slice(0, 10);
  kv.map.set("g:" + day, "3000");
  const r = await call(env(kv), { id: ID, t: NOW, sig: sign(ID, NOW) });
  assert.equal(r.status, 429);
  assert.equal(r.data.error, "global_limit");
});

await test("no KV bound or broken KV: fails closed", async () => {
  const e = env();
  delete e.LIMITS;
  upstreamCalls.length = 0;
  const r = await call(e, { id: ID, t: NOW, sig: sign(ID, NOW) });
  assert.equal(r.status, 503);
  assert.equal(r.data.error, "limits_unavailable");
  assert.equal(upstreamCalls.length, 0);
  const kv = new FakeKV();
  kv.fail = true;
  assert.equal((await call(env(kv), { id: ID, t: NOW, sig: sign(ID, NOW) })).status, 503);
  assert.equal(upstreamCalls.length, 0);
});

await test("missing secrets is 503", async () => {
  const e = env();
  delete e.TURN_KEY_API_TOKEN;
  assert.equal((await call(e, { id: ID, t: NOW, sig: sign(ID, NOW) })).status, 503);
});

await test("upstream failure is 502", async () => {
  const saved = globalThis.fetch;
  globalThis.fetch = async () => new Response("{}", { status: 401 });
  assert.equal((await call(env(), { id: ID, t: NOW, sig: sign(ID, NOW) })).status, 502);
  globalThis.fetch = saved;
});

console.log(passed + " tests passed");

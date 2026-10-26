# Relay credentials (app side)

Calls can fall back to Cloudflare TURN relays. The apps never hold the Cloudflare TURN token. They ask the
relay Worker (`relay-worker/worker.js`) for a short-lived TURN login instead. The apps are relay-only for
this path: they get TURN URLs, never STUN URLs, from the Worker.

## Constants

The Worker URL and `APP_SECRET` live in one constants file per app, and nowhere else:

- Huginn: `src/relayconfig.h`
- Muninn: `android/app/src/main/java/io/github/ravenor907/muninn/RelayConfig.kt`

```
RELAY_URL  = "https://huginn-relay.<account>.workers.dev"
APP_SECRET = "<64 hex chars, same value as the Worker secret>"
```

`APP_SECRET` is not a real secret (anyone can pull it out of an app). It only stops casual scripts. The real
protection is the Worker's daily caps.

## Request

`POST {RELAY_URL}/turn`, `Content-Type: application/json`, sent over Tor like other network requests.

```json
{"id": "<hex>", "t": 1800000000, "sig": "<hex>"}
```

- `id`: lowercase hex SHA-256 of the caller's identity public key (64 chars).
- `t`: current Unix time in whole seconds (a JSON integer, not a string).
- `sig`: lowercase hex HMAC-SHA256 with key `APP_SECRET` (the UTF-8 bytes of the string) over the UTF-8
  string `id + "." + t`, for example `aaaa...aaaa.1800000000` (the 64-char id, a dot, then the digits of t).

The Worker rejects `t` more than 300 s away from its clock. If the device clock is badly off, the request fails
with 401 `stale`.

## Response

200:

```json
{
  "urls": [
    "turn:turn.cloudflare.com:3478?transport=udp",
    "turn:turn.cloudflare.com:3478?transport=tcp",
    "turns:turn.cloudflare.com:5349?transport=tcp",
    "turns:turn.cloudflare.com:443?transport=tcp"
  ],
  "username": "...",
  "credential": "...",
  "ttl": 7200
}
```

- `urls`: only `turn:` and `turns:` URLs, UDP and TCP, as Cloudflare returns them (ports 3478, 443, 80, 5349,
  maybe 53). Use them in the order given; skip any the app can't use.
- `username` and `credential`: the TURN long-term credential (RFC 8489 style) for every URL in the list.
- `ttl`: seconds the login is valid, counted from when the response arrived.

Errors are JSON `{"error": "<code>"}`:

| Status | `error` | App behaviour |
| --- | --- | --- |
| 400 | `bad_request` | Bug in the app. Don't retry. |
| 401 | `stale`, `bad_sig` | Clock wrong or wrong `APP_SECRET`. Don't retry this call. |
| 429 | `id_limit`, `global_limit` | Daily cap reached. No relay until the next UTC day. |
| 502 | `upstream` | Cloudflare TURN API failed. Retry later. |
| 503 | `limits_unavailable`, `not_configured` | Worker not set up or KV down. Retry later. |

On any error, the call goes on without a relay (direct paths only).

## Caching

- Store `{urls, username, credential, expires}` with `expires = time the response arrived + ttl`.
- Reuse the cached login until 5 minutes before `expires` (`now < expires - 300`). After that, fetch a new one.
- Fetch lazily: only when a call needs a relay, not at app start.
- Keep the cache in memory (or app private storage). Never log the credential.
- After a 429, don't ask again until the next UTC day. After a 5xx, wait at least 60 s before asking again.
- A call already using a login keeps it; refreshing only affects new allocations.

## Health

`GET {RELAY_URL}/health` returns `{"ok":true}`. The apps don't need it; it is for the owner to check the Worker.

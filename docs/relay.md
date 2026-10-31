# Relay credentials (app side)

Over the internet, calls go through Cloudflare TURN relays (docs/protocol-0.6.md 5.1 and 5.6). The one
exception is two people who trust each other (5.8.1, the Family folder or the "Direct calls" switch): their
apps also try a direct path, which costs the relay nothing (5.8.2). The apps never hold the Cloudflare TURN
token. They ask the relay Worker (`relay-worker/worker.js`) for a short-lived TURN login instead, and get
TURN URLs from it, never STUN URLs.

## STUN (trusted contacts only)

For the direct path each app asks `stun.cloudflare.com:3478` (Cloudflare's free STUN service, no login,
nothing to do with the Worker) what its call socket's public address is: one Binding request from the call
socket, only in a call with someone it trusts, never otherwise. The answer goes only to that person, in the
offer or answer (`srflx`). The STUN server sees your public address, as the TURN server does. Nothing here
changes the Worker or its daily caps.

## Data the relay carries

- Sound: about 30 to 40 kbps each way (24 kbps Opus plus headers between two current apps, 16 kbps with
  older ones). Nothing while a free path (Wi-Fi or direct) carries the call: the relay only gets a ping a
  second then (5.8.3).
- Video: 480p, at most 700 kbps, about 0.4 GB an hour with the sound (5.8.6). On Wi-Fi or a direct path it
  may go up to 720p, without the relay.

## Constants

The Worker URL and `APP_SECRET` live in one constants file per app, and nowhere else:

- Huginn: `src/call/relayconfig.h` (`RelayConfig::kWorkerUrl`, `RelayConfig::kAppSecret`)
- Muninn: `android/app/src/main/java/io/github/ravenor907/muninn/net/call/RelayConfig.kt`
  (`RelayConfig.WORKER_URL`, `RelayConfig.APP_SECRET`)

```
WORKER_URL = "https://huginn-relay.ravenor907.workers.dev"
APP_SECRET = "<64 hex chars, same value as the Worker secret>"
```

`APP_SECRET` is not a real secret (anyone can pull it out of an app). It only stops casual scripts. The real
protection is the Worker's daily caps.

## Request

`POST {WORKER_URL}/turn`, `Content-Type: application/json`, over HTTPS.

```json
{"id": "<hex>", "t": 1800000000, "sig": "<hex>"}
```

- `id`: lowercase hex SHA-256 (64 chars) of a random 32-byte relay id that each install makes once and keeps in
  its vault (`relayId` in `secrets.json`). Until 0.8 it was the hash of the chat key, which anyone who knew the
  key could compute, use up that person's 30 daily logins with, and which let the Worker see who called.
  Huginn `RelayCredentials::body`, Muninn `TurnCreds.body`; both self-tests check the same example.
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
  "ttl": 1800
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
| 429 | `id_limit`, `ip_limit`, `global_limit` | Daily cap reached (per install, per internet address, or for everyone). No relay until the next UTC day. |
| 502 | `upstream` | Cloudflare TURN API failed. Retry later. |
| 503 | `limits_unavailable`, `not_configured` | Worker not set up or KV down. Retry later. |

On any error, the call goes on without a relay: only the Wi-Fi and Bluetooth paths can work, and an internet
call fails with "Can't connect".

## Caching

- Store `{urls, username, credential, expires}` with `expires = time the response arrived + ttl`.
- Reuse the cached login until 5 minutes before `expires` (`now < expires - 300`). After that, fetch a new one.
- Fetch when a call is near, not at app start: when a conversation with a contact that can take calls
  (`c1`) is opened (at most one try a minute), when a call starts, and when an offer arrives. The login is
  then usually cached by the time Call is pressed, so starting a call doesn't wait for the Worker.
  (Huginn `Calls.prepare`, Muninn `CallManager.prepare`.)
- Keep the cache in memory (or app private storage). Never log the credential.
- After a 429, don't ask again until the next UTC day. After a 5xx, wait at least 60 s before asking again.
- A call already using a login keeps it; refreshing only affects new allocations.

## Reaching the TURN server

The TURN URLs carry a host name (`turn.cloudflare.com`). The apps look it up with the system resolver,
a few tries a second apart. If it can't be looked up, the call has no relay and says "Can't reach the call
relay right now". The audio goes from the device to the TURN server's address directly (UDP, or TLS on 443).

## Health

`GET {WORKER_URL}/health` returns `{"ok":true}`. The apps don't need it; it is for the owner to check the Worker.

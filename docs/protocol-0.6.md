# Huginn and Muninn 0.6 protocol

This is the wire protocol both apps speak from 0.6: group chats, 1:1 calls, voice rooms and the kids' TV.
It builds on the 0.5 envelope and message ratchet without changing them. Everything deterministic
(canonical JSON, signatures, group and TV acceptance rules, call keys, media packets, replay window) is
implemented in `tools/proto06_ref.py`, which writes `tests/proto06-vectors.json`. When this text and the
reference disagree, the reference wins and this text gets fixed.

Words: MUST and MUST NOT are hard rules for interoperating. SHOULD is the expected behaviour; an app that
does otherwise still interoperates.

## 0. What stays the same

- Identity: each device has one P-256 key pair, the "chat key". Its public half travels as standard base64
  of the SubjectPublicKeyInfo DER (`fromKey`, contact codes). In 0.6 the same key also signs (ECDSA, see 2.2).
- Envelope, on every route:
  `{room, fromKey, fromName, fromId, mailbox, id, cap:"r1", caps, [invite], [o], (nonce, data) | (rh, data)}`
  (`caps` is 1.1, `o` is 0.1).
- Routes: Bluetooth LE (BleFrames), local Wi-Fi `POST /api/huginn/v1/message`, internet as a Nostr kind 1059
  event whose content is the envelope sealed to the recipient's chat key with a one-off key.
- Pairwise sealing: the 0.5 message ratchet per contact (`tools/ratchet_ref.py`), or v1 (static shared key)
  where the ratchet isn't used.
- Every message goes out on every route at once; the receiver keeps the first copy (dedupe by id) and
  answers with a sealed receipt; the sender retries the same sealed bytes (5 s, 10 s, 20 s, 60 s, then every
  5 min) for a day until "delivered".

All new payloads in this document are UTF-8 JSON plaintext inside `data`, sealed exactly like a private
message. "Send to X" always means: build an envelope for X, seal it pairwise for X, send it on all routes.

### 0.1 Onion addresses (`o`, `onion`): retired

The first 0.6 builds ran a Tor onion service and sent its address as `"o"` (contact codes, group codes,
envelopes) and `"onion"` (Bluetooth LE hellos, Wi-Fi announcements). That is gone: internet messages go
straight to the Nostr relays again, as in 0.5. Apps MUST NOT send `o` or `onion` any more, and MUST accept
codes, envelopes and hellos that still carry them (from those early 0.6 builds), ignoring the field. There
is no caps token for it.

## 1. Capabilities

### 1.1 The `caps` field

`cap` MUST stay exactly `"r1"`. Both 0.5 apps test `cap == "r1"` (Kotlin `ChatWire.capable`, Huginn
`ChatWire::saysCapable`), so a value like `"r1 g1 c1"` would make every 0.5 peer stop switching new
contacts to the ratchet.

0.6 adds a second field to every envelope it sends:

```
"caps": "r1 g1 c1 k1"
```

Space-separated tokens, in any order. Unknown tokens are ignored. 0.5 apps ignore the field.

| token | meaning |
|---|---|
| `r1` | message ratchet (same as `cap:"r1"`) |
| `g1` | group chats (section 3) |
| `c1` | 1:1 calls and voice rooms (sections 5, 6) |
| `k1` | kids' TV management (section 7). A parent app and a TV both send it. |

A receiver records the token set per contact from the last **authentic** envelope (after it opened), like
`authentic()` does for the ratchet today. Tokens are never removed by an envelope that lacks them unless
the contact's key changed (a changed key resets the set to empty, as it resets the ratchet).

### 1.2 Caps probe

A contact who hasn't sent anything since upgrading has unknown caps. To find out, send a receipt with no ids:

```
room "receipt", plaintext {"ids": [], "s": "delivered", "q": "caps"}
```

A 0.5 app accepts it and does nothing. A 0.6 app answers with `{"ids": [], "s": "delivered"}` (no `q`),
whose envelope carries its `caps`. At most one probe per contact per hour. Apps SHOULD probe when the user
opens a contact's chat and the caps are unknown.

### 1.3 Fallback for 0.5 peers

- Never send rooms `group`, `gmsg`, `gphoto`, `greceipt`, `call`, `tvconfig` to a peer without the matching
  token (`g1` for the group rooms, `c1` for `call`, `k1` for `tvconfig`). 0.5 would answer 400 and drop it.
- UI: the call button on a 0.5 contact says "Needs Muninn or Huginn 0.6"; inviting a 0.5 contact to a group
  says the same and sends nothing.
- 1:1 text, photos, voice messages, receipts, unsend and keep work exactly as in 0.5 with everyone.

## 2. Shared rules for 0.6

### 2.1 Canonical JSON

Anything that is signed or hashed is turned into bytes with this canonical form, never with a library's
`toString()` (org.json writes `\/`, Qt reorders nothing but escapes differently):

- Object: `{`, members sorted by key in byte order, `"key":value` joined with `,`, `}`. Keys are ASCII.
- Array: `[`, values in their given order joined with `,`, `]`.
- String: `"`, the text as raw UTF-8 with only `\` written as `\\` and `"` written as `\"`, `"`.
  Text fields MUST NOT contain U+0000..U+001F or U+007F; an object containing one is malformed and refused.
  No Unicode normalization.
- Integer: plain decimal, 0 <= n < 2^53. No negative numbers, no fractions, no exponent.
- `true`, `false`. No `null`.
- No whitespace anywhere.

The reference function is `canon()`; vectors are under `canonical`.

### 2.2 Signed objects

```
signed bytes = UTF-8( domain + "\n" + canon(object without its "sig" member) )
sig          = base64( DER( ECDSA-P256-SHA256(chat private key, signed bytes) ) )
hash(object) = lowercase hex SHA-256( UTF-8( canon(object with "sig") ) )
```

Domains:

| domain | used for |
|---|---|
| `huginn-muninn group v1` | group state (3.3) |
| `huginn-muninn call v1` | call offer and answer (5.2) |
| `huginn-muninn tvconfig v1` | TV config (7.2) |
| `huginn-muninn tvdisconnect v1` | TV disconnect (7.3) |

Signatures may use random k (Java `SHA256withECDSA`, OpenSSL `EVP_DigestSign`); receivers only verify.
The vectors use RFC 6979 so the file is reproducible.

Using the one P-256 key for both ECDH and ECDSA is a deliberate trade: no new key to back up, pair or
verify. The two uses never sign or agree on attacker-chosen raw values, and the domains above keep
signatures apart from each other.

### 2.3 Ids

- Group id, call id: 16 random bytes, base64url without padding (22 chars). Channel id and quick message id:
  8 random bytes, base64url (11 chars). The default channel's id is the literal `main`.
- Logical ids of group messages and group control messages (`id` in the plaintext, also called `gid`):
  16 random bytes as 32 lowercase hex chars. Hex, not base64url, because envelope ids end up in file names:
  Huginn keeps Outbox entries only for ids made of letters, digits and `-` (`fileSafe` in `src/chat.cpp`).
- Group fan-out: one logical message becomes one envelope per member. The envelope `id` for member K is

  ```
  id + "-" + first 8 hex chars of SHA-256(UTF-8(K's chat key text))
  ```

  so the Outbox and Seen (both keyed by envelope id) never collide across members, and retries rebuild the
  same id. Vectors: `group.envelopeIds`.

### 2.4 Ratchet and rate limits

- Add `group`, `gmsg`, `gphoto`, `greceipt`, `call`, `tvconfig` to `RATCHET_ROOMS` (Kotlin) and
  `useRatchet` (Huginn). Receivers keep accepting v1 as well, as today.
- The existing limit (5 envelopes per sender per 10 s, receipts exempt) applies to `gmsg`, `gphoto` pieces
  count like `photo` today. `greceipt` is exempt like `receipt`. `group` and `tvconfig`: 20 per sender per 10 s.
  `call`: 30 per sender per 10 s (a voice room join answers many offers at once).

## 3. Group chats

### 3.1 Model

- No group key. Every group message is sealed separately to each member over that member's pairwise
  session (ratchet). A group of n people costs n-1 envelopes per message; in exchange there is no group key
  to rotate, leak, or resend when someone leaves, and every route and retry rule from 0.5 applies unchanged.
- Max 50 members, 20 channels, 6 people per voice room.
- Group state is a signed snapshot. Every change is a whole new state with a higher version, signed by an
  admin. Members keep the current state and the last 32 accepted states (for `sync`, 3.6).

### 3.2 Group state

```json
{
  "g": "<group id>",
  "v": 3,
  "owner": "<chat key>",
  "by": "<chat key of the admin who signed this version>",
  "name": "Family",
  "admins": ["<chat key>", ...],
  "members": [{"k": "<chat key>", "n": "<name>", "p": "<mailbox hex or empty>"}, ...],
  "channels": [{"id": "main", "n": "general", "t": "text"}, {"id": "<8-byte id>", "n": "Hangout", "t": "voice"}],
  "ts": 1761500120000,
  "sig": "<base64 DER>"
}
```

A state is well-formed only if all of these hold (reference `check_state`):

- exactly these members, no others; `v` >= 1; `ts` is the signer's clock in ms (shown, never trusted).
- `name` 1 to 40 characters (code points), not only spaces.
- `members` sorted by `k` (byte order of the base64 text), unique, 1 to 50 entries. `n` at most 40
  characters. `p` is empty or 64 lowercase hex (Nostr mailbox).
- `admins` sorted, unique, every admin is a member, `owner` is an admin, `by` is an admin.
- `channels`: 1 to 20, unique ids, the first is `{"id":"main","t":"text"}` (its name may change and it can't
  be removed), every other id is 8 bytes; `t` is `text` or `voice`; `n` 1 to 32 characters. Array order is
  display order.
- canonical size at most 24 KB (50 members with long names is about 15 KB).

### 3.3 Accepting a state

Given the current state `cur` and an incoming state `new` (reference `accept_state`):

1. `new` is well-formed and its `sig` verifies with `new.by`.
2. First sight (`cur` is none: you are joining): accept if 1 holds, you are in `new.members`, and `new.by` is
   the admin you accepted the invite from (3.5). Otherwise go on:
3. `new.g == cur.g`.
4. `new.by` is in `cur.admins`. (An admin removed in `cur` can't sign anything you will take.)
5. `new.owner == cur.owner`, unless `new.by == cur.owner` (only the owner hands ownership over).
6. Conflict rule: accept if `new.v > cur.v`, or `new.v == cur.v` and `hash(new) < hash(cur)` (string compare of
   the lowercase hex). Otherwise refuse.

Versions may jump (v3 to v7) as long as rule 4 holds against `cur`. If rule 4 fails only because you are
behind (the signer became an admin in a version you missed), send `sync` (3.6) to the envelope sender and
apply what comes back in version order, each checked against the one before.

Two admins changing v2 at the same moment both produce a v3. Every member ends up with the one with the
lower hash. The admin whose v3 lost sees the winner arrive, and its app SHOULD re-apply its change on top
as v4 (a rename or a channel add replays cleanly; a remove is re-done only if the person is still there).
Vectors: `group.accept`, `group.conflict`.

After accepting, diff old and new members: create a group peer (3.7) for each new key, stop sending to
removed keys, and show "You were removed from Family" if you were removed (the group goes read-only).

### 3.4 Room `group`: control messages

Plaintext is `{"t": <type>, "g": <group id>, "id": <logical id, 2.3>, ...}`. `id` is the logical id (envelope ids
come from 2.3). Admins sign every state; everything else rides on the pairwise envelope's authenticity.

| t | sent by, to | fields | effect |
|---|---|---|---|
| `state` | the admin who signed it, to every member of old and new state; anyone answering `sync` | `op`, `state` | apply 3.3. `op` is a hint for the UI: `create`, `add`, `join`, `leave`, `remove`, `rename`, `addch`, `rmch`, `admin`, `unadmin`, `owner`, `sync` |
| `invite` | an admin, to one contact | `state` (current), `inv` (16-byte token, base64url), `x` (expiry, s) | show "Raven invited you to Family". Nothing changes until they accept |
| `join` | the invitee, to the inviting admin | `inv`, `n` (their name), `p` (their mailbox) | admin checks the token (unexpired, made by this admin, for this group) and sends `state` with `op:"join"` |
| `decline` | the invitee, to the inviting admin | `inv` | admin forgets the token |
| `leave` | a member, to every member | | any admin who gets it signs a state without them (and out of `admins`). The owner can't leave until ownership is handed over (UI enforces) |
| `sync` | a member who is behind, to anyone in the group | `have` (their version) | answer with `states` |
| `states` | answer to `sync` | `list` (accepted states with v > have, oldest first, at most 16) | apply each in order with 3.3 |
| `unsend` | a message's author, to every member who got it | `msg` (the gmsg id) | recipients check the original's author is `fromKey`, blank it like 0.5 unsend, answer `greceipt` `s:"unsent"` |

Creating a group: the creator makes v1 with themself as owner, the only admin and the only member, the
`main` channel, and sends nothing. Adding people is then `invite` per contact (or a group code, 3.5).

Renaming, channels, admins: the admin builds the next state from its current one, `v + 1`, signs, sends
`state` to all members.

Removing someone: the admin signs a state without them and sends `state` to everyone in the old state,
the removed person included, so their app knows.

Every `group` message is retried like a private message until a `greceipt` `delivered` for its `id` comes
back from that member, or a day passes, or the member is no longer in the state.

### 3.5 Invites and group codes (reaching non-contacts)

Two ways in:

1. **A contact**: the admin sends `invite`. The invitee's app trusts it because it came from a contact.
2. **Anyone with the group code**: an admin shows a QR or text code

   ```
   MUNINNG1:<base64url(JSON {"g", "gn": group name, "a": admin chat key, "an": admin name,
                              "p": admin mailbox, "i": token, "x": expiry seconds})>
   ```

   valid for 1 hour, usable by several people until it expires (the admin can revoke it). The joiner sends
   `join` with `inv = i` to the admin. The admin's app accepts `group` envelopes from a non-contact only
   when they are a `join` carrying a live token it made; it then adds them without asking (whoever holds
   the code was invited) and they appear in the member list for any admin to remove.

Either way the joiner accepts its first state only from the admin it joined through (3.3 step 2).

### 3.6 Staying in step

Every `gmsg` carries `sv`, the sender's current state version. A receiver whose version is lower sends
`sync` to that sender; one whose version is higher sends its current state back (`state`, `op:"sync"`).
So a member who was offline catches up with the first message they see.

### 3.7 Group peers

Members need to message each other even if they aren't contacts. A member listed in an accepted state who
isn't a contact becomes a **group peer** on this device: stored with the contacts (chat key, name and
mailbox from the state, local flag `groupOnly`), shown in member lists but not in the contact list, not
verified (their code can be checked like any contact's), and treated as `r1 g1 c1` capable (only 0.6 apps
can join). Their mailbox follows the `mailbox` field of their authentic envelopes, like a contact's.

From a group peer, an app accepts only `group`, `gmsg`, `gphoto`, `greceipt` and `call` with a `g` of a group
they share, plus `receipt` for those. A `private` message from a group peer is handled like one from a
stranger far away: it needs a contact invite or a "Keep in touch" (0.5 rules). Adding a group peer as a
contact just clears `groupOnly`.

A member whose key changes is a new person: their old key stays in the state until an admin removes it, and
the new key has to be invited again.

### 3.8 Room `gmsg`: messages

```json
{"g": "<group id>", "ch": "<channel id>", "id": "<gid>", "sv": 3, "text": "...", "ts": 1761500130000}
```

- `text` at most 2000 characters, like private messages. `ch` must be a text channel.
- Dedupe by `(g, id)` against the stored chat and the deleted-ids list, before and after opening.
- From a key that isn't in the receiver's current state: if `sv` is higher than the receiver's version,
  hold it (at most 50 held per group), send `sync`, and show it once the sender turns out to be a member;
  otherwise drop it.
- Unknown `ch` while `sv` is higher: hold and sync, as above. A channel that was removed: show it in `main`.
- The receiver answers `greceipt` `delivered` to the sender only.

### 3.9 Room `gphoto`: photos and voice messages

Exactly the 0.5 `photo` pieces (16 KB, `i`, `n`, `kind`, `ms` in the clear), plus clear fields `g`, `ch`, `gid`.
The envelope `id` follows 2.3 from `gid`. "Delivered" once all pieces are in, as in 0.5.

### 3.10 Room `greceipt`: receipts

```json
{"g": "<group id>", "ids": ["<logical id>", ...], "s": "delivered" | "read" | "unsent"}
```

- Sent only to the author of those messages, never to the whole group. At most 40 ids per receipt.
- `read` only if the reader has read receipts on (the 0.5 setting).
- The author keeps per message a set of member keys that delivered and a set that read, and shows
  "Delivered to 4 of 6", "Read by 2". Nobody else learns who read what.

## 4. Contact folders

Folders are local only: a name and a list of chat keys stored on the device (and in the encrypted backup).
Nothing about folders ever goes on the wire, and the two apps don't need to agree on anything.

## 5. Calls (1:1)

### 5.1 Overview

- Signaling travels in room `call`, sealed pairwise like any message, on all routes.
- Each call has a fresh P-256 ephemeral key on each side, signed with the chat key. ECDH of the two gives
  the call root; HKDF gives per-direction AES-256-GCM keys that change every 16384 packets.
- Audio is Opus, 20 ms frames. Every packet goes out on every live path at once: UDP on the shared Wi-Fi,
  UDP through the TURN relay over the internet, and Bluetooth LE when connected. The receiver keeps the
  first copy of each sequence number.
- **Relay only over the internet.** Nothing is ever sent straight to a peer's internet address: no STUN,
  no hole punching, no `host` or `srflx` candidates. Each side allocates its own address on the TURN relay
  (Cloudflare, logins from the relay Worker, see `docs/relay.md`) and sends to the other side's relayed
  address through it. So the person you call never learns your IP address, and you never learn theirs.
- If no path works the call fails with "Can't connect".

Calls are offered only to contacts with `c1`, and to group peers inside a voice room (section 6).

### 5.2 Signaling messages (room `call`)

Plaintext `{"t": <type>, "call": <call id>, ...}`.

| t | from | fields | meaning |
|---|---|---|---|
| `offer` | caller | `eph`, `sig`, `ts`, `cands`, [`lp`], `ble`, `codec`, [`g`, `ch`] | start a call. `g`/`ch` only inside a voice room |
| `ring` | callee | | the offer arrived and is ringing (caller shows "Ringing") |
| `answer` | callee | `eph`, `sig`, `cands`, [`lp`], `ble` | accepted |
| `decline` | callee | | said no |
| `busy` | callee | | already in a call |
| `cands` | either | `cands`, [`lp`] | candidates that weren't ready before (the relay came up, Wi-Fi changed), at most one per 2 s |
| `mute` | either | `on` (bool) | muted or unmuted |
| `hangup` | either | `why`: `hangup`, `noanswer`, `nopath`, `dropped`, `error` | the call is over |

- `eph`: the ephemeral public key, base64 SPKI DER, new for every call, private half never stored.
- `sig`: signature (2.2, domain `huginn-muninn call v1`) over exactly `{"t", "call", "eph"}`. Verify it
  with the envelope's `fromKey`; refuse the offer or answer if it fails. It binds the ephemeral key to the
  identity even if a v1-sealed envelope was used. Vectors: `call.offer`, `call.answer`.
- `ts`: caller's clock in ms, a JSON integer. The callee ignores an offer whose `ts` is more than 60 s old or 120 s ahead
  by its own clock, and shows it as a missed call instead (an offer can sit on a relay for hours).
- `codec`: `"opus16"` (5.5). An answer to an unknown codec is `decline`.
- `ble`: true if this device can carry media to the other over Bluetooth LE right now (5.6).
- `cands` and `lp`: below. `cands` is always there in `offer`, `answer` and `cands` (an empty array when
  nothing is ready yet).
- Dedupe by call id: a second `offer` with the same id is a retry; answer it again with `ring` (or the
  same `answer` bytes).

Timing:

- Caller resends the same sealed `offer` every 3 s until `ring`, `answer`, `decline` or `busy`, and gives
  up after 45 s with `hangup` `why:"noanswer"`. The callee rings for up to 45 s from the first offer.
- Callee resends the same `answer` every 2 s until the first authentic media packet, for up to 15 s.
- `hangup`, `decline`, `busy`: sent 3 times over 2 s, no further retries. Every `call` message expires: it
  is never kept in the day-long Outbox.
- A device in a call answers any other `offer` with `busy` (voice room offers excepted, section 6).

Candidates (the final 0.6 format, the same in both apps):

```json
"cands": [{"t": "relay", "a": "104.30.1.2", "p": 61000}, {"t": "lan", "p": 40312}],
"lp": 40312
```

- `relay`: this side's relayed transport address on the TURN server (`a` an IPv4 or IPv6 literal, `p` the
  port). It is the relay's address, never the device's. Sent once the allocation is up.
- `lan`: the port of this side's call socket, with **no address**: the other side already knows this
  device's Wi-Fi address from Chat's discovery (the LocalSend announcement it came from), so no IP of the
  person is ever put in a message. Sent only while Chat reaches the peer over the shared Wi-Fi.
- `lp` (top level): the same port as the `lan` candidate. Both apps send the port both ways (an early
  Muninn sent only `lp`, an early Huginn only the `lan` candidate) and accept either; when both are there
  and differ, `lp` wins.
- At most 8 entries; a message with more is malformed and refused. Ports are integers 1 to 65535.
- Receivers take the first `relay` whose `a` is an IP literal (a peer's candidate never makes an app look a
  name up, so a name is skipped) and ignore every other type, including the `host` and `srflx` of earlier drafts.
- Send the offer with what is ready (an app may wait up to 1.5 s for its relay), and anything later in
  `cands`. Resent offers and answers carry the newest candidates, and the caller takes the candidates of
  a resent answer too (same `eph` as the first), not only of `cands`. A `cands` that would come sooner than
  2 s after the last one waits, it is not dropped. The callee sends no `cands` before it answers: the
  answer carries the relay when it is ready.

### 5.3 Key derivation

```
ikm     = ECDH(my eph private, their eph public)          32-byte x coordinate
salt    = SHA-256(callId raw 16 bytes || offer eph DER || answer eph DER)
root    = HKDF-SHA256(salt, ikm, info "huginn-muninn call v1", 32)
chain_0 = root
chain_e = HMAC-SHA256(chain_{e-1}, 0x02)
km_e    = HKDF-SHA256(salt = 32 zero bytes, ikm = chain_e, info "huginn-muninn call keys", 72)
          o2a key = km_e[0:32], o2a salt = km_e[32:36], a2o key = km_e[36:68], a2o salt = km_e[68:72]
epoch(seq) = seq >> 14
tag     = SHA-256("huginn-muninn call tag" || callId raw 16 bytes)[0:2]
```

`o2a` is offerer to answerer, `a2o` the other way. Separate keys per direction are required: both sides
count `seq` from 0, so one shared key would reuse GCM nonces.

Rekeying is automatic: every 16384 packets (about 5.5 minutes at 50 packets a second, longer with silence)
a direction moves to the next epoch. Once a receiver's window (5.4) has moved fully past an epoch in both
directions, it deletes that epoch's keys and every chain value before the oldest epoch still needed, so a
device captured mid-call can't decrypt earlier minutes of it. Vectors: `call.root`, `call.epochs`.

### 5.4 Media packets

```
offset size
0      1    version, 0x81
1      1    type: 0 audio, 1 ping, 2 pong
2      2    tag (5.3)
4      4    seq, uint32 big-endian, per direction, from 0, +1 for every packet of any type
8      4    ts, uint32 big-endian, ms since this side's first packet
12     n+16 AES-256-GCM(key, nonce, plaintext, aad = bytes 0..11), 16-byte tag at the end
```

- `nonce = salt (4) || 00 00 00 00 || seq (4, big-endian)`, with key and salt of `epoch(seq)` for the
  sender's direction.
- Plaintext: audio is one Opus packet; ping is 8 random bytes; pong is the 8 bytes of the ping it answers.
- Size 28 to 1200 bytes. The first byte 0x81 tells media apart from STUN and TURN messages (first byte
  0x00 or 0x01) and ChannelData (0x40 to 0x7F) on a UDP socket, and from a JSON envelope (`{`, 0x7B) on
  Bluetooth.
- A sender never reuses a seq. A call ends before seq would wrap (2^32 packets is over two years).

Receiving (reference `Receiver.open`), in this order:

1. Size, version, type known, tag matches a live call (and the source fits, 5.6).
2. Replay window, 1024 packets: let H be the highest seq accepted so far. Drop if `seq + 1024 <= H`
   (too old) or if seq is within the window and already marked (duplicate).
3. Drop if `epoch(seq) > epoch(H) + 2` (too far ahead; stops a flood from making the receiver derive
   thousands of keys).
4. Decrypt. Drop if it fails.
5. Only now mark seq in the window (and move H if seq > H).

The same check is the dedupe across paths: a copy of a packet that already arrived on another path is a
duplicate. Vectors: `call.frames`, `call.receive` (order matters; one receiver per side for the whole list).

### 5.5 Audio

Opus, application VOIP, mono, **16 kHz (wideband), 16 kbps VBR, 20 ms frames**, in-band FEC on (expected
loss 10%), DTX on, complexity 5. Decoders may run at 16 or 48 kHz.

Why 16 kHz and 16 kbps over 48 kHz and 24 kbps: each packet is sent on up to 3 paths, and to up to 5 peers
in a voice room. At 16 kbps a packet is about 68 bytes (12 header, ~40 Opus, 16 tag), so one Bluetooth LE
write per 20 ms carries it, and a full voice room is about 190 kbps up per UDP path instead of about 250.
Wideband is what phone calls with "HD voice" use; speech gains little above 8 kHz of audio bandwidth.

Jitter buffer: play in seq order, target delay adaptive between 40 and 200 ms (start at 60 ms). A missing
frame is rebuilt from the next packet's FEC when it is there, otherwise Opus packet loss concealment.
With DTX, silence sends a packet about every 400 ms; `ts` shows the gap.

Speaking indicator: from the decoded audio, never sent. Speaking while the 20 ms level is above -45 dBFS
for 2 frames in a row, held for 300 ms after it drops.

Muted: the muted side sends no audio packets (pings go on) and sends `mute` `on:true`.

### 5.6 Paths

One UDP socket for the Wi-Fi path per call, bound to a random port on all interfaces, open only while a
call is active. Incoming UDP: first byte 0x81 is media; anything else is dropped. The relay has its own
socket or TLS connection (below).

**Relay (internet).** On starting a call, and on an offer arriving (the callee allocates while it rings,
so answering doesn't wait for the relay), get a TURN
login from the relay Worker over HTTPS (`docs/relay.md`) and allocate on the TURN server (RFC 8656:
Allocate with the long-term credential, Refresh before it lapses, a permission or ChannelBind for the
peer's relayed address, refreshed every 4 minutes). Try UDP first, then TURN over TLS on port 443, then
any other `turns:` URL. The TURN server's host name is looked up with the system resolver,
retried a few times; if it can't be looked up, there is no relay for this call ("Can't reach the call relay
right now"). Media to the peer goes to the peer's `relay`
candidate through this side's own allocation. Media from it arrives as relayed data; the relay only
lets in addresses this side gave a permission, and every packet must pass the checks of 5.4 (tag, replay
window, AEAD) before it counts, so the relayed source address is **not** required to match the peer's
`relay` candidate. A relay with several sites may translate addresses between them (Cloudflare anchors
each allocation at the nearest data center, so a phone on a carrier and a PC on home Wi-Fi can land on
different ones): when an authentic packet arrives relayed from an address other than the peer's `relay`
candidate, that address becomes a second target (permission and ChannelBind for it too) and media goes
to both from then on; the receiver drops the copies (5.4). Both sides need an allocation for the relay
path to work. The allocation is released when the call ends. A TURN error answer (for example 438, stale
nonce) is used even without MESSAGE-INTEGRITY (many servers send it without); only success answers must
carry a valid one. Cloudflare hands out IPv4 relayed addresses only (it ignores REQUESTED-ADDRESS-FAMILY),
so both sides always get the same family, whatever network the device is on.

**Wi-Fi.** When Chat reaches the peer on the shared Wi-Fi, send to the peer's Wi-Fi address (from Chat's
discovery) at the port of its `lan` candidate or `lp`. Packets on this path are accepted only from that
address.

**Live paths.** A path becomes live when an authentic packet arrives on it. Every ping is answered with a
pong on the path it came on. A path is dead after 4 s with no authentic packet on it. Pings: every 50 ms
for 2 s, then every 250 ms, until the path is live or 15 s pass; once live every 1 s (which also keeps the
relay's channel and the Wi-Fi mapping fresh); dead paths every 2 s for revival.

**Bluetooth LE.** When both sent `ble:true` and the two are connected over Bluetooth LE, media packets go
over a new characteristic `MEDIA` = `7b1e0005-6d75-6e69-6e6e-48756767696e` on Muninn's service:
Huginn (central) writes without response, Muninn (peripheral) notifies. One packet per write, no BleFrames
header, so the negotiated MTU must be at least 3 + packet size (185 or more; with a smaller MTU, Bluetooth
is not a media path). Media never waits in the message queue: a packet that can't be written within 40 ms
is dropped. Two Muninns close by may also use their Nearby Connections link (BYTES payloads) the same way.
These paths are live by the same rule (authentic packet within 4 s). Optional: an app without it sends
`ble:false` and uses the relay and Wi-Fi only.

**Sending.** Every audio packet goes out on every live path (relay, Wi-Fi, Bluetooth).

**No path.** If no path is live 15 s after the answer was sent or received, both sides show "Can't connect"
and send `hangup` `why:"nopath"`. During a call, if every path is dead, show "Reconnecting"; after 20 s,
end with `hangup` `why:"dropped"` and show "Call dropped".

### 5.7 IP addresses (shown to the user before the first internet call)

- The person you call never learns your IP address, and you never learn theirs: offers and answers carry
  only the relay's address and, on the same Wi-Fi, a port.
- The TURN relay (Cloudflare) sees your public address, because the audio goes from your device to it
  directly. It can't hear the call: media is encrypted end to end (5.3, 5.4).
- The relay Worker sees your public address (an HTTPS request), a hash of your chat key and the time.
  Your DNS server sees the TURN server's name being looked up.
- Nostr relays see nothing new: signaling is sealed like any message.
- Calls over Bluetooth or the same Wi-Fi use local addresses that Chat's discovery already shares there.
  A setting "Internet calls" (default on) can be turned off: then no relay login is fetched, no `relay`
  candidate is sent, and calls work only over Wi-Fi and Bluetooth.

## 6. Voice rooms (group calls)

- A voice room is a `voice` channel of a group. Up to 6 people at once, full mesh: every pair runs its own
  1:1 call (section 5) with its own call id, keys and paths.
- Join: send `{"t":"vjoin","g","ch","call":"-"}` in room `call` to every group member. (`call` is the literal
  `-` for room-wide messages, which carry `g` and `ch` and no `eph`.)
- Everyone already in that voice room answers the joiner with an `offer` carrying `g` and `ch`. The joiner
  auto-answers offers for the room it just joined (no ringing, no `busy`). So the joiner finds out who is
  there from the offers it gets, and nobody keeps a list that can go stale.
- Full: someone in the room who already has 5 peers there answers `{"t":"full","g","ch","call":"-"}` instead
  of an offer. A joiner that gets `full` leaves and shows "Voice room is full".
- Both offering at once (two people joined at the same moment): the side whose chat key text is lower in
  byte order keeps its offer; the other drops its own and answers.
- Leave: `{"t":"vleave","g","ch","call":"-"}` to every member, then `hangup` to each peer.
- For people not in the room: show who's in it from `vjoin`/`vleave`. Those in a room re-send `vjoin` every
  30 minutes; an entry with nothing for 3 hours is dropped.
- Mute: `mute` to each peer in the room. Speaking indicator per peer from that peer's audio (5.5).

## 7. Kids' TV

The TV is a Muninn install set to Kids' TV mode on first start (for example on an Android TV box). It has a
chat key like any device and caps `r1 k1`.

### 7.1 Pairing

1. The TV shows a QR contact code: the 0.5 `MUNINN1:` code with an invite token, plus `"tv":1`.
2. The parent scans it. The parent's app adds the TV as a contact with the local flag `managed` and sends
   the first config (7.2, `v:1`, `parents:[parent]`) in room `tvconfig`.
3. While the TV has no parents it accepts a config only from an envelope carrying a live invite token it
   showed, with `by == fromKey` and `by` in `parents`. That config's `parents` become the TV's parent keys
   and pairing mode ends.
4. From then on the TV accepts configs only from its parent keys (7.2), and takes no new contacts any other
   way.

A second parent is added by a config from an existing parent that lists both.

### 7.2 Room `tvconfig`: config

Plaintext `{"t":"config","cfg":{...}}`, where:

```json
{
  "tv": "<TV chat key>",
  "v": 2,
  "by": "<parent chat key that signed>",
  "parents": ["<chat key>", ...],
  "quick": [{"id": "<8-byte id>", "text": "I'm hungry"}, ...],
  "contacts": [{"k": "<chat key>", "n": "Grandma", "p": "<mailbox hex or empty>"}, ...],
  "dictation": false,
  "ts": 1761500100000,
  "sig": "<base64 DER>"
}
```

Well-formed (reference `check_tvconfig`): exactly these fields; `parents` sorted, unique, not empty, `by` in
it; `quick` at most 24, unique ids, each text 1 to 80 characters, array order is the order on screen;
`contacts` sorted by `k`, unique, at most 50, names at most 40; canonical size at most 16 KB.

The TV accepts `new` if: well-formed, `new.tv` is its own key, `new.by` is one of its current parent keys,
the signature (domain `huginn-muninn tvconfig v1`) verifies, and either it has no config yet, `new.v > cur.v`,
or `new.v == cur.v` with `hash(new) < hash(cur)` (two parents at once). Vectors: `tv.accept`.

Applying it: parent keys become `parents`; the TV's contacts become exactly `contacts` (people removed are
dropped with their chats); the screen shows the quick messages in order; the dictate button shows only when
`dictation` is true (speech to text on the TV, sent as a normal private message). The TV answers every parent
with `{"t":"applied","v":<v>}` so their apps can show "TV is up to date".

On the TV: private messages are accepted only from keys in `contacts` (and parents), and sent only to them.
No Nearby room, no Keep in touch, no groups, no calls in 0.6. A listed person still has to have the TV as a
contact on their side; the parent's app shares the TV's code with them.

### 7.3 Disconnect

Plaintext `{"t":"disconnect","d":{"tv":"<TV key>","v":<TV's current config version>,"by":"<parent key>","ts":...,"sig":...}}`,
signed with domain `huginn-muninn tvdisconnect v1`. The TV accepts it from any current parent whose
signature verifies and whose `v` equals its current config version (an old disconnect can't be replayed
after a new config). It then erases its chats, contacts, config and parent keys and goes back to the pairing
screen. To drop just one of two parents, the other sends a config without them.

### 7.4 No lock screen

The TV has no app lock, panic lock or hidden erase: anyone in the room can read what's on it. Parents'
apps say so when pairing. Nothing secret is in the config, and messages on the TV follow the "Delete
messages after" setting the parent sets on the TV during setup.

## 8. Message types

| room | t | plaintext | ratchet | retried | dedupe | needs |
|---|---|---|---|---|---|---|
| `private` | | text (0.5) | yes | a day, until delivered | (from, id) vs chat | |
| `receipt` | | `{ids, s, [q]}` (0.5, 1.2) | yes | no | Seen | |
| `unsend`, `keep`, `photo` | | 0.5 | yes | 0.5 | 0.5 | |
| `nearby` | | text (0.5) | never | no | id | |
| `group` | `state` `invite` `join` `decline` `leave` `sync` `states` `unsend` | 3.4 | yes | a day, until greceipt | Seen (from, envelope id) | g1 |
| `gmsg` | | 3.8 | yes | a day, until greceipt | (g, id) vs chat | g1 |
| `gphoto` | | 3.9 pieces | yes | as photo | (from, gid, i) | g1 |
| `greceipt` | | 3.10 | yes | no | Seen | g1 |
| `call` | `offer` `ring` `answer` `decline` `busy` `cands` `mute` `hangup` | 5.2 | yes | short, 5.2 | call id + t; Seen | c1 |
| `call` | `vjoin` `vleave` `full` | 6 | yes | no | Seen | c1 |
| `tvconfig` | `config` `applied` `disconnect` | 7 | yes | config and disconnect: a day, until `applied` | Seen; then version rule | k1 |

## 9. Size limits

| what | limit |
|---|---|
| envelope on any route | 64 KB (the Bluetooth limit; stays under Nostr relays' usual 64 to 128 KB after wrapping) |
| text in `private` / `gmsg` | 2000 characters |
| photo piece | 16 KB, as 0.5 |
| group members | 50 |
| channels per group | 20 (including `main`) |
| people per voice room | 6 |
| group name, member name, contact name | 40 characters |
| channel name | 32 characters |
| group state, canonical | 24 KB |
| held messages from unknown senders | 50 per group |
| state history kept | 32 states; `states` answer at most 16 |
| ids per receipt | 40 |
| candidates | 8 |
| media packet | 28 to 1200 bytes (about 68 typical) |
| quick messages | 24, each 80 characters |
| TV contacts | 50 |
| TV config, canonical | 16 KB |
| group code / invite token | 1 hour |

Characters means Unicode code points. Anything over a limit is malformed and refused, not cut.

## 10. Test vectors

`python3 tools/proto06_ref.py` writes `tests/proto06-vectors.json` and checks it twice (while building it,
then again from the file). Keys A and B are those of `tests/chat-vectors.json`; C is the P-256 key with
private value `0x33` repeated 32 times, the TV `0x44` repeated 32 times; call ephemeral keys are `0x3001`
(offerer, A) and `0x4001` (answerer, B).

Each app's unit tests should:

- `canonical.good`: `canon(value)` equals `canonical`; `canonical.refuse`: parsing then canonicalizing fails.
- `group.states`: `signed` equals the bytes it builds, `hash` matches, every signature verifies.
- `group.accept` and `tv.accept`: same accept or refuse, in order.
- `group.envelopeIds`: same ids.
- `call`: both signatures verify; ECDH, root, tag and every epoch's chain, keys and salts match; sealing each
  `frames` entry gives `packet` exactly; running `receive` in order through one receiver per side gives each
  `result`.
- `tv.disconnect`: accepted at version 3, refused at 4.

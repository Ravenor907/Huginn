# Huginn and Muninn: complete handoff

This file is everything a new assistant (or a new developer) needs to carry on with Huginn and Muninn exactly where
the last session stopped: what the project is, how it is built, the owner's rules, what has been done, what is left,
and how to work so nothing breaks.

**How to use it:** start a new chat or Claude Code session, attach or paste this whole file, and say
"Read HANDOFF.md first. Follow the owner's rules in it exactly. Then tell me where we are."
Part 16 at the bottom is a short starter prompt to paste first.

Last updated: 30 October 2026 (0.95 release day).

---

## 1. The project in plain words

**Huginn and Muninn** is a private messenger for family and friends. It is end-to-end encrypted, with no accounts, no
phone numbers, no ads and no tracking. Two apps speak the same protocol:

- **Muninn** (Android): Kotlin and Jetpack Compose, for phones, tablets, Google TV and a locked-down **Kids' TV**
  mode that a parent manages from their phone. Package `io.github.ravenor907.muninn`, minSdk 26, targetSdk 36.
- **Huginn** (desktop): C++ with Qt 6.10 and QML, for **Windows and Linux**. It ships as a Flatpak and a portable
  Windows zip, and also works with a game controller or TV remote.
  - Always say "Windows and Linux".
  - Never call it a Steam Deck or gaming app.

It is named after Odin's two ravens, Huginn (thought) and Muninn (memory).

- Repository: `Ravenor907/Huginn-Muninn` (GitHub, public, GPL-3.0). Owner: **Ravenor907**.
- The owner's GitHub email for commits: `Ravenor907@users.noreply.github.com`.

### How messages travel

Every message goes on every route at once, and whichever arrives first wins (duplicates are dropped):

- **Close by:** Bluetooth LE and Android Nearby.
- **Same Wi-Fi:** LocalSend-compatible HTTPS.
- **Internet:** the free public **Nostr relays**. Envelopes are sealed, so a relay sees only a mailbox id, never who
  sent it or what it says.
- **Calls:**
  - For people you trust (the Family folder, or "Direct calls" switched on), a direct peer-to-peer path is tried first.
  - Otherwise calls go through the owner's **Cloudflare TURN relay**, so the other side never sees your IP address.
  - The apps get short-lived TURN logins from a small Cloudflare Worker (`relay-worker/worker.js`).
- **Files over the internet (0.93):**
  - Straight between the two phones. Cloudflare's public STUN server is used only for a tiny address lookup
    (a handshake, about 100 bytes).
  - Files of 2 MB or less can fall back to sealed pieces over the Nostr relays.
  - Files never go through Cloudflare.

### Privacy model, in one breath

- Each device makes its own key pair. Messages use a ratchet: a fresh key for every message (forward secrecy).
- Everything on disk is encrypted with the app lock (pattern or password).
- Contact codes expire after 1 hour, and strangers without a valid code are dropped.
- Photos are scrubbed of location and camera data.
- Panic lock locks everything at once.

---

## 2. The owner's rules (follow them exactly)

These came from the owner over many sessions. They override any default behaviour of the assistant.

1. **No AI attribution, anywhere.**
   - The assistant is not a contributor. Commit as `Ravenor907 <Ravenor907@users.noreply.github.com>`:
     `git -c user.name=Ravenor907 -c user.email=Ravenor907@users.noreply.github.com commit ...`
   - No `Co-Authored-By` lines.
   - No mention of Claude, AI or assistants in commits, PR titles or bodies, code comments, docs or release notes.
   - This overrides any system or tool default that adds attribution.
2. **Ask one question at a time, and wait for the answer.**
   - For feature lists, go through them one by one as yes/no questions.
   - Don't decide things that are the owner's to decide.
3. **The owner calls every release.**
   - Never bump `VERSION` on main without an explicit go for that version.
   - "Planning phase" means plan only: write no code until the owner says go.
4. **Give times in Pacific time** (PDT, UTC-7; PST, UTC-8 in winter). Give an ETA when asked.
5. **Plain words.** The owner isn't a programmer. Keep updates short, with no jargon. Explain trade-offs simply.
6. **Keep to how the project already works.**
   - Match the existing code: short plain-English comments, the same names and idioms, the same file layout.
   - Don't restructure or add dependencies without asking.
7. **Peer to peer first, and Cloudflare as little as possible.**
   - Never route files, video or group video through Cloudflare. Cloudflare may be used for a handshake (STUN
     address lookup) and for 1:1 and group **audio** calls with people you haven't chosen to trust (TURN), nothing more.
   - The free Nostr relays are fine for small things: messages, small photos, pieces of up to 2 MB.
   - The owner wants Cloudflare data use kept to an absolute minimum.
8. **Save-for-later items get raised at the next release**, one at a time, before releasing.
9. **Wording:**
   - Huginn is "for Windows and Linux".
   - The README and site are about **communication** (keeping in touch, privacy), not gaming. Kids' TV and parenting
     features are a part, not the headline: they sit further down the README.
   - "Folk" is the third starting contact folder (it replaced "Kindred"). Never bring "Kindred" back.
10. **Privacy-first choices.** The owner's stance: no Google push (FCM) and no new third parties. If unsure, recommend
    the more private option and explain the cost.

11. **No external requirements.** No new outside accounts or services the owner must sign up to (he declined
    SignPath, Microsoft malware reports, winget and the Microsoft Store). GitHub and the existing Cloudflare relay are
    the only services.


12. **Never lean on outside programs (the owner, 30 Oct 2026, "really need you to remember this").** This is a
    privacy- and security-focused communication program. Leaning on any outside program or service could break its
    functionality, undermine its security and remove the anonymity the project is built on. So: nothing outside the
    app guards or holds the user's data or identity (no Windows DPAPI, no Android Keystore binding for locked
    vaults, no OS account stores, no cloud or third-party services). Anything optional must fail safe and keep the
    app fully working without it. Ask the owner before adding any such dependency, however small. (0.95 had DPAPI and
    a Keystore wrap added during the security fixes; both were removed for this reason.)

---

## 3. Where everything is

```
VERSION                     one line, e.g. 0.95. Changing it on main makes a release (see part 7)
README.md                   front page: keeping in touch, privacy, screens, battery, Kids' TV, What's new, Install
docs/CHANGELOG.md           every release's notes, newest first (0.95 back to 0.6)
docs/controller.md          Huginn with a game controller or TV remote
docs/protocol-0.6.md        THE wire protocol for both apps (sections 0 to 15)
docs/relay.md               how the apps use the relay Worker, STUN and TURN, and data per call
docs/releases.md            how releases are signed (the project's own Ed25519 key) and how to check one
docs/release-key.pem        the release public key both apps carry
SECURITY.md                 how to report a security problem privately
docs/panels/01..12.png      4K marketing panels used by the README (no 04: the handheld panel was removed in 0.95)
docs/banner.png             README banner, drawn by tools/make-banner.py in CI
docs/HANDOFF.md             this file
tools/*_ref.py              Python reference implementations that write the test vectors
tools/get-fonts.sh          downloads Cinzel, Barlow, Noto Sans Runic (needed by both builds)
tools/check-windows-binary.py  checks Huginn.exe has nothing that looks like malware
tests/*.json                test vectors (chat, ratchet, nostr, proto06, m1, m2)
relay-worker/               Cloudflare Worker: worker.js, test.mjs (node test.mjs), README.md (setup steps)
site/add/index.html         the "add me" page a QR code opens in a normal camera (GitHub Pages)
packaging/                  Flatpak manifest io.github.ravenor907.Huginn.json
CMakeLists.txt, cmake/      Huginn build
src/                        Huginn C++ (see below)
qml/                        Huginn UI (Main.qml, ChatPage.qml, SettingsPage.qml, Rail.qml, ...; assets/valknut.png)
android/                    Muninn (Gradle). Code under app/src/main/java/io/github/ravenor907/muninn/
.github/workflows/build.yml the only CI workflow
```

### Huginn (`src/`)

- `chat.*`: chats and contacts.
- `chatwire.*`: envelopes. Caps live in `ChatWire::kCaps = "r1 g1 c1 c2 t1 v1 m1"`.
- `chatcrypto.*`, `ratchet.*`, `schnorr.*`: crypto.
- `chatgroups.cpp`, `groupwire.*`: groups.
- `richwire.*`: m1 replies, reactions and edits.
- `nostr.*`: relays.
- `ble.*`, `bleframes.*`, `lan.*`, `localsend.*`, `direct.*`, `transfer.*`: close-by and Wi-Fi routes.
- `photos.*`, `voice.*`: media.
- `vault.*`: encrypted storage.
- `settings.*`, `system.*`, `updater.*`: settings, system integration and the self-updater.
- `gamepad.*`: the controller.
- `call/`: calls.
- `platform/`: platform-specific code.

Since 0.94 Huginn implements `m2`, `i1`, `f1`, `d1` and linked devices (as the phone and as the companion), matching
Muninn. Huginn caps: `r1 g1 c1 c2 t1 v1 m1 m2 d1 i1 f1`.

### Muninn (`android/app/src/main/java/io/github/ravenor907/muninn/`)

- `Muninn.kt` (the Application) and `MainActivity.kt` (the Compose root, navigation, the Settings valknut button).

**`net/`**
- `Chat.kt`: the heart of messaging. Send, receive, `handle()` per room, receipts, retries, groups glue, link,
  files, intros.
- `ChatWire.kt`: envelopes. `SEEN_ROOMS` includes `intro`, `file` and `link`.
- `Proto06.kt`: `CAPS = "r1 g1 c1 c2 k1 k2 k3 k4 t1 v1 m1 m2 i1 f1"`.
- `ChatCrypto.kt`: includes `EMOJI` and `emojiCode` for the 4-emoji check.
- `Ratchet.kt`, `Schnorr.kt`, `Tls.kt`, `Vault.kt`: crypto and storage.
- `Contacts.kt`: contacts, folders (DEFAULTS with Folk, plus the Kindred migration), `ChatFlags` (mute, archive,
  unread, colors), one-time invite codes.
- `Groups.kt`: group chats (m1 and m2 fields).
- `Rich.kt` (m1) and `More.kt` (m2 checks: polls, albums, video, previews, file pieces).
- `Trust.kt`: i1 introductions and family offers.
- `Tap.kt`: NFC tap to add; a HostApduService that answers only while the Tap screen is open.
- `FileLink.kt`: f1 files straight between phones (UDP hole punching, sealed windows, STUN at stun.cloudflare.com).
- `Link.kt`: linked devices, companion style.
- `LinkPreview.kt`, `Videos.kt`, `Photos.kt` (16 KB pieces; at most 130 pieces, about 2 MB, over the relays),
  `Voice.kt`.
- `Nostr.kt`, `Net.kt`, `Ble.kt`, `BleFrames.kt`, `Close.kt`, `CloseFiles.kt`, `NearbyService.kt`, `LocalSend.kt`,
  `Direct.kt`: routes.
- `Power.kt` and `PowerLogic.kt`: the battery logic (pure, tested; see part 11).
- `MessageService.kt` (foreground service) and `InboxWorker.kt` (WorkManager inbox check).
- `Identity.kt`: preferences, including `linkPreviews`.
- `Backup.kt`, `BackupFormat.kt`, `AppFiles.kt`, `Received.kt`, `ReplyReceiver.kt`.
- `net/call/`: calls.
  - `CallManager`, `CallSession`, `CallMedia` (paths: WIFI, DIRECT, RELAY; `videoNoRelay`), `CallLogic`.
  - `TurnClient`, `TurnCreds`, `RelayConfig` (the Worker URL and APP_SECRET), `Stun`.
  - `Opus`, `Jitter`, `AudioRoute`, `CallVideo`, `VideoWire`.
  - Rooms: `RoomManager`, `RoomLogic`, `RoomService`, `RoomVideo.kt` (0.93 group video), `GroupRing`.

**`screens/`**
- `ChatScreen.kt`: chat list, conversations, `ChatNav.open` routes ("code", "add", "newgroup", "nearby", "trash",
  "requests", "tap", "mirror", "mirror/<peer>", "g:<id>", contact keys). Also Requests and the mirror screens.
- `ChatMore.kt`: attach menu, polls, preview cards, video player, album grid, Shared page, Tap screen, file cards.
- `CameraScreen.kt`: CameraX photo and video, flip, Send/Discard.
- `SettingsScreen.kt`: folders notify, privacy, lock, calls, nearby, display, link, data, about.
- `RoomScreen.kt`, `CallScreen.kt`, `TransferScreen.kt` (with `Qr()`), `ContactsScreen.kt`, `LockScreen.kt`,
  `SetupCard.kt`, `TrashScreen.kt`, `BackupPanel.kt`.

**Other folders**
- `tv/`: Google TV and Kids' TV (`KidMode`, `TvRoot`, `TvConfig`, `TvParent`, `TvUpdate` (hourly check, only while
  charging), `TvApps`, ...).
- `ui/`: `Theme.kt` (`Palette`, `Type`), `Kit.kt` (`MButton`, `Kind`, `Field`, `Panel`, `Section`, `IconAction`,
  `Ui.say`, `Ui.sure`), `Icon.kt` (`MIcon`; icon names are listed in `Icons.paths`), `AppLock`, `Pattern`, `Panic*`,
  `ShareApp`.
- `update/`: `Updater.kt` (the download waits for Wi-Fi) and `ApkInstall.kt`.
- Tests: `android/app/src/test/java/io/github/ravenor907/muninn/*Test.kt` (32 test files), with vectors in
  `android/app/src/test/resources/`.
- Resources: `res/drawable-nodpi/settings_valknut.png`, `res/xml/tap_card.xml`, manifest (location only on
  `maxSdkVersion=32`, `neverForLocation`; NFC; TapCard service).

---

## 4. The protocol and caps

- `docs/protocol-0.6.md` is the source of truth. Where the text and a `tools/*_ref.py` disagree, **the reference
  wins**.
- **Caps:** every envelope carries `caps`, the space-separated feature tokens this app understands. Never send a peer
  something its caps don't list. Send a fallback (plain text) or nothing instead.

| Token | Meaning | Section |
|---|---|---|
| r1 | ratchet (fresh key per message) | 2.4 |
| g1 | groups | 3 |
| c1, c2 | calls, call extras (direct path, video...) | 5, 5.8 |
| k1 to k4 | Kids' TV config, calls, mic level and storage, apps | 7 |
| t1 | inviting someone to talk with the kids | 7.6 |
| v1 | voice rooms and group calls | 6 |
| m1 | replies, reactions, edits, typing, active status | 11 |
| m2 | albums, video messages, polls (answers key `"a"`), link previews | 12 |
| i1 | room `intro`: introductions, offers from a group, emoji check, one-time invites | 13 |
| f1 | files over the internet (room `file`; piece kind `"file"` with `"fn"`) | 14 |
| (room `link`) | linked devices, companion style; Muninn only, no cap token | 15 |

**Rules that bit before:**
- **No onion fields:** `NetTest` fails the build if any source has `put("o",` or `put("onion",` (Tor was removed in
  0.7). That's why poll answers use `"a"`.
- **Rate limits in `Chat.handle()`:**
  - Normal rooms: 5 messages per 10 s per sender.
  - `groupRate`: intro 20 per 10 s; call and file 30 per 10 s.
  - Room `link` is exempt.
- **Truncation:** text is cut to 2000 characters except for receipt, mx, live, link, intro and file.
- **Changing a reference:** after changing a reference script, run `python3 tools/<name>_ref.py`. It writes
  `tests/<name>-vectors.json` and the Android test copy. Commit both.

---

## 5. Building and testing

**There's no local Android build in the cloud container:** dl.google.com is blocked, so the Android SDK can't be
downloaded. **Verify everything through GitHub Actions.** Push to a branch with an open PR into main and read the
results.

`.github/workflows/build.yml` jobs, about 15 to 20 minutes per run, running in parallel:

| Job | What it proves |
|---|---|
| `flatpak` (Flatpak) | Huginn builds for Linux, a screenshot tour of every screen, self-tests against every test vector, banner. Pushes screenshots to branch `ci-tour` on push events. |
| `muninn` (Muninn (Android)) | Gradle unit tests (all `*Test.kt`) and signed release APK (`Muninn.apk`) |
| `muninn-screens` (Muninn on an emulator) | installs the debug APK on an emulator and screenshots it (`ci-muninn` branch) |
| `windows` (Windows (portable zip)) | MSVC build, windeployqt, malware-look and hardening (CFG/CET) check, Windows Defender scan, self-tests |
| `wine` (Windows zip under Wine (Proton)) | the Windows zip runs under Wine |
| `relay` (Relay Worker tests) | `node relay-worker/test.mjs` |
| `site` (Add page) | publishes `site/` to GitHub Pages (main only) |
| `release` (Release) | only on push to main when `VERSION` changed and every job above passed |

**Checks you can run in the container before pushing:**
- `python3 tools/*_ref.py` (vectors).
- `node relay-worker/test.mjs`.
- `grep` for `put("o",` / `put("onion",`.
- Careful reading of Kotlin. Check that every helper you call really exists with that signature: `MButton(text,
  modifier, kind, enabled, icon, onClick)`, `Field(value, onChange, hint, modifier, single, ...)`,
  `Type.small/normal/big`, `Palette.*`, `IconAction(icon, label, tint, modifier, size, onClick)`.

**Reading CI without the `gh` CLI:** the repo is public, so plain `curl` works:
- `https://api.github.com/repos/Ravenor907/Huginn-Muninn/actions/runs?branch=<branch>&per_page=5`
- then `<run>/jobs`
- and `https://api.github.com/repos/Ravenor907/Huginn-Muninn/check-runs/<job id>/annotations` for error messages.

In a Claude Code cloud session the GitHub MCP tools (`mcp__github__*`) also read logs, mark PRs ready and merge.

**Known flaky things (not the app's fault):**
- **Windows Defender scan step:** it once failed in 3 seconds with no threat named. The next run with the same code
  passed. Re-run once if that happens.
- **`ci-tour` push fails with "Internal Server Error" (GitHub 500), or races when two pushes land close together:**
  this fails the Flatpak job and therefore blocks the release. Fix: when the whole run has finished, **re-run failed
  jobs** once (GitHub UI: Actions › the run › "Re-run failed jobs", or the API). This happened on the 0.93 release
  run.

---

## 6. Signing and secrets (GitHub repo settings, never in code)

- `MUNINN_KEYSTORE_BASE64`, `MUNINN_KEYSTORE_PASSWORD`: Muninn's private release key (since 0.9).
  - The APK's certificate must match `android/signing/release-cert-sha256.txt` (CI checks this).
  - Losing this key means every phone has to reinstall. It has never been committed.
- `RELEASE_SIGNING_KEY` (added 30 Oct 2026): the project's Ed25519 release private key (PEM). The Release job signs
  each download with it (`<file>.sig`, over the raw bytes) and fails without it or if it doesn't match
  `docs/release-key.pem`. Both apps refuse an update whose `.sig` doesn't verify. The owner keeps an offline copy;
  losing it means shipping a new public key in an update signed with the old one. Builds also get GitHub build
  attestations (actions/attest-build-provenance). No SignPath or any other outside signing service.
- Relay Worker secrets live in Cloudflare, not GitHub: `TURN_KEY_ID`, `TURN_KEY_API_TOKEN`, `APP_SECRET`, and the KV
  binding `LIMITS`. The apps carry the Worker URL (`https://huginn-relay.ravenor907.workers.dev`) and `APP_SECRET`
  in `RelayConfig.kt` and `src/call/relayconfig.h`. `APP_SECRET` is not truly secret; the Worker's daily caps are
  the protection.

---

## 7. How to release (only when the owner says go)

1. Get every change onto a branch with an open PR into main, and wait until **every** CI job on its newest commit is
   green.
2. Raise any save-for-later items (part 12) with the owner, one at a time.
3. On the branch, make sure the README has "What's new in 0.9x" at the right place, `docs/CHANGELOG.md` has the same
   notes at the top, and `VERSION` holds the new number (one dot, read as a decimal: 0.93, 0.94, ... then 1.0).
4. Mark the PR ready and **squash-merge** it with a title like
   `0.94: <short list of features> (#N)` and the message `See docs/CHANGELOG.md.`.
   - This makes one commit on main, under the owner's name, so the release goes out from a single push.
   - In 0.93 this was done with the GitHub MCP `merge_pull_request` (`merge_method: squash`, `expectedHeadSha`: the full
     40-character sha).
5. Watch the main run. The `release` job creates tag `v<VERSION>` and uploads `Huginn.flatpak`, `Huginn-Windows.zip`
   and `Muninn.apk`, each with a `.sha256`. If a job fails for a GitHub reason (part 5), re-run failed jobs once.
6. Check the release page has all six files: `https://github.com/Ravenor907/Huginn-Muninn/releases/tag/v<VERSION>`.
7. Tell the owner in plain words, with the time in Pacific.

Both apps update themselves when opened: Muninn downloads the new APK, on Wi-Fi only since 0.93, and Android asks to
install. Huginn checks GitHub releases. Every update is checked against its SHA-256.

**After a release:** the work branch's PR is merged and finished. Start follow-up work from the latest main:

```
git fetch origin main && git checkout -B <branch> origin/main
```

Never stack new commits on the merged history.

---

## 8. Release history

- **0.5:** first messaging.
- **0.6:** groups and channels, folders, encrypted calls, Kids' TV, Windows zip, the rail layout.
- **0.7:** Tor removed (faster); calls anywhere; group calls and voice rooms; Kids' TV calls; TV mic boost; invite
  family to the TV; TV self-update; share the app; lock first.
- **0.8:** video calls; direct calls for family; smoother calls; screen share (Huginn); group text and call from the
  list; Messenger touches (m1); desktop composer; battery saver; Wine; the Flatpak back.
- **0.9:** private Muninn signing key (one-time reinstall); audio output picker; video call layout; Trash; drag to
  reorder; Nearby visibility; Finish setting up card; controller pointer; pattern by D-pad; Kids' TV fixes and
  several parents; Huginn echo cancellation; SHA-256 update checks; Flatpak folder limits.
- **0.91:** noise removal switch; send apps to a TV; Kids' TV apps; Settings in folders.
- **0.92:** check-for-messages modes; low-battery saving; quiet hours; lighter background; battery report; true
  black; video calls on the speaker.
- **0.93 (30 Oct 2026):** see part 9.
- **0.94 (30 Oct 2026):** smart notifications without Google, honest ticks, 7-day message limits (Family/Work up to
  30, d1), signed updates with the project's own key, Discord-style Huginn, Huginn catches up (m2, i1, f1, linked
  devices). See docs/CHANGELOG.md and part 11a.
- **0.95 (30 Oct 2026):** efficiency and on-time messages, one battery choice, smaller downloads, short Settings and
  plain wording, the Norse/Gothic sharp look with Dark, True black, Light and Brushed metal, press-and-hold person
  panel, keep-in-touch and caps fixes, group invite links (j1), Move here (x1), Kids' TV reconnect (k6), noise removal
  (k5) and update switch (k7), Huginn at true size with a Discord layout (handheld mode removed), and a full security
  and privacy audit with fixes (s1 route proofs and bound envelopes, padding and separate sending sockets, scrypt or
  Argon2id locks with nothing outside the app). See part 11b and docs/CHANGELOG.md.

Full notes for each release are in `docs/CHANGELOG.md`.

---

## 9. What 0.93 added

Muninn only, unless a line says otherwise. Huginn got the README and Folk.

**Quick fixes**
- **Folk replaces Kindred** in both apps. An existing "Kindred" folder is renamed (`Contacts.kt` DEFAULTS and
  migration, `src/chat.cpp`, qml).
- **No location prompt** on Android 13 and newer. Older phones are asked only when Nearby is turned on
  (`Close.offer()`; manifest `maxSdkVersion=32`, `neverForLocation`).
- **Rename from the chat list:** hold a person, then tap the pencil (`SelectBar`).
- **Refresh buttons:**
  - Chats (`Muninn.refreshChats`, `Chat.retryAllNow`).
  - Nearby (`refreshNearby`, `Close.lookAgain`).
- **Mute, archive, mark as unread, chat colors** (`ChatFlags`, `ChatFlagsTest`).
- **Black and grey valknut** as the main Settings button (Muninn top bar and tablet rail, Huginn rail).

**Battery** (`PowerLogic`, `Power`, `PowerLogicTest`)
- Follow Do Not Disturb.
- Check less when it's quiet: after 3 hours idle, background pings go from 2 to 5 minutes, and "Every 15 minutes"
  becomes 30, then 60.
- Data saver.
- Muninn's own update waits for Wi-Fi.
- Kids' TV update only while charging.

**Chat (m2, section 12):**
- **Albums:** multi-photo picker, `AlbumGrid`.
- **In-app camera** (`CameraScreen.kt`, CameraX plus `camera-video`): photos never land in the gallery; front/back
  flip.
- **Video messages:**
  - Up to 60 s, about 2 MB.
  - **Send or Discard after recording; never sent automatically.**
- **Polls:** 2 to 10 answers, several-answer switch, counts and who voted (`PollDialog`, `PollCard`).
- **Link previews:**
  - Made on the sender's phone, so the receiver's phone never visits the site.
  - Tapping a preview asks before opening.
  - Settings › Privacy › "Link previews" switch (`Identity.linkPreviews`, on by default).
- **Shared photos, videos and links page** per chat (`SharedMedia`).
- **Requests** as their own row and page (`RequestsScreen`, route "requests").

**Adding people you trust (i1, section 13; `Trust.kt`)**
- One-time invite links (`Chat.inviteLink`, `useOnce`, stored in `invites-once.json`).
- Introductions:
  - "X wants to introduce you to Y"; both say yes and become contacts.
  - `IntroduceDialog`; `Chat.introduce`, `answerIntro`.
- Offer to add the people in your Family group (`familyOffers`).
- 4 matching emoji to check a contact (`ChatCrypto.emojiCode`, `Chat.emojiWith`).
- Tap phones together over NFC (`Tap.kt`, `TapCard` service, AID F04D554E494E4E01, route "tap").

**Big jobs**
- **Files over the internet** (f1, section 14; `FileLink.kt`, `FileLinkTest`):
  - Straight between phones, up to 4 GB.
  - Needs "Direct calls" on for each other.
  - Files of 2 MB or less fall back to Nostr pieces.
  - Otherwise the sender is told to use the same Wi-Fi.
  - Never Cloudflare.
- **Group video** in voice rooms (`RoomVideo.kt`):
  - Up to 4 people.
  - VP8 640x480, 15 fps, 450 kbps.
  - Sent only to peers on a direct path (`CallMedia.videoNoRelay`).
  - Anyone without one stays on voice.
- **Linked devices, companion style** (section 15; `Link.kt`):
  - A tablet or second phone shows the phone's 1:1 chats and sends through it while the phone is online.
  - Pairing: the companion shows a `MUNINNL1:` code (QR, copy, share), good for 10 minutes and usable once. The
    phone pastes it in Settings › Linked devices.
  - On the companion, the chat list gets a "<phone>'s chats" row (routes "mirror", "mirror/<peer>").
  - Photos, voice and files show as words; the media stays on the phone.

**Docs**
- **README rewritten:**
  - About keeping in touch, not gaming. Kids' TV is moved down.
  - Tagline: "Private messaging for the people you care about."
- **Changelog and controller guide** moved to `docs/CHANGELOG.md` and `docs/controller.md`.
- **README privacy section:** now covers STUN and link previews.

---

## 10. Known gaps and what to test on real devices

None of 0.93's new features has been tried on real phones yet; CI only proves they build and pass unit tests.
Worth testing first, on two phones:

1. Linked devices: pairing, mirror list, sending from the companion, unlink on either side.
2. NFC tap: both phones on the Tap screen, unlocked. Some phones need NFC turned on in Android settings.
3. Group video with 3 or 4 people, on the same Wi-Fi and with direct calls on.
4. A large file over the internet with Direct calls on, and a small one without.
5. Camera, video message review, polls in a group, an album of 10 photos, a link preview on and off.
6. Introductions and one-time invite links between three people.

**Huginn (desktop) doesn't yet support** m2 (albums, video messages, polls, previews: it shows fallback text), i1,
f1 or linked devices. Adding them to Huginn is a candidate for a later release. Ask the owner first.

---

### 0.94 device tests (owner)

- Muninn: messages arrive within about 5 minutes with the screen off; Always right away contacts; honest ticks;
  battery report; 7-day limit and Family/Work 30 days; updating 0.93 to 0.94 and then 0.94 to the next (signed).
- Huginn: panels, themes, voice panel, deafen, push to talk, profile card, mentions, Ctrl+/; photos, polls, video,
  links; introductions, invite links, Family offer, emoji check; big files over the internet; linked devices both
  ways and their message limits; Huginn.exe starting on a real Windows 11 PC (CFG/CET).

## 11. Next: 0.94 smart notifications (PLANNING ONLY: do not write code until the owner says go)

**The owner's goal:** maximum battery life without delays. "If I send a message to my wife it must not show up an
hour later."

**Why delays happen today** (`PowerLogic.kt`):
- **Low battery:** below 30%, at most every 15 minutes; below 15%, at most **every hour**.
- **Quiet hours and Do Not Disturb:** they use the mode picked for quiet hours, which can be "Every hour".
- **"Check less when it's quiet":** after 3 hours idle, "Every 15 minutes" stretches to 30, then **60**.
- **Doze:** without "unrestricted" background use, Android cuts the network, and the 15-minute inbox check
  (WorkManager, `InboxWorker`) picks messages up.
- **Background connections today:** while "Right away", Muninn keeps **two** relay sockets open, pinged every
  **2 minutes** (5 when drowsy).

**Research done (other messengers):**

| Approach | Used by | Speed | Battery | Privacy |
|---|---|---|---|---|
| Google push (FCM) | WhatsApp, Messenger, Signal with Google | seconds | best (one shared OS connection) | Google learns when you get messages. Needs a server to send pushes, so the Worker would learn who wakes whom. |
| Own open connection | Signal without Google (WebSocket), SimpleX "Instant", XMPP apps like Conversations, Muninn today | seconds | fine if one socket and rare pings; drains with several sockets or frequent pings | nobody new |
| Periodic checks | SimpleX "Periodic" (about 10 min, under 2% a day reported), Muninn 15m and hourly | 10 to 60 min | lowest | nobody new |
| UnifiedPush (ntfy) | Molly, some FOSS apps | seconds | good (one shared connection) | needs a separate distributor app |

Sources:
- Signal: github.com/signalapp/Signal-Android issues 12490 and 8992, PR 13337.
- SimpleX: its blog post on instant notifications (2022-04-04), and Privacy Guides threads.
- unifiedpush.org FAQ, and the F-Droid post of 2022-12-18.
- XMPP: a Xabber keepalive issue and Ignite Realtime forum threads.

**The assistant's recommendation (the owner asked for it; the owner has NOT yet confirmed it):**
- One light connection, **no Google push**.
- Keep one relay connection open (not two), pinged about every 5 minutes (just under typical mobile NAT timeouts),
  timed to batch with other apps' wake-ups.
- Never fall back to periodic checking unless the owner picks that mode in Settings.
- Google push could be an opt-in later if battery turns out to matter more. Don't build it now.
- The battery cost is expected to land between today's "Right away" and "Every 15 minutes"; this is unmeasured.

**Smart rules proposed on top, each still to be asked as a yes/no, one at a time:**
1. An "always right away" list (start with the Family folder; add or remove anyone) that low battery, quiet hours
   and the idle slowdown never hold back. (The owner hasn't answered this yet; it was asked first and redirected into
   the research.)
2. A conversation window: after sending or receiving, stay fully connected for a few minutes.
3. Catch up instantly on unlock, on network change, and when Muninn opens.
4. Honest ticks for the sender: "Delivered", or "Waiting for her phone" when it hasn't arrived.
5. Replace the hourly floors (low battery under 15%, idle 60 min) with a longer ping on the one connection instead of
   dropping it.
6. Ask for "unrestricted" background use in the Finish setting up card, so Doze doesn't cut the connection.
7. Measure: extend the battery report to show the connection's cost, so the change can be checked on a real phone.

Next step: ask the owner to confirm the approach ("one light connection, no Google"). Then go through rules 1 to 7 one
at a time. Then write a plan. Code only after "go".

---

## 11a. 0.94 plan, agreed with the owner on 30 Oct 2026 (code only after "go")

The owner chose **one big 0.94** with everything below.

**A. Smart notifications (Muninn).** Approach confirmed: one light connection, no Google push. All seven rules: yes.
- One relay socket in the background (not two), pinged about every 5 minutes, batched with other wake-ups.
  Never fall back to periodic checks unless the person picks that mode in Settings.
1. "Always right away" list: Family folder by default, add or remove anyone. Low battery, quiet hours and the idle
   slowdown never hold their messages back.
2. Conversation window: about 5 minutes fully connected after sending or receiving.
3. Catch up instantly on unlock, network change and opening Muninn.
4. Honest ticks: "Delivered", or "Waiting for <name>'s phone".
5. No hourly floor: very low battery and long idle keep the one connection with a longer ping.
6. Finish setting up card asks for unrestricted background use.
7. Battery report shows the connection's cost.

**B. Message limits (both apps).**
- Default 7 days for every chat, groups and 1:1.
- Groups: an admin picks a group type (Standard, Family, Work). Family and Work groups can be set by admins from 7 up
  to 30 days; Standard stays 7. Same for everyone in the group.
- 1:1: Family and Work contacts can go up to 30 days. If the two people pick differently, the shorter one wins.
- Covers everything: text, photos, voice clips, videos, files. Encrypted backups also drop anything past the limit.
- Needs a caps token and protocol section (group type and limit in the signed group state; each side's 1:1 limit in
  envelopes). Older apps don't delete until they update.

**C. Windows trust (no outside accounts or services).**
- Keep three downloads (APK, Flatpak, Windows zip). No Microsoft Store.
- The owner wants **no external requirements**: no SignPath, no Microsoft malware reports, no winget. Without a
  code-signing certificate Windows will keep its "unknown program" warning; say so plainly if asked.
- Turn on Control Flow Guard (/guard:cf) and CET shadow stacks (/CETCOMPAT) in the Windows build.
- Project release key: CI signs every release file with the project's own private key (a GitHub secret, e.g.
  Ed25519), and both apps refuse any update not signed with it (on top of the SHA-256 check).
- GitHub build attestations (actions/attest-build-provenance) for every download.
- SECURITY.md: report problems privately through GitHub's private vulnerability reporting.
- Remove the SignPath steps from CI, docs/windows-signing.md and the README "Code signing policy" credit (keep the
  Privacy section).

**D. Discord-style Huginn (desktop).**
- Adjustable UI: resizable panels; hide or show side panels; compact or cozy messages plus text size; themes and
  accent colors.
- Member list with status; voice room panel (mute, deafen, leave); channel categories; user profile pop-out.
- Unread badges and mentions; push to talk; keyboard shortcuts panel (Ctrl+/); server-style round group icons with a
  pill for the open one.

**E. Desktop catch-up: at least everything the phone has.** m2 (albums, video messages, polls, link previews), i1
(introductions, one-time invites, family offers, 4-emoji check), f1 (files over the internet), and linked devices.

## 11b. 0.95 plan: efficiency, messages on time, lighter and easier (BUILT AND RELEASED in 0.95; kept as the record of decisions)

The owner's absolute focus for 0.95: app efficiency and timely notifications, and a lighter app that is easier for everyone. Decided with the owner on 30 Oct 2026, one question at a time:

- **Easier for everyone** comes first. Disability access items (screen reader labels, system text size, contrast, less motion, bigger targets) are optional.
- **Short Settings in both apps:** about 6 everyday choices up front (name, notifications, battery, appearance, privacy), everything else under one "More" page.
- **Plain wording on every screen in both apps:** no tech terms (relay, mailbox, caps), one short help line under anything not obvious.
- **Size limits in CI:** a build fails if the APK grows past about 8 MB, or the Windows zip or Flatpak past a little above their trimmed sizes. Growth needs the owner's OK.
- **Retries:** once a relay has said OK, re-publish only at 1 hour and 6 hours (plus right away when the other phone shows up nearby or on Wi-Fi). Today it is every 5 minutes for 24 hours. Require OK from at least 2 relays before slowing down.
- **Quick-reply window:** opened only by my own sends, 1:1 arrivals and Always-right-away contacts, not every group message.
- **APK:** release APK leaves out x86/x86_64 (about 7 MB); the emulator job keeps its own build.
- **Lock screen pattern position** (ui/Pattern.kt PatternPad in screens/LockScreen.kt): adjustable only in Settings › Lock and privacy (Top, Middle, Lower), never on the lock screen itself; default Lower half for one-handed use. Applies to unlock and to setting a pattern.
- **Owner report 30 Oct 2026 (0.94 on both phones, both in Family):** his phone shows no Call/Video for her, and a
  video message said she was on an older version. Cause: a contact's caps are learned only from a 1:1 envelope
  (`Chat.authentic`, Chat.kt:683); contacts added by QR, introduction, Family offer or met (Chat.kt:318, 594, 778,
  882, 1060) start with no caps, and group traffic never fills them in. `CallManager.canCall` needs `c1`, video
  messages need `m2`. Fix: (1) learn caps from group envelopes and group state too; (2) send a small hello with caps
  right after adding someone, both ways; (3) treat unknown caps as "probably current" in the UI and let the wire
  fall back if the other side really is old.
- **Root cause found from the owner's screenshot:** on his phone she is still a Nearby "met" person, not a contact
  ("You said yes to keeping in touch. Waiting for MoM."), so `contacts.get` is null: no caps, no Call/Video, "needs
  a newer Muninn". Her phone already has him as a contact, and `"keep"` in Chat.kt (`if (isContact(fromKey)) return
  200`) swallows his yes without answering, so he waits forever. Fix: when a "keep" yes arrives from someone who is
  already a contact, answer with our own "keep" yes (once), and the waiting side becomes contacts at once. Also let
  a met person be called and sent video when they are on the same Wi-Fi.
- **Owner confirmed 30 Oct:** scanning her code fixed calls, video and video messages. Decision: no code needed when
  both agree on Nearby. Both tapping "Keep in touch" makes them contacts at once (calls, video, messages work); the
  4-emoji check is offered with one tap and marks them verified. Permissions (calls, video, files, Always right away,
  folder, block) can be given and taken away from the long-press menu.
- **Owner, 30 Oct:** press and hold someone on the home screen (the chat list) to fully adjust their role and
  placement: folder (Family, Work, Folk), trusted/verified, Always right away, what they may do (calls, video,
  files), and where the chat sits (pin to top, move up or down, archive, mute, mark unread), plus nickname, color
  and block. One sheet, same everywhere (chat list, contacts, member list).
- **Long-press a person** (chat list, contacts, group member list): verify with the 4 emoji, trusted, folder
  (Family/Work/Folk), Always right away, nickname, block, all in one menu.
- **Look (owner, 30 Oct):** keep the sharper corners of the current apps (about 4px, like Kit.kt `Corner`), not the
  rounded mockups. Lean into it as the house style: hints of noble Norse and Gothic, on a modern, clean layout.
- **Themes in both apps (owner, 30 Oct):** Dark (default), True black, Light (parchment, as Huginn's today) and a new
  Brushed metal (iron and steel greys with a subtle brushed texture on rails and headers). Muninn gets Light and
  Brushed metal for the first time; Huginn gets Brushed metal.
- **Platforms and layout (owner, 30 Oct):** only three downloads, ever: Windows (zip), Linux (Flatpak) and Android
  (APK). Remove Huginn's handheld mode; keep a scaling choice in Settings. Layout follows orientation, not device:
  every portrait screen (phone, tablet held upright, a portrait window) uses the same portrait layout, and every
  landscape screen (tablet, desktop, landscape phone) the same landscape layout. Google TV (kid mode) is the only
  exception and keeps its own layout. Controller support stays (in the normal layout), checked only while a
  controller is connected. The button legend is hidden by default: the Select (View/Back) button shows it, and
  pressing Select again or any other button hides it.
- **Desktop scaling and a Discord layout (owner, 30 Oct, photo of Huginn on a large monitor: everything huge, the New
  group hint cut off at the left, controller hints showing with no controller):**
  - Cause: `System::uiScale` (src/system.cpp:67) grows the desktop with screen height (0.85 x height/1080, up to 2x) and
    Theme.qml adds x1.3 on top, while Qt already applies the OS DPI scaling. 1440p ends up about 1.47x, 4K up to 2.2x,
    and with Windows display scaling it doubles up. Fix: desktop base 1.0 at every resolution, no x1.3, let the OS DPI
    do HiDPI; Size 50% to 200% in Settings, also Ctrl+= / Ctrl+- / Ctrl+0, saved.
  - Mimic Discord's current desktop layout at 100%: server rail 72 px; channel/DM list 240 px (drag 200 to 360);
    member list 240 px; 48 px headers; user panel at the bottom left under the rail and list; message text 16 px;
    density Compact / Default / Spacious; themes like Discord's (Light, Dark, a darker one) plus ours.
  - Dialogs: title on its own line, help text wraps under it (never cut off), sized to content, not full width.
  - Controller hints only while a controller is in use (Select shows the legend).
- **Kids' TV: a parent who wiped or reinstalled can reconnect (owner, 30 Oct).** If another parent is still connected,
  they approve with one tap. If none is left, the TV's parent menu shows a "Connect a parent" code (opening it needs a
  5-second hold on the remote, so a child can't); the parent scans it. The old, wiped parent is removed from the TV's
  list automatically.
- **Move to another device (owner, 30 Oct).** The QR screen offers "Use on both" (linked devices, as today) and "Move
  here" (takeover). Move here sends identity, contacts, groups and history straight over the home Wi-Fi, encrypted
  with a key carried in the QR, nothing through the internet; contacts don't re-add or re-verify. The 4 emoji confirm
  on both screens. Afterwards the old device is locked out (can't send, read or receive as you; its keys are erased)
  and shows "Moved to [name]", with one tap to become a linked device of the new one. An interrupted move resumes;
  the old device stays locked until it finishes. Moving back works the same way. Needs a wire addition (a move
  message and the locked state) in both apps, with vectors.
- **Group invite links like Discord (owner, 30 Oct).** Expiry 30 min, 1 h, 6 h, 12 h, 1 day, 7 days (default) or
  Never; max uses 1, 5, 10, 25, 50, 100 or no limit; a list of active links (creator, uses, time left) with Revoke.
  Admins can always make links; a group switch "Members can invite" (default off) lets members too. Enforced by the
  admins' devices that accept the join; old join codes keep working.
- **Outside-dependency decisions (owner, 30 Oct, for 0.95):** Kids' TV dictation on-device only (button hidden
  otherwise); the no-lock vault key in Muninn's own files, not the Keystore; "Check for updates automatically"
  switch, on by default; Google Nearby split into its own switch, off by default; link previews off by default for
  new installs; 3 more default relays (relay.snort.social, nostr-pub.wellorder.net, offchain.pub); backup STUN
  servers stun.nextcloud.com and stun.stunprotocol.org after Cloudflare (no Google); incognito keyboard in Muninn;
  plain User-Agent for update checks; random Google Nearby id; fonts pinned with SHA-256. Kept as is: untrusted calls
  fail when the call relay is down (never a direct fallback); relayId stays fixed. Later: Flatpak's dependence on
  Flathub for its runtime and the org.freedesktop.Flatpak permission (the Windows zip under Proton is the fallback).
- **Mockup:** the owner asked for a mockup of the Android app after the 0.95 changes, to review before code.

Already approved trims (from the 0.94 quality check): remove the duplicate "Photos and voice"; remove Huginn's Steam screenshot scanning and the Flatpak Steam userdata permission; kids' TV noise removal selectable (None, Standard WebRTC, Strong RNNoise, Both; default Both; on the parent phone and the TV); Nearby folded into one switch with details under More; one battery choice (Fastest, Balanced, Most saving) with switches under More; remove Huginn's browser TransferServer page; one "Add someone" screen, QR first; remove old 0.5/Tor migration code.

Battery audit fixes to do (Muninn, file:line from integ94 366dfb0):
1. Stop the reconnect hot loop: backoff on swaps with a global failure streak, no reconnect while there is no network (Nostr.kt:336-348, PowerLogic.kt:327).
2. Per-relay `since` cursor: last EOSE minus about 20 minutes, never older than 2 days (Nostr.kt:280).
3. Receipts: at most one per message id per few minutes, one per photo not per piece (Chat.kt:636,994); retryAllNow skips anything a relay already holds.
4. Disk: update() returns false when nothing changed (Chat.kt:1539); nostrSeen in its own small file written at most every 30 s; battery counters in memory, saved on screen-on or hourly; skip use() when the plan is unchanged (Power.kt:331).
5. Don't reset `chosen` in plan() when nothing changed (Nostr.kt:105); battery broadcasts only on crossing 15/30% or a plug change.
6. LocalSend stale-session check before the `continue` (LocalSend.kt:196-203); transfer wakelock about 10 minutes, renewed on progress (NearbyService.kt:37).
7. Arm the keepalive alarm at once on screen-off; only closing sockets waits for the grace (Power.kt:333).
8. Battery report shows relay-side pings (OkHttp receivedPingCount, guarded); skip the probe when the socket proved alive recently, prefer the relay with fewest server pings.
9. Owner decided: keep Schnorr verify but make it fast (Jacobian coordinates, one inversion per multiplication); must still pass the existing vectors.
10. Smaller: UI polling loops stop when the activity stops (SetupCard, Settings); TvParent and RoomManager timers only when needed; onAvailable reconnects once; Schnorr hex without String.format.
Avoid: stretching the mobile-data check past about 5 minutes, removing the setAndAllowWhileIdle backup, WorkManager instead of the alarm, a lookback under the 600 s backdate.

Quality check fixes (both apps): message history rewrite off the UI thread and only when changed; Shared page loads in pages; gamepad not polled every 10 ms; idle timers off; LocalSend subnet sweep not in the background; Huginn lists update in place instead of resetting on every change; recentPictures off the UI thread; Windows zip drops dxcompiler/dxil, qmltooling, pkgconf (about 7.5 MB); APK useLegacyPackaging.

## 12. Saved for later and other open items

- **Relay Worker update on Cloudflare (the owner does this; raised again at the 0.93 release, not yet done):**
  1. In the Cloudflare dashboard, create a KV namespace `LIMITS` (Storage & Databases › KV › Create).
  2. Bind it to the `huginn-relay` Worker with variable name `LIMITS` (Worker › Bindings › Add binding › KV
     namespace).
  3. Paste the new `relay-worker/worker.js` into the Worker editor and Deploy.
  4. Check `https://huginn-relay.ravenor907.workers.dev/health`.

  Until then the old Worker keeps running; the new one needs `LIMITS` or it answers 503 `limits_unavailable`.
  Full steps are in `relay-worker/README.md` (steps 28 to 40).
- **Discord-style ideas for Huginn on PC:** never gone through with the owner. Ask about them one at a time if the
  owner wants desktop parity.
- **Huginn support for m2, i1, f1 and linked devices:** see part 10.
- **Decided against (don't build):**
  - forwarding messages
  - location sharing
  - chat bubbles and picture-in-picture
  - voice message speed and transcripts
  - notes to self
  - plans and events
  - the weekly battery report

---

## 13. How to work, step by step (so nothing breaks)

1. Read this file, then the parts of `docs/protocol-0.6.md` and the code you're about to touch.
2. Branch from the latest main.
3. Make small commits with plain-English messages, committed as Ravenor907 (rule 1).
4. For anything on the wire:
   - add a caps token and a protocol section
   - write a `tools/*_ref.py` and vectors, plus a Kotlin test (and C++ self-test if Huginn does it)
   - send fallbacks to older peers
5. Never call a Compose helper or Kotlin API you haven't confirmed exists (grep for it). CI is the only compiler, and
   each round trip costs about 15 minutes.
6. Push, then read every CI job. Fix real failures, and re-run GitHub flakes once (part 5).
7. Update `docs/CHANGELOG.md`, and at release time the README "What's new".
8. Update this file at the end of a session: what was done, what's left, and any new owner rules.

---

## 14. Things the owner has said, worth remembering verbatim

- "Do not attribute yourself or any AI to the project, you are not a contributor and any of the commits that are
  pushed are done anonymously or under my own username."
- "Ask me yes or no", "Let's go through one by one".
- "I don't want cloudflare to use any data, if it can just be for a handshake, that's it, I want to keep the usage of
  cloudflare to an absolute minimum."
- "I want to simplify the read me, I need to make it less gamer focused and more communication focused."
- "The parenting stuff shouldn't be first and foremost."
- "What I don't want to get to is if I send a message to my wife and it doesn't show up for an hour."
- "Do not even start the build, this is still planning phase."

---

## 15. State at handoff

- `main` has 0.95 (squash merge of branch `integ95`), `VERSION` 0.95. Start new work from main.
- Caps: Muninn `r1 g1 c1 c2 t1 v1 m1 m2 i1 f1 d1 j1 x1 s1 k1 k2 k3 k4 k5 k6 k7` (tokens Huginn reads first: a 0.94
  Huginn keeps only 16); Huginn `r1 g1 c1 c2 t1 v1 m1 m2 d1 i1 f1 j1 x1 s1` and keeps up to 32 tokens.
- Owner device tests for 0.95 are still to do (see docs/CHANGELOG.md 0.95).
- Left for later: pin tools/get-fonts.sh to a google/fonts commit with SHA-256 (google/fonts was unreachable here);
  3 more default relays and backup STUN servers (the owner approved; a safety check in the build environment
  blocked the edit, so they were not added); Flatpak's dependence on Flathub for its runtime.
- The relay Worker on Cloudflare still needs the owner's update (part 12), now also for the per-IP cap and 30-minute
  TURN logins. The apps work either way.
- Feature branches (n94-*, n95-*, integ94, integ95) can be deleted by hand.

---

## 16. Starter prompt (paste this first in a new chat)

> You're picking up an existing open-source project, Huginn and Muninn (GitHub: Ravenor907/Huginn-Muninn), a
> private end-to-end encrypted messenger: Muninn for Android phones, tablets and Google TV, Huginn for Windows and
> Linux. I'm the owner, Ravenor907. I've attached HANDOFF.md: read all of it before answering.
>
> Rules: never credit yourself or any AI anywhere (commits are mine, as Ravenor907
> <Ravenor907@users.noreply.github.com>); ask me one question at a time; I decide releases; give times in Pacific;
> use plain words; keep to how the project already works; peer to peer first and Cloudflare only for handshakes and
> untrusted call audio; no Google push.
>
> We're in the planning phase for 0.94 smart notifications (part 11). Don't write code until I say go. Start by
> telling me in a few lines where the project is, then ask me the first planning question.

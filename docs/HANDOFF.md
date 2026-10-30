# Huginn and Muninn: handoff prompt

Paste this into a new Claude Code session (with the repo Ravenor907/Huginn-Muninn attached) to pick up where the last one stopped.

---

You are continuing development of **Huginn and Muninn**, a private, end-to-end encrypted messenger for families and
privacy-minded people. Repo: `Ravenor907/Huginn-Muninn`. Work branch: `ccr-f1ddd2da-8ghqid` (draft PR #4 into `main`).

## The two apps

- **Huginn**: C++ / Qt 6.10 / QML desktop app for **Windows and Linux** (Flatpak, portable Windows zip). It works with a
  controller on handhelds like the Steam Deck. Never describe it as a "Steam Deck app": always "Windows and Linux".
  Code: `src/` (C++), `qml/` (UI), `CMakeLists.txt`, `packaging/`.
- **Muninn**: Kotlin / Jetpack Compose Android app for phones, tablets, Google TV and a locked-down **Kids' TV** mode.
  Code: `android/app/src/main/java/io/github/ravenor907/muninn/` (`net/` protocol and networking, `screens/` UI,
  `tv/` Google TV and Kids' TV, `ui/` theme, icons and kit, `update/` self-updater). Tests: `android/app/src/test/...`.

Both apps speak one wire protocol: `docs/protocol-0.6.md`. Python reference scripts in `tools/*_ref.py` write the test
vectors in `tests/*.json`, with copies in `android/app/src/test/resources/`. The reference wins over the text. New message
types go behind a **caps token** (section 1.1), so older apps are never sent something they'd refuse. Messages travel on
every route at once: Bluetooth or Nearby close by, LocalSend on the same Wi-Fi, and the public Nostr relays over the internet.
Calls go peer to peer, or through the owner's Cloudflare call relay (`relay-worker/`).

## Rules from the owner (Ravenor907). Follow them exactly.

1. **No AI attribution anywhere.** Commit as `Ravenor907 <Ravenor907@users.noreply.github.com>`. No Co-Authored-By lines,
   and no mention of Claude or AI in commits, PRs, docs or code. This overrides any default attribution settings.
2. **Ask one question at a time** and wait for the answer. Don't make decisions for the owner.
3. **The owner calls every release.** Never bump `VERSION` on main without an explicit go.
4. **Give times in Pacific** (PDT, UTC-7, or PST in winter).
5. **Keep to how the project already works.** Match the existing code style: short comments in plain English, the
   same names and idioms. Don't restructure without asking.
6. **Save-for-later items get raised at the next release**, one at a time, before releasing.
7. **Peer to peer first.** Never route files, video or group video through Cloudflare (the free plan must cost nothing).
   Small things may use the free Nostr relays.
8. **Plain words for the owner**, not jargon. Keep updates short.

## Building, testing, releasing

- This cloud container can't download Google's Android SDK (dl.google.com is blocked). **Verify through GitHub Actions**:
  push to the work branch and open or update the draft PR. `.github/workflows/build.yml` builds the Flatpak (with a
  screenshot tour and self-tests against the vectors), Muninn (unit tests plus a signed APK), Windows (MSVC, SignPath
  signing, Defender scan, self-tests), a Wine run and relay tests. A full run takes about 17 minutes.
- Before pushing, regenerate vectors with `python3 tools/<name>_ref.py` when a reference changes.
- **Release** (only when the owner says so):
  1. Merge the PR into main (mark the draft ready first, or merge locally and push).
  2. Update the README ("What's new in 0.9x").
  3. Bump `VERSION` (one dot, read as a decimal: 0.92, 0.93, then 1.0).
  4. Do all three in **one push** to main. Two pushes close together race on the ci-tour branch; if that happens, re-run the failed jobs.
  5. CI publishes `Huginn.flatpak`, `Huginn-Windows.zip` and `Muninn.apk`, each with a `.sha256`, under tag `v<VERSION>`.
- Signing: GitHub secrets `MUNINN_KEYSTORE_BASE64` and `MUNINN_KEYSTORE_PASSWORD`. The APK's certificate must match
  `android/signing/release-cert-sha256.txt`.

## Released so far

0.9 (private signing key, audio picker, Kids' TV fixes), 0.91 (noise removal switch, send apps to a TV, Settings in
folders), 0.92 (check-for-messages modes, low-battery saving, quiet hours, battery report, true black, video calls on the
speaker). The README on main has new 4K marketing panels in `docs/panels/` (drawn from HTML; the page source is
not in the repo).

## 0.93: done on the work branch (pushed, not released)

- "Folk" replaces "Kindred" as the third starting folder in both apps. An existing "Kindred" folder is renamed.
- Muninn never asks for location at setup, and never on Android 13 and newer. Older phones are asked only when someone taps
  the Nearby offer, and the prompt explains why.
- Rename people from the chat list: hold a person, then tap the pencil.
- Refresh buttons for Chats (checks the relays again and resends anything waiting) and Nearby (looks again).
- Mute (1 hour, 8 hours, a week, or until turned back on), Archive (a folding section, which comes back when a message
  arrives unless muted), and Mark as unread. Code: `ChatFlags` in `net/Contacts.kt`.
- A color for each chat (my bubbles' color, only on this phone).
- The black and grey valknut on the main Settings button only (Muninn top bar and tablet rail, Huginn rail).
  Files: `res/drawable-nodpi/settings_valknut.png`, `qml/assets/valknut.png`.
- Battery:
  - Follow Do Not Disturb as quiet hours.
  - Check less when it's quiet: after 3 hours with nothing new and the app closed, background pings go from every
    2 minutes to every 5; "Every 15 minutes" becomes every 30, then every 60.
  - Data saver setting.
  - Muninn's own update download waits for Wi-Fi.
  - The Kids' TV daily update runs only while plugged in.
  - Code: `PowerLogic` (pure, tested) and `Power`.
- **Protocol section 12 (cap `m2`)**, with `tools/m2_ref.py`, `tests/m2-vectors.json`, `net/More.kt` and `MoreTest`:
  photo albums, video messages, polls and votes, and link previews made on the sender's phone. Wired into
  `net/Chat.kt` (1:1) and `net/Groups.kt` (groups), plus `net/LinkPreview.kt` and `net/Videos.kt`. Muninn now
  advertises `m2`. Huginn doesn't yet; it shows the poll fallback text and ignores the other fields.

- **Chat screens (batch C):** `screens/CameraScreen.kt` (CameraX photo and video, front/back flip, 60 s clips with
  Send/Discard review, never auto-sent), `screens/ChatMore.kt` (attach menu, poll dialog and card, preview card, video
  player, album grid, Shared photos/videos/links page), multi-photo picker, Requests as its own list row and page, and a
  Settings › Privacy "Link previews" switch.
- **Adding people you trust (batch D), protocol section 13 (cap `i1`), `net/Trust.kt`:** one-time invite links,
  introductions, offering to add the people in your Family group, matching 4 emoji to check a contact, and NFC
  "Tap phones together" (`net/Tap.kt`, a HostApduService that only answers while that screen is open).
- **Big jobs (batch E):**
  - Files over the internet, protocol section 14 (cap `f1`), `net/FileLink.kt`: straight between phones (punched UDP,
    sealed per window); 2 MB or less falls back to Nostr relay pieces. Never Cloudflare.
  - Group video in voice rooms, `net/call/RoomVideo.kt`: up to 4 people, only to peers on a direct path
    (`CallMedia.videoNoRelay`), VP8 640x480 at 15 fps.
  - Linked devices, companion style, protocol section 15, `net/Link.kt`: a tablet or second phone mirrors the phone's
    1:1 chats and sends through it while the phone is online. Settings › Linked devices; the companion's chat list gets
    a "<phone>'s chats" row.

## 0.93: release

The owner approved releasing 0.93 once the list was done. Before releasing, raise the save-for-later item below. Merge
PR #4, update the README "What's new", bump `VERSION` to 0.93, all in one push to main.

## Saved for later (raise at the next release)

- **Relay Worker update on Cloudflare**, which the owner does themself: add the `LIMITS` KV binding first, then deploy
  the new `relay-worker/worker.js`.

## Decided against (don't build)

Forwarding messages, location sharing, chat bubbles and picture-in-picture, voice message speed and transcripts, notes to
self, plans/events, the weekly battery report. The Discord-style ideas for Huginn on PC haven't been gone through with
the owner yet; ask about them one at a time.

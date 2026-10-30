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

Check that PR #4's latest CI run is green before building on it. The two newest commits (m2 in 1:1 chats and in groups)
had not finished building at handoff.

## 0.93: still to do (all approved by the owner)

**Chat features, finishing batch C. The data layer is done; these are mostly screens:**
- Show albums as a grid of photos from one sender with the same album id. Add multi-select in the photo picker
  (`sendPhotos` / `sendGroupPhotos`).
- **In-app camera:** CameraX is already a dependency. A viewfinder inside the app, a front/back flip button, then send.
- **Video messages:** record up to 60 s with CameraX `camera-video`. Add that dependency, at about 220 kbps video and
  32 kbps audio, a 480 px side, so it stays under 2 MB (`Videos`). **After recording, show a preview with Send and
  Discard. Never send automatically when the button is released** (the owner asked for this). Play the video with the
  decrypted file in the cache, deleted after playing. `sendVideo` / `sendGroupVideo` only go to `m2` peers.
- **Poll screen:** a question, 2 to 10 answers, and a "several answers" switch. Show results with counts and who voted.
  Code: `sendPoll`, `vote`, `sendGroupPoll`, `groupVote`.
- **Link preview card** under a bubble. Tapping it asks "Open example.com?". Add a Settings › Privacy switch
  "Link previews" (`Identity.linkPreviews`, on by default).
- **Message requests:** they already exist ("Wants to chat" panels). Move them into their own "Requests" list row.
- **Shared media tab** in each chat: photos, videos, files and links. A gallery for photos and voice already exists.
- Update the Data saver text if needed. Photos and videos are pushed in pieces, so only internet *files* (batch E) can
  really wait for a tap.

**Adding people you trust (batch D):** one-time expiring invite links; introductions ("Mom introduces you to Grandma",
both accept); offering to add as a contact when someone joins your Family group; tap phones together over NFC; checking a
contact by matching 4 emojis. These need a new caps token and protocol section, with a reference script and vectors, like `m2`.

**Big jobs (batch E):**
- Files over the internet, directly device to device (reuse the call direct-path code). Small files may fall back to Nostr
  relay pieces; large files wait and retry. **Never through Cloudflare.**
- Group video calls, peer to peer only: up to 4 trusted contacts, never through Cloudflare. Anyone who can't connect
  directly stays on voice.
- Same you on every device (linked devices, chats synced). First check how much exists today.

**Then release 0.93:** the owner said to release it once this list is done. Before releasing, raise the save-for-later
item below. Update the README, bump `VERSION` to 0.93, and do it all in one push.

## Saved for later (raise at the next release)

- **Relay Worker update on Cloudflare**, which the owner does themself: add the `LIMITS` KV binding first, then deploy
  the new `relay-worker/worker.js`.

## Decided against (don't build)

Forwarding messages, location sharing, chat bubbles and picture-in-picture, voice message speed and transcripts, notes to
self, plans/events, the weekly battery report. The Discord-style ideas for Huginn on PC haven't been gone through with
the owner yet; ask about them one at a time.

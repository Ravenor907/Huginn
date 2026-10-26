<p align="center">
  <img src="docs/banner.png" width="100%" alt="Huginn and Muninn">
</p>

<p align="center">
  <a href="https://github.com/Ravenor907/Huginn-Muninn/releases"><img src="https://img.shields.io/github/v/release/Ravenor907/Huginn-Muninn?include_prereleases&label=release&style=for-the-badge&color=C2A25E&labelColor=101112" alt="Release"></a>
  <a href="https://github.com/Ravenor907/Huginn-Muninn/actions/workflows/build.yml"><img src="https://img.shields.io/github/actions/workflow/status/Ravenor907/Huginn-Muninn/build.yml?branch=main&label=build&style=for-the-badge&labelColor=101112" alt="Build"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-9C978D?style=for-the-badge&labelColor=101112" alt="GPL-3.0"></a>
</p>

Odin sends two ravens out every morning: Huginn (thought) and Muninn (memory). They fly the world and come back with what he needs to know.

These two do the same for you. **Huginn** lives on your Steam Deck or SteamOS handheld, **Muninn** lives on your Android phone. Together they move your files, talk to each other, and keep your conversations private: no accounts, no phone numbers, no servers that can read anything, and no tracking.

## Why

- **Private by design.** Every message, photo and voice note is end-to-end encrypted. Only the two people talking can read it. Nothing is stored in the clear, even on your own device.
- **Built for the hardware.** Huginn is made for a controller, a 7 inch screen and the couch. Muninn is made for one hand.
- **Works with or without the internet.** Next to each other, the ravens talk over Bluetooth and Wi-Fi. Apart, they use the internet. Messages take every route at once and arrive by whichever is fastest.
- **Free and open.** GPL-3.0, no ads, no analytics, no sign-up.

## The messenger (both apps)

| | |
|---|---|
| **End-to-end encryption** | Each person has a key pair made on their own device. Messages are sealed with AES-256-GCM using keys only the two people share. Internet messages are wrapped a second time, so relays can't even see who is writing to whom. |
| **A fresh key for every message** | Forward secrecy: keys move on with every message, so a key stolen tomorrow can't open yesterday's conversation. |
| **Key change warnings** | If a contact's security key ever changes, the chat says so and asks you to check the code with them. Nothing is trusted silently. |
| **No account** | You are a key, not a phone number or email. Share your code or QR to add someone. |
| **Codes that expire** | Your contact code and QR last 1 hour. A code leaked online can't be used to spam you later. Strangers without a valid code are dropped silently. |
| **Every route at once** | Bluetooth, local Wi-Fi and the internet together, with automatic retries for a full day if the other person is offline. |
| **Sent, delivered, read** | Ticks for each message. Read receipts can be turned off. |
| **Unsend** | Remove a message from both devices. The other side sees "Message deleted by" with your name. |
| **Photos and voice messages** | Photos are shrunk and scrubbed of location and camera data. Voice messages are recorded in memory and carry no metadata. |
| **Lock** | A 9-dot pattern or a password is required to open the chat. Everything on disk is encrypted with it, and wrong guesses lock you out for longer each time. There's no visible erase button for a snoop to press. |
| **Notifications** | A notification and the raven's call for every message, unless you're already looking at that chat. Muninn listens in the background. |
| **Nearby chat** | Talk to people in the same room without exchanging codes first. |
| **Private nicknames** | Rename anyone ("Raven 6645" becomes "Wifey"). Only you see it; it never leaves your device. |
| **Contacts** | Block, unblock or remove people from Settings. Hold a message to delete it. |
| **Encrypted backup** | Save all your chats to one password-protected file and restore them later. |
| **Panic lock** | Lock everything instantly: a quick settings tile, a home screen shortcut or a hard shake on Muninn; hold L3 and R3 on Huginn. Message text stays hidden in notifications until you unlock. |
| **Accessibility** | Interface size, larger text, high contrast, a color blind palette and reduced motion. Tablets get the list and the chat side by side. |

## Huginn: the handheld

A file explorer for SteamOS Game Mode, with the messenger built in. No trip to Desktop Mode needed.

| | |
|---|---|
| **Two panes, one thumb** | Browse with the d-pad, select with X, hold Y for the action wheel: copy, move, rename, extract, delete, zip, new folder, send to phone, undo. |
| **Move to…** | Send files straight to a ROM folder, a game's folder (for mods), the SD card or a recent spot. |
| **Storage** | What's using space on each drive, biggest first. Clear shader caches or empty the trash in one press. |
| **Steam folders by name** | `compatdata/1245620` becomes Elden Ring, with its saves, Proton prefix and shader cache side by side. Back up saves, clear shader cache, reset a broken prefix. |
| **Phone transfer** | Encrypted transfers to and from Muninn or any LocalSend app. Scan the QR code, type the PIN, done. |
| **USB tools** | Install or update Ventoy, copy ISOs onto it, write an image, format a stick. |
| **Look inside archives** | zip, 7z and tar, browsed before extracting. |
| **Previews and quick edits** | Screenshots full screen with a filmstrip. Config files and logs as text, with one-line edits (a .bak copy is kept). |
| **Network shares** | SFTP shares through the same plumbing Dolphin uses. |
| **TV mode** | Docked to a big screen, everything grows to couch size on its own. |

## Muninn: the phone

The messenger, plus the file tools you want in your pocket. Android 8 or newer.

| | |
|---|---|
| **Messenger** | Everything above, in a clean layout: search, round avatars with unread counts, one + button for adding people, and full-screen conversations. Hold the mic to record voice. |
| **Send files** | Encrypted transfers with Huginn and LocalSend. |
| **Browse files** | Your phone's storage, organized and searchable. |
| **Useful ISOs** | Bootable USB drives from the phone, sorted into folders: **Gaming** (SteamOS, Bazzite, CachyOS, Batocera), **Desktop** (Linux Mint, Ubuntu, Fedora) and **Tools** (Memtest86+, GParted, Clonezilla, SystemRescue, Rescuezilla). Each one comes from its official source and is checked before it's written, or added to a Ventoy drive. Bring your own ISO for anything else, Windows included. |

## Install

Both apps ship together, in the same release, with the same version number, and both update themselves when they open.

**Huginn** (Desktop Mode, in Konsole):

```
cd ~/Downloads && curl -LO https://github.com/Ravenor907/Huginn-Muninn/releases/latest/download/Huginn.flatpak && flatpak install --user -y Huginn.flatpak
```

Add it to Steam (Add a Non-Steam Game › Huginn) to launch it from Game Mode.

**Muninn**: on the phone, open this link and install it:

https://github.com/Ravenor907/Huginn-Muninn/releases/latest/download/Muninn.apk

### The one-time USB setup

Installing Ventoy and writing images open a USB stick byte by byte, which Linux only lets an admin do. Huginn's USB Tools has a **Set up** button for Desktop Mode: it asks for your password once and adds a polkit rule that lets your user open **USB drives only**. Game Mode never asks after that. Settings › Safety removes it again.

Never set a password on SteamOS? Open Konsole and run `passwd` first.

## Controls (Huginn)

| Button | Files | Chat |
|---|---|---|
| A | Open | Write a message |
| B | Up a folder, clear selection or search | Back |
| X | Select | Message options |
| Y (hold) | Action wheel, release to pick | Record a voice message |
| LB / RB | Switch pane | RB: send a picture |
| LT / RT | Page up / down | Scroll |
| View | Search this folder | Keep in touch |
| Menu | Settings | Settings |
| L3 + R3 (hold) | | Panic lock |

A keyboard works too in Desktop Mode: arrows, Enter, Escape, Space, Tab and F10.

## What's next

0.6: group chats, contact folders (Family, Work, whatever you like), a Windows portable build, a QR scanner and controller mouse control on Huginn.

## Versions

One dot only, read as a decimal: 0.3, 0.31, 0.32, 0.4, 0.5, then 1.0 when it's rock solid. The `VERSION` file on main decides releases for both apps: change it and CI builds Huginn and Muninn, tests both, and publishes them together.

## Building

```
tools/get-fonts.sh
flatpak-builder --user --install --force-clean build packaging/io.github.ravenor907.Huginn.json
```

Muninn builds with Gradle from `android/`. CI builds both, runs the crypto and drive helper tests, and opens every screen of both apps to catch broken screens before a release.

## License

GPL-3.0. Fonts: Cinzel, Barlow and Noto Sans Runic, under the SIL Open Font License.

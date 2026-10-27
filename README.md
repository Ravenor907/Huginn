<p align="center">
  <img src="docs/banner.png" width="100%" alt="Huginn and Muninn">
</p>

<p align="center">
  <a href="https://github.com/Ravenor907/Huginn-Muninn/releases"><img src="https://img.shields.io/github/v/release/Ravenor907/Huginn-Muninn?include_prereleases&label=release&style=for-the-badge&color=C2A25E&labelColor=101112" alt="Release"></a>
  <a href="https://github.com/Ravenor907/Huginn-Muninn/actions/workflows/build.yml"><img src="https://img.shields.io/github/actions/workflow/status/Ravenor907/Huginn-Muninn/build.yml?branch=main&label=build&style=for-the-badge&labelColor=101112" alt="Build"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-9C978D?style=for-the-badge&labelColor=101112" alt="GPL-3.0"></a>
</p>

Odin sends two ravens out every morning: Huginn (thought) and Muninn (memory). They fly the world and come back with what he needs to know.

These two do the same for you. **Huginn** runs on Windows, Linux and SteamOS handhelds. **Muninn** runs on Android phones, tablets and Google TV. Together they are a private place to talk: messages, groups, calls and file sharing, with no accounts, no phone numbers, no servers that can read anything, and no tracking.

## Why

- **Private by design.** Every message, photo, voice note and call is end-to-end encrypted. Only the people talking can read or hear it.
- **Your IP address stays hidden.** Internet messages travel over Tor, so no relay and no contact ever learns where you are. The app never shows anyone's IP address.
- **Calls without delay.** Call audio goes through an encrypted relay instead of Tor, so voices arrive in real time and the person you call still never sees your address. Close by, calls go straight over Wi-Fi.
- **Works with or without the internet.** Next to each other, the ravens talk over Bluetooth and Wi-Fi. Apart, they use Tor. Messages take every route at once and arrive by whichever is fastest.
- **Free and open.** GPL-3.0, no ads, no analytics, no sign-up.

## What's new in 0.6

| | |
|---|---|
| **Group chats and channels** | Groups with their own channels, a member list and admins. Every message is encrypted to each member separately. |
| **Contact folders** | Sort people into Family, Work, Kindred or any folder you name. |
| **Encrypted calls** | One-to-one voice calls on both apps, with a call history. |
| **Kids' TV** | Muninn on a Google TV becomes a simple screen for children to reach Mom and Dad: big buttons, quick messages, voice clips and dictation. No sign-in. A parent pairs it once from their phone, edits its quick messages, and is the only one who can unlock it. |
| **Windows** | Huginn as a portable zip: unzip anywhere and run, nothing to install. |
| **New layouts** | Huginn, and Muninn on tablets, use a Discord-style rail of groups, channels and members. |
| **Tor built in** | Both apps carry their own Tor. Each device has its own onion address for direct, private delivery. |

## The messenger (both apps)

| | |
|---|---|
| **End-to-end encryption** | Each person has a key pair made on their own device. Messages are sealed with AES-256-GCM using keys only the people talking share. |
| **A fresh key for every message** | Forward secrecy: a key stolen tomorrow can't open yesterday's conversation. |
| **Key change warnings** | If a contact's security key ever changes, the chat says so and asks you to check the code with them. |
| **No account** | You are a key, not a phone number or email. Share your code or QR to add someone. |
| **Codes that expire** | Your contact code lasts 1 hour. Strangers without a valid code are dropped silently. |
| **Sent, delivered, read** | Ticks for each message. Read receipts can be turned off. |
| **Unsend** | Remove a message from every device in the chat. |
| **Photos and voice messages** | Photos are scrubbed of location and camera data. Voice messages carry no metadata. |
| **File sharing** | Encrypted transfers between Huginn, Muninn and any LocalSend app on the same Wi-Fi. |
| **Lock** | A 9-dot pattern or a password opens the app. Everything on disk is encrypted with it. |
| **Private nicknames** | Rename anyone. Only you see it. |
| **Encrypted backup** | Save all your chats to one password-protected file and restore them later. |
| **Panic lock** | Lock everything instantly: a quick settings tile, a shortcut or a hard shake on Muninn; hold L3 and R3 on Huginn. |
| **Accessibility** | Interface size, larger text, high contrast, a color blind palette and reduced motion. |

## Install

Both apps ship together, in the same release, with the same version number, and both update themselves when they open.

**Huginn on Windows:** download [Huginn-Windows.zip](https://github.com/Ravenor907/Huginn-Muninn/releases/latest/download/Huginn-Windows.zip), unzip it anywhere and run `Huginn.exe`.

**Huginn on Linux and SteamOS** (in a terminal; on SteamOS, Konsole in Desktop Mode):

```
cd ~/Downloads && curl -LO https://github.com/Ravenor907/Huginn-Muninn/releases/latest/download/Huginn.flatpak && flatpak install --user -y Huginn.flatpak
```

On SteamOS, add it to Steam (Add a Non-Steam Game › Huginn) to launch it from Game Mode.

**Muninn on Android and Google TV:** open this link on the device and install it:

https://github.com/Ravenor907/Huginn-Muninn/releases/latest/download/Muninn.apk

## Controller (Huginn)

| Button | Chat |
|---|---|
| A | Write a message |
| B | Back |
| X | Message options |
| Y (hold) | Record a voice message |
| RB | Send a picture |
| LT / RT | Scroll |
| Menu | Settings |
| L3 + R3 (hold) | Panic lock |

A mouse and keyboard work too.

## What's next

Group voice rooms, calls on Kids' TV, calls over Bluetooth, and a Discord-style layout on phones.

## Versions

One dot only, read as a decimal: 0.5, 0.6, then 1.0 when it's rock solid. The `VERSION` file on main decides releases for both apps: change it and CI builds Huginn for Linux and Windows and Muninn, tests all three, and publishes them together.

## Building

```
tools/get-fonts.sh
flatpak-builder --user --install --force-clean build packaging/io.github.ravenor907.Huginn.json
```

Windows builds with CMake, Qt 6.10 and vcpkg (see the Windows job in `.github/workflows/build.yml`). Muninn builds with Gradle from `android/`.

## License

GPL-3.0. Fonts: Cinzel, Barlow and Noto Sans Runic, under the SIL Open Font License.

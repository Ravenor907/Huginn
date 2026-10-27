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
- **Relays see only a sealed envelope.** Internet messages go through public Nostr relays, end-to-end encrypted and sealed so a relay sees only a mailbox id, never who sent it or what it says. Relay servers can see the IP address that connects to them. The app never shows anyone's IP address.
- **Calls without showing your address.** Internet calls go through the Cloudflare call relay, so the person you call never sees your IP address. Close by, calls go straight over Wi-Fi.
- **Works with or without the internet.** Next to each other, the ravens talk over Bluetooth and Wi-Fi. Apart, they use the Nostr relays. Messages take every route at once and arrive by whichever is fastest.
- **Free and open.** GPL-3.0, no ads, no analytics, no sign-up.

## What's new in 0.8

| | |
|---|---|
| **Video calls** | One-to-one video calls between phones and Windows, end-to-end encrypted, with camera switching and a small self view. |
| **Direct calls for family** | People in your Family folder (or anyone you switch on) connect straight to you, so calls and video use no relay data. Everyone else stays on the relay and never sees your IP address. |
| **Smoother calls** | An adaptive buffer, loss repair and higher quality sound; calls ring faster and reconnect when you change networks. |
| **Screen share** | Share your screen from Huginn in a voice room. |
| **Group text and call from the list** | Hold a contact, tick a few more, and start a group text or a group call. Group messages show how many have read them. |
| **Messenger touches** | Reactions, swipe to reply, reply from the notification, edit with "(edited)", emoji picker, typing dots, active status, a photo gallery and search in each chat. |
| **Desktop like it should be** | Huginn on a PC types in a normal box at the bottom of the chat, with Ctrl+K, threads, pins, drag and drop, @mentions and a New messages line. |
| **Kids' TV** | Clearer calls with echo cancelling and noise removal, a microphone control on the TV, full-screen photos, the show pauses for a call and picks back up after, storage and Clear history from the parent's phone, and people who haven't accepted yet show as waiting. |
| **Battery saver** | Muninn uses far less battery with the screen off while still getting messages and calls. Turn it off for maximum speed. |
| **Windows** | No more antivirus false alarms from the updater, a cleaner folder, updates checked before they install, and it runs on Linux through Proton and Wine. |
| **Linux and SteamOS** | The Flatpak is back. |

## Also in 0.7

| | |
|---|---|
| **Faster messages** | Tor is gone. Messages, photos and voice clips go straight to the relays again, still end-to-end encrypted, and arrive right away. |
| **Calls that work anywhere** | Calls over cell service connect quickly and carry sound both ways. |
| **Group calls and voice rooms** | Ring a whole group, or make a channel a voice room people drop in and out of. |
| **Kids' TV calls** | Kids call the people a parent approved, and the TV rings over whatever is playing when a parent calls, even right after it restarts. Message notifications show too. |
| **Louder TV microphone** | The TV's microphone is boosted for voice clips, dictation and calls, with a level slider on the parent's phone. |
| **Invite family to the TV** | From the phone, a parent can invite another adult to talk with the kids on the TV, and add people to family groups. |
| **TV updates itself** | Muninn on the TV checks for updates daily and installs them with the remote, or when a parent taps Update TV. |
| **Share the app** | Someone who scans your code with a regular camera gets a download page, and "Share the app" sends Muninn straight to a nearby phone. |
| **Lock first** | Opening the app never shows a chat before the lock screen, and the recent apps view stays blank. |
| **Rename anywhere** | Rename people from the Contacts tab or by holding a chat. |
| **Tidy Windows folder** | The Windows zip holds just Huginn.exe and a lib folder, and the message sound crash is fixed. |
| **Sharing stays home** | Huginn's Wi-Fi sharing listens only on your home network. |

## Also in 0.6

| | |
|---|---|
| **Group chats and channels** | Groups with their own channels, a member list and admins. Every message is encrypted to each member separately. |
| **Contact folders** | Sort people into Family, Work, Kindred or any folder you name. |
| **Encrypted calls** | One-to-one voice calls on both apps, with a call history. |
| **Kids' TV** | Muninn on a Google TV becomes a simple screen for children to reach Mom and Dad: big buttons, quick messages, voice clips and dictation. No sign-in. A parent pairs it once from their phone, edits its quick messages, and is the only one who can unlock it. |
| **Windows** | Huginn as a portable zip: unzip anywhere and run, nothing to install. |
| **New layouts** | Huginn, and Muninn on tablets, use a Discord-style rail of groups, channels and members. |

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

| Button | What it does |
|---|---|
| D-pad | Move the highlight, from the last thing highlighted or clicked |
| A | Open, choose, or write a message |
| B | Back |
| Y (hold) | Record a voice message |
| LB | Next section (hold LB and press D-pad left or right to go back or forward) |
| LT | Options: message options, Send a picture, Call and more |
| Left stick | Scroll |
| Right stick | Move the pointer (it hides after a few seconds) |
| RB or R3 (right stick click) | Click |
| RT | Right click |
| Menu | Settings |
| L3 + R3 (hold) | Panic lock |

The pattern lock works with the D-pad: move to a dot, A starts there, move on through the dots, A again when done. B starts over.

A mouse and keyboard work too (Tab and Shift+Tab switch sections, Page Up and Page Down scroll).

## What's next

1.0 once everything has been tested on every device.

## Versions

One dot only, read as a decimal: 0.5, 0.6, 0.7, then 1.0 when it's rock solid. The `VERSION` file on main decides releases for both apps: change it and CI builds Huginn for Linux, SteamOS and Windows and Muninn, tests them all, and publishes them together.

## Building

```
tools/get-fonts.sh
flatpak-builder --user --install --force-clean build packaging/io.github.ravenor907.Huginn.json
```

Windows builds with CMake, Qt 6.10 and vcpkg (see the Windows job in `.github/workflows/build.yml`). Muninn builds with Gradle from `android/`.

## License

GPL-3.0. Fonts: Cinzel, Barlow and Noto Sans Runic, under the SIL Open Font License.

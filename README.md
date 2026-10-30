<p align="center">
  <img src="docs/banner.png" width="100%" alt="Huginn and Muninn">
</p>

<p align="center">
  <a href="https://github.com/Ravenor907/Huginn-Muninn/releases"><img src="https://img.shields.io/github/v/release/Ravenor907/Huginn-Muninn?include_prereleases&label=release&style=for-the-badge&color=C2A25E&labelColor=101112" alt="Release"></a>
  <a href="https://github.com/Ravenor907/Huginn-Muninn/actions/workflows/build.yml"><img src="https://img.shields.io/github/actions/workflow/status/Ravenor907/Huginn-Muninn/build.yml?branch=main&label=build&style=for-the-badge&labelColor=101112" alt="Build"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-9C978D?style=for-the-badge&labelColor=101112" alt="GPL-3.0"></a>
</p>

<h3 align="center">Private messaging for the people you care about.</h3>

<p align="center">
  Chats, calls, video and file sharing, end-to-end encrypted.<br>
  No accounts. No phone numbers. No ads. No tracking. Nothing to sign up for.
</p>

<p align="center">
  <a href="https://github.com/Ravenor907/Huginn-Muninn/releases/latest/download/Muninn.apk"><b>Muninn for Android and Google TV</b></a> &nbsp;·&nbsp;
  <a href="https://github.com/Ravenor907/Huginn-Muninn/releases/latest/download/Huginn-Windows.zip"><b>Huginn for Windows</b></a> &nbsp;·&nbsp;
  <a href="#install"><b>Huginn for Linux</b></a>
</p>

Odin sends two ravens out every morning: Huginn (thought) and Muninn (memory). They fly the world and bring back what he needs to know.

These two carry your messages. **Muninn** runs on Android phones, tablets and Google TV. **Huginn** runs on Windows and Linux computers. Together they are a private place for your family and friends to keep in touch, with nobody in the middle who can read it.

## Keep in touch

- **Chats and groups:** messages, photos, albums, voice and video messages, polls, reactions and replies. Groups have their own channels.
- **Calls:** voice and video calls, group calls, and voice rooms people drop in and out of. Group video works for up to 4 people.
- **Files:** send files to someone across the room or across the country. They go straight from your device to theirs.
- **Easy to add the people you trust:** share your code or QR, send an invite link that works once, tap phones together, or let someone you both know introduce you.
- **All your screens:** your phone, a tablet or second phone linked to it, and your computer.
- **Kids' TV:** turn a Google TV into a simple screen your kids use to reach the people you approve.

## Private by default

<p align="center"><img src="docs/panels/09-private.png" width="100%" alt="How it stays private"></p>

- **Only the people talking can read or hear it.** Every message, photo, voice note, call and file is end-to-end encrypted, with a fresh key for every message.
- **No account.** You are a key made on your own device, not a phone number or email address. Strangers without your code can't reach you.
- **Works with or without the internet.** Close by, your devices talk over Wi-Fi and Bluetooth. Apart, sealed messages travel through free public relays that can't see who sent them or what they say.
- **Your address stays yours.** Calls with people you haven't chosen to trust go through a relay, so they never see your IP address.
- **Locked on your device.** A pattern or password opens the app, and everything it keeps is encrypted. Panic lock locks it all in a moment.
- **Messages don't stay forever.** Chats delete messages, photos, voice clips, videos and files after 7 days on every device, and backups leave them out. Family and Work groups, and people in your Family or Work folder, can keep them up to 30 days.
- **Free and open.** GPL-3.0, no ads, no analytics, no tracking.

## On every screen you have

<p align="center"><img src="docs/panels/01-muninn-phone.png" width="100%" alt="Muninn on your phone: chats, calls and file sharing"></p>
<p align="center"><img src="docs/panels/02-muninn-tablet.png" width="100%" alt="Muninn on tablets: groups, channels and voice rooms"></p>
<p align="center"><img src="docs/panels/03-huginn-desktop.png" width="100%" alt="Huginn on Windows and Linux"></p>

## Easy on the battery

<p align="center"><img src="docs/panels/08-battery.png" width="100%" alt="Choose how often Muninn checks for messages, quiet hours and a battery report"></p>

## Kids' TV

Muninn can also turn a Google TV into a simple screen your kids can use to reach Mom and Dad. No sign-in, no strangers, no ads: big buttons for a TV remote, and only the people a parent approves. Parents set it up and manage it from their own phones.

<p align="center"><img src="docs/panels/05-muninn-tv.png" width="100%" alt="Muninn on Google TV: kids pick who they want to talk to"></p>
<p align="center"><img src="docs/panels/06-kids-tv-buttons.png" width="100%" alt="One button to call a parent, talk, or send a quick message"></p>
<p align="center"><img src="docs/panels/07-parents-control.png" width="100%" alt="Parents manage the TV from their phone: quick messages, who they can reach, apps"></p>

## What's new in 0.94

| | |
|---|---|
| **Messages on time, without Google** | In the background Muninn now keeps one light connection to the healthiest relay, checked about every 5 minutes and lined up with the phone's other wake-ups, and swaps to another relay at once if it stops answering. It never falls back to checking every 15 minutes or every hour unless you pick that in Settings. |
| **Always right away** | Your Family folder to start with, and anyone you add (Settings › Notifications, or a chat's menu). A low battery, quiet hours and checking less never hold their messages back. During quiet hours everyone else's messages arrive without a sound. |
| **Replies come at once** | For about 5 minutes after you send or get a message, Muninn stays fully connected so the conversation flows. |
| **Catches up instantly** | When you unlock the phone, when it joins a network, and when you open Muninn. |
| **Honest ticks** | "Delivered" means it reached their phone. Until then it says "Waiting for Anna's phone". In groups, the message's menu shows whose phones have it and whose it's waiting for. |
| **No more hourly floor** | A very low battery or a long quiet spell now checks the one connection every 10 minutes instead of dropping to hourly checks. |
| **Unrestricted background use** | The Finish setting up card explains why it helps and asks for it in one tap. |
| **Battery report shows the connection's cost** | Time on the light connection, checks and wake-ups, reconnects, and a rough share of the battery. |
| **Messages disappear after 7 days** | Every chat, 1:1 and group, deletes messages older than 7 days on every phone and computer: text, photos, voice clips, videos, files, polls and previews. Encrypted backups leave them out too. Chats you already have get the limit the first time 0.94 starts, and the chat list says so once. |
| **Family and Work keep longer** | A group's admins pick its type: Standard, Family or Work. Family and Work groups can keep messages up to 30 days, the same for everyone. In a 1:1 chat with someone in your Family or Work folder you can pick up to 30 days; if you two pick differently, the shorter one counts. |
| **Older apps** | Someone on an older version keeps messages until they update; the chat's info says so. Your side still deletes them on time. |
| **Huginn looks and feels like Discord on a PC** | Drag the edges between panels to resize them, hide the list or the member list, compact or cozy messages with their own text size, and Dark, True black or Light themes with any accent color (Settings › Appearance). |
| **Who's around** | A group's member list shows who's online, idle or in a voice room. Click a name for their profile card: Message, Call, Video, a nickname, a note only you see, and their folders. |
| **Voice panel** | In a voice room on a PC, who you're with, Mute, Deafen and Leave sit at the foot of the list. Push to talk: hold a key you choose (Settings › Calls). |
| **Unread and mentions** | Groups and channels show a dot for anything new and a number for @mentions of you. Fold a group's Text or Voice category away. |
| **Keyboard shortcuts** | Ctrl+/ lists them all. |
| **Huginn catches up** | On a PC: albums, video messages, polls, link previews and the Shared page; invite links, introductions, the Family group offer and the 4 matching emoji; files of up to 4 GB over the internet; and Linked devices, both as your phone's companion and as the phone others link to. |
| **Signed updates** | Every download is signed with the project's own key, and both apps refuse an update that isn't. Huginn.exe also gets extra Windows protections (Control Flow Guard, CET). |

Every earlier release is in [What changed](docs/CHANGELOG.md).

## Install

Both apps come out together with the same version number, and both update themselves when they open.

**Muninn on Android and Google TV:** open this link on the device and install it:
https://github.com/Ravenor907/Huginn-Muninn/releases/latest/download/Muninn.apk

**Huginn on Windows:** download [Huginn-Windows.zip](https://github.com/Ravenor907/Huginn-Muninn/releases/latest/download/Huginn-Windows.zip), unzip it anywhere and run `Huginn.exe`. Nothing to install.

**Huginn on Linux** (in a terminal):

```
cd ~/Downloads && curl -LO https://github.com/Ravenor907/Huginn-Muninn/releases/latest/download/Huginn.flatpak && flatpak install --user -y Huginn.flatpak
```

Every download is signed with the project's own release key, and both apps refuse an update that isn't. Windows may still warn about an unknown publisher the first time: choose **More info**, then **Run anyway**. How to check a download yourself: [Releases and how to check them](docs/releases.md).

Huginn also works with a game controller or TV remote: see [Using Huginn with a controller](docs/controller.md).

## For developers

Versions use one dot, read as a decimal (0.92, 0.93, then 1.0). The `VERSION` file on main decides releases for both apps: change it and CI builds, tests and publishes them together. 1.0 comes once everything has been tested on every device.

```
tools/get-fonts.sh
flatpak-builder --user --install --force-clean build packaging/io.github.ravenor907.Huginn.json
```

Windows builds with CMake, Qt 6.10 and vcpkg (see the Windows job in `.github/workflows/build.yml`). Muninn builds with Gradle from `android/`. The wire protocol is in [docs/protocol-0.6.md](docs/protocol-0.6.md). Found a security problem? See [SECURITY.md](SECURITY.md).

## Privacy

Huginn and Muninn collect nothing about you: no accounts, no analytics, no ads, no tracking. This program will not transfer any information to other networked systems unless specifically requested by the user or the person installing or operating it. What leaves your device, and only to do what you asked:

- **Messages, photos and voice clips** are end-to-end encrypted and sealed before they reach the public Nostr relays that carry them. Relays see only a sealed mailbox id and the IP address that connects to them.
- **How long messages stay**: 7 days, or up to 30 in Family and Work chats, then they are deleted on every device. Each app tells the other how long it keeps a chat, next to the list of features it understands.
- **Calls** go through the Cloudflare call relay ([Cloudflare privacy policy](https://www.cloudflare.com/privacypolicy/)), or straight between devices for contacts you trust. Audio and video are end-to-end encrypted. The relay login service keeps no logs.
- **Updates** are downloaded from GitHub ([GitHub privacy statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement)).
- **Files over the internet** go straight between the two devices, end-to-end encrypted. To find a direct path, each device asks a public STUN server (Cloudflare's) for its own internet address; the file itself never goes through it. Small files that can't go straight travel in sealed pieces through the Nostr relays.
- **Link previews** are made on the sender's phone, which fetches the page, so that site sees the sender's IP address. Turn them off in Settings › Privacy.
- **Nearby sharing** stays on your home Wi-Fi or Bluetooth.

## License

GPL-3.0. Fonts: Cinzel, Barlow and Noto Sans Runic, under the SIL Open Font License.

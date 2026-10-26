<p align="center">
  <img src="packaging/io.github.ravenor907.Huginn-256.png" width="128" alt="Huginn">
</p>

<h1 align="center">Huginn</h1>

<p align="center">A file explorer for SteamOS Game Mode. Built for a controller, a 7 inch screen and the couch.</p>

<p align="center">
  <a href="https://github.com/Ravenor907/Huginn/releases"><img src="https://img.shields.io/github/v/release/Ravenor907/Huginn?include_prereleases&label=release&style=for-the-badge&color=C2A25E&labelColor=101112" alt="Release"></a>
  <a href="https://github.com/Ravenor907/Huginn/actions/workflows/build.yml"><img src="https://img.shields.io/github/actions/workflow/status/Ravenor907/Huginn/build.yml?branch=main&label=build&style=for-the-badge&labelColor=101112" alt="Build"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-9C978D?style=for-the-badge&labelColor=101112" alt="GPL-3.0"></a>
</p>

Odin's raven Huginn flies out, looks around and comes back with what you were after. This one does that with your files, without sending you to Desktop Mode.

## What it does

| | |
|---|---|
| **Two panes, one thumb** | Browse with the d-pad, select with X, hold Y for the action wheel: copy to, move to, rename, extract to, delete, zip, new folder, send to phone, undo. |
| **Move to…** | Send files straight to a ROM folder, a game's folder (for mods), the SD card or a recent spot, without setting up the other pane. |
| **Storage** | What's using space on each drive, biggest first. Clear shader caches or empty the trash in one press. |
| **Steam folders by name** | `compatdata/1245620` becomes Elden Ring, with its saves, Proton prefix and shader cache side by side. Back up saves, clear shader cache, reset a broken prefix. |
| **Phone transfer** | Scan the QR code, type the PIN, drop files from your phone. Pick files on the handheld and they show up on the phone. |
| **USB tools** | Install or update Ventoy, copy ISOs onto it, write one image, format a stick. The drive code comes from [Handheld Forge](https://github.com/Ravenor907/Handheld-Forge). |
| **Look inside archives** | zip, 7z and tar, browsed before extracting. Pick what you want or take it all. |
| **Previews and quick edits** | Screenshots full screen with a filmstrip. Config files and logs as text, with one-line edits through Steam's keyboard (a .bak copy is kept). |
| **Network shares** | SFTP shares through the same plumbing Dolphin uses. Windows shares arrive in 0.2. |
| **Steam's keyboard** | Renaming or searching opens Steam's own keyboard. No new keyboard to learn. |
| **TV mode** | Docked to a big screen, everything grows to couch size on its own. |

## Install

One file, and it never goes stale: Huginn checks for updates every time it starts and updates itself, so whenever you install it you end up on the newest version. In Desktop Mode, open Konsole and run:

```
cd ~/Downloads && curl -LO https://github.com/Ravenor907/Huginn/releases/latest/download/Huginn.flatpak && flatpak install --user -y Huginn.flatpak
```

Add it to Steam (Add a Non-Steam Game › Huginn) to launch it from Game Mode.

### Muninn, the phone companion

Muninn is Huginn's twin raven, for Android 8 or newer: send and receive files with Huginn and LocalSend, chat with everyone nearby, browse files, and make USB drives from the phone. On the phone, open this link and install it:

https://github.com/Ravenor907/Huginn/releases/latest/download/Muninn.apk

Muninn offers each update when it opens. Both apps ship together, in the same release, with the same version number.

### The one-time USB setup

Installing Ventoy and writing images open a USB stick byte by byte, which Linux only lets an admin do. Huginn's USB Tools has a **Set up** button for Desktop Mode: it asks for your password once and adds a polkit rule that lets your user open **USB drives only**. Game Mode never asks after that. Settings › Safety removes it again.

Never set a password on SteamOS? Open Konsole and run `passwd` first.

Copying ISOs, formatting and ejecting never need the setup.

## Controls

| Button | Browser |
|---|---|
| A | Open |
| B | Up a folder, clear selection or search |
| X | Select |
| Y (hold) | Action wheel, release to pick |
| LB / RB | Switch pane |
| LT / RT | Page up / down |
| View | Search this folder |
| Left stick click | Sort by name, newest or size |
| Menu | Settings |

A keyboard works too in Desktop Mode: arrows, Enter, Escape, Space, Tab and F10.

## Updates and versions

Two numbers only: 0.1, 0.2, then 1.0 when it's rock solid. The `VERSION` file on main decides releases for both apps: change it and CI builds Huginn and Muninn, checks both, and publishes them together. Test builds are the next version plus a test number (0.2 Test 3) and only reach you on the Testing channel, which can roll back to stable from Settings.

## Building

```
tools/get-fonts.sh
flatpak-builder --user --install --force-clean build packaging/io.github.ravenor907.Huginn.json
```

CI builds the Flatpak, runs the drive helper tests, and opens every screen with `huginn --tour` to catch broken screens before a release.

## License

GPL-3.0. Fonts: Cinzel, Barlow and Noto Sans Runic, under the SIL Open Font License.

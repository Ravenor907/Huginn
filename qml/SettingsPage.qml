import QtQuick
import Huginn

// Settings: sections in the middle column, their rows in the main column.
Rectangle {
    id: page
    color: Theme.ground
    // False while the controller focus is on the rail, so only one thing looks focused.
    readonly property bool active: !Ui.main || Ui.main.focusZone === "content"
    property int section: 0
    property int row: 0
    property bool inSection: false
    // Another page opens a folder by name ({ section: "Privacy", row: 0 }), or by number.
    function setArgs(a) {
        if (a.section === undefined) return
        const i = typeof a.section === "string" ? sections.indexOf(a.section) : a.section
        if (i < 0) return
        section = i; inSection = true; row = a.row || 0
    }

    // The same folders as Muninn's Settings (Notifications is Muninn's only: Huginn has nothing for it).
    readonly property var sections: ["Privacy", "Lock", "Calls", "Nearby and sharing", "Display and accessibility", "Backup and erase", "About and updates"]
    // The folders, then Exit Huginn at the bottom of the list (A on it closes Huginn).
    readonly property var navItems: sections.concat(["Exit Huginn"])
    readonly property bool onExit: section === sections.length
    readonly property var prompts: inSection ? [{ key: "a", label: "Change" }, { key: "b", label: "Folders" }]
                                             : [{ key: "a", label: onExit ? "Exit" : "Open" }, { key: "b", label: "Back" }]
    readonly property string status: "Changes save as you go"

    // Each row: { kind: "segmented"|"toggle"|"accent"|"button"|"info", title, note, ... }
    readonly property var rows: {
        switch (sections[section]) {
        case "Privacy": return [
            { kind: "button", key: "contacts", title: "Contacts", note: "Everyone you're in touch with or met close by. Block, unblock or remove people here", label: "Open" },
            { kind: "button", key: "linked", title: "Linked devices", note: Linked.phone.key ? "Showing " + Linked.phone.name + "'s chats" : Linked.companions.length ? Linked.companions.length + " linked" : "Show your phone's chats here, or this computer's on another device", label: "Open" },
            { kind: "segmented", key: "deleteAfter", chat: true, title: "Delete messages after", note: "Applies to both sides of every chat on this computer",
              options: [{ value: "0", label: "Never" }, { value: "86400000", label: "Day" }, { value: "604800000", label: "Week" }, { value: "2592000000", label: "Month" }] },
            { kind: "toggle", key: "readReceipts", chat: true, title: "Send read receipts", note: "Off: people don't see when you've read their messages. Delivered still shows" },
            { kind: "toggle", key: "showPreview", chat: true, title: "Show message text in notifications", note: "Off: notifications only say “New message”" },
            { kind: "toggle", key: "activeStatus", chat: true, title: "Active status", note: "Contacts see a green dot while Huginn is open. Off: nobody sees yours, and you don't see theirs" }
        ]
        case "Lock": return [
            { kind: "button", key: "changeLock", title: "Chat lock", note: "Chat opens with your " + (Vault.usesPattern ? "pattern" : "password") + ". Everything it keeps is encrypted with it", label: "Change", enabled: Vault.exists },
            { kind: "toggle", key: "panicLock", vault: true, title: "Panic lock (hold L3 and R3)", note: "Hold both stick clicks for a second anywhere in Huginn: Chat locks at once and notifications hide message text until you unlock" },
            { kind: "toggle", key: "lockOnLeave", vault: true, title: "Lock when I leave Huginn", note: "Chat locks the moment you switch to Steam or another app, or the handheld sleeps" }
        ]
        case "Calls": return [
            { kind: "toggle", key: "noiseRemoval", title: "Noise removal", note: Settings.noiseRemoval
                ? "Background noise is taken out of your voice. Turn it off if people say you sound quiet. Changes start with your next call"
                : "Off: background sound comes through with your voice. Echo is still removed. Changes start with your next call" }
        ]
        case "Nearby and sharing": return [
            { kind: "button", key: "chatName", title: "Your name: " + Chat.username, note: "What people see instead of your real name", label: "Change" },
            { kind: "toggle", key: "visibleNearby", chat: true, title: "Visible to people nearby", note: "Off: no Nearby room, and strangers close by or on the same Wi-Fi can't see your name or message you. Your contacts still can. File sharing has its own switch" },
            { kind: "toggle", key: "nearbyOn", chat: true, hide: !Chat.visibleNearby, title: "Nearby chat", note: "Everyone close by or on the same Wi-Fi with Huginn or Muninn can see and message you" },
            { kind: "toggle", key: "keepNearby", chat: true, hide: !Chat.visibleNearby, title: "Keep Nearby messages", note: "Off: they clear when Huginn closes. Encrypted when kept" },
            { kind: "toggle", key: "nearbySharing", title: "Nearby sharing (LocalSend)", note: "Muninn and the free LocalSend app on phones and PCs can find this device. You accept every transfer on screen" }
        ].filter(r => !r.hide)
        case "Display and accessibility": return [
            { kind: "segmented", key: "layout", title: "Layout", note: "Desktop is smaller, for a mouse and keyboard. Automatic uses it in a wide window when no controller is in use",
              options: [{ value: "handheld", label: "Handheld" }, { value: "desktop", label: "Desktop" }, { value: "auto", label: "Automatic" }] },
            { kind: "segmented", key: "uiSize", title: "Size", note: "Text and buttons. Normal already fits the screen; go bigger if it's hard to read",
              options: [{ value: "85", label: "Smaller" }, { value: "100", label: "Normal" }, { value: "120", label: "Larger" }, { value: "140", label: "Largest" }] },
            { kind: "segmented", key: "textSize", title: "Text size", note: "Just the text, on top of Size",
              options: [{ value: "100", label: "Normal" }, { value: "115", label: "Large" }, { value: "130", label: "Larger" }, { value: "150", label: "Largest" }] },
            { kind: "toggle", key: "highContrast", title: "High contrast", note: "White text on black, stronger outlines and focus" },
            { kind: "toggle", key: "colorBlind", title: "Color-blind friendly", note: "Warnings in amber instead of red; status always says it in words" },
            { kind: "toggle", key: "reduceMotion", title: "Reduce motion", note: "No sliding or fading" },
            { kind: "accent", key: "accent", title: "Accent", note: "Highlight color for focus and selected items" }
        ]
        case "Backup and erase": return [
            { kind: "button", key: "backup", title: "Back up chats", note: "An encrypted copy in Documents, opened with a backup password. Use the backup on one device at a time.", label: Vault.busy ? "Working" : "Back up", enabled: Vault.exists && !Vault.busy },
            { kind: "button", key: "restore", title: "Restore from backup", note: "Replaces everything in Chat on this computer with a backup from Documents", label: "Restore", enabled: Vault.exists && !Vault.busy },
            { kind: "button", key: "deleteMessages", title: "Delete all messages", note: "Every conversation on this computer. Contacts stay", label: "Delete" },
            { kind: "button", key: "eraseChat", title: "Erase everything in Chat", note: "Messages, photos, voice, contacts, keys and the lock. Huginn closes", label: "Erase" }
        ]
        case "About and updates": return [
            { kind: "button", key: "check", title: Updater.status, note: "Huginn " + System.version + ". Updates install by themselves when Huginn starts.",
              label: Updater.available ? "Install " + Updater.latest : "Check now", enabled: !Updater.busy },
            { kind: "info", title: "Huginn " + System.version, note: "Private chat and file sharing, built for a controller. Made by Ravenor907." },
            { kind: "info", title: "License", note: "GPL-3.0. Fonts: Cinzel, Barlow and Noto Sans Runic under the SIL Open Font License." }
        ]
        default: return [
            { kind: "info", title: "Exit Huginn", note: "Press A to close Huginn. In Game Mode you land back in Steam." }
        ]
        }
    }

    // ------------------------------------------------------------- middle column: sections
    Rectangle {
        id: middle
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        width: Theme.px(270)
        color: Theme.column
        PageHeader { id: header; width: parent.width; title: "Settings" }
        Column {
            id: nav
            anchors.top: header.bottom
            anchors.topMargin: Theme.px(10)
            x: Theme.px(10)
            width: parent.width - Theme.px(20)
            spacing: Theme.px(3)
            Repeater {
                model: page.navItems
                Rectangle {
                    readonly property bool on: index === page.section
                    readonly property bool exit: index === page.sections.length
                    width: nav.width; height: Theme.px(46); radius: Theme.px(10)
                    color: on ? Theme.tile : navHover.hovered ? Theme.raised : "transparent"
                    border.width: on && page.active && !page.inSection ? Theme.focusWidth : 0
                    border.color: Theme.accent
                    Accessible.role: Accessible.PageTab
                    Accessible.name: modelData
                    Row {
                        anchors.left: parent.left; anchors.leftMargin: Theme.px(14); anchors.verticalCenter: parent.verticalCenter
                        spacing: Theme.px(14)
                        Rectangle { anchors.verticalCenter: parent.verticalCenter; width: Theme.px(7); height: width; rotation: 45; color: parent.parent.on ? Theme.accent : "transparent"; border.width: parent.parent.on ? 0 : 1; border.color: Theme.ornament }
                        Text { text: modelData; color: parent.parent.exit ? Theme.textMuted : parent.parent.on ? Theme.text : Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(17); font.weight: parent.parent.on ? Font.DemiBold : Font.Normal }
                    }
                    HoverHandler { id: navHover }
                    TapHandler { onTapped: { if (parent.exit) page.press("exit"); else { page.section = index; page.row = 0; page.inSection = false } } }
                }
            }
        }
        Text {
            anchors.bottom: parent.bottom
            anchors.bottomMargin: Theme.px(14)
            x: Theme.px(20)
            text: "Huginn " + System.version
            color: Theme.textMuted
            font.family: Theme.body; font.pixelSize: Theme.fs(14)
        }
    }

    PageHeader {
        id: rowsHead
        anchors.left: middle.right
        anchors.right: parent.right
        carved: false
        title: page.navItems[page.section] || ""
    }

    // ------------------------------------------------------------- main column: rows
    // The rows scroll so the focused one is always on screen (Chat has more than fit).
    Flickable {
        id: rowsView
        anchors.top: rowsHead.bottom
        anchors.topMargin: Theme.px(14)
        anchors.left: middle.right
        anchors.leftMargin: Theme.px(20)
        anchors.right: parent.right
        anchors.rightMargin: Theme.px(20)
        anchors.bottom: parent.bottom
        anchors.bottomMargin: Theme.px(10)
        contentWidth: width
        contentHeight: rowsCol.implicitHeight
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        Column {
            id: rowsCol
            width: rowsView.width
            spacing: Theme.px(10)
            Repeater {
                id: rowRepeater
                model: page.rows
                Rectangle {
                    readonly property bool focused: page.active && page.inSection && page.row === index
                    width: parent.width
                    height: Math.max(Theme.px(72), texts.implicitHeight + Theme.px(24))
                    radius: Theme.radius
                    color: focused ? Theme.focusFill : Theme.panel
                    border.width: focused ? Theme.focusWidth : 1
                    border.color: focused ? Theme.accent : Theme.line
                    Column {
                        id: texts
                        anchors.left: parent.left
                        anchors.leftMargin: Theme.px(20)
                        anchors.right: control.left
                        anchors.rightMargin: Theme.px(20)
                        anchors.verticalCenter: parent.verticalCenter
                        spacing: Theme.px(2)
                        Text { width: parent.width; wrapMode: Text.WordWrap; text: modelData.title; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(19); font.weight: Font.DemiBold }
                        Text { width: parent.width; wrapMode: Text.WordWrap; text: modelData.note; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(14) }
                    }
                    Item {
                        id: control
                        anchors.right: parent.right
                        anchors.rightMargin: Theme.px(20)
                        anchors.verticalCenter: parent.verticalCenter
                        width: modelData.kind === "segmented" ? segCtl.implicitWidth
                             : modelData.kind === "toggle" ? toggleCtl.implicitWidth
                             : modelData.kind === "accent" ? accentCtl.implicitWidth
                             : modelData.kind === "button" ? buttonCtl.implicitWidth : 0
                        height: Theme.px(44)
                        Segmented {
                            id: segCtl
                            anchors.right: parent.right
                            anchors.verticalCenter: parent.verticalCenter
                            visible: modelData.kind === "segmented"
                            options: modelData.options || []
                            value: modelData.kind === "segmented" ? (modelData.chat ? String(Chat[modelData.key]) : Settings[modelData.key]) : ""
                            onPicked: v => { if (modelData.chat) Chat[modelData.key] = Number(v); else Settings[modelData.key] = v }
                        }
                        ToggleSwitch {
                            id: toggleCtl
                            anchors.right: parent.right
                            anchors.verticalCenter: parent.verticalCenter
                            visible: modelData.kind === "toggle"
                            checked: modelData.kind === "toggle" ? page.source(modelData)[modelData.key] : false
                            onToggled: { const o = page.source(modelData); o[modelData.key] = !o[modelData.key] }
                        }
                        Row {
                            id: accentCtl
                            anchors.right: parent.right
                            anchors.verticalCenter: parent.verticalCenter
                            visible: modelData.kind === "accent"
                            spacing: Theme.px(10)
                            Repeater {
                                model: [{ v: "gold", n: "Gold" }, { v: "iron", n: "Iron" }, { v: "copper", n: "Copper" }]
                                Rectangle {
                                    width: Theme.px(44); height: width; radius: Theme.radius
                                    color: Theme.accents[modelData.v]
                                    border.width: Settings.accent === modelData.v ? 2 : 1
                                    border.color: Settings.accent === modelData.v ? Theme.text : Theme.lineMuted
                                    TapHandler { onTapped: Settings.accent = modelData.v }
                                }
                            }
                        }
                        HButton {
                            id: buttonCtl
                            anchors.right: parent.right
                            anchors.verticalCenter: parent.verticalCenter
                            visible: modelData.kind === "button"
                            implicitHeight: Theme.px(44)
                            text: modelData.label || ""
                            enabled: modelData.enabled !== false
                            onClicked: page.press(modelData.key)
                        }
                    }
                    TapHandler { onTapped: { page.inSection = true; page.row = index } }
                }
            }
        }
    }
    function showRow() {
        const item = rowRepeater.itemAt(row)
        if (!item) return
        const top = row === 0 ? 0 : item.y - Theme.px(10)
        const bottom = item.y + item.height + Theme.px(10)
        if (top < rowsView.contentY) rowsView.contentY = top
        else if (bottom > rowsView.contentY + rowsView.height) rowsView.contentY = Math.min(bottom - rowsView.height, Math.max(0, rowsView.contentHeight - rowsView.height))
    }
    onRowChanged: Qt.callLater(showRow)
    onSectionChanged: rowsView.contentY = 0

    // Anything that closes or restarts Huginn checks first that it won't cut off a job.
    function running() {
        if (LocalSend.busy) return LocalSend.status + " Closing now stops it partway."
        return ""
    }
    // Where a toggle's value lives.
    function source(r) { return r.vault ? Vault : r.chat ? Chat : Settings }

    // ------------------------------------------------------------- chat backup
    function startBackup() {
        if (!Vault.exists || Vault.screenLocked) { Ui.say("Open Chat and unlock it first.", true); return }
        Ui.ask("Backup password", "At least 8 characters. You need it to restore, and it can't be recovered", "", "Next", t => {
            if (t.length < 8) { Ui.say("The backup password needs at least 8 characters.", true); return }
            Ui.ask("Type it again", "To be sure", "", "Back up", t2 => {
                if (t2 !== t) { Ui.say("They didn't match. Nothing was saved.", true); return }
                Ui.say("Backing up your chats...")
                Vault.backup(t)
            }, true)
        }, true)
    }
    function startRestore() {
        if (!Vault.exists || Vault.screenLocked) { Ui.say("Open Chat and unlock it first.", true); return }
        const list = Vault.backups()
        if (!list.length) { Ui.say("No backups in Documents. Put a .mbk file there first.", true); return }
        const apps = {}
        list.forEach(e => apps[e.url] = e.app)
        Ui.pickFrom("Restore which backup?", [{ header: "In Documents", items: list }], url => {
            if (apps[url] === "muninn") { Ui.say("That's a Muninn backup. Restore it in Muninn.", true); return }
            Ui.ask("Backup password", "The one you chose when you backed up", "", "Open", pw => {
                Ui.say("Opening the backup...")
                Vault.openBackup(url, pw)
            }, true)
        })
    }
    Connections {
        target: Vault
        function onDone(what, ok, message) {
            if (what === "open" && ok)
                Ui.sure("Replace everything in Chat?",
                        message + " Every message, photo, voice message, contact and key on this computer is replaced by it. Your chat lock stays the same. Use the backup on one device at a time. Huginn restarts after.",
                        "Hold to restore", () => Vault.restoreOpened(), true, true, () => Vault.forgetOpened())
            else
                Ui.say(message, !ok)
        }
    }

    function press(key) {
        const job = running()
        if (key === "exit") Ui.main.requestExit()
        else if (key === "chatName") Ui.ask("Your chat name", "Pick a name, not your real one", Chat.username, "Save", t => Chat.username = t)
        else if (key === "contacts") Ui.openPage("contacts", {})
        else if (key === "linked") PeopleActions.linkedDevices()
        else if (key === "backup") startBackup()
        else if (key === "restore") startRestore()
        else if (key === "changeLock") Ui.openPage("chat", { changeLock: true })
        else if (key === "deleteMessages" && !Chat.ready) Ui.say("Open Chat and unlock it first.", true)
        else if (key === "deleteMessages") Ui.sure("Delete all messages?", "Every conversation on this computer is deleted. Contacts stay.", "Hold to delete", () => { Chat.deleteAll(); Ui.say("All messages deleted.") }, true, true)
        else if (key === "eraseChat") Ui.sure("Erase everything in Chat?", "Messages, photos, voice, contacts, keys and the lock are deleted for good, and Huginn closes. Your files and other settings stay.",
                                              "Hold to erase", () => { Vault.eraseAll(); Qt.quit() }, true, true)
        else if (key === "check") {
            if (!Updater.available) Updater.check()
            else if (job) Ui.sure("Update now?", "Huginn restarts to update. " + job, "Update anyway", () => Updater.install(), true, true)
            else Updater.install()
        }
    }

    function change(dir) {
        const r = rows[row]
        if (!r) return
        if (r.kind === "toggle") { const o = source(r); o[r.key] = !o[r.key] }
        else if (r.kind === "segmented") {
            const opts = r.options
            let i = opts.findIndex(o => o.value === (r.chat ? String(Chat[r.key]) : Settings[r.key]))
            i = dir === 0 ? (i + 1) % opts.length : Math.max(0, Math.min(opts.length - 1, i + dir))
            if (r.chat) Chat[r.key] = Number(opts[i].value); else Settings[r.key] = opts[i].value
        } else if (r.kind === "accent") {
            const order = ["gold", "iron", "copper"]
            let i = order.indexOf(Settings.accent)
            i = dir === 0 ? (i + 1) % 3 : Math.max(0, Math.min(2, i + dir))
            Settings.accent = order[i]
        } else if (r.kind === "button" && dir === 0 && r.enabled !== false) press(r.key)
    }

    // Returns false for what this page doesn't use, so Main.qml can take it (Left or B to the rail).
    function handleButton(b) {
        if (!inSection) {
            switch (b) {
            case "up": section = Math.max(0, section - 1); return true
            case "down": section = Math.min(navItems.length - 1, section + 1); return true
            case "a": if (onExit) { press("exit"); return true } inSection = true; row = 0; return true
            case "right": if (onExit) return true; inSection = true; row = 0; return true
            }
            return false
        }
        switch (b) {
        case "up": row = Math.max(0, row - 1); return true
        case "down": row = Math.min(rows.length - 1, row + 1); return true
        case "a": change(0); return true
        case "left":
            if (rows[row] && (rows[row].kind === "segmented" || rows[row].kind === "accent")) change(-1)
            else inSection = false
            return true
        case "right": change(1); return true
        case "b": inSection = false; return true
        }
        return false
    }
}

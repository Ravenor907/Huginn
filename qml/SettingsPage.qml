import QtQuick
import Huginn

// Settings, short: your name and the everyday choices on the left (notifications, look and text
// size, calls and voice, lock and privacy, how long messages stay), and More settings beside it as
// cards with one plain line each. A choice opens its rows on the right. Changes save as you go.
Rectangle {
    id: page
    color: Theme.ground
    // False while the controller focus is on the rail, so only one thing looks focused.
    readonly property bool active: !Ui.main || Ui.main.focusZone === "content"
    property string section: "more"   // the open page: one of mainItems' ids or a card's id
    property int pick: 0              // the focused item on the left
    property int card: 0              // the focused card in More settings
    property int row: 0
    property bool inSection: false
    // Another page opens one by name ({ section: "lock" }).
    function setArgs(a) {
        if (a.section === undefined) return
        const old = { "Appearance": "look", "Nearby and sharing": "nearby", "Privacy": "lock", "Lock": "lock", "Calls": "calls" }
        const id = old[a.section] || a.section
        if (!titles[id]) return
        section = id; row = a.row || 0; inSection = true
        const i = mainItems.findIndex(m => m.id === id)
        pick = i >= 0 ? i : mainItems.length - 1
    }

    readonly property var themeNames: ({ dark: "Dark", black: "True black", light: "Light", metal: "Brushed metal" })
    readonly property var mainItems: [
        { id: "notify", title: "Notifications", note: Chat.showPreview ? "On, with the message text" : "On, without the text" },
        { id: "look", title: "Look and text size", note: (themeNames[Theme.mode] || "Dark") + ", " + (Theme.compact ? "compact" : Theme.spacious ? "spacious" : "default") + ", " + Settings.uiSize + "%" },
        { id: "calls", title: "Calls and voice", note: Settings.pushToTalk ? "Push to talk: hold " + TalkKey.keyName : "Noise removal " + (Settings.noiseRemoval ? "on" : "off") },
        { id: "lock", title: "Lock and privacy", note: Vault.exists ? (Vault.usesPattern ? "Pattern" : "Password") + ", " + (Settings.lockPosition === "top" ? "at the top" : Settings.lockPosition === "middle" ? "in the middle" : "in the lower half") : "No lock yet" },
        { id: "keep", title: "Keep messages for", note: Chat.deleteAfter > 0 ? "A day on this computer" : "Each chat's own limit (7 days, or 30 in Family and Work)" },
        { id: "more", title: "More settings", note: "Everything else" }
    ]
    readonly property var cards: [
        { id: "nearby", title: "Find people close by", note: "One switch here, details under it" },
        { id: "contacts", title: "Contacts", note: "Everyone you're in touch with; block or restore them" },
        { id: "linked", title: "Linked devices", note: "Show your phone's chats here, or this computer's on another device" },
        { id: "shortcuts", title: "Keyboard shortcuts", note: "Ctrl+/ shows them all" },
        { id: "controller", title: "Controller", note: "Buttons and the on-screen pointer" },
        { id: "backup", title: "Backup", note: "Save your chats to a file only you can open" },
        { id: "access", title: "Accessibility", note: "High contrast, color-blind friendly, less motion" },
        { id: "panels", title: "Panel sizes", note: "Show or hide the lists, and put every panel back to its first size" },
        { id: "about", title: "Updates and about", note: "Version and updates" },
        { id: "erase", title: "Erase everything", note: "Removes every chat and contact from this computer" }
    ]
    readonly property var titles: ({ notify: "Notifications", look: "Look and text size", calls: "Calls and voice", lock: "Lock and privacy", keep: "Keep messages for",
                                     more: "More settings", nearby: "Find people close by", controller: "Controller", backup: "Backup", access: "Accessibility",
                                     panels: "Panel sizes", about: "Updates and about", erase: "Erase everything" })
    readonly property bool onMore: section === "more"
    readonly property bool fromMore: cards.some(c => c.id === section)
    readonly property var prompts: inSection ? [{ key: "a", label: "Change" }, { key: "b", label: "Back" }]
                                             : [{ key: "a", label: "Open" }, { key: "b", label: "Back" }]
    readonly property string status: "Changes save as you go"

    // Each row: { kind: "segmented"|"toggle"|"accent"|"button"|"size"|"info", title, note, ... }
    readonly property var rows: {
        switch (section) {
        case "notify": return [
            { kind: "toggle", key: "showPreview", chat: true, title: "Show message text", note: "Off: notifications only say “New message”" },
            { kind: "info", title: "When they show", note: "A notification and a sound when a message arrives while you're in another window, or the chat isn't open." }
        ]
        case "look": return [
            { kind: "segmented", key: "theme", title: "Theme", note: "True black saves power on OLED screens",
              options: [{ value: "dark", label: "Dark" }, { value: "black", label: "True black" }, { value: "light", label: "Light" }, { value: "metal", label: "Brushed metal" }] },
            { kind: "accent", key: "accent", title: "Accent color", note: "For what's picked and lit up. The last one takes any color" },
            { kind: "segmented", key: "density", title: "Messages", note: "Compact fits the most on screen; Spacious leaves more room",
              options: [{ value: "compact", label: "Compact" }, { value: "cozy", label: "Default" }, { value: "spacious", label: "Spacious" }] },
            { kind: "size", key: "uiSize", title: "Size: " + Settings.uiSize + "%", note: "Everything, from 50% to 200%. Ctrl+= and Ctrl+- change it too, Ctrl+0 goes back to 100%" },
            { kind: "segmented", key: "textSize", title: "Text size", note: "Just the text, on top of Size",
              options: [{ value: "100", label: "Normal" }, { value: "115", label: "Large" }, { value: "130", label: "Larger" }, { value: "150", label: "Largest" }] }
        ]
        case "calls": return [
            { kind: "toggle", key: "noiseRemoval", title: "Noise removal", note: Settings.noiseRemoval
                ? "Background noise is taken out of your voice. Turn it off if people say you sound quiet"
                : "Off: background sound comes through with your voice. Echo is still removed" },
            { kind: "toggle", key: "pushToTalk", title: "Push to talk", note: "In a voice room your microphone only sends while you hold the key" },
            { kind: "button", key: "talkKey", title: "Push to talk key: " + TalkKey.keyName, note: TalkKey.capturing ? "Press the key you want to use. Esc keeps " + TalkKey.keyName
                : "Hold it to talk", label: TalkKey.capturing ? "Press a key" : "Change" }
        ]
        case "lock": return [
            { kind: "button", key: "changeLock", title: "Lock", note: "Huginn opens with your " + (Vault.usesPattern ? "pattern" : "password") + ". Everything it keeps is locked with it", label: "Change", enabled: Vault.exists },
            { kind: "segmented", key: "lockPosition", title: "Where the pattern sits", note: "On the lock screen. Lower is easiest to reach",
              options: [{ value: "top", label: "Top" }, { value: "middle", label: "Middle" }, { value: "lower", label: "Lower" }] },
            { kind: "toggle", key: "lockOnLeave", vault: true, title: "Lock when I leave Huginn", note: "Huginn locks the moment you switch to another app or the computer sleeps" },
            { kind: "toggle", key: "panicLock", vault: true, title: "Panic lock", note: "Ctrl+Shift+Q (or both stick buttons held on a controller) locks Huginn at once and hides message text until you unlock" },
            { kind: "toggle", key: "readReceipts", chat: true, title: "Send read receipts", note: "Off: people don't see when you've read their messages. Delivered still shows" },
            { kind: "toggle", key: "activeStatus", chat: true, title: "Active status", note: "Contacts see a green dot while Huginn is open. Off: nobody sees yours, and you don't see theirs" },
            { kind: "toggle", key: "linkPreviews", chat: true, title: "Link previews", note: "A link you send shows its page's title and picture. Off: links go as plain text" }
        ]
        case "keep": return [
            { kind: "segmented", key: "deleteAfter", chat: true, title: "Keep messages for",
              note: "Chats keep messages for 7 days, or up to 30 in Family and Work. Each chat's own limit is under its options. A day deletes sooner on this computer",
              options: [{ value: "0", label: "Each chat's limit" }, { value: "86400000", label: "Shorten all to a day" }] }
        ]
        case "nearby": return [
            { kind: "toggle", key: "visibleNearby", chat: true, title: "Find people close by", note: "Off: strangers close by or on the same Wi-Fi can't see your name or message you. Your contacts still can" },
            { kind: "toggle", key: "nearbyOn", chat: true, hide: !Chat.visibleNearby, title: "Nearby room", note: "Everyone close by or on the same Wi-Fi with Huginn or Muninn can see and message you" },
            { kind: "toggle", key: "keepNearby", chat: true, hide: !Chat.visibleNearby, title: "Keep Nearby messages", note: "Off: they clear when Huginn closes" },
            { kind: "toggle", key: "nearbySharing", title: "Share files nearby", note: "Muninn and the free LocalSend app on phones and computers can find this one. You accept every file on screen" }
        ].filter(r => !r.hide)
        case "controller": return [
            { kind: "info", title: "Buttons", note: "Press View (Select) to see what every button does; press it again, or any other button, to hide it. LB steps through the sections" },
            { kind: "info", title: "The pointer", note: "The right stick moves a pointer; RB clicks, RT right-clicks. The left stick scrolls" }
        ]
        case "backup": return [
            { kind: "button", key: "backup", title: "Back up chats", note: "A locked copy in Documents, opened with a backup password. Use it on one device at a time", label: Vault.busy ? "Working" : "Back up", enabled: Vault.exists && !Vault.busy },
            { kind: "button", key: "restore", title: "Restore from backup", note: "Replaces everything on this computer with a backup from Documents", label: "Restore", enabled: Vault.exists && !Vault.busy }
        ]
        case "access": return [
            { kind: "toggle", key: "highContrast", title: "High contrast", note: "White text on black, stronger outlines" },
            { kind: "toggle", key: "colorBlind", title: "Color-blind friendly", note: "Warnings in amber instead of red; status always says it in words" },
            { kind: "toggle", key: "reduceMotion", title: "Less motion", note: "No sliding or fading" }
        ]
        case "panels": return [
            { kind: "toggle", key: "showList", title: "Show the list beside the rail", note: "Chats, or a group's channels. Ctrl+Shift+L" },
            { kind: "toggle", key: "showMembers", title: "Show a group's member list", note: "Who's online, idle or in a voice room. Ctrl+Shift+U" },
            { kind: "button", key: "resetPanels", title: "Panel sizes", note: "Drag the edge between two panels to resize them. This puts them back", label: "Reset" }
        ]
        case "about": return [
            { kind: "button", key: "check", title: Updater.status, note: "Huginn " + System.version + (Settings.autoUpdate ? ". Updates install by themselves when Huginn starts" : ". Huginn only checks when you press Check now"),
              label: Updater.available ? "Install " + Updater.latest : "Check now", enabled: !Updater.busy },
            { kind: "toggle", key: "autoUpdate", title: "Check for updates automatically", note: "Asks GitHub for a new version when Huginn starts" },
            { kind: "info", title: "Huginn " + System.version, note: "Private messages, calls and file sharing for Windows and Linux. Made by Ravenor907." },
            { kind: "info", title: "License", note: "GPL-3.0. Fonts: Cinzel, Barlow and Noto Sans Runic under the SIL Open Font License." }
        ]
        case "erase": return [
            { kind: "button", key: "deleteMessages", title: "Delete all messages", note: "Every chat on this computer. Contacts stay", label: "Delete" },
            { kind: "button", key: "eraseChat", title: "Erase everything", note: "Messages, photos, voice, contacts, keys and the lock. Huginn closes", label: "Erase" }
        ]
        }
        return []
    }

    // ------------------------------------------------------------- left: name and the everyday choices
    Rectangle {
        id: middle
        visible: !Theme.portrait || !page.inSection
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        anchors.bottomMargin: Ui.dock
        width: Theme.portrait ? page.width : Math.max(Theme.listWidth, Theme.px(320))
        color: Theme.column
        Brushed {}
        PageHeader { id: header; width: parent.width; title: "Settings" }
        Column {
            id: nav
            anchors.top: header.bottom
            anchors.topMargin: Theme.px(12)
            x: Theme.px(12)
            width: parent.width - Theme.px(24)
            spacing: Theme.px(12)
            // Your name.
            Rectangle {
                width: parent.width
                height: nameCol.implicitHeight + Theme.px(20)
                radius: Theme.radius
                color: nameHover.hovered ? Theme.raised : Theme.panel
                border.color: Theme.line
                Accessible.role: Accessible.Button
                Accessible.name: "Your name: " + Chat.username
                Column {
                    id: nameCol
                    x: Theme.px(12); y: Theme.px(10); width: parent.width - Theme.px(24)
                    spacing: Theme.px(2)
                    Text { text: "Your name"; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(12) }
                    Text { width: parent.width; elide: Text.ElideRight; text: Chat.username; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(16); font.weight: Font.Medium }
                    Text { width: parent.width; wrapMode: Text.WordWrap; text: "What people see. It doesn't have to be your real name."; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(12) }
                }
                HoverHandler { id: nameHover; cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: page.press("chatName") }
            }
            Rectangle {
                width: parent.width
                height: mainCol.implicitHeight
                radius: Theme.radius
                color: Theme.panel
                border.color: Theme.line
                Column {
                    id: mainCol
                    width: parent.width
                    Repeater {
                        model: page.mainItems
                        Rectangle {
                            readonly property bool here: page.section === modelData.id && !Theme.portrait
                            readonly property bool on: page.active && !page.inSection && page.pick === index && !!Ui.main && Ui.main.padInUse
                            width: mainCol.width
                            height: Math.max(Theme.px(52), itemCol.implicitHeight + Theme.px(16))
                            color: here || on ? Theme.tile : itemHover.hovered ? Theme.raised : "transparent"
                            border.width: on ? Theme.focusWidth : 0
                            border.color: Theme.accent
                            Accessible.role: Accessible.PageTab
                            Accessible.name: modelData.title
                            Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.seam; visible: index < page.mainItems.length - 1 }
                            Column {
                                id: itemCol
                                anchors.left: parent.left; anchors.leftMargin: Theme.px(12)
                                anchors.right: chev.left; anchors.rightMargin: Theme.px(8)
                                anchors.verticalCenter: parent.verticalCenter
                                Text { width: parent.width; elide: Text.ElideRight; text: modelData.title; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15) }
                                Text { width: parent.width; elide: Text.ElideRight; text: modelData.note; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(12) }
                            }
                            Icon { id: chev; anchors.right: parent.right; anchors.rightMargin: Theme.px(10); anchors.verticalCenter: parent.verticalCenter; name: "forward"; size: Theme.px(14); color: Theme.textMuted }
                            HoverHandler { id: itemHover; cursorShape: Qt.PointingHandCursor }
                            TapHandler { onTapped: { page.pick = index; page.openMain() } }
                        }
                    }
                }
            }
        }
        Text {
            anchors.bottom: parent.bottom
            anchors.bottomMargin: Theme.px(12)
            x: Theme.px(14)
            width: parent.width - Theme.px(28)
            elide: Text.ElideRight
            text: "Huginn " + System.version + " · Ctrl+, opens Settings"
            color: Theme.textMuted
            font.family: Theme.body; font.pixelSize: Theme.fs(12)
        }
    }

    // ------------------------------------------------------------- right: the open page
    PageHeader {
        id: rowsHead
        visible: !Theme.portrait || page.inSection
        anchors.left: middle.visible ? middle.right : parent.left
        anchors.right: parent.right
        title: page.titles[page.section] || ""
        backShown: Theme.portrait || page.fromMore
        onBack: page.back()
    }
    Text {
        id: moreNote
        visible: page.onMore && rowsHead.visible
        anchors.top: rowsHead.bottom; anchors.topMargin: Theme.px(16)
        anchors.left: rowsHead.left; anchors.leftMargin: Theme.px(24)
        text: "Things most people never need to change."
        color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(14)
    }
    // More settings: cards with one plain line each.
    Flickable {
        id: cardView
        visible: page.onMore && rowsHead.visible
        anchors.top: moreNote.bottom; anchors.topMargin: Theme.px(12)
        anchors.left: rowsHead.left; anchors.leftMargin: Theme.px(24)
        anchors.right: parent.right; anchors.rightMargin: Theme.px(24)
        anchors.bottom: parent.bottom; anchors.bottomMargin: Theme.px(12)
        contentHeight: cardGrid.implicitHeight
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        Grid {
            id: cardGrid
            width: parent.width
            columns: Math.max(1, Math.min(3, Math.floor((width + spacing) / (Theme.px(240) + spacing))))
            spacing: Theme.px(12)
            Repeater {
                model: page.cards
                Rectangle {
                    readonly property bool on: page.active && page.inSection && page.onMore && page.card === index && !!Ui.main && Ui.main.padInUse
                    width: (cardGrid.width - cardGrid.spacing * (cardGrid.columns - 1)) / cardGrid.columns
                    height: Math.max(Theme.px(76), cardCol.implicitHeight + Theme.px(28))
                    radius: Theme.radius
                    color: on ? Theme.focusFill : cardHover.hovered ? Theme.raised : Theme.panel
                    border.width: on ? Theme.focusWidth : 1
                    border.color: on ? Theme.accent : modelData.id === "erase" ? Theme.dangerLine : Theme.line
                    Accessible.role: Accessible.Button
                    Accessible.name: modelData.title + ". " + modelData.note
                    Column {
                        id: cardCol
                        x: Theme.px(16); y: Theme.px(14); width: parent.width - Theme.px(32)
                        spacing: Theme.px(4)
                        Text { width: parent.width; wrapMode: Text.WordWrap; text: modelData.title; color: modelData.id === "erase" ? Theme.danger : Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.Medium }
                        Text { width: parent.width; wrapMode: Text.WordWrap; text: modelData.note; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
                    }
                    HoverHandler { id: cardHover; cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: { page.card = index; page.openCard() } }
                }
            }
        }
    }

    // The rows of the open page. They scroll so the focused one is always on screen.
    Flickable {
        id: rowsView
        visible: !page.onMore && rowsHead.visible
        anchors.top: rowsHead.bottom
        anchors.topMargin: Theme.px(16)
        anchors.left: rowsHead.left
        anchors.leftMargin: Theme.px(24)
        anchors.right: parent.right
        anchors.rightMargin: Theme.px(24)
        anchors.bottom: parent.bottom
        anchors.bottomMargin: Theme.px(10)
        contentWidth: width
        contentHeight: rowsCol.implicitHeight
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        Column {
            id: rowsCol
            width: Math.min(rowsView.width, Theme.px(760))
            spacing: Theme.px(8)
            Repeater {
                id: rowRepeater
                model: page.rows
                Rectangle {
                    readonly property bool focused: page.active && page.inSection && page.row === index && !!Ui.main && Ui.main.padInUse
                    // Wide choices go under the words when there isn't room beside them.
                    readonly property bool stacked: control.width > width * 0.5
                    width: parent.width
                    height: stacked ? texts.implicitHeight + control.height + Theme.px(34) : Math.max(Theme.px(60), texts.implicitHeight + Theme.px(24))
                    radius: Theme.radius
                    color: focused ? Theme.focusFill : Theme.panel
                    border.width: focused ? Theme.focusWidth : 1
                    border.color: focused ? Theme.accent : Theme.line
                    Column {
                        id: texts
                        anchors.left: parent.left
                        anchors.leftMargin: Theme.px(16)
                        anchors.right: parent.stacked ? parent.right : control.left
                        anchors.rightMargin: Theme.px(16)
                        y: parent.stacked ? Theme.px(12) : (parent.height - implicitHeight) / 2
                        spacing: Theme.px(2)
                        Text { width: parent.width; wrapMode: Text.WordWrap; text: modelData.title; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.Medium }
                        Text { width: parent.width; wrapMode: Text.WordWrap; text: modelData.note; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
                    }
                    Item {
                        id: control
                        anchors.right: parent.right
                        anchors.rightMargin: Theme.px(16)
                        y: parent.stacked ? texts.y + texts.implicitHeight + Theme.px(10) : (parent.height - height) / 2
                        width: modelData.kind === "segmented" ? segCtl.implicitWidth
                             : modelData.kind === "toggle" ? toggleCtl.implicitWidth
                             : modelData.kind === "accent" ? accentCtl.implicitWidth
                             : modelData.kind === "size" ? sizeCtl.implicitWidth
                             : modelData.kind === "button" ? buttonCtl.implicitWidth : 0
                        height: Theme.px(36)
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
                            id: sizeCtl
                            anchors.right: parent.right
                            anchors.verticalCenter: parent.verticalCenter
                            visible: modelData.kind === "size"
                            spacing: Theme.px(6)
                            HButton { text: "Smaller"; onClicked: page.press("sizeDown") }
                            HButton { text: "100%"; kind: "plain"; onClicked: page.press("sizeReset") }
                            HButton { text: "Bigger"; onClicked: page.press("sizeUp") }
                        }
                        Row {
                            id: accentCtl
                            anchors.right: parent.right
                            anchors.verticalCenter: parent.verticalCenter
                            visible: modelData.kind === "accent"
                            spacing: Theme.px(8)
                            Repeater {
                                model: Theme.accentNames
                                Rectangle {
                                    width: Theme.px(28); height: width; radius: Theme.radius
                                    color: Theme.light ? Qt.darker(Theme.accents[modelData], 1.7) : Theme.accents[modelData]
                                    border.width: Settings.accent === modelData ? Theme.px(2) : 1
                                    border.color: Settings.accent === modelData ? Theme.text : Theme.lineMuted
                                    Accessible.role: Accessible.RadioButton
                                    Accessible.name: modelData.charAt(0).toUpperCase() + modelData.slice(1)
                                    Accessible.checked: Settings.accent === modelData
                                    HoverHandler { cursorShape: Qt.PointingHandCursor }
                                    TapHandler { onTapped: Settings.accent = modelData }
                                }
                            }
                            // Any color: a code like #6E9CC8.
                            Rectangle {
                                readonly property bool custom: Theme.accentNames.indexOf(Settings.accent) < 0
                                width: Theme.px(28); height: width; radius: Theme.radius
                                color: custom ? Theme.accent : Theme.raised
                                border.width: custom ? Theme.px(2) : 1
                                border.color: custom ? Theme.text : Theme.lineMuted
                                Accessible.role: Accessible.Button
                                Accessible.name: "Any color"
                                Icon { visible: !parent.custom; anchors.centerIn: parent; name: "plus"; size: Theme.px(16); color: Theme.textMuted }
                                HoverHandler { cursorShape: Qt.PointingHandCursor }
                                TapHandler { onTapped: page.pickColor() }
                            }
                        }
                        HButton {
                            id: buttonCtl
                            anchors.right: parent.right
                            anchors.verticalCenter: parent.verticalCenter
                            visible: modelData.kind === "button"
                            kind: modelData.key === "eraseChat" || modelData.key === "deleteMessages" ? "danger" : "normal"
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

    function openMain() {
        const m = mainItems[pick]
        if (!m) return
        section = m.id; row = 0; card = 0
        inSection = true
    }
    // Some cards open somewhere else; the rest open their rows here.
    function openCard() {
        const c = cards[card]
        if (!c) return
        if (c.id === "contacts") Ui.openPage("contacts", {})
        else if (c.id === "linked") PeopleActions.linkedDevices()
        else if (c.id === "shortcuts") Ui.main.showShortcuts()
        else { section = c.id; row = 0; inSection = true }
    }
    function back() {
        if (fromMore) { section = "more"; inSection = true; return }
        inSection = false
    }

    // Anything that closes or restarts Huginn checks first that it won't cut off a job.
    function running() {
        if (LocalSend.busy) return LocalSend.status + " Closing now stops it partway."
        return ""
    }
    // Any accent color, as a code like #6E9CC8.
    function pickColor() {
        const now = Theme.validColor(Settings.accent) ? Settings.accent : String(Theme.accentBase).toUpperCase()
        Ui.ask("Accent color", "A color code like #6E9CC8", now, "Use it", t => {
            const v = t.trim().startsWith("#") ? t.trim() : "#" + t.trim()
            if (Theme.validColor(v)) Settings.accent = v.toUpperCase()
            else Ui.say("That isn't a color code. Use # and six digits or letters A to F, like #6E9CC8.", true)
        })
    }
    // Where a toggle's value lives.
    function source(r) { return r.vault ? Vault : r.chat ? Chat : Settings }

    // ------------------------------------------------------------- chat backup
    function startBackup() {
        if (!Vault.exists || Vault.screenLocked) { Ui.say("Unlock Huginn first.", true); return }
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
        if (!Vault.exists || Vault.screenLocked) { Ui.say("Unlock Huginn first.", true); return }
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
                Ui.sure("Replace everything?",
                        message + " Every message, photo, voice message, contact and key on this computer is replaced by it. Your chat lock stays the same. Use the backup on one device at a time. Huginn restarts after.",
                        "Hold to restore", () => Vault.restoreOpened(), true, true, () => Vault.forgetOpened())
            else
                Ui.say(message, !ok)
        }
    }

    function press(key) {
        const job = running()
        if (key === "exit") Ui.main.requestExit()
        else if (key === "trashPage") Ui.openPage("dms", {})
        else if (key === "chatName") Ui.ask("Your name", "What people see. It doesn't have to be your real name.", Chat.username, "Save", t => Chat.username = t)
        else if (key === "sizeDown") Ui.main.stepSize(-1)
        else if (key === "sizeUp") Ui.main.stepSize(1)
        else if (key === "sizeReset") Ui.main.stepSize(0)
        else if (key === "contacts") Ui.openPage("contacts", {})
        else if (key === "linked") PeopleActions.linkedDevices()
        else if (key === "talkKey") { if (TalkKey.capturing) TalkKey.stopCapture(); else TalkKey.capture() }
        else if (key === "resetPanels") { Ui.resetPanels(); Ui.say("Panels are back to their first sizes.") }
        else if (key === "shortcuts") Ui.main.showShortcuts()
        else if (key === "backup") startBackup()
        else if (key === "restore") startRestore()
        else if (key === "changeLock") Ui.openPage("chat", { changeLock: true })
        else if (key === "deleteMessages" && !Chat.ready) Ui.say("Unlock Huginn first.", true)
        else if (key === "deleteMessages") Ui.sure("Delete all messages?", "Every conversation on this computer is deleted. Contacts stay.", "Hold to delete", () => { Chat.deleteAll(); Ui.say("All messages deleted.") }, true, true)
        else if (key === "eraseChat") Ui.sure("Erase everything?", "Messages, photos, voice, contacts, keys and the lock are deleted for good, and Huginn closes. Your files and other settings stay.",
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
            // The named colors, then Any color (A on it asks for a code).
            const order = Theme.accentNames.concat(["custom"])
            const at = Theme.accentNames.indexOf(Settings.accent) < 0 ? order.length - 1 : Theme.accentNames.indexOf(Settings.accent)
            const i = dir === 0 ? (at + 1) % order.length : Math.max(0, Math.min(order.length - 1, at + dir))
            if (i === at && dir !== 0) return
            if (order[i] === "custom") pickColor()
            else Settings.accent = order[i]
        } else if (r.kind === "size") { if (dir === 0) press("sizeReset"); else Ui.main.stepSize(dir) }
        else if (r.kind === "button" && dir === 0 && r.enabled !== false) press(r.key)
    }

    // Returns false for what this page doesn't use, so Main.qml can take it (Left or B to the rail).
    function handleButton(b) {
        if (!inSection) {
            switch (b) {
            case "up": pick = Math.max(0, pick - 1); return true
            case "down": pick = Math.min(mainItems.length - 1, pick + 1); return true
            case "a": case "right": openMain(); return true
            }
            return false
        }
        if (onMore) {
            const cols = cardGrid.columns
            switch (b) {
            case "up": card = Math.max(0, card - cols); return true
            case "down": card = Math.min(cards.length - 1, card + cols); return true
            case "left": if (card % cols === 0) inSection = false; else card--; return true
            case "right": card = Math.min(cards.length - 1, card + 1); return true
            case "a": openCard(); return true
            case "b": inSection = false; return true
            }
            return false
        }
        switch (b) {
        case "up": row = Math.max(0, row - 1); return true
        case "down": row = Math.min(rows.length - 1, row + 1); return true
        case "a": change(0); return true
        case "left":
            if (rows[row] && (rows[row].kind === "segmented" || rows[row].kind === "accent" || rows[row].kind === "size")) change(-1)
            else back()
            return true
        case "right": change(1); return true
        case "b": back(); if (!fromMore && section !== "more") inSection = false; return true
        }
        return false
    }
}

import QtQuick
import Huginn

// Settings, short: your name and the everyday choices on the left (notifications, look and text
// size, calls and voice, lock and privacy, how long messages stay), and More settings beside it as
// cards with one plain line each. A choice opens its rows on the right. Changes save as you go.
Rectangle {
    id: page
    // Autumn: a frosted pane, so the leaves show while Look's sliders are moved.
    color: Theme.sheet
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
        // Your picture is changed under Look and text size.
        if (id === "picture") { section = "look"; inSection = true; pick = 1; return }
        if (!titles[id]) return
        section = id; row = a.row || 0; inSection = true
        const i = mainItems.findIndex(m => m.id === id)
        pick = i >= 0 ? i : mainItems.length - 1
    }
    // The desktop's list column edge, so Main.qml can put the resize handle on it.
    readonly property int listEdge: middle.visible ? middle.width : 0

    readonly property var themeNames: ({ dark: "Dark", black: "True black", light: "Light", autumn: "Autumn" })
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
                                     panels: "Panel sizes", about: "Updates and about", erase: "Erase everything", callhistory: "Call history" })
    readonly property bool onMore: section === "more"
    readonly property bool fromMore: cards.some(c => c.id === section)
    // Call history: the Calls page, inside Settings › Calls and voice.
    readonly property bool onCalls: section === "callhistory"
    readonly property var prompts: onCalls && callsView.item ? callsView.item.prompts
                                 : inSection ? [{ key: "a", label: "Change" }, { key: "b", label: "Back" }]
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
            { kind: "button", key: "picture", title: "Your picture", note: "Shown beside your name. Sent only to your contacts and the groups you're in, encrypted like a message",
              label: Profile.hasMyPicture ? "Change" : "Choose" },
            { kind: "segmented", key: "theme", title: "Theme", note: Theme.mode === "autumn" ? "Autumn is new and still settling in: leaves fall behind the panes" : "True black saves power on OLED screens",
              options: [{ value: "dark", label: "Dark" }, { value: "black", label: "True black" }, { value: "light", label: "Light" }, { value: "autumn", label: "Autumn (Experimental)" }] },
            // Autumn only: the leaves.
            { kind: "slider", key: "leafSize", hide: Theme.mode !== "autumn", title: "Leaf size: " + Settings.leafSize + "%", note: "From half size to twice", from: 50, to: 200, step: 5 },
            { kind: "slider", key: "leafCount", hide: Theme.mode !== "autumn", title: "How many: " + Settings.leafCount + "%", note: "Fewer leaves use less of the computer", from: 25, to: 200, step: 5 },
            { kind: "accent", key: "accent", title: "Accent color", note: "For what's picked and lit up. The last one takes any color" },
            { kind: "segmented", key: "density", title: "Messages", note: "Compact fits the most on screen; Spacious leaves more room",
              options: [{ value: "compact", label: "Compact" }, { value: "cozy", label: "Default" }, { value: "spacious", label: "Spacious" }] },
            { kind: "size", key: "uiSize", title: "Size: " + Settings.uiSize + "%", note: "Everything, from 50% to 200%. Ctrl+= and Ctrl+- change it too, Ctrl+0 goes back to 100%" },
            { kind: "segmented", key: "textSize", title: "Text size", note: "Just the text, on top of Size",
              options: [{ value: "100", label: "Normal" }, { value: "115", label: "Large" }, { value: "130", label: "Larger" }, { value: "150", label: "Largest" }] }
        ].filter(r => !r.hide)
        case "calls": return [
            { kind: "toggle", key: "noiseRemoval", title: "Noise removal", note: Settings.noiseRemoval
                ? "Background noise is taken out of your voice. Turn it off if people say you sound quiet"
                : "Off: background sound comes through with your voice. Echo is still removed" },
            { kind: "toggle", key: "pushToTalk", title: "Push to talk", note: "In a voice room your microphone only sends while you hold the key" },
            { kind: "button", key: "talkKey", title: "Push to talk key: " + TalkKey.keyName, note: TalkKey.capturing ? "Press the key you want to use. Esc keeps " + TalkKey.keyName
                : "Hold it to talk", label: TalkKey.capturing ? "Press a key" : "Change" },
            { kind: "button", key: "callHistory", title: "Call history", note: Calls.missedCount > 0 ? (Calls.missedCount === 1 ? "1 missed call" : Calls.missedCount + " missed calls") : "Calls you made, answered or missed", label: "Open" }
        ]
        case "lock": return [
            { kind: "button", key: "changeLock", title: "Lock", note: "Huginn opens with your " + (Vault.usesPattern ? "pattern" : "password") + ". Everything it keeps is locked with it", label: "Change", enabled: Vault.exists },
            { kind: "segmented", key: "lockPosition", title: "Where the pattern sits", note: "On the lock screen. Lower is easiest to reach",
              options: [{ value: "top", label: "Top" }, { value: "middle", label: "Middle" }, { value: "lower", label: "Lower" }] },
            { kind: "toggle", key: "patternTrail", invert: true, title: "Hide the pattern as I draw", note: "The line and the lit dots don't show while you draw, so nobody looking over your shoulder sees it. Each dot still gives a short pulse" },
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

    // Scrolls the left column so the row the controller is on is in view.
    function revealNav(item) {
        const y = item.mapToItem(nav, 0, 0).y
        if (y < navView.contentY) navView.contentY = y
        else if (y + item.height > navView.contentY + navView.height) navView.contentY = Math.min(navView.contentHeight - navView.height, y + item.height - navView.height)
    }

    // ------------------------------------------------------------- left: name and the everyday choices
    Rectangle {
        id: middle
        visible: !Theme.portrait || !page.inSection
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        anchors.bottomMargin: Ui.dock
        width: Theme.portrait ? page.width : Math.min(Math.max(Theme.listWidth, Theme.px(320)), Math.round(page.width * 0.45))
        color: Theme.column
        PageHeader { id: header; width: parent.width; title: "Settings" }
        // Scrolls when the window is short or the Size is large.
        Flickable {
            id: navView
            anchors.top: header.bottom
            anchors.topMargin: Theme.px(12)
            anchors.bottom: versionText.top
            anchors.bottomMargin: Theme.px(8)
            x: Theme.px(12)
            width: parent.width - Theme.px(24)
            contentWidth: width
            contentHeight: nav.implicitHeight
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            interactive: contentHeight > height
            Column {
                id: nav
                width: navView.width
                spacing: Theme.px(12)
                // Link a phone: Muninn's chats here, or this computer's there.
                Rectangle {
                    width: parent.width
                    height: Theme.px(52)
                    radius: Theme.radius
                    color: linkHover.hovered ? Theme.raised : Theme.panel
                    border.color: Theme.line
                    Accessible.role: Accessible.Button
                    Accessible.name: "Link a phone"
                    Accessible.onPressAction: page.press("linked")
                    Icon { id: linkIcon; anchors.left: parent.left; anchors.leftMargin: Theme.px(12); anchors.verticalCenter: parent.verticalCenter; name: "phone"; size: Theme.px(20); color: Theme.accent }
                    Column {
                        anchors.left: linkIcon.right; anchors.leftMargin: Theme.px(10)
                        anchors.right: linkChev.left; anchors.rightMargin: Theme.px(8)
                        anchors.verticalCenter: parent.verticalCenter
                        Text { width: parent.width; elide: Text.ElideRight; text: "Link a phone"; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15) }
                        Text { width: parent.width; elide: Text.ElideRight; text: Linked.phone.key ? "Linked to " + Linked.phone.name : "See your phone's chats here, or this computer's on your phone"; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(12) }
                    }
                    Icon { id: linkChev; anchors.right: parent.right; anchors.rightMargin: Theme.px(10); anchors.verticalCenter: parent.verticalCenter; name: "forward"; size: Theme.px(14); color: Theme.textMuted }
                    HoverHandler { id: linkHover; cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: page.press("linked") }
                }
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
                                id: navRow
                                readonly property bool here: (page.section === modelData.id || (page.onCalls && modelData.id === "calls")) && !Theme.portrait
                                readonly property bool on: page.active && !page.inSection && page.pick === index && !!Ui.main && Ui.main.padInUse
                                width: mainCol.width
                                onOnChanged: if (on) page.revealNav(navRow)
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
        }
        Text {
            id: versionText
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
        backShown: Theme.portrait || page.fromMore || page.onCalls
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

    // Call history: the Calls page, under the Settings header.
    Loader {
        id: callsView
        active: page.onCalls
        visible: active && rowsHead.visible
        anchors.top: rowsHead.bottom
        anchors.left: rowsHead.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        source: "CallsPage.qml"
        onLoaded: item.embedded = true
    }
    // The rows of the open page. They scroll so the focused one is always on screen.
    Flickable {
        id: rowsView
        visible: !page.onMore && !page.onCalls && rowsHead.visible
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
                        // Never wider than the row: on a narrow window the choices squeeze to fit.
                        width: Math.min(parent.width - Theme.px(32),
                               modelData.kind === "segmented" ? segCtl.implicitWidth
                             : modelData.kind === "toggle" ? toggleCtl.implicitWidth
                             : modelData.kind === "accent" ? accentCtl.naturalWidth
                             : modelData.kind === "size" ? sizeCtl.naturalWidth
                             : modelData.kind === "slider" ? Theme.px(220)
                             : modelData.kind === "button" ? buttonCtl.implicitWidth : 0)
                        height: Math.max(Theme.px(36), segCtl.visible ? segCtl.implicitHeight : 0, buttonCtl.visible ? buttonCtl.implicitHeight : 0)
                        // Look › Autumn: Leaf size and How many.
                        LookSlider {
                            id: sliderCtl
                            anchors.right: parent.right
                            anchors.verticalCenter: parent.verticalCenter
                            visible: modelData.kind === "slider"
                            width: parent.width
                            from: modelData.from || 0; to: modelData.to || 100; stepSize: modelData.step || 1
                            value: modelData.kind === "slider" ? Number(Settings[modelData.key]) : 0
                            label: modelData.title || ""
                            focused: parent.parent.focused
                            onMoved: v => Settings[modelData.key] = Math.round(v)
                        }
                        Segmented {
                            id: segCtl
                            anchors.right: parent.right
                            anchors.verticalCenter: parent.verticalCenter
                            visible: modelData.kind === "segmented"
                            width: parent.width
                            options: modelData.options || []
                            value: modelData.kind === "segmented" ? (modelData.chat ? String(Chat[modelData.key]) : Settings[modelData.key]) : ""
                            onPicked: v => { if (modelData.chat) Chat[modelData.key] = Number(v); else Settings[modelData.key] = v }
                        }
                        ToggleSwitch {
                            id: toggleCtl
                            anchors.right: parent.right
                            anchors.verticalCenter: parent.verticalCenter
                            visible: modelData.kind === "toggle"
                            // invert: the switch reads the other way round from the setting (Hide the pattern = trail off).
                            checked: modelData.kind === "toggle" ? (page.source(modelData)[modelData.key] !== !!modelData.invert) : false
                            onToggled: { const o = page.source(modelData); o[modelData.key] = !o[modelData.key] }
                        }
                        Row {
                            id: sizeCtl
                            anchors.right: parent.right
                            anchors.verticalCenter: parent.verticalCenter
                            visible: modelData.kind === "size"
                            spacing: Theme.px(6)
                            readonly property real naturalWidth: smallerBtn.implicitWidth + resetBtn.implicitWidth + biggerBtn.implicitWidth + spacing * 2
                            readonly property bool squeezed: control.width < naturalWidth - 0.5
                            readonly property real share: (control.width - spacing * 2) / 3
                            HButton { id: smallerBtn; width: sizeCtl.squeezed ? sizeCtl.share : implicitWidth; text: "Smaller"; onClicked: page.press("sizeDown") }
                            HButton { id: resetBtn; width: sizeCtl.squeezed ? sizeCtl.share : implicitWidth; text: "100%"; kind: "plain"; onClicked: page.press("sizeReset") }
                            HButton { id: biggerBtn; width: sizeCtl.squeezed ? sizeCtl.share : implicitWidth; text: "Bigger"; onClicked: page.press("sizeUp") }
                        }
                        Row {
                            id: accentCtl
                            anchors.right: parent.right
                            anchors.verticalCenter: parent.verticalCenter
                            visible: modelData.kind === "accent"
                            spacing: Theme.px(8)
                            // Seven named colours and Any color; smaller swatches on a narrow window.
                            readonly property real naturalWidth: Theme.px(28) * 8 + spacing * 7
                            readonly property int swatch: Math.max(Theme.px(20), Math.min(Theme.px(28), Math.floor((control.width - spacing * 7) / 8)))
                            Repeater {
                                model: Theme.accentNames
                                Rectangle {
                                    width: accentCtl.swatch; height: width; radius: Theme.radius
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
                                width: accentCtl.swatch; height: width; radius: Theme.radius
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
        if (onCalls) { section = "calls"; inSection = true; row = rows.findIndex(r => r.key === "callHistory"); return }
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
        else if (key === "picture") { if (Ui.profile) Ui.profile.pickPicture() }
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
        else if (key === "callHistory") { section = "callhistory"; inSection = true }
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
        else if (r.kind === "slider") {
            // Left and Right step it; A puts it back to 100%.
            const step = r.step || 1
            const now = Number(Settings[r.key]) || 100
            Settings[r.key] = dir === 0 ? 100 : Math.max(r.from, Math.min(r.to, now + dir * step))
        }
        else if (r.kind === "button" && dir === 0 && r.enabled !== false) press(r.key)
    }

    // Returns false for what this page doesn't use, so Main.qml can take it (Left or B to the rail).
    function handleButton(b) {
        if (onCalls) {
            if (b === "b") { back(); return true }
            const c = callsView.item
            if (c && c.handleButton && c.handleButton(b)) return true
            if (b === "left") { back(); return true }
            return false
        }
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
            if (rows[row] && (rows[row].kind === "segmented" || rows[row].kind === "accent" || rows[row].kind === "size" || rows[row].kind === "slider")) change(-1)
            else back()
            return true
        case "right": change(1); return true
        case "b": back(); if (!fromMore && section !== "more") inSection = false; return true
        }
        return false
    }

    // Look › Autumn: a plain slider in house style. A line, a knob, Left and Right or a drag to move it.
    component LookSlider: Item {
        id: sl
        property real from: 0
        property real to: 100
        property real stepSize: 1
        property real value: 0
        property string label: ""
        property bool focused: false
        signal moved(real value)
        implicitHeight: Theme.px(28)
        readonly property real frac: to > from ? Math.max(0, Math.min(1, (value - from) / (to - from))) : 0
        Accessible.role: Accessible.Slider
        Accessible.name: label
        Accessible.onIncreaseAction: sl.moved(Math.min(sl.to, sl.value + sl.stepSize))
        Accessible.onDecreaseAction: sl.moved(Math.max(sl.from, sl.value - sl.stepSize))
        function at(px) {
            const f = Math.max(0, Math.min(1, (px - knob.width / 2) / (width - knob.width)))
            let v = from + f * (to - from)
            v = Math.round(v / stepSize) * stepSize
            moved(Math.max(from, Math.min(to, v)))
        }
        Rectangle { anchors.verticalCenter: parent.verticalCenter; x: knob.width / 2; width: parent.width - knob.width; height: Theme.px(4); radius: Theme.radiusSmall; color: Theme.lineMuted }
        Rectangle { anchors.verticalCenter: parent.verticalCenter; x: knob.width / 2; width: (parent.width - knob.width) * sl.frac; height: Theme.px(4); radius: Theme.radiusSmall; color: Theme.accent }
        Rectangle {
            id: knob
            anchors.verticalCenter: parent.verticalCenter
            x: (sl.width - width) * sl.frac
            width: Theme.px(18); height: width; radius: Theme.autumn ? width / 2 : Theme.radiusSmall
            color: slHover.hovered || sl.focused ? Theme.accent : Theme.textSoft
            border.width: sl.focused ? Theme.focusWidth : 0
            border.color: Theme.text
        }
        HoverHandler { id: slHover; cursorShape: Qt.PointingHandCursor }
        TapHandler { onTapped: point => sl.at(point.position.x) }
        DragHandler {
            target: null
            onCentroidChanged: if (active) sl.at(centroid.position.x)
        }
    }
}

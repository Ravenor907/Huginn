import QtQuick
import Huginn

// A group, Discord style: the channel column (TEXT channels, VOICE rooms), the open channel's
// conversation with its message box, and the member column (or a thread or the pins beside it).
// The same on a desktop and a handheld, only bigger there. LT opens the group's options (invite,
// the group code, channels, members, rename, leave). D-pad left and right move between the columns.
Rectangle {
    id: page
    color: Theme.ground
    readonly property bool active: !Ui.main || Ui.main.focusZone === "content"
    readonly property string gid: Ui.main && Ui.main.pageName.startsWith("g:") ? Ui.main.pageName.substring(2) : ""
    property bool tour: false
    property string zone: "channels"       // "channels", "messages", "side" (a thread or the pins) or "members"
    property string channelId: "main"
    property int chRow: 0
    property int memRow: 0
    property int sel: -1
    property int sideSel: -1               // the message picked in the thread or pins panel
    property string viewing: ""            // a photo open full size
    property string shownCode: ""          // the group code on screen
    property real codeUntil: 0
    // The controller or its pointer is in use: what opens takes the controller's focus, not a text cursor.
    readonly property bool pad: !!Ui.main && Ui.main.padInUse
    // Narrow screens (a Steam Deck at handheld sizes, or a big Size): the member column folds away
    // so the conversation keeps room to read, and a thread or the pins slide over the conversation
    // instead of sitting beside it.
    readonly property bool membersFit: width - chanCol.width - Theme.px(230) >= Theme.px(520)
    readonly property bool sideDocked: width - chanCol.width - Theme.px(360) >= Theme.px(420)
    // The message box the controller is typing in, if any (picking a message takes it out).
    readonly property var typingIn: compose.hasFocus && zone === "messages" && sel < 0 ? compose
                                  : threadBox.hasFocus && zone === "side" && sideSel < 0 ? threadBox : null
    // Cap m1: answering, editing, where "New messages" starts, the reaction picker's message, and the
    // side panel ("thread" for one message and its replies, "pins" for this channel's pins).
    property string replyId: ""
    property string editId: ""
    property int newFrom: -1
    property string reactFor: ""
    property string side: ""
    property string threadRoot: ""
    readonly property var replyMsg: replyId === "" ? null : messages.find(m => m.id === replyId) || null
    readonly property var pins: isTour || locked ? [] : (Chat.groups, Chat.pinned(chatKey))
    readonly property var pinnedMsgs: pins.map(id => messages.find(m => m.id === id)).filter(m => !!m)
    readonly property var threadMsgs: threadRoot === "" ? [] : messages.filter(m => m.id === threadRoot || m.re === threadRoot)
    readonly property var memberNames: members.filter(m => !m.me).map(m => m.name)

    function setArgs(a) {
        tour = !!a.tour
        zone = a.zone || (Theme.listShown || a.voice ? "channels" : "messages")
        sel = -1
        viewing = ""
        shownCode = ""
        channelId = a.channel || "main"
        chRow = Math.max(0, rows.findIndex(r => r.kind === "channel" && r.id === channelId))
        if (a.voice) {
            const v = rows.findIndex(r => r.kind === "channel" && r.type === "voice")
            if (v >= 0) chRow = v
        }
        if (a.groupOptions) Qt.callLater(groupOptions)
    }
    onGidChanged: { channelId = "main"; sel = -1; Voice.stop() }
    onSideSelChanged: if (sideSel >= 0) sideList.positionViewAtIndex(sideSel, ListView.Contain)
    onSideChanged: {
        sideSel = -1
        if (side === "" && zone === "side") zone = "messages"
        // A hidden thread box can't keep the keyboard: back to the page (the chat's box keeps it).
        if (side !== "thread" && !compose.hasFocus && Ui.main) Ui.main.restoreFocus()
    }

    readonly property bool locked: !tour && (!Vault.exists || Vault.screenLocked)
    readonly property bool isTour: tour || gid.startsWith("tour-")

    // ------------------------------------------------------------- what's shown
    readonly property var tourInfo: ({
        g: gid, name: gid === "tour-family" ? "Family" : gid === "tour-folk" ? "Folk" : "Gaming Crew", amAdmin: true, amOwner: true, readOnly: false, count: 6,
        channels: [{ id: "main", name: "general", type: "text", unread: 0 }, { id: "s", name: "screenshots", type: "text", unread: 2, mentions: 1 },
                   { id: "p", name: "plans", type: "text", unread: 0 }, { id: "l", name: "Lounge", type: "voice", unread: 0 },
                   { id: "r", name: "Raid night", type: "voice", unread: 0 }],
        members: [{ key: "me", name: Chat.username, owner: true, admin: true, me: true, contact: false, route: "" },
                  { key: "j", name: "Jake", admin: true, owner: false, me: false, contact: true, route: "wifi" },
                  { key: "s", name: "Sam", admin: false, owner: false, me: false, contact: true, route: "internet" },
                  { key: "m", name: "Mike", admin: false, owner: false, me: false, contact: false, route: "internet" },
                  { key: "d", name: "Dee", admin: false, owner: false, me: false, contact: false, route: "" },
                  { key: "t", name: "Tony", admin: false, owner: false, me: false, contact: true, route: "bluetooth" }]
    })
    readonly property var info: isTour ? tourInfo : (Chat.groups, Chat.groupInfo(gid))
    readonly property bool exists: !!info.name
    readonly property bool readOnly: !!info.readOnly
    readonly property var channels: info.channels || []
    readonly property var members: info.members || []
    readonly property var channel: channels.find(c => c.id === channelId) || { id: "main", name: "general", type: "text" }
    // The channel column: the TEXT category, then VOICE (voice rooms and group calls). A click (or A)
    // on a category folds it away, keeping only the open channel, the room you're in and rooms with
    // people in them; a folded category adds up its unread messages and mentions.
    property var folded: ({})
    function foldCat(cat) { const f = Object.assign({}, folded); f[gid + "/" + cat] = !f[gid + "/" + cat]; folded = f }
    readonly property var rows: {
        const out = []
        const add = (cat, text, list) => {
            const shut = !!folded[gid + "/" + cat]
            const kept = shut ? list.filter(c => c.id === channelId && cat === "text" || (cat === "voice" && (Rooms.isIn(gid, c.id) || roomPeople(c.id).length > 0))) : list
            out.push({ kind: "header", text: text, cat: cat, folded: shut,
                       unread: shut ? list.filter(c => kept.indexOf(c) < 0).reduce((n, c) => n + (c.unread || 0), 0) : 0,
                       mentions: shut ? list.filter(c => kept.indexOf(c) < 0).reduce((n, c) => n + (c.mentions || 0), 0) : 0 })
            kept.forEach(c => out.push(c))
        }
        add("text", "TEXT", channels.filter(c => c.type === "text").map(c => Object.assign({ kind: "channel" }, c)))
        const voice = channels.filter(c => c.type === "voice").map(c => Object.assign({ kind: "channel" }, c))
        // Group calls going on in this group show with the voice rooms, while anyone is in them.
        const calls = isTour || locked ? [] : (Rooms.presence, Rooms.inRoom, Rooms.groupCalls(gid))
        calls.forEach(c => voice.push({ kind: "channel", type: "voice", id: c.ch, name: "Group call", unread: 0, call: true }))
        if (voice.length) add("voice", "VOICE", voice)
        return out
    }
    // Who's in a voice room or group call right now: [{name, speaking}] (the tour shows how it looks).
    function roomPeople(id) {
        if (isTour) return id === "l" ? [{ name: "Bjorn" }, { name: "Jake", speaking: true }, { name: "Sam" }] : []
        if (locked) return []
        return (Rooms.presence, Rooms.people, Rooms.whoIn(gid, id))
    }
    readonly property var messages: {
        if (isTour)
            return channelId !== "main" ? [] : [
                { id: "1", name: "Jake", from: "j", text: "Who's on tonight? I can host at 8.", mine: false, time: Date.now() - 600000 },
                { id: "2", name: "Sam", from: "s", text: "In. Grabbing food first.", mine: false, time: Date.now() - 480000 },
                { id: "3", name: Chat.username, from: "me", text: "Jumping in the Lounge now.", mine: true, time: Date.now() - 60000, state: "delivered", receipt: "Delivered to 4 of 5" }]
        return (Chat.groups, Chat.groupMessages(gid, channelId))
    }
    readonly property string chatKey: "g:" + gid + ":" + channelId

    readonly property var prompts: shownCode !== "" ? [{ key: "a", label: "Copy" }, { key: "x", label: "New code" }, { key: "b", label: "Close" }]
        : reactPicker.visible || compose.emojiShown || threadBox.emojiShown ? [{ key: "a", label: "Pick" }, { key: "b", label: "Close" }]
        : typingIn ? [{ key: "a", label: "Send" }, { key: "y", label: "Keyboard" }, { key: "lt", label: "Options" }, { key: "b", label: "Done" }]
        : zone === "side" ? (side === "thread" && !readOnly ? [{ key: "a", label: "Reply in the thread" }] : side === "pins" ? [{ key: "a", label: "Go to it" }] : [])
                            .concat([{ key: "lt", label: "Options" }, { key: "b", label: "Close" }])
        : zone === "channels" ? [{ key: "a", label: "Open" }, { key: "lt", label: "Group options" }, { key: "b", label: "Back" }]
        : zone === "members" ? [{ key: "a", label: "Choose" }, { key: "lt", label: "Group options" }, { key: "b", label: "Back" }]
        : (readOnly ? [] : [{ key: "a", label: "Write" }, { key: "y", label: "Hold to talk" }]).concat([{ key: "lt", label: "Options" }, { key: "b", label: "Back" }])
    readonly property string status: Chat.netStatus

    function clock(ms) { return Qt.formatTime(new Date(ms), "h:mm AP") }
    function initial(name) { const n = String(name || "").trim(); return n.length ? n.charAt(0).toUpperCase() : "?" }
    function stateLabel(m) {
        if (!m.mine || m.unsent) return ""
        if ((m.readCount || 0) > 0) return ""   // the check mark and the count say it
        if (m.receipt) return " · " + m.receipt
        return m.state === "sent" ? " · Sent" : m.state === "sending" ? " · Sending" : ""
    }
    function selectable(i) { return !!rows[i] && (rows[i].kind === "channel" || rows[i].kind === "header") }

    onChatKeyChanged: {
        sel = -1; replyId = ""; editId = ""; reactFor = ""; threadRoot = ""; if (side === "thread") side = ""
        compose.text = ""
        // Where the unread ones start, for the "New messages" line (before they're marked read).
        newFrom = -1
        const n = channel.unread || 0
        if (!locked && !isTour && n > 0) {
            let seen = 0, i = messages.length
            while (i > 0 && seen < n) { i--; if (!messages[i].mine && !messages[i].system) seen++ }
            newFrom = i
            Qt.callLater(() => list.positionViewAtIndex(page.newFrom, ListView.Beginning))
        }
        if (!locked && !isTour) { Chat.openChat = chatKey; Chat.markGroupRead(gid, channelId) }
    }
    onMessagesChanged: if (!locked && !isTour && gid !== "" && Qt.application.state === Qt.ApplicationActive) Chat.markGroupRead(gid, channelId)
    Component.onCompleted: { Vault.back(); if (!locked && !isTour && gid !== "") { Chat.openChat = chatKey; Chat.markGroupRead(gid, channelId) } }
    Component.onDestruction: { Chat.openChat = ""; Voice.cancel(); Voice.stop(); Vault.away() }

    // ============================================================= channel column
    // The desktop can hide the channel list (Ctrl+Shift+L); the controller still opens it with Left.
    readonly property bool listHidden: !chanCol.visible
    readonly property bool membersVisible: memberCol.visible
    Rectangle {
        id: chanCol
        visible: Theme.listShown || page.zone === "channels"
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        anchors.bottomMargin: Ui.dock
        width: visible ? Theme.listWidth : 0
        color: Theme.column

        PageHeader { id: chanHead; width: parent.width; title: page.locked ? "Locked" : (page.info.name || "Group") }

        ListView {
            id: chanList
            visible: !page.locked && page.exists
            anchors.top: chanHead.bottom
            anchors.topMargin: Theme.px(12)
            anchors.bottom: voiceStrip.top
            anchors.bottomMargin: Theme.px(6)
            x: Theme.px(10)
            width: parent.width - Theme.px(20)
            spacing: Theme.px(2)
            clip: true
            model: page.rows
            currentIndex: page.chRow
            highlightFollowsCurrentItem: false
            onCurrentIndexChanged: positionViewAtIndex(currentIndex, ListView.Contain)
            delegate: Item {
                id: rowItem
                readonly property bool header: modelData.kind === "header"
                readonly property bool voice: modelData.type === "voice"
                readonly property bool on: page.active && page.zone === "channels" && page.chRow === index
                readonly property bool here: !header && (voice ? Rooms.inRoom && Rooms.group === page.gid && Rooms.room === modelData.id : modelData.id === page.channelId)
                readonly property var people: header ? [] : page.roomPeople(modelData.id)
                width: chanList.width
                height: header ? Theme.px(index === 0 ? 30 : 42) : Theme.px(Theme.desktop ? 40 : 48) + (people.length ? Theme.px(36) : 0)
                // A category: its name, a fold arrow, and what a folded one hides that's new.
                Rectangle {
                    visible: rowItem.header
                    anchors.bottom: parent.bottom
                    width: parent.width
                    height: Theme.px(28)
                    radius: Theme.px(6)
                    color: rowItem.on ? Theme.tile : "transparent"
                    border.width: rowItem.on ? Theme.focusWidth : 0
                    border.color: Theme.accent
                    Accessible.role: Accessible.Button
                    Accessible.name: (modelData.cat === "voice" ? "Voice rooms" : "Text channels") + (modelData.folded ? ", folded" : "")
                    Accessible.onPressAction: page.foldCat(modelData.cat)
                    Icon {
                        id: foldIcon
                        x: Theme.px(4)
                        anchors.verticalCenter: parent.verticalCenter
                        name: "down"; size: Theme.px(12); lineWidth: 2
                        rotation: modelData.folded ? -90 : 0
                        color: catHover.hovered ? Theme.text : Theme.textMuted
                    }
                    Text {
                        anchors.left: foldIcon.right; anchors.leftMargin: Theme.px(4)
                        anchors.verticalCenter: parent.verticalCenter
                        text: modelData.text || ""
                        color: catHover.hovered ? Theme.text : Theme.textMuted
                        font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold; font.letterSpacing: Theme.px(1.5)
                    }
                    CountBadge {
                        anchors.right: parent.right; anchors.rightMargin: Theme.px(10)
                        anchors.verticalCenter: parent.verticalCenter
                        unread: modelData.unread || 0
                        mentions: modelData.mentions || 0
                    }
                    HoverHandler { id: catHover; cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: { page.zone = "channels"; page.chRow = index; page.foldCat(modelData.cat) } }
                }
                Rectangle {
                    id: chanTile
                    visible: !rowItem.header
                    width: parent.width
                    height: Theme.px(Theme.desktop ? 40 : 48)
                    radius: Theme.px(9)
                    color: rowItem.on || rowItem.here ? Theme.tile : chanHover.hovered ? Theme.raised : "transparent"
                    border.width: rowItem.on ? Theme.focusWidth : 0
                    border.color: Theme.accent
                    Accessible.role: Accessible.ListItem
                    Accessible.name: (rowItem.voice ? "Voice room " : "Channel ") + (modelData.name || "")
                    Icon {
                        id: chanIcon
                        anchors.left: parent.left; anchors.leftMargin: Theme.px(10)
                        anchors.verticalCenter: parent.verticalCenter
                        name: rowItem.voice ? "speaker" : "hash"
                        size: Theme.px(18)
                        color: Theme.textMuted
                    }
                    Text {
                        anchors.left: chanIcon.right; anchors.leftMargin: Theme.px(10)
                        anchors.right: unreadDot.left; anchors.rightMargin: Theme.px(6)
                        anchors.verticalCenter: parent.verticalCenter
                        elide: Text.ElideRight
                        text: modelData.name || ""
                        color: rowItem.on || rowItem.here || modelData.unread > 0 ? Theme.text : Theme.textSoft
                        font.family: Theme.body; font.pixelSize: Theme.fs(16); font.weight: modelData.unread > 0 ? Font.Bold : Font.Normal
                    }
                    // Unread: a dot; @mentions of me: how many, like Discord.
                    CountBadge {
                        id: unreadDot
                        anchors.right: parent.right; anchors.rightMargin: Theme.px(10)
                        anchors.verticalCenter: parent.verticalCenter
                        unread: rowItem.header ? 0 : modelData.unread || 0
                        mentions: rowItem.header ? 0 : modelData.mentions || 0
                    }
                    HoverHandler { id: chanHover }
                    TapHandler { onTapped: { page.zone = "channels"; page.chRow = index; page.openRow() } }
                }
                // Who's in a voice room, as small avatars under it.
                Row {
                    visible: rowItem.people.length > 0
                    anchors.top: chanTile.bottom
                    anchors.topMargin: Theme.px(4)
                    x: Theme.px(38)
                    spacing: Theme.px(6)
                    Repeater {
                        model: rowItem.people
                        Rectangle {
                            width: Theme.px(28); height: width; radius: width / 2
                            color: Theme.avatar(modelData.name, !!modelData.me).bg
                            // A gold ring while they talk.
                            border.width: modelData.speaking ? Theme.px(2) : 0
                            border.color: Theme.accent
                            Accessible.role: Accessible.StaticText
                            Accessible.name: modelData.name + (modelData.speaking ? ", speaking" : "")
                            Text { anchors.centerIn: parent; text: page.initial(modelData.name); color: Theme.avatar(modelData.name, !!modelData.me).fg; font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold }
                        }
                    }
                }
            }
        }
        Text {
            visible: page.locked || !page.exists
            anchors.top: chanHead.bottom
            anchors.topMargin: Theme.px(16)
            x: Theme.px(20)
            width: parent.width - Theme.px(40)
            wrapMode: Text.WordWrap
            text: page.locked ? "Chat is locked. Open Direct messages to unlock it." : "This group isn't on this device anymore."
            color: Theme.textMuted
            font.family: Theme.body; font.pixelSize: Theme.fs(15)
        }

        // Voice rooms wait for calls; and how Huginn reaches people right now.
        Rectangle {
            id: voiceStrip
            anchors.bottom: parent.bottom
            width: parent.width
            height: Theme.px(44)
            color: Theme.groundDeep
            Row {
                anchors.left: parent.left; anchors.leftMargin: Theme.px(14)
                anchors.verticalCenter: parent.verticalCenter
                spacing: Theme.px(8)
                Icon { anchors.verticalCenter: parent.verticalCenter; name: "shield"; size: Theme.px(15); color: Chat.internet > 0 ? Theme.accent : Theme.textMuted }
                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    width: voiceStrip.width - Theme.px(50)
                    elide: Text.ElideRight
                    text: Chat.netStatus + " · encrypted" + (isTour || !exists ? "" : " · " + (Chat.groups, Chat.groupLimit(gid)).days + " days")
                    color: Theme.textSoft
                    font.family: Theme.body; font.pixelSize: Theme.fs(13)
                }
            }
        }
    }

    // ============================================================= conversation
    Item {
        id: main
        anchors.left: chanCol.right
        anchors.right: sidePanel.visible && page.sideDocked ? sidePanel.left : memberCol.visible ? memberCol.left : parent.right
        anchors.top: parent.top
        anchors.bottom: parent.bottom

        PageHeader {
            id: convHead
            width: parent.width
            carved: false
            title: page.locked || !page.exists ? "" : "# " + page.channel.name
            subtitle: page.locked || !page.exists ? "" : page.readOnly ? "Read only: you're no longer in " + page.info.name
                    : "Encrypted for the " + page.info.count + (page.info.count === 1 ? " person in " : " people in ") + page.info.name
            // Pinned messages of this channel (only on this computer).
            HButton {
                anchors.verticalCenter: parent.verticalCenter
                visible: !page.locked && page.exists
                text: page.pins.length ? "Pins · " + page.pins.length : "Pins"
                fontSize: 15
                onClicked: page.side = page.side === "pins" ? "" : "pins"
            }
            // The desktop: show or hide the channel list and the member list.
            PaneToggle { which: "list" }
            PaneToggle { which: "members"; visible: Theme.desktop && page.membersFit && !page.locked && page.exists }
        }

        Item {
            id: pane
            visible: !page.locked && page.exists
            anchors.top: convHead.bottom
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: compose.visible ? compose.top : parent.bottom
            clip: true

            // Removed or left: say so, and what to do with it.
            Rectangle {
                id: gone
                visible: page.readOnly
                anchors.top: parent.top; anchors.left: parent.left; anchors.right: parent.right
                anchors.margins: Theme.px(12)
                height: visible ? goneText.implicitHeight + Theme.px(20) : 0
                radius: Theme.px(8); color: Theme.dangerFill; border.color: Theme.danger; border.width: 1
                Text {
                    id: goneText
                    x: Theme.px(14); y: Theme.px(10); width: parent.width - Theme.px(28)
                    wrapMode: Text.WordWrap
                    text: (page.info.left ? "You left " : "You were removed from ") + (page.info.name || "this group") + ". The messages stay here until you delete it: X, then Delete from Huginn."
                    color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15)
                }
            }

            ListView {
                id: list
                anchors.top: gone.visible ? gone.bottom : parent.top
                anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
                anchors.leftMargin: Theme.px(14); anchors.rightMargin: Theme.px(14)
                anchors.topMargin: Theme.px(12); anchors.bottomMargin: Theme.px(12)
                spacing: Theme.px(8)
                model: page.messages
                onCountChanged: positionViewAtEnd()
                delegate: MessageRow {
                    host: page
                    width: list.width
                    m: modelData
                    at: index
                    picked: page.active && page.zone === "messages" && page.sel === index
                    lineH: index === page.newFrom ? Theme.px(30) : 0
                    hoverTools: !page.readOnly
                    onChosen: { page.zone = "messages"; page.sel = index }
                    onOptions: { page.zone = "messages"; page.sel = index; page.messageOptions() }
                }
            }
            Text {
                anchors.centerIn: parent
                width: parent.width * 0.7
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.WordWrap
                visible: list.count === 0
                text: page.readOnly ? "Nothing here." : "Nothing in #" + page.channel.name + " yet. Press A to write, hold Y to talk."
                color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(16)
            }

            Rectangle {
                anchors.fill: parent
                visible: page.viewing !== ""
                color: "black"
                Image { anchors.fill: parent; anchors.margins: Theme.px(10); fillMode: Image.PreserveAspectFit; source: page.viewing !== "" ? Chat.photoUrl(page.viewing) : "" }
                TapHandler { onTapped: page.viewing = "" }
            }
            Rectangle {
                anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
                anchors.margins: Theme.px(12)
                height: Theme.px(52)
                visible: Voice.recording
                radius: Theme.px(8); color: Theme.dangerFill; border.color: Theme.danger; border.width: 1
                Row {
                    anchors.verticalCenter: parent.verticalCenter
                    x: Theme.px(16); spacing: Theme.px(12)
                    Rectangle { width: Theme.px(12); height: width; radius: width / 2; color: Theme.danger; anchors.verticalCenter: parent.verticalCenter }
                    Text { text: Voice.length(Voice.elapsed) + "   Let go of Y to send · B to cancel"; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(17) }
                }
            }

            // The "New messages" line is out of view: a button to go there.
            Rectangle {
                readonly property bool lineInView: {
                    list.contentY; list.height
                    const it = page.newFrom >= 0 ? list.itemAtIndex(page.newFrom) : null
                    return !!it && it.y + it.height > list.contentY && it.y < list.contentY + list.height
                }
                visible: page.newFrom >= 0 && !lineInView && list.count > 0
                anchors.horizontalCenter: parent.horizontalCenter
                y: Theme.px(10)
                width: jumpText.implicitWidth + Theme.px(28); height: Theme.px(32); radius: height / 2
                color: Theme.danger
                z: 3
                Accessible.role: Accessible.Button
                Accessible.name: "Jump to new messages"
                Text { id: jumpText; anchors.centerIn: parent; text: "New messages · jump"; color: Theme.ground; font.family: Theme.body; font.pixelSize: Theme.fs(13); font.weight: Font.DemiBold }
                HoverHandler { cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: list.positionViewAtIndex(page.newFrom, ListView.Beginning) }
            }

            // Drop pictures (or other files) on the channel to send them.
            DropArea {
                anchors.fill: parent
                enabled: !page.readOnly && !page.isTour
                onDropped: drop => { if (drop.hasUrls) page.dropFiles(drop.urls); drop.accept() }
                Rectangle {
                    anchors.fill: parent
                    visible: parent.containsDrag
                    color: Qt.rgba(0, 0, 0, 0.55)
                    border.color: Theme.accent; border.width: 2; radius: Theme.px(10)
                    Text { anchors.centerIn: parent; text: "Drop to send to #" + page.channel.name; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(20) }
                }
            }

            EmojiPicker {
                id: reactPicker
                quick: true
                anchors.centerIn: parent
                onPicked: e => { page.reactTo(page.reactFor, e === current ? "" : e); visible = false }
            }
        }

        // The message box, docked at the bottom and typed into directly (A puts the controller in it).
        Composer {
            id: compose
            visible: !page.locked && page.exists && !page.readOnly
            anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
            anchors.leftMargin: Theme.px(20); anchors.rightMargin: Theme.px(20); anchors.bottomMargin: Theme.px(18)
            height: implicitHeight
            placeholder: "Message #" + page.channel.name
            target: page.isTour ? "" : page.chatKey
            mentionNames: page.memberNames
            editing: page.editId !== ""
            bannerTitle: page.editId !== "" ? "Editing your message" : page.replyMsg ? "Replying to " + (page.replyMsg.mine ? "yourself" : page.replyMsg.name) : ""
            bannerText: page.editId !== "" ? "Enter saves, Esc cancels" : page.replyMsg ? (page.replyMsg.photo ? (page.replyMsg.voice ? "Voice message" : "Photo") : page.replyMsg.text || "") : ""
            onSend: t => page.sendText(t, page.replyId)
            onHasFocusChanged: if (hasFocus) { page.zone = "messages"; page.sel = -1 }
            onEditLast: page.editLast()
            onEscaped: page.escapeKey()
            onCancelBanner: { page.replyId = ""; if (page.editId !== "") { page.editId = ""; compose.text = "" } }
            onAttach: page.pickPicture()
        }
    }

    // ============================================================= side panel: a thread or the pins
    property alias threadCompose: threadBox
    Rectangle {
        id: sidePanel
        visible: !page.locked && page.exists && page.side !== ""
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        width: Math.min(Theme.px(360), page.width - chanCol.width - Theme.px(48))
        color: Theme.column
        // Over the conversation on a narrow screen: an edge so it reads as on top.
        Rectangle { visible: !page.sideDocked; width: 1; height: parent.height; color: Theme.lineStrong }
        PageHeader {
            id: sideHead
            width: parent.width
            title: page.side === "thread" ? "Thread" : "Pinned"
            subtitle: page.side === "thread" ? "#" + page.channel.name : page.pinnedMsgs.length ? page.pinnedMsgs.length + " in #" + page.channel.name + " · only on this computer" : "Nothing pinned in #" + page.channel.name
            HButton { anchors.verticalCenter: parent.verticalCenter; text: "Close"; fontSize: 14; onClicked: { page.side = ""; page.threadRoot = "" } }
        }
        ListView {
            id: sideList
            anchors.top: sideHead.bottom; anchors.topMargin: Theme.px(10)
            anchors.bottom: threadBox.visible ? threadBox.top : parent.bottom; anchors.bottomMargin: Theme.px(10)
            x: Theme.px(8); width: parent.width - Theme.px(16)
            spacing: Theme.px(6)
            clip: true
            model: page.side === "thread" ? page.threadMsgs : page.pinnedMsgs
            onCountChanged: if (page.side === "thread") positionViewAtEnd()
            delegate: MessageRow {
                host: page
                width: sideList.width
                m: modelData
                at: index
                inThread: true
                picked: page.active && page.zone === "side" && page.sideSel === index
                hoverTools: !page.readOnly
                onChosen: { page.zone = "side"; page.sideSel = index }
                onOptions: page.sideOptions(index)
            }
        }
        Text {
            visible: page.side === "pins" && page.pinnedMsgs.length === 0
            anchors.top: sideHead.bottom; anchors.topMargin: Theme.px(16)
            x: Theme.px(16); width: parent.width - Theme.px(32)
            wrapMode: Text.WordWrap
            text: "Pin a message with the pin over its corner. Pins stay on this computer."
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(15)
        }
        Composer {
            id: threadBox
            visible: page.side === "thread" && !page.readOnly
            anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
            anchors.margins: Theme.px(12)
            height: implicitHeight
            placeholder: "Reply in the thread"
            target: page.isTour ? "" : page.chatKey
            mentionNames: page.memberNames
            onSend: t => page.sendText(t, page.threadRoot)
            onHasFocusChanged: if (hasFocus) { page.zone = "side"; page.sideSel = -1 }
            onEscaped: { page.side = ""; page.threadRoot = ""; compose.focusField() }
            onAttach: page.pickPicture()
        }
    }

    component CountBadge: Rectangle {
        property int unread: 0
        property int mentions: 0
        visible: unread > 0 || mentions > 0
        width: mentions > 0 ? Math.max(height, countText.implicitWidth + Theme.px(10)) : Theme.px(8)
        height: mentions > 0 ? Theme.px(18) : Theme.px(8)
        radius: height / 2
        color: mentions > 0 ? Theme.danger : Theme.accent
        Accessible.role: Accessible.StaticText
        Accessible.name: mentions > 0 ? mentions + (mentions === 1 ? " mention" : " mentions") : unread + " unread"
        Text {
            id: countText
            visible: parent.mentions > 0
            anchors.centerIn: parent
            text: parent.mentions > 99 ? "99+" : String(parent.mentions)
            color: Theme.ground
            font.family: Theme.body; font.pixelSize: Theme.fs(11); font.weight: Font.Bold
        }
    }

    // ============================================================= member column (folds away when narrow)
    // Who's in a voice room, online, idle or neither (MemberList.qml). The desktop can hide it
    // (Ctrl+Shift+U); the controller still reaches it with Right.
    MemberList {
        id: memberCol
        host: page
        visible: page.membersFit && !page.locked && page.exists && !sidePanel.visible && (Theme.membersShown || page.zone === "members")
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        width: Theme.memberWidth
    }
    // A member's profile card, with what an admin can do with them behind Group options.
    function showMember(m) {
        if (!m) return
        const status = memberCol.statusOf(m)
        const extra = { group: info.name, admin: !!m.admin, owner: !!m.owner, room: status === "voice" ? memberCol.voiceIn[m.me ? "me" : m.key] : "" }
        if (isTour) { extra.tour = true; extra.status = status }
        else if (!m.me && !readOnly) extra.g = gid   // "Add as a contact" (cap i1) for someone who isn't one yet
        else if (!m.me && !readOnly && info.amAdmin) extra.more = () => page.memberOptions(m)
        Ui.showProfile(m.me ? "me" : m.key, m.name, extra)
    }

    // ============================================================= the group code
    Rectangle {
        anchors.fill: parent
        visible: page.shownCode !== ""
        color: Qt.rgba(0, 0, 0, 0.85)
        Rectangle {
            anchors.centerIn: parent
            width: Theme.px(560); height: codeCol.implicitHeight + Theme.px(40)
            color: Theme.panel; radius: Theme.px(10); border.color: Theme.line; border.width: 1
            Column {
                id: codeCol
                x: Theme.px(20); y: Theme.px(20); width: parent.width - Theme.px(40)
                spacing: Theme.px(12)
                Text { text: "Join " + (page.info.name || ""); color: Theme.text; font.family: Theme.display; font.pixelSize: Theme.fs(21); font.weight: Font.DemiBold }
                Text { width: parent.width; wrapMode: Text.WordWrap; text: "Anyone who scans this with Muninn, or pastes it into Huginn's Add someone, joins the group. It works for an hour; remove anyone you didn't mean to let in from Members."; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(16) }
                Text {
                    property real now: Date.now()
                    Timer { interval: 10000; repeat: true; running: page.shownCode !== ""; onTriggered: parent.now = Date.now() }
                    text: page.codeUntil > now ? "Good for " + Math.ceil((page.codeUntil - now) / 60000) + " more minutes · X: new code" : "This code has expired · X: new code"
                    color: page.codeUntil > now ? Theme.accent : Theme.danger; font.family: Theme.body; font.pixelSize: Theme.fs(15)
                }
                Rectangle {
                    anchors.horizontalCenter: parent.horizontalCenter
                    width: Theme.px(300); height: width; color: Theme.text
                    Image { anchors.fill: parent; anchors.margins: Theme.px(14); smooth: false; source: page.shownCode !== "" ? "image://qr/" + encodeURIComponent(page.shownCode) : "" }
                }
                Text { width: parent.width; text: "A: copy the code · B: close"; horizontalAlignment: Text.AlignHCenter; color: Theme.textFaint; font.family: Theme.body; font.pixelSize: Theme.fs(14) }
            }
        }
        TapHandler { onTapped: page.shownCode = "" }
    }

    // ============================================================= actions
    function tell(result, done) { if (result === "") { if (done) Ui.say(done) } else Ui.say(result, true) }
    function openRow() {
        const r = rows[chRow]
        if (r && r.kind === "header") { foldCat(r.cat); return }
        if (!r || r.kind !== "channel") return
        if (r.type === "voice") { roomAction(r); return }
        channelId = r.id
        zone = "messages"
    }
    // A voice room or group call: one tap joins; in it already, its options.
    function roomAction(r) {
        if (isTour) { Ui.say(r.name + " is a voice room: choose it to join, up to " + Rooms.maxPeople + " people at once."); return }
        if (readOnly) { Ui.say("You're no longer in this group.", true); return }
        if (Rooms.isIn(gid, r.id)) {
            Ui.pickFrom(r.name, [{ header: Rooms.statusText, items: [
                { name: Rooms.muted ? "Unmute" : "Mute", url: "mute" },
                { name: Rooms.deafened ? "Undeafen" : "Deafen", detail: "Deafened, you hear nobody and you're muted", url: "deafen" },
                { name: Rooms.sharing ? "Stop sharing your screen" : "Share your screen", detail: Rooms.canShare ? "Everyone in the room sees it, encrypted to each" : "Not in this build", url: "share" },
                { name: "Output: " + Rooms.outputName, detail: "Choose again for the next one", url: "output" },
                { name: "Leave " + r.name, detail: "You stop hearing and being heard", url: "leave" }] }], a => {
                if (a === "mute") Rooms.toggleMute()
                else if (a === "deafen") Rooms.toggleDeafen()
                else if (a === "share" && Rooms.canShare) Rooms.toggleShare()
                else if (a === "output") Rooms.nextOutput()
                else if (a === "leave") Rooms.leave()
            })
            return
        }
        const why = Rooms.join(gid, r.id)
        if (why !== "") Ui.say(why, true)
        else Ui.say("You joined " + r.name + ". Up to " + Rooms.maxPeople + " people at once.")
    }
    // Ring some members into a group call: choose people (again to leave them out), then Start.
    function pickCallees(chosen) {
        const g = gid
        const items = [{ name: chosen.length ? "Start the call" : "Choose who to ring",
                         detail: chosen.length ? chosen.length + (chosen.length === 1 ? " person" : " people") + " will be rung" : "Then come back here", url: "go" }]
        members.filter(m => !m.me).forEach(m => items.push({ name: m.name, url: m.key,
            detail: Rooms.needsUpdate(m.key) ? "Needs an update to join calls"
                  : chosen.indexOf(m.key) >= 0 ? "Will be rung · choose again to leave out" : "Choose to ring" }))
        Ui.pickFrom("Call some people", [{ header: "Up to " + Rooms.maxPeople + " people talk at once", items: items }], k => {
            if (k === "go") {
                if (chosen.length) tell(Rooms.callGroup(g, chosen), "Ringing " + chosen.length + (chosen.length === 1 ? " person." : " people."))
                else Qt.callLater(() => page.pickCallees(chosen))
                return
            }
            const next = chosen.indexOf(k) >= 0 ? chosen.filter(x => x !== k) : chosen.concat([k])
            Qt.callLater(() => page.pickCallees(next))
        })
    }
    function write() {
        if (readOnly) { Ui.say("You're no longer in this group.", true); return }
        if (isTour) return
        if (compose.visible) { zone = "messages"; sel = -1; compose.focusField(); return }
        const g = gid, ch = channelId, re = replyId
        Ui.ask(re !== "" && replyMsg ? "Reply to " + replyMsg.name : "Message #" + channel.name, re !== "" && replyMsg ? (replyMsg.text || "Photo") : "Encrypted for everyone in " + info.name, "", "Send",
               t => { Chat.sendGroup(g, ch, t, re); page.replyId = "" }, false, true)
    }
    // Pictures (several at once as an album), a video message, a video file or a poll (MoreLayer).
    function pickPicture() {
        if (readOnly || isTour) return
        more.attach()
    }
    function showCode() {
        if (isTour) return
        const c = Chat.groupCode(gid)
        if (c === "") { Ui.say("Only the group's admins can share its code.", true); return }
        shownCode = c
        codeUntil = Date.now() + 3600000
    }
    function invite() {
        if (isTour) return
        const inGroup = members.map(m => m.key)
        const people = Chat.people.filter(p => p.contact && !p.blocked && inGroup.indexOf(p.key) < 0)
        if (!people.length) { Ui.say("Everyone you're in touch with is already here. For anyone else, show the group code.", true); return }
        const g = gid
        Ui.pickFrom("Invite to " + info.name, [{ header: "Contacts", items: people.map(p => ({ name: p.name, url: p.key,
            detail: Chat.canGroup(p.key) ? "Gets an invite to say yes to" : Chat.capsKnown(p.key) ? "Needs Muninn or Huginn 0.6" : "Checking their app first" })) }],
            k => tell(Chat.inviteToGroup(g, k), "Invite sent. They join when they say yes."))
    }
    function groupItems() {
        const people = [], chans = [], group = [], calls = []
        if (!readOnly && !isTour && info.count > 1) {
            calls.push({ name: "Call everyone", detail: "Rings the whole group. Up to " + Rooms.maxPeople + " people talk at once", url: "callall" })
            if (info.count > 2) calls.push({ name: "Call some people", detail: "Choose who to ring", url: "callsome" })
        }
        if (!readOnly && info.amAdmin) {
            people.push({ name: "Invite someone", detail: "A contact gets an invite to say yes to", url: "invite" })
            people.push({ name: "Show the group code", detail: "For anyone else: they scan or paste it within the hour", url: "code" })
        }
        if (!memberCol.visible) people.push({ name: "Members", detail: info.count + (info.count === 1 ? " person" : " people"), url: "members" })
        if (!readOnly && info.amAdmin) {
            chans.push({ name: "Add a text channel", url: "addtext" }, { name: "Add a voice room", detail: "Up to " + Rooms.maxPeople + " people talk at once", url: "addvoice" })
            const r = rows[chRow]
            const target = zone === "channels" && r && r.kind === "channel" ? r : channel
            chans.push({ name: "Rename " + (target.type === "voice" ? "" : "#") + target.name, url: "rnch:" + target.id })
            if (target.id !== "main") chans.push({ name: "Remove " + (target.type === "voice" ? "" : "#") + target.name, detail: "Its messages stay on this device", url: "rmch:" + target.id })
            group.push({ name: "Rename the group", detail: "Now: " + info.name, url: "rename" })
        }
        if (!isTour) chans.push({ name: "Pinned in #" + channel.name, detail: pins.length ? pins.length + " · only on this computer" : "Nothing pinned yet", url: "pins" })
        if (!isTour) group.push({ name: "Messages", detail: limitDetail(), url: "limit" })
        if (!readOnly) group.push({ name: "Send pictures, a video or a poll", detail: "To #" + channel.name, url: "picture" })
        if (!isTour) chans.push({ name: "Shared in #" + channel.name, detail: "Photos, videos and links", url: "shared" })
        if (readOnly) group.push({ name: "Delete from Huginn", detail: "Its messages and photos on this device", url: "forget" })
        else group.push({ name: "Leave the group", detail: info.amOwner && info.count > 1 ? "Hand it to someone first (Members)" : "You stop getting its messages", url: "leave" })
        return [{ header: "Calls", items: calls }, { header: "People", items: people }, { header: "Channels", items: chans }, { header: "Group", items: group }]
    }
    function groupOptions() {
        if (!exists) return
        Ui.pickFrom(info.name || "Group", groupItems(), a => groupAction(a))
    }
    function groupAction(a) {
        const g = gid
        {
            if (a === "invite") invite()
            else if (a === "callall") tell(Rooms.callGroup(g, []), "Ringing everyone in " + info.name + ".")
            else if (a === "callsome") pickCallees([])
            else if (a === "code") showCode()
            else if (a === "members") pickMember()
            else if (a === "pins") openPins()
            else if (a === "shared") more.openShared()
            else if (a === "limit") pickGroupLimit()
            else if (a === "picture") pickPicture()
            else if (a === "addtext" || a === "addvoice") {
                const voice = a === "addvoice"
                Ui.ask(voice ? "New voice room" : "New text channel", "Up to 32 characters", "", "Add", t => tell(Chat.addChannel(g, t, voice), voice ? "Voice room added." : "Channel added."), false, true)
            } else if (a.startsWith("rnch:")) {
                const id = a.substring(5)
                const c = channels.find(x => x.id === id)
                Ui.ask("Rename " + (c ? c.name : "channel"), "Up to 32 characters", c ? c.name : "", "Save", t => tell(Chat.renameChannel(g, id, t), "Renamed."), false, true)
            } else if (a.startsWith("rmch:")) {
                const id = a.substring(5)
                const c = channels.find(x => x.id === id)
                Ui.sure("Remove " + (c ? c.name : "this channel") + "?", "It goes for everyone in the group. Messages already here stay on this device, in #general.", "Remove",
                        () => { if (channelId === id) channelId = "main"; tell(Chat.removeChannel(g, id), "Removed.") }, false, true)
            } else if (a === "rename") {
                Ui.ask("Rename the group", "Everyone in it sees the new name", info.name, "Save", t => tell(Chat.renameGroup(g, t), "Renamed."), false, true)
            } else if (a === "leave") {
                if (info.amOwner && info.count > 1) { Ui.say("You own this group. Hand it to someone first: Members, then Make owner.", true); return }
                Ui.sure("Leave " + info.name + "?", "You stop getting its messages. To come back, someone has to invite you again.", "Leave", () => tell(Chat.leaveGroup(g), "You left the group."), false, true)
            } else if (a === "forget") {
                Ui.sure("Delete " + info.name + " from Huginn?", "Its messages and photos on this device are deleted. Nobody else is affected.", "Hold to delete",
                        () => { Chat.forgetGroup(g); Ui.openPage("dms", {}) }, true, true)
            }
        }
    }
    // Cap d1: the group's type and how many days messages stay, the same for everyone. Only admins change it.
    function limitDetail() {
        const l = Chat.groupLimit(gid)
        return l.kindName + " group · messages disappear after " + l.days + " days"
    }
    function pickGroupLimit() {
        const g = gid, l = Chat.groupLimit(g)
        const older = l.older.length ? "Older apps keep messages until they update: " + l.older.join(", ") : ""
        if (readOnly || !info.amAdmin) { Ui.say(limitDetail() + (older ? ". " + older + "." : ".")); return }
        const items = [{ name: "Standard · 7 days", detail: l.kind === "" ? "Now" : "", url: ":7" }]
        for (const k of ["family", "work"])
            for (const d of l.choices)
                items.push({ name: (k === "family" ? "Family" : "Work") + " · " + d + " days", detail: l.kind === k && l.days === d ? "Now" : "", url: k + ":" + d })
        Ui.pickFrom("Messages in " + info.name, [{ header: older || "The same for everyone in the group", items: items }], a => {
            const p = a.split(":")
            tell(Chat.setGroupLimit(g, p[0], Number(p[1])), "Messages in " + info.name + " now disappear after " + p[1] + " days.")
        })
    }
    function pickMember() {
        Ui.pickFrom("Members of " + info.name, [{ header: "", items: members.map(m => ({ name: m.name + (m.me ? " (you)" : ""), url: m.key,
            detail: m.owner ? "Owner" : m.admin ? "Admin" : m.contact ? "Contact" : "In this group" })) }],
            k => { const m = members.find(x => x.key === k); if (m) memberOptions(m) })
    }
    function memberOptions(m) {
        if (!m || isTour) return
        const items = []
        if (m.contact && !m.me) items.push({ name: "Message", detail: "Your direct chat", url: "dm" })
        if (!m.contact && !m.me && !readOnly) items.push({ name: "Add as a contact", detail: "They're asked too; once both say yes you can message directly", url: "add" })
        if (!readOnly && !m.me && info.amAdmin) {
            if (!m.admin) items.push({ name: "Make admin", detail: "They can invite, remove and change the group too", url: "admin" })
            else if (info.amOwner && !m.owner) items.push({ name: "Take admin away", url: "unadmin" })
            if (info.amOwner) items.push({ name: "Make owner", detail: "Hands the group to them; you stay an admin", url: "owner" })
            if (!m.owner && (!m.admin || info.amOwner)) items.push({ name: "Remove from the group", detail: "They're told, and stop getting its messages", url: "remove" })
        }
        if (!items.length) { Ui.say(m.me ? "That's you." : m.name + (m.owner ? " owns the group." : m.admin ? " is an admin." : " is in the group.")); return }
        const g = gid
        Ui.pickFrom(m.name, [{ header: m.owner ? "Owner" : m.admin ? "Admin" : "", items: items }], a => {
            if (a === "dm") Ui.openPage("dms", { open: m.key })
            else if (a === "add") PeopleActions.askToAdd(g, m)
            else if (a === "admin") tell(Chat.setAdmin(g, m.key, true), m.name + " is an admin now.")
            else if (a === "unadmin") tell(Chat.setAdmin(g, m.key, false), m.name + " isn't an admin anymore.")
            else if (a === "owner") Ui.sure("Hand " + info.name + " to " + m.name + "?", "They become the owner. You stay an admin, and can leave after.", "Hand it over", () => tell(Chat.handOver(g, m.key), m.name + " owns the group now."), false, true)
            else if (a === "remove") Ui.sure("Remove " + m.name + "?", "They're told, and stop getting the group's messages. Their old messages stay.", "Remove", () => tell(Chat.removeMember(g, m.key), m.name + " was removed."), false, true)
        })
    }
    function messageOptions() {
        const m = messages[sel]
        if (!m || m.system) { groupOptions(); return }
        const items = []
        if (!m.unsent && !readOnly) {
            items.push({ name: "React", detail: m.myReaction ? "Yours: " + m.myReaction : "❤️ 😂 👍 😮 😢 or any emoji", url: "react" })
            items.push({ name: "Reply", detail: "Quotes it in your answer", url: "reply" })
            items.push({ name: "Open thread", detail: (m.replies || 0) + ((m.replies || 0) === 1 ? " reply" : " replies"), url: "thread" })
        }
        if (m.mine && !m.unsent && !m.photo && !readOnly) items.push({ name: "Edit", detail: "Everyone sees it marked (edited)", url: "edit" })
        if (!m.unsent) items.push({ name: pins.indexOf(m.id) >= 0 ? "Unpin" : "Pin", detail: "Only on this computer", url: "pin" })
        if (m.text && !m.unsent) items.push({ name: "Copy text", url: "copy" })
        if (m.photo && !m.voice && !m.unsent) items.push({ name: "View full size", url: "view" }, { name: "Save to Pictures", url: "save" })
        if (m.voice && !m.unsent) items.push({ name: Voice.playing === m.id ? "Stop" : "Play", url: "play" })
        if (m.mine && !m.unsent && !readOnly) items.push({ name: "Unsend", detail: "Removes it for everyone in the group", url: "unsend" })
        if (m.mine && m.failed) items.push({ name: "Try again", url: "retry" })
        items.push({ name: "Delete for me", detail: "Only on this computer", url: "delete" })
        const g = gid
        Ui.pickFrom("Message", [{ header: "", items: items }].concat(groupItems()), a => {
            if (a === "react") pickReaction(m)
            else if (a === "reply") startReply(m.id)
            else if (a === "thread") openThread(m.id)
            else if (a === "edit") startEdit(m)
            else if (a === "pin") togglePin(m.id)
            else if (a === "copy") { Chat.copyText(m.text); Ui.say("Copied.") }
            else if (a === "view") viewing = m.photo
            else if (a === "save") Ui.say(Chat.savePhoto(m.photo) ? "Saved in Pictures › Huginn." : "Couldn't save it.")
            else if (a === "play") { if (Voice.playing === m.id) Voice.stop(); else Voice.play(m.photo) }
            else if (a === "unsend") Chat.unsendGroup(g, m.id)
            else if (a === "retry") Chat.retryGroup(g, m.id)
            else if (a === "delete") Chat.deleteGroupMessage(g, m.id)
            else groupAction(a)
        })
    }
    // ------------------------------------------------------------- cap m1: replies, reactions, edits, threads, pins
    function jumpTo(id) {
        const i = messages.findIndex(m => m.id === id)
        if (i < 0) { Ui.say("That message is in another channel or isn't here anymore."); return }
        sel = i
        list.positionViewAtIndex(i, ListView.Center)
    }
    function reactTo(id, e) {
        if (isTour || id === "") return
        const r = Chat.reactGroup(gid, id, e)
        if (r !== "") Ui.say(r, true)
    }
    function openReact(id, mine) {
        if (isTour || readOnly) return
        reactFor = id
        reactPicker.current = mine
        reactPicker.visible = true
    }
    function startReply(id) {
        if (readOnly) return
        editId = ""
        replyId = id
        write()
    }
    function startEdit(m) {
        if (!m || !m.mine || m.photo || m.unsent || readOnly) return
        replyId = ""
        if (compose.visible) { editId = m.id; zone = "messages"; sel = -1; compose.setText(m.text) }
        else { const g = gid, id = m.id; Ui.ask("Edit your message", "Everyone sees it marked (edited)", m.text, "Save", t => tell(Chat.editGroup(g, id, t), ""), false, true) }
    }
    function editLast() {
        for (let i = messages.length - 1; i >= 0; i--) {
            const m = messages[i]
            if (m.mine && !m.photo && !m.unsent && !m.failed && !m.system) { startEdit(m); return }
        }
    }
    function escapeKey() {
        replyId = ""; editId = ""; newFrom = -1
        if (!isTour && gid !== "") Chat.markGroupRead(gid, channelId)
    }
    function sendText(t, re) {
        if (isTour || readOnly) return
        if (editId !== "") { tell(Chat.editGroup(gid, editId, t), ""); editId = ""; return }
        Chat.sendGroup(gid, channelId, t, re || "")
        replyId = ""
        newFrom = -1
    }
    // A thread beside the channel. The controller (or its pointer) goes into the panel; a mouse
    // gets a cursor in its reply box.
    function openThread(id) {
        threadRoot = id
        side = "thread"
        if (pad) { zone = "side"; sideSel = threadMsgs.length - 1 }
        else Qt.callLater(() => threadBox.focusField())
    }
    function openPins() {
        side = "pins"
        if (pad) { zone = "side"; sideSel = pinnedMsgs.length ? 0 : -1 }
    }
    function sideMsgs() { return side === "thread" ? threadMsgs : side === "pins" ? pinnedMsgs : [] }
    // Options for a message in the panel: the same as in the channel.
    function sideOptions(i) {
        const m = sideMsgs()[i]
        if (!m) return
        zone = "side"; sideSel = i
        const at = messages.findIndex(x => x.id === m.id)
        if (at >= 0) { sel = at; messageOptions() }
    }
    function togglePin(id) {
        if (isTour) return
        const on = pins.indexOf(id) < 0
        Chat.setPinned(chatKey, id, on)
        Ui.say(on ? "Pinned. Pins are only on this computer." : "Unpinned.")
    }
    function dropFiles(urls) { more.dropFiles(urls) }
    function pickReaction(m) {
        const g = gid, id = m.id
        const items = Chat.quickReactions().map(e => ({ name: e, detail: e === m.myReaction ? "Yours · choose to take it back" : "", url: e }))
        if (m.myReaction && Chat.quickReactions().indexOf(m.myReaction) < 0) items.push({ name: m.myReaction, detail: "Yours · choose to take it back", url: m.myReaction })
        items.push({ name: "More emoji", url: "+" })
        Ui.pickFrom("React", [{ header: "", items: items }], e => {
            if (e === "+") { openReact(id, m.myReaction || ""); return }
            tell(Chat.reactGroup(g, id, e === m.myReaction ? "" : e), "")
        })
    }

    // ------------------------------------------------------------- controller
    function step(dir) {
        let i = chRow
        do { i += dir } while (i >= 0 && i < rows.length && !selectable(i))
        if (i >= 0 && i < rows.length) chRow = i
    }
    // The list the left stick scrolls when the pointer isn't showing: the one with the focus.
    function scrollView() { return zone === "messages" ? list : zone === "side" ? sideList : zone === "members" ? memberCol.flick : chanList }
    // The controller in a message box: A sends (or brings Steam's keyboard back), Y brings the
    // keyboard back, LT the box's options, B leaves it. Up and the rest leave it and act as usual.
    function boxButton(box, b) {
        switch (b) {
        case "a": case "start": if (box.text.trim() !== "") box.enter(); else System.showSteamKeyboard(); return true
        case "y": System.showSteamKeyboard(); return true
        case "x": boxOptions(box); return true
        case "down": return true
        }
        box.leave()
        return b === "b"
    }
    function boxOptions(box) {
        const items = [{ name: "Send", url: "send" }, { name: "Emoji", detail: "Pick one with the D-pad", url: "emoji" },
                       { name: "Send pictures, a video or a poll", detail: "To #" + channel.name, url: "picture" }]
        if (box.bannerTitle !== "") items.push({ name: editId !== "" ? "Stop editing" : "Don't reply", url: "cancel" })
        items.push({ name: "Done typing", detail: "What you wrote stays in the box", url: "leave" })
        Ui.pickFrom(box.placeholder, [{ header: "", items: items }], a => {
            if (a === "send") box.enter()
            else if (a === "emoji") box.toggleEmoji()
            else if (a === "picture") pickPicture()
            else if (a === "cancel") box.cancelBanner()
            else if (a === "leave") box.leave()
        })
    }
    // A thread or the pins: up and down pick, A answers in the thread or goes to a pin, B closes.
    function sideButton(b) {
        const n = sideMsgs().length
        switch (b) {
        case "up": if (n) sideSel = sideSel < 0 ? n - 1 : Math.max(0, sideSel - 1); return true
        case "down": if (sideSel >= 0) sideSel = Math.min(n - 1, sideSel + 1); return true
        case "left": zone = "messages"; return true
        case "right": return true
        case "a":
            if (side === "thread") { if (threadBox.visible) threadBox.focusField(); else Ui.say("You're no longer in this group.", true) }
            else if (side === "pins" && sideSel >= 0) { const m = sideMsgs()[sideSel]; zone = "messages"; if (m) jumpTo(m.id) }
            return true
        case "x": if (sideSel >= 0) sideOptions(sideSel); else groupOptions(); return true
        case "y": return true
        case "b": side = ""; threadRoot = ""; return true
        }
        return false
    }
    function handleButton(b) {
        if (locked) {
            if (b === "a") { Ui.openPage("dms", {}); return true }
            return false
        }
        if (more.busy) return more.handleButton(b)
        if (shownCode !== "") {
            if (b === "a") { Chat.copyText(shownCode); Ui.say("Code copied. It works for an hour.") }
            else if (b === "x") showCode()
            else if (b === "b") shownCode = ""
            return true
        }
        if (viewing !== "") { if (b === "b" || b === "a") viewing = ""; return true }
        if (Voice.recording) { if (b === "b") { Voice.cancel(); Ui.say("Recording cancelled.") } return true }
        if (!exists) return b !== "left" && b !== "b"
        if (reactPicker.visible) return reactPicker.handleButton(b)
        if (compose.emojiShown) return compose.emojiButton(b)
        if (threadBox.emojiShown) return threadBox.emojiButton(b)
        if (typingIn && boxButton(typingIn, b)) return true
        if (zone === "side") return sideButton(b)
        const memCount = memberCol.ordered.length
        switch (b) {
        case "up":
            if (zone === "channels") step(-1)
            else if (zone === "members") memRow = Math.max(0, memRow - 1)
            else if (messages.length > 0) { sel = sel < 0 ? messages.length - 1 : Math.max(0, sel - 1); list.positionViewAtIndex(sel, ListView.Contain) }
            return true
        case "down":
            if (zone === "channels") step(1)
            else if (zone === "members") memRow = Math.min(memCount - 1, memRow + 1)
            else if (sel >= 0) { sel = Math.min(messages.length - 1, sel + 1); list.positionViewAtIndex(sel, ListView.Contain) }
            return true
        case "right":
            if (zone === "channels") zone = "messages"
            else if (zone === "messages" && sidePanel.visible) { const n = sideMsgs().length; zone = "side"; sideSel = !n ? -1 : side === "thread" ? n - 1 : 0 }
            else if (zone === "messages" && page.membersFit && !sidePanel.visible && memCount > 0) { zone = "members"; memRow = Math.min(memRow, memCount - 1) }
            return true
        case "left":
            if (zone === "channels") return false
            if (zone === "members") zone = "messages"
            else { zone = "channels"; sel = -1 }
            return true
        case "a":
            if (zone === "channels") openRow()
            else if (zone === "members") showMember(memberCol.ordered[memRow])
            else write()
            return true
        case "x":
            if (zone === "messages" && sel >= 0) messageOptions()
            else groupOptions()
            return true
        case "y":
            if (zone === "messages" && !readOnly && !isTour && !Voice.recording) {
                if (!Voice.start(chatKey)) Ui.say("The microphone isn't available right now.", true)
            }
            return true
        case "b":
            if (zone === "members") { zone = "messages"; return true }
            if (zone === "messages") { zone = "channels"; sel = -1; return true }
            return false
        }
        return false
    }
    function handleRelease(b) {
        if (b === "y" && Voice.recording) Voice.finish(chatKey)
        return true
    }
    Connections {
        target: Voice
        function onErrorChanged() { if (Voice.error !== "") Ui.say(Voice.error, true) }
    }
    Connections {
        target: Qt.application
        function onStateChanged() {
            if (Qt.application.state === Qt.ApplicationActive) { Vault.back(); if (!page.locked && !page.isTour && page.gid !== "") Chat.markGroupRead(page.gid, page.channelId) }
            else Vault.away()
        }
    }

    // Cap m2: albums full size, video messages, the camera, polls and the Shared page.
    property alias more: moreLayer
    MoreLayer {
        id: moreLayer
        visible: !page.locked
        where: page.locked || page.readOnly || page.isTour || page.gid === "" ? "" : page.chatKey
        title: "#" + (page.channel.name || "")
        tour: page.isTour
    }
}

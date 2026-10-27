import QtQuick
import Huginn

// A group, Discord style: the channel column (TEXT channels, VOICE rooms), the open channel's
// conversation, and on the desktop the member column. X opens the group's options (invite, the
// group code, channels, members, rename, leave). D-pad left and right move between the columns.
Rectangle {
    id: page
    color: Theme.ground
    readonly property bool active: !Ui.main || Ui.main.focusZone === "content"
    readonly property string gid: Ui.main && Ui.main.pageName.startsWith("g:") ? Ui.main.pageName.substring(2) : ""
    property bool tour: false
    property string zone: "channels"       // "channels", "messages" or "members"
    property string channelId: "main"
    property int chRow: 0
    property int memRow: 0
    property int sel: -1
    property string viewing: ""            // a photo open full size
    property string shownCode: ""          // the group code on screen
    property real codeUntil: 0
    // Desktop with a mouse and keyboard: type straight into the box at the bottom (Game Mode and
    // controllers keep the big TextPrompt).
    readonly property bool typeHere: Theme.desktop && !System.gameMode
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
        zone = a.zone || "channels"
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

    readonly property bool locked: !tour && (!Vault.exists || Vault.screenLocked)
    readonly property bool isTour: tour || gid.startsWith("tour-")

    // ------------------------------------------------------------- what's shown
    readonly property var tourInfo: ({
        g: gid, name: gid === "tour-family" ? "Family" : gid === "tour-kindred" ? "Kindred" : "Gaming Crew", amAdmin: true, amOwner: true, readOnly: false, count: 6,
        channels: [{ id: "main", name: "general", type: "text", unread: 0 }, { id: "s", name: "screenshots", type: "text", unread: 1 },
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
    // The channel column: headers, then text channels, then voice rooms.
    readonly property var rows: {
        const out = [{ kind: "header", text: "TEXT" }]
        channels.filter(c => c.type === "text").forEach(c => out.push(Object.assign({ kind: "channel" }, c)))
        const voice = channels.filter(c => c.type === "voice")
        // Group calls going on in this group show with the voice rooms, while anyone is in them.
        const calls = isTour || locked ? [] : (Rooms.presence, Rooms.inRoom, Rooms.groupCalls(gid))
        if (voice.length || calls.length) {
            out.push({ kind: "header", text: "VOICE" })
            voice.forEach(c => out.push(Object.assign({ kind: "channel" }, c)))
            calls.forEach(c => out.push({ kind: "channel", type: "voice", id: c.ch, name: "Group call", unread: 0, call: true }))
        }
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
        : zone === "channels" ? [{ key: "a", label: "Open" }, { key: "x", label: "Group options" }, { key: "b", label: "Back" }]
        : zone === "members" ? [{ key: "a", label: "Choose" }, { key: "x", label: "Group options" }, { key: "b", label: "Back" }]
        : (readOnly ? [] : [{ key: "a", label: "Write" }, { key: "y", label: "Hold to talk" }]).concat([{ key: "x", label: "Options" }, { key: "b", label: "Back" }])
    readonly property string status: Chat.netStatus

    function clock(ms) { return Qt.formatTime(new Date(ms), "h:mm AP") }
    function initial(name) { const n = String(name || "").trim(); return n.length ? n.charAt(0).toUpperCase() : "?" }
    function stateLabel(m) {
        if (!m.mine || m.unsent) return ""
        if ((m.readCount || 0) > 0) return ""   // the check mark and the count say it
        if (m.receipt) return " · " + m.receipt
        return m.state === "sent" ? " · Sent" : m.state === "sending" ? " · Sending" : ""
    }
    function selectable(i) { return rows[i] && rows[i].kind === "channel" }

    onChatKeyChanged: {
        sel = -1; replyId = ""; editId = ""; reactFor = ""; threadRoot = ""; if (side === "thread") side = ""
        if (typeHere) compose.text = ""
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
    Rectangle {
        id: chanCol
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        width: Theme.px(260)
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
                height: header ? Theme.px(index === 0 ? 26 : 40) : Theme.px(Theme.desktop ? 40 : 48) + (people.length ? Theme.px(36) : 0)
                Text {
                    visible: rowItem.header
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: Theme.px(6)
                    x: Theme.px(10)
                    text: modelData.text || ""
                    color: Theme.textMuted
                    font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold; font.letterSpacing: Theme.px(1.5)
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
                    Rectangle {
                        id: unreadDot
                        anchors.right: parent.right; anchors.rightMargin: Theme.px(10)
                        anchors.verticalCenter: parent.verticalCenter
                        width: Theme.px(8); height: width; radius: width / 2
                        color: Theme.accent
                        visible: modelData.unread > 0
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
                    text: Chat.netStatus + " · encrypted"
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
        anchors.right: sidePanel.visible ? sidePanel.left : memberCol.visible ? memberCol.left : parent.right
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
                visible: page.typeHere && !page.locked && page.exists
                text: page.pins.length ? "Pins · " + page.pins.length : "Pins"
                fontSize: 15
                onClicked: page.side = page.side === "pins" ? "" : "pins"
            }
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
                    width: list.width
                    m: modelData
                    at: index
                    picked: page.active && page.zone === "messages" && page.sel === index
                    lineH: index === page.newFrom ? Theme.px(30) : 0
                    hoverTools: page.typeHere && !page.readOnly
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
                enabled: page.typeHere && !page.readOnly && !page.isTour
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

        // Desktop: the message box, docked at the bottom and typed into directly. Handheld writes with A.
        Composer {
            id: compose
            visible: page.typeHere && !page.locked && page.exists && !page.readOnly
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
            onEditLast: page.editLast()
            onEscape: page.escapeKey()
            onCancelBanner: { page.replyId = ""; if (page.editId !== "") { page.editId = ""; compose.text = "" } }
            onAttach: page.pickPicture()
        }
    }

    // ============================================================= side panel (desktop): a thread or the pins
    property alias threadCompose: threadBox
    Rectangle {
        id: sidePanel
        visible: Theme.desktop && !page.locked && page.exists && page.side !== ""
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        width: Theme.px(360)
        color: Theme.column
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
                width: sideList.width
                m: modelData
                at: index
                inThread: true
                hoverTools: page.typeHere && !page.readOnly
                onOptions: { const i = page.messages.findIndex(x => x.id === modelData.id); if (i >= 0) { page.sel = i; page.messageOptions() } }
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
            visible: page.side === "thread" && page.typeHere && !page.readOnly
            anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
            anchors.margins: Theme.px(12)
            height: implicitHeight
            placeholder: "Reply in the thread"
            target: page.isTour ? "" : page.chatKey
            mentionNames: page.memberNames
            onSend: t => page.sendText(t, page.threadRoot)
            onEscape: { page.side = ""; page.threadRoot = ""; compose.focusField() }
            onAttach: page.pickPicture()
        }
    }

    // ============================================================= member column (desktop)
    Rectangle {
        id: memberCol
        visible: Theme.desktop && !page.locked && page.exists && !sidePanel.visible
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        width: Theme.px(230)
        color: Theme.column
        readonly property var admins: page.members.filter(m => m.admin)
        readonly property var rest: page.members.filter(m => !m.admin)
        // Rows in the order they're drawn, for the controller.
        readonly property var ordered: admins.concat(rest)

        Flickable {
            anchors.fill: parent
            anchors.topMargin: Theme.px(18)
            anchors.leftMargin: Theme.px(14)
            anchors.rightMargin: Theme.px(10)
            contentHeight: memCol.implicitHeight
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            Column {
                id: memCol
                width: parent.width
                spacing: Theme.px(4)
                MemberHeader { text: "ADMINS · " + memberCol.admins.length }
                Repeater { model: memberCol.admins; MemberRow { person: modelData; at: index } }
                Item { width: 1; height: Theme.px(8) }
                MemberHeader { visible: memberCol.rest.length > 0; text: "MEMBERS · " + memberCol.rest.length }
                Repeater { model: memberCol.rest; MemberRow { person: modelData; at: memberCol.admins.length + index } }
            }
        }
    }
    // One message: name, time (and "(edited)"), for mine a check mark with how many have read it in a
    // circle; the quoted message of a reply, the text or picture or voice, reactions, "N replies" (a
    // thread), and on the desktop the tools over its corner. An @mention of me is lit up.
    component MessageRow: Rectangle {
        id: msg
        property var m: ({})
        property int at: 0
        property bool picked: false
        property int lineH: 0
        property bool hoverTools: false
        property bool inThread: false
        signal options()
        readonly property string who: m.name || "Someone"
        height: m.system ? note.height + Theme.px(12) : lineH + Math.max(face.height, col.implicitHeight) + Theme.px(14)
        radius: Theme.px(8)
        color: picked ? Theme.focusFill : m.mention ? Theme.accentFill : msgHover.hovered ? Theme.panelLow : "transparent"
        border.width: picked ? Theme.focusWidth : 0
        border.color: Theme.accent
        Rectangle { visible: !!msg.m.mention && !msg.picked; x: 0; y: msg.lineH; width: Theme.px(3); height: parent.height - msg.lineH; color: Theme.accent }

        Item {
            visible: msg.lineH > 0
            width: parent.width; height: msg.lineH
            Rectangle { anchors.verticalCenter: parent.verticalCenter; width: parent.width; height: 1; color: Theme.danger }
            Rectangle {
                anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
                width: newText.implicitWidth + Theme.px(14); height: Theme.px(20); radius: Theme.px(4); color: Theme.danger
                Text { id: newText; anchors.centerIn: parent; text: "NEW MESSAGES"; color: Theme.ground; font.family: Theme.body; font.pixelSize: Theme.fs(11); font.weight: Font.Bold }
            }
        }
        // A note from Huginn itself (someone joined, the group was renamed).
        Rectangle {
            id: note
            visible: !!msg.m.system
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.verticalCenter: parent.verticalCenter
            width: Math.min(parent.width * 0.9, noteText.implicitWidth + Theme.px(28))
            height: noteText.implicitHeight + Theme.px(14)
            radius: Theme.px(8)
            color: Theme.panel
            border.width: 1
            border.color: Theme.line
            Text {
                id: noteText
                anchors.centerIn: parent
                width: Math.min(implicitWidth, msg.width * 0.9 - Theme.px(28))
                wrapMode: Text.Wrap
                text: msg.m.system ? (msg.m.text || "") : ""
                color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(14)
            }
        }
        Rectangle {
            id: face
            visible: !msg.m.system
            x: Theme.px(8); y: msg.lineH + Theme.px(7)
            width: Theme.px(40); height: width; radius: width / 2
            color: Theme.avatar(msg.who, msg.m.mine).bg
            Text {
                anchors.centerIn: parent
                text: page.initial(msg.who)
                color: Theme.avatar(msg.who, msg.m.mine).fg
                font.family: Theme.body; font.pixelSize: Theme.fs(16); font.weight: Font.DemiBold
            }
        }
        Column {
            id: col
            visible: !msg.m.system
            anchors.left: face.right; anchors.leftMargin: Theme.px(12)
            anchors.right: parent.right; anchors.rightMargin: Theme.px(10)
            y: msg.lineH + Theme.px(7)
            spacing: Theme.px(3)
            Row {
                spacing: Theme.px(8)
                Text {
                    id: whoText
                    text: msg.who
                    color: Theme.avatar(msg.who, msg.m.mine).fg
                    font.family: Theme.body; font.pixelSize: Theme.fs(16); font.weight: Font.DemiBold
                }
                Text {
                    anchors.baseline: whoText.baseline
                    text: page.clock(msg.m.time) + (msg.m.isEdited ? " · (edited)" : "") + page.stateLabel(msg.m)
                    color: msg.m.failed ? Theme.danger : msg.m.mine && msg.m.state === "read" ? Theme.accent : Theme.textMuted
                    font.family: Theme.body; font.pixelSize: Theme.fs(13)
                }
                // Mine and read: a check mark and how many have read it, in a circle.
                Row {
                    visible: !!msg.m.mine && !msg.m.unsent && (msg.m.readCount || 0) > 0
                    anchors.verticalCenter: whoText.verticalCenter
                    spacing: Theme.px(3)
                    Accessible.role: Accessible.StaticText
                    Accessible.name: "Read by " + (msg.m.readCount || 0)
                    Icon { anchors.verticalCenter: parent.verticalCenter; name: "check"; size: Theme.px(15); color: Theme.accent }
                    Rectangle {
                        anchors.verticalCenter: parent.verticalCenter
                        width: Math.max(Theme.px(18), readN.implicitWidth + Theme.px(8)); height: Theme.px(18); radius: height / 2
                        color: "transparent"; border.color: Theme.accent; border.width: 1
                        Text { id: readN; anchors.centerIn: parent; text: msg.m.readCount || 0; color: Theme.accent; font.family: Theme.body; font.pixelSize: Theme.fs(11); font.weight: Font.DemiBold }
                    }
                }
            }
            QuoteLine {
                visible: !!msg.m.reName && !msg.m.unsent
                who: msg.m.reName || ""
                what: msg.m.reText || ""
                onJump: page.jumpTo(msg.m.re)
            }
            Image {
                visible: !!msg.m.photo && !msg.m.voice && !msg.m.unsent
                width: Theme.px(300); height: Theme.px(170)
                fillMode: Image.PreserveAspectCrop
                asynchronous: true
                source: visible && !page.isTour ? Chat.photoUrl(msg.m.photo) : ""
            }
            Row {
                visible: !!msg.m.voice && !msg.m.unsent
                spacing: Theme.px(10)
                Icon { name: Voice.playing === msg.m.id ? "stop" : "play"; color: Theme.accent }
                Column {
                    anchors.verticalCenter: parent.verticalCenter
                    Rectangle {
                        width: Theme.px(180); height: Theme.px(4); color: Theme.line
                        Rectangle { width: parent.width * (Voice.playing === msg.m.id ? Voice.progress : 0); height: parent.height; color: Theme.accent }
                    }
                    Text { text: "Voice message · " + Voice.length(msg.m.voice || 0); color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
                }
            }
            Text {
                visible: !msg.m.photo || !!msg.m.unsent
                width: parent.width; wrapMode: Text.Wrap
                textFormat: Text.PlainText
                text: msg.m.unsent ? (msg.m.mine ? "Message deleted by you" : "Message deleted by " + msg.m.unsent) : (msg.m.text || "")
                font.italic: !!msg.m.unsent
                color: msg.m.unsent ? Theme.textFaint : Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(17)
            }
            Reactions {
                width: parent.width
                list: msg.m.rxList || []
                add: msg.hoverTools
                onToggled: (e, mine) => page.reactTo(msg.m.id, mine ? "" : e)
                onMore: page.openReact(msg.m.id, msg.m.myReaction || "")
            }
            // A thread: how many replies, opening them beside the channel.
            Text {
                visible: !msg.inThread && (msg.m.replies || 0) > 0 && !msg.m.unsent
                text: (msg.m.replies === 1 ? "1 reply" : msg.m.replies + " replies") + " ›"
                color: Theme.accent; font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.DemiBold
                Accessible.role: Accessible.Button
                Accessible.name: "Open the thread"
                HoverHandler { cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: page.openThread(msg.m.id) }
            }
        }
        HoverBar {
            visible: msg.hoverTools && msgHover.hovered && !msg.m.system && !msg.m.unsent
            anchors.right: parent.right; anchors.rightMargin: Theme.px(10)
            y: msg.lineH - Theme.px(6)
            z: 2
            canEdit: !!msg.m.mine && !msg.m.photo && !msg.m.failed
            canThread: !msg.inThread
            canPin: !msg.inThread
            pinnedNow: page.pins.indexOf(msg.m.id) >= 0
            onReact: page.openReact(msg.m.id, msg.m.myReaction || "")
            onReply: msg.inThread ? page.threadCompose.focusField() : page.startReply(msg.m.id)
            onThread: page.openThread(msg.m.id)
            onEdit: page.startEdit(msg.m)
            onPin: page.togglePin(msg.m.id)
            onMore: msg.options()
        }
        HoverHandler { id: msgHover }
        // Click: a photo opens; right click (or a click on a handheld) opens the options.
        TapHandler {
            acceptedButtons: Qt.LeftButton | Qt.RightButton
            onTapped: (point, button) => {
                if (button === Qt.RightButton || !page.typeHere) msg.options()
                else if (msg.m.photo && !msg.m.voice && !msg.m.unsent) page.viewing = msg.m.photo
            }
        }
    }
    component MemberHeader: Text {
        color: Theme.textMuted
        bottomPadding: Theme.px(4)
        font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold; font.letterSpacing: Theme.px(1.5)
    }
    component MemberRow: Rectangle {
        id: mr
        property var person: ({})
        property int at: 0
        readonly property bool on: page.active && page.zone === "members" && page.memRow === at
        readonly property bool reachable: !!person.me || (person.route || "") !== ""
        width: memCol.width
        height: Theme.px(42)
        radius: Theme.px(8)
        color: on ? Theme.tile : memHover.hovered ? Theme.raised : "transparent"
        border.width: on ? Theme.focusWidth : 0
        border.color: Theme.accent
        Accessible.role: Accessible.ListItem
        Accessible.name: (person.name || "") + (person.owner ? ", owner" : person.admin ? ", admin" : "")
        Rectangle {
            id: mface
            anchors.left: parent.left; anchors.leftMargin: Theme.px(6)
            anchors.verticalCenter: parent.verticalCenter
            width: Theme.px(32); height: width; radius: width / 2
            color: mr.reachable ? Theme.avatar(mr.person.name, mr.person.me).bg : Theme.raised
            Text { anchors.centerIn: parent; text: page.initial(mr.person.name); color: mr.reachable ? Theme.avatar(mr.person.name, mr.person.me).fg : Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13); font.weight: Font.DemiBold }
        }
        Text {
            anchors.left: mface.right; anchors.leftMargin: Theme.px(10)
            anchors.right: crown.left; anchors.rightMargin: Theme.px(4)
            anchors.verticalCenter: parent.verticalCenter
            elide: Text.ElideRight
            text: (mr.person.name || "") + (mr.person.me ? " (you)" : "")
            color: mr.reachable ? Theme.text : Theme.textMuted
            font.family: Theme.body; font.pixelSize: Theme.fs(15)
        }
        Icon {
            id: crown
            visible: !!mr.person.owner
            anchors.right: parent.right; anchors.rightMargin: Theme.px(6)
            anchors.verticalCenter: parent.verticalCenter
            name: "crown"; size: Theme.px(15); color: Theme.accent
        }
        HoverHandler { id: memHover }
        TapHandler { onTapped: { page.zone = "members"; page.memRow = mr.at; page.memberOptions(mr.person) } }
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
                { name: "Output: " + Rooms.outputName, detail: "Choose again for the next one", url: "output" },
                { name: "Leave " + r.name, detail: "You stop hearing and being heard", url: "leave" }] }], a => {
                if (a === "mute") Rooms.toggleMute()
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
        if (typeHere) { zone = "messages"; compose.focusField(); return }
        const g = gid, ch = channelId, re = replyId
        Ui.ask(re !== "" && replyMsg ? "Reply to " + replyMsg.name : "Message #" + channel.name, re !== "" && replyMsg ? (replyMsg.text || "Photo") : "Encrypted for everyone in " + info.name, "", "Send",
               t => { Chat.sendGroup(g, ch, t, re); page.replyId = "" }, false, true)
    }
    function pickPicture() {
        if (readOnly || isTour) return
        const pics = Chat.recentPictures()
        if (!pics.length) { Ui.say("No pictures found. Steam screenshots and the Pictures folder show up here.", true); return }
        const key = chatKey
        Ui.pickFrom("Send a picture to #" + channel.name + " (location and camera details are removed)", [{ header: "Newest first", items: pics.map(p => Object.assign({ icon: "image" }, p)) }], path =>
            Ui.pickFrom("Send this picture", [{ header: "", items: [
                { name: "Send", detail: "Quick anywhere", url: "normal" },
                { name: "High quality", detail: "Sharper. Best close by or on Wi-Fi", url: "hd" }] }],
                q => { if (Chat.sendPhoto(key, path, q === "hd")) Ui.say("Sending the picture.") }))
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
        if (!Theme.desktop) people.push({ name: "Members", detail: info.count + (info.count === 1 ? " person" : " people"), url: "members" })
        if (!readOnly && info.amAdmin) {
            chans.push({ name: "Add a text channel", url: "addtext" }, { name: "Add a voice room", detail: "Up to " + Rooms.maxPeople + " people talk at once", url: "addvoice" })
            const r = rows[chRow]
            const target = zone === "channels" && r && r.kind === "channel" ? r : channel
            chans.push({ name: "Rename " + (target.type === "voice" ? "" : "#") + target.name, url: "rnch:" + target.id })
            if (target.id !== "main") chans.push({ name: "Remove " + (target.type === "voice" ? "" : "#") + target.name, detail: "Its messages stay on this device", url: "rmch:" + target.id })
            group.push({ name: "Rename the group", detail: "Now: " + info.name, url: "rename" })
        }
        if (!readOnly) group.push({ name: "Send a picture", detail: "To #" + channel.name, url: "picture" })
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
    function pickMember() {
        Ui.pickFrom("Members of " + info.name, [{ header: "", items: members.map(m => ({ name: m.name + (m.me ? " (you)" : ""), url: m.key,
            detail: m.owner ? "Owner" : m.admin ? "Admin" : m.contact ? "Contact" : "In this group" })) }],
            k => { const m = members.find(x => x.key === k); if (m) memberOptions(m) })
    }
    function memberOptions(m) {
        if (!m || isTour) return
        const items = []
        if (m.contact && !m.me) items.push({ name: "Message", detail: "Your direct chat", url: "dm" })
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
            if (typeHere) items.push({ name: "Open thread", detail: (m.replies || 0) + ((m.replies || 0) === 1 ? " reply" : " replies"), url: "thread" })
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
        if (typeHere) compose.focusField(); else write()
    }
    function startEdit(m) {
        if (!m || !m.mine || m.photo || m.unsent || readOnly) return
        replyId = ""
        if (typeHere) { editId = m.id; compose.setText(m.text) }
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
    function openThread(id) {
        threadRoot = id
        side = "thread"
        Qt.callLater(() => threadBox.focusField())
    }
    function togglePin(id) {
        if (isTour) return
        const on = pins.indexOf(id) < 0
        Chat.setPinned(chatKey, id, on)
        Ui.say(on ? "Pinned. Pins are only on this computer." : "Unpinned.")
    }
    function dropFiles(urls) {
        const others = []
        let pics = 0
        for (let i = 0; i < urls.length; i++) {
            const path = Chat.localPath(urls[i])
            if (path === "") continue
            if (Chat.isPicture(path)) { if (Chat.sendPhoto(chatKey, path, false)) pics++ }
            else others.push(urls[i])
        }
        if (pics) Ui.say(pics === 1 ? "Sending the picture." : "Sending " + pics + " pictures.")
        if (others.length) {
            Transfer.share(others)
            Ui.say((others.length === 1 ? "That file is" : "Those files are") + " ready in Files: pick a device on the same Wi-Fi to send.")
            Ui.openPage("files", {})
        }
    }
    function pickReaction(m) {
        const g = gid, id = m.id
        const items = Chat.quickReactions().map(e => ({ name: e, detail: e === m.myReaction ? "Yours · choose to take it back" : "", url: e }))
        if (m.myReaction && Chat.quickReactions().indexOf(m.myReaction) < 0) items.push({ name: m.myReaction, detail: "Yours · choose to take it back", url: m.myReaction })
        if (typeHere) items.push({ name: "More emoji", url: "+" })
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
    function handleButton(b) {
        if (locked) {
            if (b === "a") { Ui.openPage("dms", {}); return true }
            return false
        }
        if (shownCode !== "") {
            if (b === "a") { Chat.copyText(shownCode); Ui.say("Code copied. It works for an hour.") }
            else if (b === "x") showCode()
            else if (b === "b") shownCode = ""
            return true
        }
        if (viewing !== "") { if (b === "b" || b === "a") viewing = ""; return true }
        if (Voice.recording) { if (b === "b") { Voice.cancel(); Ui.say("Recording cancelled.") } return true }
        if (!exists) return b !== "left" && b !== "b"
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
            else if (zone === "messages" && memberCol.visible && memCount > 0) { zone = "members"; memRow = Math.min(memRow, memCount - 1) }
            return true
        case "left":
            if (zone === "channels") return false
            if (zone === "members") zone = "messages"
            else { zone = "channels"; sel = -1 }
            return true
        case "a":
            if (zone === "channels") openRow()
            else if (zone === "members") memberOptions(memberCol.ordered[memRow])
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
}

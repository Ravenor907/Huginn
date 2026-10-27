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

    function setArgs(a) {
        tour = !!a.tour
        zone = a.zone || "channels"
        sel = -1
        viewing = ""
        shownCode = ""
        channelId = "main"
        chRow = Math.max(0, rows.findIndex(r => r.kind === "channel" && r.id === "main"))
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
        if (voice.length) {
            out.push({ kind: "header", text: "VOICE" })
            voice.forEach(c => out.push(Object.assign({ kind: "channel" }, c)))
        }
        return out
    }
    // Who's in a voice room: only known once calls land (the tour shows how it looks).
    function roomPeople(id) { return isTour && id === "l" ? ["Bjorn", "Jake", "Sam"] : [] }
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
        if (m.receipt) return " · " + m.receipt
        return m.state === "sent" ? " · Sent" : m.state === "sending" ? " · Sending" : ""
    }
    function selectable(i) { return rows[i] && rows[i].kind === "channel" }

    onChatKeyChanged: { sel = -1; if (!locked && !isTour) { Chat.openChat = chatKey; Chat.markGroupRead(gid, channelId) } }
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
                readonly property bool here: !header && !voice && modelData.id === page.channelId
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
                            color: Theme.avatar(modelData).bg
                            Text { anchors.centerIn: parent; text: page.initial(modelData); color: Theme.avatar(modelData).fg; font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold }
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
                Icon { anchors.verticalCenter: parent.verticalCenter; name: "shield"; size: Theme.px(15); color: Chat.torReady || Chat.internet > 0 ? Theme.accent : Theme.textMuted }
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
        anchors.right: memberCol.visible ? memberCol.left : parent.right
        anchors.top: parent.top
        anchors.bottom: parent.bottom

        PageHeader {
            id: convHead
            width: parent.width
            carved: false
            title: page.locked || !page.exists ? "" : "# " + page.channel.name
            subtitle: page.locked || !page.exists ? "" : page.readOnly ? "Read only: you're no longer in " + page.info.name
                    : "Encrypted for the " + page.info.count + (page.info.count === 1 ? " person in " : " people in ") + page.info.name
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
                delegate: Rectangle {
                    id: msg
                    readonly property bool picked: page.active && page.zone === "messages" && page.sel === index
                    readonly property string who: modelData.name || "Someone"
                    width: list.width
                    height: modelData.system ? note.height + Theme.px(12) : Math.max(face.height, col.implicitHeight) + Theme.px(14)
                    radius: Theme.px(8)
                    color: picked ? Theme.focusFill : msgHover.hovered ? Theme.panelLow : "transparent"
                    border.width: picked ? Theme.focusWidth : 0
                    border.color: Theme.accent

                    // A note from Huginn itself (someone joined, the group was renamed).
                    Rectangle {
                        id: note
                        visible: !!modelData.system
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
                            text: modelData.system ? (modelData.text || "") : ""
                            color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(14)
                        }
                    }
                    Rectangle {
                        id: face
                        visible: !modelData.system
                        x: Theme.px(8); y: Theme.px(7)
                        width: Theme.px(40); height: width; radius: width / 2
                        color: Theme.avatar(msg.who, modelData.mine).bg
                        Text {
                            anchors.centerIn: parent
                            text: page.initial(msg.who)
                            color: Theme.avatar(msg.who, modelData.mine).fg
                            font.family: Theme.body; font.pixelSize: Theme.fs(16); font.weight: Font.DemiBold
                        }
                    }
                    Column {
                        id: col
                        visible: !modelData.system
                        anchors.left: face.right; anchors.leftMargin: Theme.px(12)
                        anchors.right: parent.right; anchors.rightMargin: Theme.px(10)
                        y: Theme.px(7)
                        spacing: Theme.px(3)
                        Row {
                            spacing: Theme.px(8)
                            Text {
                                id: whoText
                                text: msg.who
                                color: Theme.avatar(msg.who, modelData.mine).fg
                                font.family: Theme.body; font.pixelSize: Theme.fs(16); font.weight: Font.DemiBold
                            }
                            Text {
                                anchors.baseline: whoText.baseline
                                text: page.clock(modelData.time) + page.stateLabel(modelData)
                                color: modelData.failed ? Theme.danger : modelData.mine && modelData.state === "read" ? Theme.accent : Theme.textMuted
                                font.family: Theme.body; font.pixelSize: Theme.fs(13)
                            }
                        }
                        Image {
                            visible: !!modelData.photo && !modelData.voice && !modelData.unsent
                            width: Theme.px(300); height: Theme.px(170)
                            fillMode: Image.PreserveAspectCrop
                            asynchronous: true
                            source: visible && !page.isTour ? Chat.photoUrl(modelData.photo) : ""
                        }
                        Row {
                            visible: !!modelData.voice && !modelData.unsent
                            spacing: Theme.px(10)
                            Icon { name: Voice.playing === modelData.id ? "stop" : "play"; color: Theme.accent }
                            Column {
                                anchors.verticalCenter: parent.verticalCenter
                                Rectangle {
                                    width: Theme.px(180); height: Theme.px(4); color: Theme.line
                                    Rectangle { width: parent.width * (Voice.playing === modelData.id ? Voice.progress : 0); height: parent.height; color: Theme.accent }
                                }
                                Text { text: "Voice message · " + Voice.length(modelData.voice || 0); color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
                            }
                        }
                        Text {
                            visible: !modelData.photo || !!modelData.unsent
                            width: parent.width; wrapMode: Text.Wrap
                            text: modelData.unsent ? (modelData.mine ? "Message deleted by you" : "Message deleted by " + modelData.unsent) : (modelData.text || "")
                            font.italic: !!modelData.unsent
                            color: modelData.unsent ? Theme.textFaint : Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(17)
                        }
                    }
                    HoverHandler { id: msgHover }
                    TapHandler { onTapped: { page.zone = "messages"; page.sel = index; page.messageOptions() } }
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
        }

        // Desktop: a message box to click, like any chat app. Handheld writes with A.
        Rectangle {
            id: compose
            visible: Theme.desktop && !page.locked && page.exists && !page.readOnly
            anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
            anchors.leftMargin: Theme.px(20); anchors.rightMargin: Theme.px(20); anchors.bottomMargin: Theme.px(18)
            height: Theme.px(46)
            radius: Theme.px(11)
            color: Theme.raised
            border.width: composeHover.hovered ? 1 : 0
            border.color: Theme.lineMuted
            Accessible.role: Accessible.EditableText
            Accessible.name: composeText.text
            Icon {
                id: attachIcon
                anchors.left: parent.left; anchors.leftMargin: Theme.px(14)
                anchors.verticalCenter: parent.verticalCenter
                name: "attach"; size: Theme.px(20); color: Theme.textMuted
                TapHandler { onTapped: page.pickPicture() }
            }
            Text {
                id: composeText
                anchors.left: attachIcon.right; anchors.leftMargin: Theme.px(14)
                anchors.right: parent.right; anchors.rightMargin: Theme.px(14)
                anchors.verticalCenter: parent.verticalCenter
                elide: Text.ElideRight
                text: "Message #" + page.channel.name
                color: Theme.textMuted
                font.family: Theme.body; font.pixelSize: Theme.fs(16)
            }
            HoverHandler { id: composeHover; cursorShape: Qt.IBeamCursor }
            TapHandler { onTapped: { page.zone = "messages"; page.write() } }
        }
    }

    // ============================================================= member column (desktop)
    Rectangle {
        id: memberCol
        visible: Theme.desktop && !page.locked && page.exists
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
        if (r.type === "voice") { Ui.say(r.name + " is a voice room. Calls are coming soon."); return }
        channelId = r.id
        zone = "messages"
    }
    function write() {
        if (readOnly) { Ui.say("You're no longer in this group.", true); return }
        if (isTour) return
        const g = gid, ch = channelId
        Ui.ask("Message #" + channel.name, "Encrypted for everyone in " + info.name, "", "Send", t => Chat.sendGroup(g, ch, t), false, true)
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
        const people = [], chans = [], group = []
        if (!readOnly && info.amAdmin) {
            people.push({ name: "Invite someone", detail: "A contact gets an invite to say yes to", url: "invite" })
            people.push({ name: "Show the group code", detail: "For anyone else: they scan or paste it within the hour", url: "code" })
        }
        if (!Theme.desktop) people.push({ name: "Members", detail: info.count + (info.count === 1 ? " person" : " people"), url: "members" })
        if (!readOnly && info.amAdmin) {
            chans.push({ name: "Add a text channel", url: "addtext" }, { name: "Add a voice room", detail: "Calls are coming soon", url: "addvoice" })
            const r = rows[chRow]
            const target = zone === "channels" && r && r.kind === "channel" ? r : channel
            chans.push({ name: "Rename " + (target.type === "voice" ? "" : "#") + target.name, url: "rnch:" + target.id })
            if (target.id !== "main") chans.push({ name: "Remove " + (target.type === "voice" ? "" : "#") + target.name, detail: "Its messages stay on this device", url: "rmch:" + target.id })
            group.push({ name: "Rename the group", detail: "Now: " + info.name, url: "rename" })
        }
        if (!readOnly) group.push({ name: "Send a picture", detail: "To #" + channel.name, url: "picture" })
        if (readOnly) group.push({ name: "Delete from Huginn", detail: "Its messages and photos on this device", url: "forget" })
        else group.push({ name: "Leave the group", detail: info.amOwner && info.count > 1 ? "Hand it to someone first (Members)" : "You stop getting its messages", url: "leave" })
        return [{ header: "People", items: people }, { header: "Channels", items: chans }, { header: "Group", items: group }]
    }
    function groupOptions() {
        if (!exists) return
        Ui.pickFrom(info.name || "Group", groupItems(), a => groupAction(a))
    }
    function groupAction(a) {
        const g = gid
        {
            if (a === "invite") invite()
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
        if (m.photo && !m.voice && !m.unsent) items.push({ name: "View full size", url: "view" }, { name: "Save to Pictures", url: "save" })
        if (m.voice && !m.unsent) items.push({ name: Voice.playing === m.id ? "Stop" : "Play", url: "play" })
        if (m.mine && !m.unsent && !readOnly) items.push({ name: "Unsend", detail: "Removes it for everyone in the group", url: "unsend" })
        if (m.mine && m.failed) items.push({ name: "Try again", url: "retry" })
        items.push({ name: "Delete for me", detail: "Only on this computer", url: "delete" })
        const g = gid
        Ui.pickFrom("Message", [{ header: "", items: items }].concat(groupItems()), a => {
            if (a === "view") viewing = m.photo
            else if (a === "save") Ui.say(Chat.savePhoto(m.photo) ? "Saved in Pictures › Huginn." : "Couldn't save it.")
            else if (a === "play") { if (Voice.playing === m.id) Voice.stop(); else Voice.play(m.photo) }
            else if (a === "unsend") Chat.unsendGroup(g, m.id)
            else if (a === "retry") Chat.retryGroup(g, m.id)
            else if (a === "delete") Chat.deleteGroupMessage(g, m.id)
            else groupAction(a)
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

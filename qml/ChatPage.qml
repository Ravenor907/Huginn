import QtQuick
import Huginn

// Direct messages, the same as Muninn's. Middle column: Add someone, My code, requests, the Nearby
// room, chats and people around you. Main column: the open conversation. Locked with a pattern or
// password like Muninn.
Rectangle {
    id: page
    color: Theme.ground
    // False while the controller focus is on the rail, so only one thing looks focused.
    readonly property bool active: !Ui.main || Ui.main.focusZone === "content"
    property int row: 0
    property string zone: "list"      // "chips" (folder filters), "list" or "messages"
    property string folder: ""        // the folder filter, "" for everyone
    property int chip: 0
    property string openKey: ""       // "" = Nearby room, otherwise a private chat
    property int sel: -1              // selected message, for its options
    property bool showCode: false
    property string viewing: ""       // a photo open full size
    property bool tour: false
    property bool changeLock: false
    // Groups arrive later; a direct message never shows the member column.
    readonly property bool isGroup: false

    function setArgs(a) {
        tour = !!a.tour
        changeLock = !!a.changeLock
        if (a.zone) zone = a.zone
        if (a.folders) { zone = "chips"; chip = Math.min(1, chips.length - 1) }
        if (a.open) { openKey = a.open; zone = "messages" }   // from Contacts › Message
    }

    readonly property bool locked: !tour && (!Vault.exists || Vault.screenLocked)
    // The lock's own LB / RB (pattern or password) win over moving along the rail.
    readonly property bool ownsShoulders: lock.visible && lock.mode !== "unlock"

    // Folder chips at the top: All, each folder, then + to make one.
    readonly property var chips: [{ id: "", name: "All" }].concat(Chat.folders).concat([{ id: "+", name: "+ Folder" }])
    readonly property var entries: {
        const out = [{ kind: "add", name: "Add someone", detail: "Paste their code, or a group code" },
                     { kind: "code", name: "My code", detail: "A QR anyone can scan to message you" }]
        if (folder !== "") {
            Chat.chats.filter(c => (c.folders || []).indexOf(folder) >= 0)
                .forEach(c => out.push({ kind: "chat", key: c.key, name: c.name, detail: c.last || page.routeText(c.route), unread: c.unread, route: c.route }))
            return out
        }
        Chat.groupInvites.forEach(i => out.push({ kind: "ginvite", g: i.g, name: i.name, waiting: i.waiting, fromName: i.fromName,
                                                  detail: i.waiting ? "Waiting for " + i.fromName + " to let you in" : i.fromName + " invited you · " + i.count + (i.count === 1 ? " person" : " people") }))
        Chat.requests.forEach(r => out.push({ kind: "request", key: r.key, name: r.name, replaces: r.replaces || "", replacesName: r.replacesName || "",
                                              detail: r.replaces ? "Says they're " + r.replacesName + " with a new security key" : "Wants to chat (has your code)" }))
        out.push({ kind: "nearby", name: "Nearby room", detail: Chat.nearbyOn ? Chat.nearbyCount + " around you · you're " + Chat.username : "Off" })
        Chat.chats.forEach(c => out.push({ kind: "chat", key: c.key, name: c.name, detail: c.last || page.routeText(c.route), unread: c.unread, route: c.route }))
        Chat.around.forEach(p => out.push({ kind: "stranger", key: p.key, name: p.name, detail: page.routeText(p.route) + " · A to message", route: p.route }))
        return out
    }
    readonly property var messages: {
        if (tour && openKey === "")
            return [{ id: "1", name: "Raven 4821", text: "Anyone have the Emerald save from yesterday?", mine: false, time: Date.now() - 60000 },
                    { id: "2", name: Chat.username, text: "Sending it over LocalSend now", mine: true, time: Date.now(), state: "read" }]
        return openKey === "" ? Chat.nearby : (Chat.chats, Chat.messagesWith(openKey))
    }
    readonly property string openName: openKey === "" ? "" : (Chat.chats, Chat.nameOf(openKey))   // Chat.chats: follows renames
    readonly property string openRoute: openKey === "" ? "" : (Chat.chats, Chat.route(openKey))

    // What the hint bar shows (Main.qml adds LB RB).
    readonly property var prompts: lock.visible ? [{ key: "b", label: "Back" }]
        : zone === "chips" ? [{ key: "a", label: "Show" }, { key: "x", label: "Folder options" }, { key: "b", label: "Back" }]
        : zone === "list" ? [{ key: "a", label: "Open" }, { key: "b", label: "Back" }]
        : [{ key: "a", label: "Write" }].concat(openKey !== "" ? [{ key: "y", label: "Hold to talk" }] : [])
            .concat(sel >= 0 || openKey !== "" ? [{ key: "x", label: "Options" }] : []).concat([{ key: "b", label: "Back" }])
    readonly property string status: lock.visible ? "" : Chat.netStatus

    function routeText(r) {
        return r === "bluetooth" ? "Close by (Bluetooth)" : r === "wifi" ? "Same Wi-Fi" : r === "internet" ? "Over the internet" : "Not reachable right now"
    }
    function routeShort(r) {
        return r === "bluetooth" ? "Bluetooth" : r === "wifi" ? "Wi-Fi" : r === "internet" ? "Online" : "Away"
    }
    function clock(ms) { return Qt.formatTime(new Date(ms), "h:mm AP") }
    function stateLabel(m) {
        if (!m.mine) return ""
        if (m.failed) return " · Didn't send"
        if (m.state === "unsending") return " · Removing from their phone"
        if (m.unsent) return ""
        return m.state === "read" ? " · Read" : m.state === "delivered" ? " · Delivered" : m.state === "sent" ? " · Sent" : m.state === "sending" ? " · Sending" : ""
    }
    function initial(name) { const n = String(name || "").trim(); return n.length ? n.charAt(0).toUpperCase() : "?" }
    function senderName(m) { return m.mine ? Chat.username : openKey === "" ? (m.name || "Someone") : openName }

    onOpenKeyChanged: {
        sel = -1; Voice.stop()
        if (openKey !== "") { Chat.markRead(openKey); if (!tour && Chat.isContact(openKey)) Chat.probeCaps(openKey) }
        if (openKey !== "" && !tour) Calls.prepare(openKey)   // the relay login is ready before Call is pressed
        Chat.openChat = openKey
    }
    // Read only counts when someone is actually looking: not while a game is in front.
    onMessagesChanged: if (openKey !== "" && !locked && Qt.application.state === Qt.ApplicationActive) Chat.markRead(openKey)
    Component.onCompleted: Vault.back()   // away more than a minute: locked again
    Component.onDestruction: { Chat.openChat = ""; Voice.cancel(); Voice.stop(); Vault.away() }
    Connections {
        target: Qt.application
        function onStateChanged() {
            if (Qt.application.state === Qt.ApplicationActive) { Vault.back(); if (page.openKey !== "" && !page.locked) Chat.markRead(page.openKey) }
            else Vault.away()
        }
    }

    // ============================================================= middle column: the list
    Rectangle {
        id: middle
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        width: Theme.px(270)
        color: Theme.column

        PageHeader { id: listHead; width: parent.width; title: "Direct messages" }

        // Folders (only on this computer) as filter chips.
        Flow {
            id: chipRow
            visible: !page.locked
            anchors.top: listHead.bottom
            anchors.topMargin: Theme.px(10)
            x: Theme.px(10)
            width: parent.width - Theme.px(20)
            spacing: Theme.px(6)
            Repeater {
                model: page.chips
                Rectangle {
                    readonly property bool on: page.active && page.zone === "chips" && page.chip === index
                    readonly property bool picked: modelData.id === page.folder && modelData.id !== "+"
                    height: Theme.px(30)
                    width: chipLabel.implicitWidth + Theme.px(22)
                    radius: height / 2
                    color: picked ? Theme.accentFill : chipHover.hovered ? Theme.raised : Theme.tile
                    border.width: on ? Theme.focusWidth : picked ? 1 : 0
                    border.color: Theme.accent
                    Accessible.role: Accessible.Button
                    Accessible.name: modelData.id === "+" ? "New folder" : "Show " + modelData.name
                    Text {
                        id: chipLabel
                        anchors.centerIn: parent
                        text: modelData.name || ""
                        color: parent.picked ? Theme.accent : Theme.textSoft
                        font.family: Theme.body; font.pixelSize: Theme.fs(13); font.weight: parent.picked ? Font.DemiBold : Font.Normal
                    }
                    HoverHandler { id: chipHover }
                    TapHandler { onTapped: { page.zone = "chips"; page.chip = index; page.pickChip() } }
                }
            }
        }

        ListView {
            id: listPane
            visible: !page.locked
            anchors.top: chipRow.visible ? chipRow.bottom : listHead.bottom
            anchors.topMargin: Theme.px(10)
            anchors.bottom: netStrip.top
            anchors.bottomMargin: Theme.px(6)
            x: Theme.px(10)
            width: parent.width - Theme.px(20)
            spacing: Theme.px(3)
            clip: true
            model: page.entries
            currentIndex: page.row
            highlightFollowsCurrentItem: false
            onCurrentIndexChanged: positionViewAtIndex(currentIndex, ListView.Contain)
            delegate: Rectangle {
                readonly property bool on: page.active && page.zone === "list" && page.row === index
                readonly property bool here: (modelData.kind === "nearby" && page.openKey === "") || (modelData.key !== undefined && modelData.key === page.openKey)
                readonly property bool person: modelData.kind === "chat" || modelData.kind === "stranger" || modelData.kind === "request"
                width: listPane.width
                height: Theme.px(56)
                radius: Theme.px(10)
                color: on || here ? Theme.tile : rowHover.hovered ? Theme.raised : "transparent"
                border.width: on ? Theme.focusWidth : 0
                border.color: Theme.accent
                Accessible.role: Accessible.ListItem
                Accessible.name: modelData.name + ", " + modelData.detail
                Rectangle {
                    id: ava
                    anchors.left: parent.left; anchors.leftMargin: Theme.px(10)
                    anchors.verticalCenter: parent.verticalCenter
                    width: Theme.px(34); height: width; radius: width / 2
                    color: parent.person ? Theme.avatar(modelData.name).bg : Theme.raised
                    Text {
                        visible: parent.parent.person
                        anchors.centerIn: parent
                        text: page.initial(modelData.name)
                        color: Theme.avatar(modelData.name).fg
                        font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.DemiBold
                    }
                    Icon {
                        visible: !parent.parent.person
                        anchors.centerIn: parent
                        size: Theme.px(18)
                        name: modelData.kind === "add" ? "plus" : modelData.kind === "code" ? "qr" : modelData.kind === "ginvite" ? "hash" : "people"
                        color: parent.parent.here || parent.parent.on ? Theme.accent : Theme.textMuted
                    }
                    // Requests wait for an answer: a lock badge so they stand out.
                    Rectangle {
                        visible: modelData.kind === "request" || (modelData.kind === "ginvite" && !modelData.waiting)
                        anchors.right: parent.right; anchors.bottom: parent.bottom
                        anchors.margins: -Theme.px(2)
                        width: Theme.px(14); height: width; radius: width / 2
                        color: Theme.accent
                    }
                }
                Column {
                    anchors.left: ava.right; anchors.leftMargin: Theme.px(10)
                    anchors.right: dot.left; anchors.rightMargin: Theme.px(6)
                    anchors.verticalCenter: parent.verticalCenter
                    Text {
                        width: parent.width; elide: Text.ElideRight; text: modelData.name
                        color: parent.parent.on || parent.parent.here || modelData.unread ? Theme.text : Theme.textSoft
                        font.family: Theme.body; font.pixelSize: Theme.fs(16); font.weight: modelData.unread ? Font.Bold : Font.DemiBold
                    }
                    Text {
                        width: parent.width; elide: Text.ElideRight; text: modelData.detail
                        color: modelData.kind === "request" || (modelData.kind === "ginvite" && !modelData.waiting) ? Theme.accent : Theme.textMuted
                        font.family: Theme.body; font.pixelSize: Theme.fs(13)
                    }
                }
                Rectangle {
                    id: dot
                    anchors.right: parent.right; anchors.rightMargin: Theme.px(10)
                    anchors.verticalCenter: parent.verticalCenter
                    width: Theme.px(9); height: width; radius: width / 2
                    color: Theme.accent
                    visible: !!modelData.unread
                }
                HoverHandler { id: rowHover }
                TapHandler { onTapped: { page.zone = "list"; page.row = index; page.choose() } }
            }
        }
        Text {
            visible: page.locked
            anchors.top: listHead.bottom
            anchors.topMargin: Theme.px(16)
            x: Theme.px(20)
            width: parent.width - Theme.px(40)
            wrapMode: Text.WordWrap
            text: Vault.exists ? "Locked. Unlock to see your conversations." : "Set a lock to start chatting."
            color: Theme.textMuted
            font.family: Theme.body; font.pixelSize: Theme.fs(15)
        }

        // How Huginn reaches people right now, like the voice strip in the mockups.
        Rectangle {
            id: netStrip
            anchors.bottom: parent.bottom
            width: parent.width
            height: Theme.px(40)
            color: Theme.groundDeep
            Row {
                anchors.left: parent.left; anchors.leftMargin: Theme.px(14)
                anchors.verticalCenter: parent.verticalCenter
                spacing: Theme.px(8)
                Icon {
                    anchors.verticalCenter: parent.verticalCenter
                    name: "shield"; size: Theme.px(15)
                    color: Chat.internet > 0 || Chat.nearbyOn ? Theme.accent : Theme.textMuted
                }
                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    width: netStrip.width - Theme.px(50)
                    elide: Text.ElideRight
                    text: Chat.netStatus + (Chat.nearbyOn ? " · nearby on" : "") + " · encrypted"
                    color: Theme.textSoft
                    font.family: Theme.body; font.pixelSize: Theme.fs(13)
                }
            }
        }
    }

    // ============================================================= main column: the conversation
    Item {
        id: main
        anchors.left: middle.right
        anchors.right: members.visible ? members.left : parent.right
        anchors.top: parent.top
        anchors.bottom: parent.bottom

        PageHeader {
            id: convHead
            width: parent.width
            carved: page.locked
            title: page.locked ? "Locked" : page.openKey === "" ? "Nearby room" : page.openName
            subtitle: page.locked ? "" : page.openKey === "" ? "Everyone close by or on this Wi-Fi with Huginn or Muninn"
                    : "Encrypted · " + (Chat.keyChanged(page.openKey) ? "security key changed, not verified" : Chat.isVerified(page.openKey) ? "verified" : "not verified")
            Row {
                visible: !page.locked && page.openKey !== ""
                spacing: Theme.px(6)
                Rectangle {
                    anchors.verticalCenter: parent.verticalCenter
                    width: Theme.px(8); height: width; radius: width / 2
                    color: page.openRoute === "" || page.openRoute === "none" ? Theme.textFaint : Theme.hc ? Theme.text : "#7FB08A"
                }
                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: page.routeShort(page.openRoute)
                    color: Theme.textMuted
                    font.family: Theme.body; font.pixelSize: Theme.fs(14)
                }
            }
            HButton {
                anchors.verticalCenter: parent.verticalCenter
                visible: !page.locked && page.openKey !== ""
                text: "Call"
                fontSize: 15
                onClicked: page.startCall()
            }
        }

        // ------------------------------------------------------- locked
        LockPanel {
            id: lock
            visible: page.locked || page.changeLock
            mode: page.changeLock && !page.locked ? "change" : Vault.exists ? "unlock" : "setup"
            anchors.top: convHead.bottom; anchors.topMargin: Theme.px(16)
            anchors.horizontalCenter: parent.horizontalCenter
            width: Math.min(parent.width - Theme.px(40), Theme.px(620))
            onFinished: how => { if (how === "change") page.changeLock = false }   // after an unlock, the change step follows
            onCancelled: { if (page.changeLock) page.changeLock = false; else Ui.closePage() }
        }

        Item {
            id: pane
            visible: !lock.visible
            anchors.top: convHead.bottom
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: compose.visible ? compose.top : parent.bottom
            clip: true

            // Keep in touch: someone met close by, after 10 messages or once they're out of range.
            Rectangle {
                id: keep
                visible: page.openKey !== "" && Chat.shouldAskKeep(page.openKey) && (Chat.chats, true)
                anchors.top: parent.top; anchors.left: parent.left; anchors.right: parent.right
                anchors.margins: Theme.px(12)
                height: visible ? keepCol.implicitHeight + Theme.px(20) : 0
                radius: Theme.px(8); color: Theme.accentFill; border.color: Theme.accent; border.width: 1
                Column {
                    id: keepCol
                    x: Theme.px(14); y: Theme.px(10); width: parent.width - Theme.px(28)
                    Text { text: Chat.theySaidKeep(page.openKey) ? page.openName + " wants to keep in touch" : "Keep in touch with " + page.openName + "?"; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(17); font.weight: Font.DemiBold }
                    Text { width: parent.width; wrapMode: Text.WordWrap; text: "If you both say yes, you can message each other from anywhere. Menu: Yes · View: Not now"; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(14) }
                }
            }

            ListView {
                id: list
                anchors.top: keep.visible ? keep.bottom : parent.top
                anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
                anchors.leftMargin: Theme.px(14); anchors.rightMargin: Theme.px(14)
                anchors.topMargin: Theme.px(12); anchors.bottomMargin: Theme.px(12)
                spacing: Theme.px(6)
                model: page.messages
                onCountChanged: positionViewAtEnd()
                delegate: Rectangle {
                    id: msg
                    readonly property bool picked: page.active && page.zone === "messages" && page.sel === index
                    width: list.width
                    height: modelData.system ? note.height + Theme.px(12) : Math.max(face.height, col.implicitHeight) + Theme.px(14)
                    radius: Theme.px(8)
                    color: picked ? Theme.focusFill : msgHover.hovered ? Theme.panelLow : "transparent"
                    border.width: picked ? Theme.focusWidth : 0
                    border.color: Theme.accent

                    // A note from Huginn itself (a changed security key) sits in the middle.
                    Rectangle {
                        id: note
                        visible: !!modelData.system
                        anchors.horizontalCenter: parent.horizontalCenter
                        anchors.verticalCenter: parent.verticalCenter
                        width: Math.min(parent.width * 0.9, noteText.implicitWidth + Theme.px(28))
                        height: noteText.implicitHeight + Theme.px(16)
                        radius: Theme.px(8)
                        color: Theme.dangerFill
                        border.width: 1
                        border.color: Theme.danger
                        Text {
                            id: noteText
                            anchors.centerIn: parent
                            width: Math.min(implicitWidth, msg.width * 0.9 - Theme.px(28))
                            wrapMode: Text.Wrap
                            text: modelData.system ? modelData.text : ""
                            font.italic: true
                            color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15)
                        }
                    }

                    Rectangle {
                        id: face
                        visible: !modelData.system
                        x: Theme.px(8); y: Theme.px(7)
                        width: Theme.px(40); height: width; radius: width / 2
                        color: Theme.avatar(page.senderName(modelData), modelData.mine).bg
                        Text {
                            anchors.centerIn: parent
                            text: page.initial(page.senderName(modelData))
                            color: Theme.avatar(page.senderName(modelData), modelData.mine).fg
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
                                id: who
                                text: page.senderName(modelData)
                                color: Theme.avatar(page.senderName(modelData), modelData.mine).fg
                                font.family: Theme.body; font.pixelSize: Theme.fs(16); font.weight: Font.DemiBold
                            }
                            Text {
                                anchors.baseline: who.baseline
                                text: page.clock(modelData.time) + page.stateLabel(modelData)
                                color: modelData.failed ? Theme.danger : modelData.state === "read" && modelData.mine ? Theme.accent : Theme.textMuted
                                font.family: Theme.body; font.pixelSize: Theme.fs(13)
                            }
                        }
                        Image {
                            visible: !!modelData.photo && !modelData.voice && !modelData.unsent
                            width: Theme.px(280); height: Theme.px(170)
                            fillMode: Image.PreserveAspectCrop
                            asynchronous: true
                            source: visible ? Chat.photoUrl(modelData.photo) : ""
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
                    TapHandler { onTapped: { page.zone = "messages"; page.sel = index; page.options() } }
                }
            }
            Text {
                anchors.centerIn: parent
                width: parent.width * 0.7
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.WordWrap
                visible: list.count === 0
                text: page.openKey === "" ? (Chat.nearbyOn ? (Chat.nearbyCount === 0 ? "Nobody else is here yet. Open Muninn on a phone close by or on this Wi-Fi." : "Say hello. Press A to write.")
                                                           : "Nearby chat is off. Turn it on in Settings › Chat.")
                                          : "No messages yet. Press A to write, hold Y to talk, X for a picture."
                color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(16)
            }

            // A photo full size.
            Rectangle {
                anchors.fill: parent
                visible: page.viewing !== ""
                color: "black"
                Image { anchors.fill: parent; anchors.margins: Theme.px(10); fillMode: Image.PreserveAspectFit; source: page.viewing !== "" ? Chat.photoUrl(page.viewing) : "" }
                TapHandler { onTapped: page.viewing = "" }
            }

            // Recording a voice message.
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
            visible: Theme.desktop && !lock.visible
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
                visible: page.openKey !== ""
                anchors.left: parent.left; anchors.leftMargin: Theme.px(14)
                anchors.verticalCenter: parent.verticalCenter
                name: "attach"; size: Theme.px(20); color: Theme.textMuted
                TapHandler { onTapped: page.pickPicture() }
            }
            Text {
                id: composeText
                anchors.left: attachIcon.visible ? attachIcon.right : parent.left
                anchors.leftMargin: Theme.px(14)
                anchors.right: parent.right; anchors.rightMargin: Theme.px(14)
                anchors.verticalCenter: parent.verticalCenter
                elide: Text.ElideRight
                text: page.openKey === "" ? "Message everyone nearby" : "Message " + page.openName
                color: Theme.textMuted
                font.family: Theme.body; font.pixelSize: Theme.fs(16)
            }
            HoverHandler { id: composeHover; cursorShape: Qt.IBeamCursor }
            TapHandler { onTapped: { page.zone = "messages"; page.write() } }
        }
    }

    // ============================================================= member column (groups, later)
    Rectangle {
        id: members
        visible: Theme.desktop && page.isGroup
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        width: Theme.px(210)
        color: Theme.column
    }

    // ============================================================= my code
    Rectangle {
        anchors.fill: parent
        visible: page.showCode && !lock.visible
        color: Qt.rgba(0, 0, 0, 0.85)
        Rectangle {
            anchors.centerIn: parent
            width: Theme.px(560); height: codeCol.implicitHeight + Theme.px(40)
            color: Theme.panel; radius: Theme.px(10); border.color: Theme.line; border.width: 1
            Column {
                id: codeCol
                x: Theme.px(20); y: Theme.px(20); width: parent.width - Theme.px(40)
                spacing: Theme.px(12)
                Text { text: "My code"; color: Theme.text; font.family: Theme.display; font.pixelSize: Theme.fs(21); font.weight: Font.DemiBold }
                Text { width: parent.width; wrapMode: Text.WordWrap; text: "They scan this with their phone's camera: it opens Muninn, or shows where to download it. It works for an hour, so a leaked code can't be used to spam you; people who already have you keep working."; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(16) }
                Text {
                    property real now: Date.now()
                    Timer { interval: 10000; repeat: true; running: page.showCode; onTriggered: parent.now = Date.now() }
                    text: Chat.linkExpires > now ? "Good for " + Math.ceil((Chat.linkExpires - now) / 60000) + " more minutes · X: new code" : "This code has expired · X: new code"
                    color: Chat.linkExpires > now ? Theme.accent : Theme.danger; font.family: Theme.body; font.pixelSize: Theme.fs(15)
                }
                Rectangle {
                    anchors.horizontalCenter: parent.horizontalCenter
                    width: Theme.px(300); height: width; color: Theme.text
                    Image { anchors.fill: parent; anchors.margins: Theme.px(14); smooth: false; source: page.showCode ? "image://qr/" + encodeURIComponent(Chat.myLink) : "" }
                }
                Text { width: parent.width; text: "A: copy the link · B: close"; horizontalAlignment: Text.AlignHCenter; color: Theme.textFaint; font.family: Theme.body; font.pixelSize: Theme.fs(14) }
            }
        }
        TapHandler { onTapped: page.showCode = false }
    }

    // ============================================================= actions
    function write() {
        if (openKey === "") {
            if (!Chat.nearbyOn) { Ui.say("Nearby chat is off. Turn it on in Settings › Chat.", true); return }
            Ui.ask("Message everyone nearby", "You're " + Chat.username, "", "Send", t => Chat.sendNearby(t), false, true)
        } else {
            const k = openKey
            Ui.ask("Message " + openName, "End-to-end encrypted", "", "Send", t => Chat.sendPrivate(k, t), false, true)
        }
    }
    function addSomeone() {
        Ui.ask("Add someone", "Paste the code or link they sent you (the code starts with MUNINN1), or a group code (MUNINNG1)", "", "Add", t => {
            if (t.indexOf("MUNINNG1:") >= 0) {
                const gc = Chat.parseGroupCode(t)
                const r = Chat.joinWithCode(t)
                if (r === "") Ui.say("Asked " + gc.adminName + " to let you in to " + gc.name + ". It opens once they do.")
                else Ui.say(r, true)
                return
            }
            const c = Chat.parseCode(t)
            if (!c.key) { Ui.say("That isn't a Muninn code. Ask them for My code › Share code.", true); return }
            if (c.mine) { Ui.say("That's your own code.", true); return }
            if (c.expired) { Ui.say("That code has expired (they last an hour). Ask them for a new one.", true); return }
            const r = Chat.replaces(t)
            const added = () => { Ui.say(c.name + " added. Say hi: they'll get it wherever they are."); openKey = c.key; zone = "messages" }
            if (!r.key) { Chat.addContact(t); added(); return }
            // A contact with this inbox or name already has another security key: never replaced quietly.
            if (r.sameInbox) {
                Ui.sure(r.name + "'s security key changed?", "This code has " + r.name + "'s inbox but a different security key. Use it only if you've checked with them "
                        + "another way (in person or a call). Your chat carries on with the new key, marked not verified.", "Use the new key",
                        () => { Chat.addContact(t, r.key); openKey = c.key; zone = "messages" }, false, true)
                return
            }
            Ui.pickFrom("Is this " + r.name + "?", [{ header: "Not the security key you have for " + r.name, items: [
                { name: "Someone new", detail: "Add " + c.name + " as another contact", url: "new" },
                { name: "Same person, new key", detail: "Your chat with " + r.name + " carries on, marked not verified. Check the code with them", url: "same" }] }],
                a => {
                    if (a === "same") { Chat.addContact(t, r.key); openKey = c.key; zone = "messages" }
                    else { Chat.addContact(t); added() }
                })
        })
    }
    // A name only for this computer: never sent. Empty goes back to their own name.
    function rename(k) {
        Ui.ask("Rename " + Chat.realNameOf(k), "Only on this computer, they aren't told. Leave it empty to use their own name.", Chat.nicknameOf(k), "Save",
               t => { Chat.setNickname(k, t); Ui.say(t.trim() === "" ? "Back to " + Chat.realNameOf(k) + "." : "Renamed to " + Chat.nameOf(k) + ".") }, false, true)
    }
    function checkCode(k) {
        const n = Chat.nameOf(k)
        Ui.sure("Check it's really " + n, "Code: " + Chat.codeWith(k) + "\n\nAsk them to open your chat and tap Check code. If their screen shows the same code, nobody is in between.",
                "It matches", () => Chat.verify(k))
    }
    function conversationItems() {
        const items = [{ name: "Call", detail: Calls.canCall(openKey) ? "Encrypted voice call" : "Needs Muninn or Huginn 0.6", url: "call" },
                       { name: "Send a picture", detail: "Location and camera details are removed", url: "picture" },
                       { name: "Rename", detail: "A name only you see. Now: " + openName, url: "rename" },
                       { name: "Folders", detail: folderNames(openKey), url: "folders" }]
        if (!Chat.isVerified(openKey)) items.push({ name: "Check code", detail: Chat.keyChanged(openKey) ? "Their security key changed: make sure it's really them" : "Make sure nobody is in between", url: "code" })
        return items
    }
    function startCall() {
        const w = Calls.call(openKey)
        if (w !== "") Ui.say(w, true)
    }
    function conversationOptions() {
        const k = openKey
        Ui.pickFrom(openName, [{ header: "", items: conversationItems() }], a => {
            if (a === "call") startCall()
            else if (a === "picture") pickPicture()
            else if (a === "rename") rename(k)
            else if (a === "code") checkCode(k)
            else if (a === "folders") pickFolders(k)
        })
    }
    // ------------------------------------------------------------- folders (only on this computer)
    function folderNames(k) {
        const ids = Chat.foldersOf(k)
        const names = Chat.folders.filter(f => ids.indexOf(f.id) >= 0).map(f => f.name)
        return names.length ? "In " + names.join(", ") : "Sort them into Family, Work, Kindred or your own"
    }
    function pickFolders(k) {
        const ids = Chat.foldersOf(k)
        const items = Chat.folders.map(f => ({ name: f.name, url: f.id, detail: ids.indexOf(f.id) >= 0 ? "In it · A takes them out" : "A puts them in" }))
        items.push({ name: "New folder", detail: "Make one and put them in it", url: "+" })
        Ui.pickFrom("Folders for " + Chat.nameOf(k), [{ header: "Only on this computer", items: items }], id => {
            if (id === "+") {
                Ui.ask("New folder", "A name only you see", "", "Make it", t => { const f = Chat.addFolder(t); if (f !== "") Chat.setInFolder(f, k, true) }, false, true)
                return
            }
            const on = Chat.foldersOf(k).indexOf(id) < 0
            Chat.setInFolder(id, k, on)
            const f = Chat.folders.find(x => x.id === id)
            Ui.say((on ? "Added to " : "Taken out of ") + (f ? f.name : "the folder") + ".")
        })
    }
    function pickChip() {
        const c = chips[chip]
        if (!c) return
        if (c.id === "+") {
            Ui.ask("New folder", "A name only you see, like Family, Work or Kindred", "", "Make it", t => { if (Chat.addFolder(t) === "") Ui.say("Give the folder a name.", true) }, false, true)
            return
        }
        folder = c.id
        row = 0
    }
    function chipOptions() {
        const c = chips[chip]
        if (!c || c.id === "" || c.id === "+") { pickChip(); return }
        const id = c.id
        Ui.pickFrom(c.name, [{ header: "Only on this computer", items: [
            { name: "Rename", url: "rename" },
            { name: "Delete the folder", detail: "The people in it stay", url: "delete" }] }], a => {
            if (a === "rename") Ui.ask("Rename " + c.name, "A name only you see", c.name, "Save", t => Chat.renameFolder(id, t), false, true)
            else if (a === "delete") Ui.sure("Delete " + c.name + "?", "Only the folder goes. Everyone in it stays in your chats.", "Delete",
                                         () => { if (folder === id) folder = ""; Chat.deleteFolder(id) }, false, false)
        })
    }
    function choose() {
        const e = entries[row]
        if (!e) return
        switch (e.kind) {
        case "add": addSomeone(); return
        case "code": Chat.newLink(); showCode = true; return
        case "nearby": openKey = ""; zone = "messages"; return
        case "ginvite":
            if (e.waiting) {
                Ui.sure("Waiting for " + e.fromName, "They let you in to " + e.name + " when their app gets your request. Stop waiting?", "Stop waiting", () => Chat.declineGroupInvite(e.g), false, false)
                return
            }
            Ui.sure(e.fromName + " invited you to " + e.name, "Everyone in a group sees your name and can message the group. You can leave any time.", "Join",
                    () => { const r = Chat.acceptGroupInvite(e.g); if (r === "") Ui.say("Joining " + e.name + "…"); else Ui.say(r, true) }, false, false,
                    () => Chat.declineGroupInvite(e.g))
            Ui.confirm.cancelText = "Decline"
            return
        case "request":
            if (e.replaces) {
                Ui.sure(e.name + " says they're " + e.replacesName, "Their security key isn't the one you have for " + e.replacesName + ". Accept only if you've checked with them "
                        + "another way (in person or a call). Your chat with " + e.replacesName + " then carries on with the new key, marked not verified.", "Accept",
                        () => { Chat.acceptRequest(e.key); openKey = e.key; zone = "messages" }, false, true, () => Chat.declineRequest(e.key))
                Ui.confirm.cancelText = "Decline"
                return
            }
            Ui.sure(e.name + " wants to chat", "They have your code. Only accept if you know who it is.", "Accept",
                    () => { Chat.acceptRequest(e.key); openKey = e.key; zone = "messages" }, false, false, () => Chat.declineRequest(e.key))
            Ui.confirm.cancelText = "Decline"   // backing out means no, so say so
            return
        case "stranger": Chat.meet(e.key, e.name); openKey = e.key; zone = "messages"; return
        default: openKey = e.key; zone = "messages"
        }
    }
    function pickPicture() {
        if (openKey === "") { Ui.say("Pictures go to one person. Open a chat first.", true); return }
        const pics = Chat.recentPictures()
        if (!pics.length) { Ui.say("No pictures found. Steam screenshots and the Pictures folder show up here.", true); return }
        const k = openKey
        Ui.pickFrom("Send a picture (location and camera details are removed)", [{ header: "Newest first", items: pics.map(p => Object.assign({ icon: "image" }, p)) }], path =>
            Ui.pickFrom("Send this picture", [{ header: "", items: [
                { name: "Send", detail: "Quick anywhere", url: "normal" },
                { name: "High quality", detail: "Sharper. Best close by or on Wi-Fi; up to a minute over the internet", url: "hd" }] }],
                q => { if (Chat.sendPhoto(k, path, q === "hd")) Ui.say("Sending the picture.") }))
    }
    function options() {
        const m = messages[sel]
        if (m && m.system) {
            const k = openKey
            const items = Chat.isVerified(k) ? [] : [{ name: "Check code", detail: "Make sure it's really them", url: "code" }]
            items.push({ name: "Delete for me", detail: "Only on this computer", url: "delete" })
            Ui.pickFrom("Security key", [{ header: "", items: items }], a => { if (a === "code") checkCode(k); else if (a === "delete") Chat.deleteMessage(k, m.id) })
            return
        }
        if (!m || m.unsent && !m.mine) { if (m && openKey !== "") Ui.sure("Delete this message?", "It's deleted on this computer only.", "Delete", () => Chat.deleteMessage(openKey, m.id), false, true); return }
        const items = []
        if (m.photo && !m.voice && !m.unsent) items.push({ name: "View full size", url: "view" }, { name: "Save to Pictures", url: "save" })
        if (m.voice && !m.unsent) items.push({ name: Voice.playing === m.id ? "Stop" : "Play", url: "play" })
        if (openKey !== "" && m.mine && !m.unsent && !m.failed) items.push({ name: "Unsend", detail: "Removes it from both sides; they'll see “Message deleted by " + Chat.username + "”", url: "unsend" })
        if (m.mine && m.failed) items.push({ name: "Try again", url: "retry" })
        if (openKey === "" && !m.mine) items.push({ name: "Mute " + m.name, detail: "Hide their messages here", url: "mute" })
        items.push({ name: openKey === "" ? "Delete" : "Delete for me", detail: "Only on this computer", url: "delete" })
        const k = openKey
        const sections = [{ header: "", items: items }]
        if (k !== "") sections.push({ header: "Conversation", items: conversationItems() })
        Ui.pickFrom("Message", sections, a => {
            if (a === "view") viewing = m.photo
            else if (a === "save") Ui.say(Chat.savePhoto(m.photo) ? "Saved in Pictures › Huginn." : "Couldn't save it.")
            else if (a === "play") { if (Voice.playing === m.id) Voice.stop(); else Voice.play(m.photo) }
            else if (a === "unsend") Chat.unsend(k, m.id)
            else if (a === "retry") Chat.retry(k, m.id)
            else if (a === "mute") Chat.block(m.peer, true)
            else if (a === "delete") { if (k === "") Chat.deleteNearbyMessage(m.id); else Chat.deleteMessage(k, m.id) }
            else if (a === "picture") pickPicture()
            else if (a === "rename") rename(k)
            else if (a === "code") checkCode(k)
        })
    }

    // Returns false for what this page doesn't use, so Main.qml can take it (Left or B to the rail).
    function handleButton(b) {
        if (lock.visible) return lock.handleButton(b)
        if (showCode) {
            if (b === "a") { Chat.copyLink(); Ui.say("Link copied. It works for an hour.") }
            else if (b === "x") Chat.newLink()
            else if (b === "b") showCode = false
            return true
        }
        if (viewing !== "") { if (b === "b" || b === "a") viewing = ""; return true }
        if (Voice.recording) { if (b === "b") { Voice.cancel(); Ui.say("Recording cancelled.") } return true }
        if (zone === "messages" && openKey !== "" && keep.visible) {
            if (b === "start") { Chat.keepInTouch(openKey); return true }
            if (b === "back") { Chat.notNow(openKey); return true }
        }
        if (zone === "chips") {
            switch (b) {
            case "left": if (chip === 0) return false; chip--; return true
            case "right": chip = Math.min(chips.length - 1, chip + 1); return true
            case "down": zone = "list"; return true
            case "a": pickChip(); return true
            case "x": chipOptions(); return true
            case "b": zone = "list"; return true
            case "up": return true
            }
            return false
        }
        switch (b) {
        case "up":
            if (zone === "list" && row === 0 && !locked) { zone = "chips"; chip = Math.max(0, chips.findIndex(c => c.id === folder)) }
            else if (zone === "list") row = Math.max(0, row - 1)
            else if (messages.length > 0) { sel = sel < 0 ? messages.length - 1 : Math.max(0, sel - 1); list.positionViewAtIndex(sel, ListView.Contain) }
            return true
        case "down":
            if (zone === "list") row = Math.min(entries.length - 1, row + 1)
            else if (sel >= 0) { sel = Math.min(messages.length - 1, sel + 1); list.positionViewAtIndex(sel, ListView.Contain) }
            return true
        case "right": zone = "messages"; return true
        case "left":
            if (zone === "list") return false
            zone = "list"; sel = -1
            return true
        case "a": if (zone === "list") choose(); else write(); return true
        case "x":
            if (zone === "messages" && sel >= 0) options()
            else if (zone === "messages" && openKey !== "") conversationOptions()
            else if (zone === "messages") write()
            return true
        case "y":
            if (zone === "messages" && openKey !== "" && !Voice.recording) {
                if (!Voice.start(openKey)) Ui.say("The microphone isn't available right now.", true)
            }
            return true
        case "b":
            if (zone === "messages") { zone = "list"; sel = -1; return true }
            return false
        }
        return false
    }
    function handleRelease(b) {
        if (b === "y" && Voice.recording && openKey !== "") Voice.finish(openKey)
        return true
    }
    Connections {
        target: Voice
        function onErrorChanged() { if (Voice.error !== "") Ui.say(Voice.error, true) }
    }
}

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
    // Desktop with a mouse and keyboard: type straight into the box at the bottom (Game Mode and
    // controllers keep the big TextPrompt).
    readonly property bool typeHere: Theme.desktop && !System.gameMode
    // Cap m1: the message being answered or edited, where the "New messages" line goes, the message
    // whose reaction picker is open.
    property string replyId: ""
    property string editId: ""
    property int newFrom: -1
    property string reactFor: ""
    readonly property var replyMsg: replyId === "" ? null : messages.find(m => m.id === replyId) || null
    // The side panel (desktop, a 1:1 chat only): "thread" for one message and its replies, "pins" for
    // this chat's pins (only on this computer), the same as in groups.
    property string side: ""
    property string threadRoot: ""
    readonly property bool isTour: tour
    readonly property var pins: tour || locked || openKey === "" ? [] : (Chat.chats, Chat.pinned(openKey))
    readonly property var pinnedMsgs: pins.map(id => messages.find(m => m.id === id)).filter(m => !!m)
    readonly property var threadMsgs: threadRoot === "" ? [] : messages.filter(m => m.id === threadRoot || m.re === threadRoot)
    readonly property bool theyType: openKey !== "" && !locked && Chat.typing[openKey] !== undefined
    readonly property bool theyActive: openKey !== "" && !locked && Chat.active[openKey] !== undefined

    function setArgs(a) {
        tour = !!a.tour
        changeLock = !!a.changeLock
        if (a.zone) zone = a.zone
        if (a.folders) { zone = "chips"; chip = Math.min(1, chips.length - 1) }
        if (a.open) { openKey = a.open; zone = "messages" }   // from Contacts › Message
    }

    readonly property bool locked: !tour && (!Vault.exists || Vault.screenLocked)
    // The lock's own LB (pattern or password) wins over moving along the rail.
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

    // What the hint bar shows (Main.qml adds LB). LT opens the options (the page gets it as X).
    readonly property var prompts: lock.visible ? [{ key: "a", label: lock.usePattern ? "Start or finish" : "Type password" }, { key: "b", label: "Back" }]
        : zone === "chips" ? [{ key: "a", label: "Show" }, { key: "lt", label: "Folder options" }, { key: "b", label: "Back" }]
        : zone === "list" ? [{ key: "a", label: "Open" }, { key: "b", label: "Back" }]
        : [{ key: "a", label: "Write" }].concat(openKey !== "" ? [{ key: "y", label: "Hold to talk" }] : [])
            .concat(sel >= 0 || openKey !== "" ? [{ key: "lt", label: "Options" }] : []).concat([{ key: "b", label: "Back" }])
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
        replyId = ""; editId = ""; reactFor = ""; threadRoot = ""; side = ""
        if (typeHere) compose.text = ""
        // Where the unread ones start (after my last message), for the "New messages" line.
        newFrom = -1
        if (openKey !== "" && !tour && !locked) {
            const c = Chat.chats.find(x => x.key === openKey)
            if (c && c.unread) {
                let i = messages.length
                while (i > 0 && !messages[i - 1].mine) i--
                newFrom = i < messages.length ? i : -1
            }
        }
        if (newFrom >= 0) Qt.callLater(() => list.positionViewAtIndex(page.newFrom, ListView.Beginning))
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
        anchors.right: sidePanel.visible ? sidePanel.left : members.visible ? members.left : parent.right
        anchors.top: parent.top
        anchors.bottom: parent.bottom

        PageHeader {
            id: convHead
            width: parent.width
            carved: page.locked
            title: page.locked ? "Locked" : page.openKey === "" ? "Nearby room" : page.openName
            subtitle: page.locked ? "" : page.openKey === "" ? "Everyone close by or on this Wi-Fi with Huginn or Muninn"
                    : page.theyType ? "typing…"
                    : (page.theyActive ? "Active now · " : "") + "Encrypted · " + (Chat.keyChanged(page.openKey) ? "security key changed, not verified" : Chat.isVerified(page.openKey) ? "verified" : "not verified")
            Row {
                visible: !page.locked && page.openKey !== ""
                spacing: Theme.px(6)
                Rectangle {
                    anchors.verticalCenter: parent.verticalCenter
                    width: Theme.px(8); height: width; radius: width / 2
                    color: page.openRoute === "" || page.openRoute === "none" ? Theme.textFaint : Theme.hc ? Theme.text : "#7FB08A"
                }
                // Active status (cap m1): their app is open right now.
                Text {
                    visible: page.theyActive
                    anchors.verticalCenter: parent.verticalCenter
                    text: "Active"
                    color: Theme.hc ? Theme.text : "#7FB08A"
                    font.family: Theme.body; font.pixelSize: Theme.fs(14)
                }
                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: page.routeShort(page.openRoute)
                    color: Theme.textMuted
                    font.family: Theme.body; font.pixelSize: Theme.fs(14)
                }
            }
            // Pinned messages of this chat (only on this computer).
            HButton {
                anchors.verticalCenter: parent.verticalCenter
                visible: page.typeHere && !page.locked && page.openKey !== ""
                text: page.pins.length ? "Pins · " + page.pins.length : "Pins"
                fontSize: 15
                onClicked: page.side = page.side === "pins" ? "" : "pins"
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
                    // The "New messages" line sits over the first unread one.
                    readonly property int lineH: index === page.newFrom ? Theme.px(30) : 0
                    width: list.width
                    height: modelData.system ? note.height + Theme.px(12) : lineH + Math.max(face.height, col.implicitHeight) + Theme.px(14)
                    radius: Theme.px(8)
                    color: picked ? Theme.focusFill : msgHover.hovered ? Theme.panelLow : "transparent"
                    border.width: picked ? Theme.focusWidth : 0
                    border.color: Theme.accent

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
                        x: Theme.px(8); y: msg.lineH + Theme.px(7)
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
                        y: msg.lineH + Theme.px(7)
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
                                text: page.clock(modelData.time) + (modelData.isEdited ? " · (edited)" : "") + page.stateLabel(modelData)
                                color: modelData.failed ? Theme.danger : modelData.state === "read" && modelData.mine ? Theme.accent : Theme.textMuted
                                font.family: Theme.body; font.pixelSize: Theme.fs(13)
                            }
                        }
                        // A reply: the message it answers, tap to go there.
                        QuoteLine {
                            visible: !!modelData.reName && !modelData.unsent
                            who: modelData.reName || ""
                            what: modelData.reText || ""
                            onJump: page.jumpTo(modelData.re)
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
                            textFormat: Text.PlainText
                            text: modelData.unsent ? (modelData.mine ? "Message deleted by you" : "Message deleted by " + modelData.unsent) : (modelData.text || "")
                            font.italic: !!modelData.unsent
                            color: modelData.unsent ? Theme.textFaint : Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(17)
                        }
                        Reactions {
                            width: parent.width
                            list: modelData.rxList || []
                            add: page.typeHere && page.openKey !== ""
                            onToggled: (e, mine) => page.reactTo(modelData.id, mine ? "" : e)
                            onMore: page.openReact(modelData.id, modelData.myReaction || "")
                        }
                        // A thread: how many replies, opening them beside the chat.
                        Text {
                            visible: page.typeHere && page.openKey !== "" && (modelData.replies || 0) > 0 && !modelData.unsent
                            text: (modelData.replies === 1 ? "1 reply" : modelData.replies + " replies") + " ›"
                            color: Theme.accent; font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.DemiBold
                            Accessible.role: Accessible.Button
                            Accessible.name: "Open the thread"
                            HoverHandler { cursorShape: Qt.PointingHandCursor }
                            TapHandler { onTapped: page.openThread(modelData.id) }
                        }
                    }
                    // Desktop: react, reply, edit and more over the corner while the pointer is on it.
                    HoverBar {
                        visible: page.typeHere && page.openKey !== "" && msgHover.hovered && !modelData.system && !modelData.unsent
                        anchors.right: parent.right; anchors.rightMargin: Theme.px(10)
                        y: msg.lineH - Theme.px(6)
                        z: 2
                        canEdit: !!modelData.mine && !modelData.photo && !modelData.failed
                        canThread: true
                        canPin: true
                        pinnedNow: page.pins.indexOf(modelData.id) >= 0
                        onReact: page.openReact(modelData.id, modelData.myReaction || "")
                        onReply: page.startReply(modelData.id)
                        onThread: page.openThread(modelData.id)
                        onPin: page.togglePin(modelData.id)
                        onEdit: page.startEdit(modelData)
                        onMore: { page.zone = "messages"; page.sel = index; page.options() }
                    }
                    HoverHandler { id: msgHover }
                    // Click: a photo opens; right click (or a click on a handheld) opens the options.
                    TapHandler {
                        acceptedButtons: Qt.LeftButton | Qt.RightButton
                        onTapped: (point, button) => {
                            page.zone = "messages"; page.sel = index
                            if (button === Qt.RightButton || !page.typeHere) page.options()
                            else if (modelData.photo && !modelData.voice && !modelData.unsent) page.viewing = modelData.photo
                        }
                    }
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
                                          : "No messages yet. Press A to write, hold Y to talk, LT for a picture and more."
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

            // "Raven is typing" under the last message (cap m1).
            Text {
                visible: page.theyType
                anchors.left: parent.left; anchors.leftMargin: Theme.px(22)
                anchors.bottom: parent.bottom; anchors.bottomMargin: Theme.px(2)
                text: page.openName + " is typing…"
                color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13); font.italic: true
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

            // Drop pictures (or other files) on the chat to send them.
            DropArea {
                anchors.fill: parent
                enabled: page.typeHere && page.openKey !== "" && !page.locked
                onDropped: drop => { if (drop.hasUrls) page.dropFiles(drop.urls); drop.accept() }
                Rectangle {
                    anchors.fill: parent
                    visible: parent.containsDrag
                    color: Qt.rgba(0, 0, 0, 0.55)
                    border.color: Theme.accent; border.width: 2; radius: Theme.px(10)
                    Text { anchors.centerIn: parent; text: "Drop to send to " + page.openName; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(20) }
                }
            }

            // Reacting: the quick five and any emoji.
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
            visible: page.typeHere && !lock.visible
            anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
            anchors.leftMargin: Theme.px(20); anchors.rightMargin: Theme.px(20); anchors.bottomMargin: Theme.px(18)
            height: implicitHeight
            placeholder: page.openKey === "" ? "Message everyone nearby" : "Message " + page.openName
            target: page.openKey
            editing: page.editId !== ""
            bannerTitle: page.editId !== "" ? "Editing your message" : page.replyMsg ? "Replying to " + (page.replyMsg.mine ? "yourself" : page.openName) : ""
            bannerText: page.editId !== "" ? "Enter saves, Esc cancels" : page.replyMsg ? (page.replyMsg.photo ? (page.replyMsg.voice ? "Voice message" : "Photo") : page.replyMsg.text || "") : ""
            onSend: t => page.sendText(t)
            onTyping: on => { if (page.openKey !== "" && !page.tour) Chat.typingTo(page.openKey, on) }
            onEditLast: page.editLast()
            onEscaped: page.escapeKey()
            onCancelBanner: { page.replyId = ""; if (page.editId !== "") { page.editId = ""; compose.text = "" } }
            onAttach: page.pickPicture()
        }
    }

    // ============================================================= side panel (desktop): a thread or the pins
    property alias threadCompose: threadBox
    Rectangle {
        id: sidePanel
        visible: page.typeHere && !lock.visible && page.openKey !== "" && page.side !== ""
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        width: Theme.px(360)
        color: Theme.column
        PageHeader {
            id: sideHead
            width: parent.width
            title: page.side === "thread" ? "Thread" : "Pinned"
            subtitle: page.side === "thread" ? "With " + page.openName : page.pinnedMsgs.length ? page.pinnedMsgs.length + " pinned · only on this computer" : "Nothing pinned with " + page.openName
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
                who: page.senderName(modelData)
                inThread: true
                hoverTools: page.typeHere
                onOptions: { const i = page.messages.findIndex(x => x.id === modelData.id); if (i >= 0) { page.zone = "messages"; page.sel = i; page.options() } }
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
            visible: page.side === "thread"
            anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
            anchors.margins: Theme.px(12)
            height: implicitHeight
            placeholder: "Reply in the thread"
            target: page.openKey
            onSend: t => page.sendText(t, page.threadRoot)
            onTyping: on => { if (page.openKey !== "" && !page.tour) Chat.typingTo(page.openKey, on) }
            onEscaped: { page.side = ""; page.threadRoot = ""; compose.focusField() }
            onAttach: page.pickPicture()
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
        if (typeHere && !lock.visible) { zone = "messages"; compose.focusField(); return }
        if (openKey === "") {
            if (!Chat.nearbyOn) { Ui.say("Nearby chat is off. Turn it on in Settings › Chat.", true); return }
            Ui.ask("Message everyone nearby", "You're " + Chat.username, "", "Send", t => Chat.sendNearby(t), false, true)
        } else {
            const k = openKey, re = replyId
            Ui.ask(re !== "" && replyMsg ? "Reply to " + (replyMsg.mine ? "yourself" : openName) : "Message " + openName, re !== "" && replyMsg ? (replyMsg.text || "Photo") : "End-to-end encrypted", "", "Send",
                   t => { Chat.sendPrivate(k, t, re); page.replyId = "" }, false, true)
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
        const items = [{ name: "Call", detail: Calls.canCall(openKey) ? "Encrypted voice call" : "Needs Muninn or Huginn 0.6", url: "call" }]
        if (Calls.canVideo(openKey)) items.push({ name: "Video call", detail: "Encrypted, 480p through the relay to save data", url: "video" })
        items.push(
                       { name: "Send a picture", detail: "Location and camera details are removed", url: "picture" },
                       { name: "Rename", detail: "A name only you see. Now: " + openName, url: "rename" },
                       { name: "Folders", detail: folderNames(openKey), url: "folders" })
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
            else if (a === "video") { const w = Calls.callVideo(k); if (w !== "") Ui.say(w, true) }
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
    // A controller: the quick reactions in a list (the emoji panel needs a pointer).
    function pickReaction(m) {
        const k = openKey, id = m.id
        const items = Chat.quickReactions().map(e => ({ name: e, detail: e === m.myReaction ? "Yours · choose to take it back" : "", url: e }))
        if (m.myReaction && Chat.quickReactions().indexOf(m.myReaction) < 0) items.push({ name: m.myReaction, detail: "Yours · choose to take it back", url: m.myReaction })
        if (typeHere) items.push({ name: "More emoji", url: "+" })
        Ui.pickFrom("React", [{ header: "", items: items }], e => {
            if (e === "+") { openReact(id, m.myReaction || ""); return }
            const r = Chat.react(k, id, e === m.myReaction ? "" : e)
            if (r !== "") Ui.say(r, true)
        })
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
        if (openKey !== "" && !m.unsent && !m.failed) {
            items.push({ name: "React", detail: m.myReaction ? "Yours: " + m.myReaction : "❤️ 😂 👍 😮 😢 or any emoji", url: "react" })
            items.push({ name: "Reply", detail: "Quotes it in your answer", url: "reply" })
            if (typeHere) items.push({ name: "Open thread", detail: (m.replies || 0) + ((m.replies || 0) === 1 ? " reply" : " replies"), url: "thread" })
        }
        if (openKey !== "" && !m.unsent && !tour) items.push({ name: pins.indexOf(m.id) >= 0 ? "Unpin" : "Pin", detail: "Only on this computer", url: "pin" })
        if (openKey !== "" && m.mine && !m.unsent && !m.failed && !m.photo) items.push({ name: "Edit", detail: "They see it marked (edited)", url: "edit" })
        if (m.text && !m.unsent) items.push({ name: "Copy text", url: "copy" })
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
            if (a === "react") pickReaction(m)
            else if (a === "reply") startReply(m.id)
            else if (a === "thread") openThread(m.id)
            else if (a === "pin") togglePin(m.id)
            else if (a === "edit") startEdit(m)
            else if (a === "copy") { Chat.copyText(m.text); Ui.say("Copied.") }
            else if (a === "view") viewing = m.photo
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

    // ------------------------------------------------------------- cap m1: replies, reactions, edits
    function jumpTo(id) {
        const i = messages.findIndex(m => m.id === id)
        if (i < 0) { Ui.say("That message isn't here anymore."); return }
        sel = i
        list.positionViewAtIndex(i, ListView.Center)
    }
    function reactTo(id, e) {
        if (openKey === "" || id === "") return
        const r = Chat.react(openKey, id, e)
        if (r !== "") Ui.say(r, true)
    }
    function openReact(id, mine) {
        if (openKey === "") return
        reactFor = id
        reactPicker.current = mine
        reactPicker.visible = true
    }
    function startReply(id) {
        if (openKey === "") return
        editId = ""
        replyId = id
        if (typeHere) compose.focusField(); else write()
    }
    function startEdit(m) {
        if (!m || !m.mine || m.photo || m.unsent || openKey === "") return
        if (!Chat.hasM1(openKey)) { Ui.say(openName + " needs a newer Huginn or Muninn to see edits.", true); return }
        replyId = ""
        if (typeHere) { editId = m.id; compose.setText(m.text) }
        else { const k = openKey, id = m.id; Ui.ask("Edit your message", "They see it marked (edited)", m.text, "Save", t => { const r = Chat.editMessage(k, id, t); if (r !== "") Ui.say(r, true) }, false, true) }
    }
    // Up in an empty box: edit my last text message.
    function editLast() {
        for (let i = messages.length - 1; i >= 0; i--) {
            const m = messages[i]
            if (m.mine && !m.photo && !m.unsent && !m.failed && !m.system) { startEdit(m); return }
        }
    }
    // Esc: done with this chat for now: no reply or edit, and it's all read.
    function escapeKey() {
        replyId = ""; editId = ""; newFrom = -1
        if (openKey !== "") Chat.markRead(openKey)
    }
    // A thread: the message and its replies beside the chat, with its own reply box.
    function openThread(id) {
        if (openKey === "" || !typeHere) return
        threadRoot = id
        side = "thread"
        Qt.callLater(() => threadBox.focusField())
    }
    function togglePin(id) {
        if (tour || openKey === "") return
        const on = pins.indexOf(id) < 0
        Chat.setPinned(openKey, id, on)
        Ui.say(on ? "Pinned. Pins are only on this computer." : "Unpinned.")
    }
    // re: the message answered; a reply in a thread passes the thread's first message.
    function sendText(t, re) {
        if (editId !== "") {
            const r = Chat.editMessage(openKey, editId, t)
            if (r !== "") Ui.say(r, true)
            editId = ""
            return
        }
        if (openKey === "") {
            if (!Chat.nearbyOn) { Ui.say("Nearby chat is off. Turn it on in Settings › Chat.", true); return }
            Chat.sendNearby(t)
        } else Chat.sendPrivate(openKey, t, re !== undefined ? re : replyId)
        if (re === undefined) replyId = ""
        newFrom = -1
    }
    // Dropped files: pictures go in the chat; anything else goes to Files, ready to send over the Wi-Fi.
    function dropFiles(urls) {
        const others = []
        let pics = 0
        for (let i = 0; i < urls.length; i++) {
            const path = Chat.localPath(urls[i])
            if (path === "") continue
            if (Chat.isPicture(path)) { if (Chat.sendPhoto(openKey, path, false)) pics++ }
            else others.push(urls[i])
        }
        if (pics) Ui.say(pics === 1 ? "Sending the picture." : "Sending " + pics + " pictures.")
        if (others.length) {
            Transfer.share(others)
            Ui.say((others.length === 1 ? "That file is" : "Those files are") + " ready in Files: pick a device on the same Wi-Fi to send.")
            Ui.openPage("files", {})
        }
    }

    // The list the left stick scrolls when the pointer isn't showing: the one with the focus.
    function scrollView() { return zone === "messages" ? list : listPane }
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

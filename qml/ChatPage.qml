import QtQuick
import Huginn

// Chat, the same as Muninn's. Left: Add someone, My code, requests, the Nearby room, chats and
// people around you. Right: the conversation. Locked with a pattern or password like Muninn.
Rectangle {
    id: page
    color: Theme.ground
    property int row: 0
    property string zone: "list"      // "list", "messages" or "lock"
    property string openKey: ""       // "" = Nearby room, otherwise a private chat
    property int sel: -1              // selected message, for its options
    property bool showCode: false
    property string viewing: ""       // a photo open full size
    property bool tour: false
    property bool changeLock: false

    function setArgs(a) { tour = !!a.tour; changeLock = !!a.changeLock }

    readonly property bool locked: !tour && (!Vault.exists || Vault.screenLocked)

    readonly property var entries: {
        const out = [{ kind: "add", name: "Add someone", detail: "Paste the code they sent you" },
                     { kind: "code", name: "My code", detail: "Show a QR anyone can scan to message you" }]
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

    function routeText(r) {
        return r === "bluetooth" ? "Close by (Bluetooth)" : r === "wifi" ? "Same Wi-Fi" : r === "internet" ? "Over the internet" : "Not reachable right now"
    }
    function routeIcon(r) { return r === "bluetooth" ? "phone" : r === "wifi" ? "network" : r === "internet" ? "network" : "phone" }
    function clock(ms) { return Qt.formatTime(new Date(ms), "h:mm AP") }
    function status(m) {
        if (!m.mine) return ""
        if (m.failed) return " · Didn't send"
        if (m.state === "unsending") return " · Removing from their phone"
        if (m.unsent) return ""
        return m.state === "read" ? " · Read" : m.state === "delivered" ? " · Delivered" : m.state === "sent" ? " · Sent" : m.state === "sending" ? " · Sending" : ""
    }

    onOpenKeyChanged: { sel = -1; Voice.stop(); if (openKey !== "") Chat.markRead(openKey); Chat.openChat = openKey }
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

    PageHeader {
        id: header
        width: parent.width
        title: "Chat"
        subtitle: page.locked ? "Locked" : page.openKey === "" ? "Nearby: everyone close by or on this Wi-Fi with Huginn or Muninn"
                : (Chat.chats, page.routeText(Chat.route(page.openKey))) + " · end-to-end encrypted · "
                  + (Chat.keyChanged(page.openKey) ? "security key changed, not verified" : Chat.isVerified(page.openKey) ? "verified" : "not verified")
    }
    Divider { id: divider; anchors.top: header.bottom; x: Theme.px(28); width: parent.width - Theme.px(56) }

    // ------------------------------------------------------------- locked
    LockPanel {
        id: lock
        visible: page.locked || page.changeLock
        mode: page.changeLock && !page.locked ? "change" : Vault.exists ? "unlock" : "setup"
        anchors.top: divider.bottom; anchors.topMargin: Theme.px(16)
        anchors.horizontalCenter: parent.horizontalCenter
        width: Math.min(parent.width - Theme.px(56), Theme.px(620))
        onFinished: how => { if (how === "change") page.changeLock = false }   // after an unlock, the change step follows
        onCancelled: { if (page.changeLock) page.changeLock = false; else Ui.closePage() }
    }

    // ------------------------------------------------------------- list
    ListView {
        id: listPane
        visible: !lock.visible
        anchors.top: divider.bottom
        anchors.topMargin: Theme.px(12)
        anchors.bottom: bar.top
        anchors.bottomMargin: Theme.px(14)
        anchors.left: parent.left
        anchors.leftMargin: Theme.px(28)
        width: Theme.px(380)
        spacing: Theme.px(8)
        clip: true
        model: page.entries
        currentIndex: page.row
        highlightFollowsCurrentItem: false
        onCurrentIndexChanged: positionViewAtIndex(currentIndex, ListView.Contain)
        delegate: Rectangle {
            readonly property bool on: page.zone === "list" && page.row === index
            readonly property bool here: (modelData.kind === "nearby" && page.openKey === "") || (modelData.key !== undefined && modelData.key === page.openKey)
            width: listPane.width
            height: Theme.px(64)
            radius: Theme.radius
            color: on ? Theme.focusFill : here ? Theme.panel : Theme.panelLow
            border.width: on ? Theme.focusWidth : 1
            border.color: on || here ? Theme.accent : Theme.line
            Accessible.role: Accessible.ListItem
            Accessible.name: modelData.name + ", " + modelData.detail
            Icon {
                id: ico
                anchors.left: parent.left; anchors.leftMargin: Theme.px(14)
                anchors.verticalCenter: parent.verticalCenter
                name: modelData.kind === "add" ? "plus" : modelData.kind === "code" ? "qr" : modelData.kind === "nearby" ? "network"
                    : modelData.kind === "request" ? "lock" : page.routeIcon(modelData.route)
                color: parent.here || parent.on ? Theme.accent : Theme.textMuted
            }
            Column {
                anchors.left: ico.right; anchors.leftMargin: Theme.px(12)
                anchors.right: dot.left; anchors.rightMargin: Theme.px(8)
                anchors.verticalCenter: parent.verticalCenter
                Text { width: parent.width; elide: Text.ElideRight; text: modelData.name; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(18); font.weight: Font.DemiBold }
                Text { width: parent.width; elide: Text.ElideRight; text: modelData.detail; color: modelData.kind === "request" ? Theme.accent : Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(14) }
            }
            Rectangle {
                id: dot
                anchors.right: parent.right; anchors.rightMargin: Theme.px(14)
                anchors.verticalCenter: parent.verticalCenter
                width: Theme.px(10); height: width; radius: width / 2
                color: Theme.accent
                visible: !!modelData.unread
            }
            TapHandler { onTapped: { page.zone = "list"; page.row = index; page.choose() } }
        }
    }

    // ------------------------------------------------------------- conversation
    Rectangle {
        id: pane
        visible: !lock.visible
        anchors.top: listPane.top
        anchors.left: listPane.right
        anchors.leftMargin: Theme.px(16)
        anchors.right: parent.right
        anchors.rightMargin: Theme.px(28)
        anchors.bottom: bar.top
        anchors.bottomMargin: Theme.px(14)
        radius: Theme.radius + 1
        color: Theme.panel
        border.width: page.zone === "messages" ? Theme.focusWidth : 1
        border.color: page.zone === "messages" ? Theme.accent : Theme.line
        clip: true

        // Keep in touch: someone met close by, after 10 messages or once they're out of range.
        Rectangle {
            id: keep
            visible: page.openKey !== "" && Chat.shouldAskKeep(page.openKey) && (Chat.chats, true)
            anchors.top: parent.top; anchors.left: parent.left; anchors.right: parent.right
            anchors.margins: Theme.px(10)
            height: visible ? keepCol.implicitHeight + Theme.px(20) : 0
            radius: Theme.radius; color: Theme.accentFill; border.color: Theme.accent; border.width: 1
            Column {
                id: keepCol
                x: Theme.px(14); y: Theme.px(10); width: parent.width - Theme.px(28)
                Text { text: Chat.theySaidKeep(page.openKey) ? page.openName + " wants to keep in touch" : "Keep in touch with " + page.openName + "?"; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(18); font.weight: Font.DemiBold }
                Text { width: parent.width; wrapMode: Text.WordWrap; text: "If you both say yes, you can message each other from anywhere. Start: Yes · Select: Not now"; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(14) }
            }
        }

        ListView {
            id: list
            anchors.top: keep.visible ? keep.bottom : parent.top
            anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
            anchors.margins: Theme.px(14)
            spacing: Theme.px(8)
            model: page.messages
            onCountChanged: positionViewAtEnd()
            delegate: Item {
                width: list.width
                height: bubble.height
                readonly property bool picked: page.zone === "messages" && page.sel === index
                Rectangle {
                    id: bubble
                    // A note from Huginn itself (a changed security key) sits in the middle.
                    anchors.right: modelData.mine ? parent.right : undefined
                    anchors.horizontalCenter: modelData.system ? parent.horizontalCenter : undefined
                    width: Math.min(list.width * (modelData.system ? 0.9 : 0.75), Math.max(body.implicitWidth, meta.implicitWidth, modelData.photo ? Theme.px(260) : 0) + Theme.px(28))
                    height: col.implicitHeight + Theme.px(18)
                    radius: Theme.radius + 2
                    color: modelData.system ? Theme.dangerFill : modelData.mine ? Theme.accentFill : Theme.raised
                    border.width: parent.picked ? Theme.focusWidth : modelData.system ? 1 : 0
                    border.color: modelData.system && !parent.picked ? Theme.danger : Theme.accent
                    Column {
                        id: col
                        x: Theme.px(14); y: Theme.px(9)
                        width: parent.width - Theme.px(28)
                        spacing: Theme.px(4)
                        Text { visible: !modelData.mine && !modelData.system && page.openKey === ""; text: modelData.name; color: Theme.accent; font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.DemiBold }
                        Image {
                            visible: !!modelData.photo && !modelData.voice && !modelData.unsent
                            width: Theme.px(260); height: Theme.px(190)
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
                            id: body
                            visible: !modelData.photo || !!modelData.unsent
                            width: parent.width; wrapMode: Text.Wrap
                            text: modelData.unsent ? (modelData.mine ? "Message deleted by you" : "Message deleted by " + modelData.unsent) : modelData.text
                            font.italic: !!modelData.unsent || !!modelData.system
                            color: modelData.unsent ? Theme.textFaint : Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(18)
                        }
                        Text {
                            id: meta
                            text: page.clock(modelData.time) + page.status(modelData)
                            color: modelData.failed ? Theme.danger : modelData.state === "read" && modelData.mine ? Theme.accent : Theme.textFaint
                            font.family: Theme.body; font.pixelSize: Theme.fs(13)
                        }
                    }
                    TapHandler { onTapped: { page.zone = "messages"; page.sel = index; page.options() } }
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
                                      : "No messages yet. Press A to write, hold Y to talk, RB for a picture."
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(17)
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
            anchors.margins: Theme.px(10)
            height: Theme.px(56)
            visible: Voice.recording
            radius: Theme.radius; color: Theme.dangerFill; border.color: Theme.danger; border.width: 1
            Row {
                anchors.verticalCenter: parent.verticalCenter
                x: Theme.px(16); spacing: Theme.px(12)
                Rectangle { width: Theme.px(12); height: width; radius: width / 2; color: Theme.danger; anchors.verticalCenter: parent.verticalCenter }
                Text { text: Voice.length(Voice.elapsed) + "   Let go of Y to send · B to cancel"; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(18) }
            }
        }
    }

    // ------------------------------------------------------------- my code
    Rectangle {
        anchors.fill: parent
        visible: page.showCode && !lock.visible
        color: Qt.rgba(0, 0, 0, 0.85)
        Rectangle {
            anchors.centerIn: parent
            width: Theme.px(560); height: codeCol.implicitHeight + Theme.px(40)
            color: Theme.panel; radius: Theme.radius + 1; border.color: Theme.line; border.width: 1
            Column {
                id: codeCol
                x: Theme.px(20); y: Theme.px(20); width: parent.width - Theme.px(40)
                spacing: Theme.px(12)
                Text { text: "My code"; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(22); font.weight: Font.DemiBold }
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

    PromptBar {
        id: bar
        anchors.bottom: parent.bottom
        width: parent.width
        status: lock.visible ? "" : Chat.internet > 0 ? "Internet: connected" : "Internet: offline"
        prompts: lock.visible ? [{ key: "b", label: "Back" }]
            : page.zone === "list" ? [{ key: "a", label: "Open" }, { key: "b", label: "Back" }]
            : [{ key: "a", label: "Write" }].concat(page.openKey !== "" ? [{ key: "y", label: "Hold to talk" }, { key: "rb", label: "Picture" }] : [])
                .concat(page.sel >= 0 || page.openKey !== "" ? [{ key: "x", label: "Options" }] : []).concat([{ key: "b", label: "List" }])
        onPrompt: k => page.handleButton(k)
    }

    // ------------------------------------------------------------- actions
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
        Ui.ask("Add someone", "Paste the code they sent you (it starts with MUNINN1)", "", "Add", t => {
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
        const items = [{ name: "Rename", detail: "A name only you see. Now: " + openName, url: "rename" }]
        if (!Chat.isVerified(openKey)) items.push({ name: "Check code", detail: Chat.keyChanged(openKey) ? "Their security key changed: make sure it's really them" : "Make sure nobody is in between", url: "code" })
        return items
    }
    function conversationOptions() {
        const k = openKey
        Ui.pickFrom(openName, [{ header: "", items: conversationItems() }], a => {
            if (a === "rename") rename(k)
            else if (a === "code") checkCode(k)
        })
    }
    function choose() {
        const e = entries[row]
        if (!e) return
        switch (e.kind) {
        case "add": addSomeone(); return
        case "code": Chat.newLink(); showCode = true; return
        case "nearby": openKey = ""; zone = "messages"; return
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
        const pics = Chat.recentPictures()
        if (!pics.length) { Ui.say("No pictures found. Steam screenshots and the Pictures folder show up here.", true); return }
        const k = openKey
        Ui.pickFrom("Send a picture (location and camera details are removed)", [{ header: "Newest first", items: pics }], path =>
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
            else if (a === "rename") rename(k)
            else if (a === "code") checkCode(k)
        })
    }

    function handleButton(b) {
        if (lock.visible) { lock.handleButton(b); return true }
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
            if (b === "select") { Chat.notNow(openKey); return true }
        }
        switch (b) {
        case "up":
            if (zone === "list") row = Math.max(0, row - 1)
            else { sel = sel < 0 ? messages.length - 1 : Math.max(0, sel - 1); list.positionViewAtIndex(sel, ListView.Contain) }
            break
        case "down":
            if (zone === "list") row = Math.min(entries.length - 1, row + 1)
            else if (sel >= 0) { sel = Math.min(messages.length - 1, sel + 1); list.positionViewAtIndex(sel, ListView.Contain) }
            break
        case "right": zone = "messages"; break
        case "left": zone = "list"; sel = -1; break
        case "a": if (zone === "list") choose(); else write(); break
        case "x":
            if (zone === "messages" && sel >= 0) options()
            else if (zone === "messages" && openKey !== "") conversationOptions()
            else if (zone === "messages") write()
            break
        case "y":
            if (zone === "messages" && openKey !== "" && !Voice.recording) {
                if (!Voice.start(openKey)) Ui.say("The microphone isn't available right now.", true)
            }
            break
        case "rb": if (zone === "messages" && openKey !== "") pickPicture(); break
        case "b": if (zone === "messages") { zone = "list"; sel = -1 } else Ui.closePage(); break
        }
        return true
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

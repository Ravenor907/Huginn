import QtQuick
import Huginn

// Chat with Muninn phones and other Huginns nearby. Left: Nearby (the public room), requests,
// conversations and people. Right: the messages. A writes with Steam's keyboard.
Rectangle {
    id: page
    color: Theme.ground
    property int row: 0
    property string zone: "list"      // "list" or "messages"
    property string openKey: ""       // "" = Nearby, otherwise a private conversation
    property string openName: ""
    property bool tour: false

    function setArgs(a) { tour = !!a.tour }

    readonly property var entries: {
        const out = [{ kind: "nearby", name: "Nearby", detail: Chat.nearbyOn ? Chat.nearbyCount + " here · you're " + Chat.username : "Off" }]
        Chat.requests.forEach(r => out.push({ kind: "request", key: r.key, name: r.name, detail: "Wants to chat · code " + r.code, code: r.code }))
        const seen = {}
        Chat.conversations.forEach(c => { seen[c.key] = true; out.push({ kind: "convo", key: c.key, name: c.name, detail: c.last }) })
        Chat.people.forEach(p => { if (!seen[p.key]) out.push({ kind: "person", key: p.key, name: p.name, detail: p.trusted ? "Verified" : "Not verified yet" }) })
        return out
    }
    readonly property var messages: {
        if (tour && openKey === "" && Chat.nearby.length === 0)
            return [{ id: "1", name: "Raven 4821", text: "Anyone have the Emerald save from yesterday?", mine: false, time: "8:41 PM" },
                    { id: "2", name: Chat.username, text: "Sending it over LocalSend now", mine: true, time: "8:42 PM" }]
        return openKey === "" ? Chat.nearby : (Chat.conversations, Chat.messagesWith(openKey))
    }

    PageHeader {
        id: header
        width: parent.width
        title: "Chat"
        subtitle: page.openKey === "" ? "Nearby: everyone on this Wi-Fi with Huginn or Muninn" : "Private with " + page.openName + " · only you two can read it"
    }
    Divider { id: divider; anchors.top: header.bottom; x: Theme.px(28); width: parent.width - Theme.px(56) }

    Column {
        id: left
        anchors.top: divider.bottom
        anchors.topMargin: Theme.px(12)
        anchors.left: parent.left
        anchors.leftMargin: Theme.px(28)
        width: Theme.px(360)
        spacing: Theme.px(8)
        Repeater {
            model: page.entries
            Rectangle {
                readonly property bool on: page.zone === "list" && page.row === index
                readonly property bool here: (modelData.kind === "nearby" && page.openKey === "") || (modelData.key && modelData.key === page.openKey)
                width: left.width
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
                    name: modelData.kind === "nearby" ? "network" : modelData.kind === "request" ? "lock" : "phone"
                    color: parent.here || parent.on ? Theme.accent : Theme.textMuted
                }
                Column {
                    anchors.left: ico.right; anchors.leftMargin: Theme.px(12)
                    anchors.right: parent.right; anchors.rightMargin: Theme.px(12)
                    anchors.verticalCenter: parent.verticalCenter
                    Text { width: parent.width; elide: Text.ElideRight; text: modelData.name; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.px(18); font.weight: Font.DemiBold }
                    Text { width: parent.width; elide: Text.ElideRight; text: modelData.detail; color: modelData.kind === "request" ? Theme.accent : Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.px(14) }
                }
                TapHandler { onTapped: { page.zone = "list"; page.row = index; page.choose() } }
            }
        }
    }

    Rectangle {
        id: pane
        anchors.top: left.top
        anchors.left: left.right
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
        ListView {
            id: list
            anchors.fill: parent
            anchors.margins: Theme.px(14)
            spacing: Theme.px(8)
            model: page.messages
            onCountChanged: positionViewAtEnd()
            delegate: Item {
                width: list.width
                height: bubble.height
                Rectangle {
                    id: bubble
                    anchors.right: modelData.mine ? parent.right : undefined
                    width: Math.min(list.width * 0.75, body.implicitWidth + Theme.px(28))
                    height: col.implicitHeight + Theme.px(18)
                    radius: Theme.radius + 2
                    color: modelData.mine ? Theme.accentFill : Theme.raised
                    Column {
                        id: col
                        x: Theme.px(14); y: Theme.px(9)
                        width: parent.width - Theme.px(28)
                        Text { visible: !modelData.mine && page.openKey === ""; text: modelData.name; color: Theme.accent; font.family: Theme.body; font.pixelSize: Theme.px(14); font.weight: Font.DemiBold }
                        Text { id: body; width: parent.width; wrapMode: Text.Wrap; text: modelData.text; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.px(18) }
                        Text { text: modelData.time + (modelData.failed ? " · didn't send" : ""); color: modelData.failed ? Theme.danger : Theme.textFaint; font.family: Theme.body; font.pixelSize: Theme.px(13) }
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
            text: page.openKey === "" ? (Chat.nearbyOn ? (Chat.nearbyCount === 0 ? "Nobody else is here yet. Open Muninn on a phone on this Wi-Fi." : "Say hello. Press A to write.")
                                                       : "Nearby chat is off. Turn it on in Settings › General.")
                                      : "No messages yet. Press A to write."
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.px(17)
        }
    }

    PromptBar {
        id: bar
        anchors.bottom: parent.bottom
        width: parent.width
        status: "Steam's keyboard opens when you write"
        prompts: [{ key: "a", label: page.zone === "list" ? "Open" : "Write" }, { key: "x", label: "Write" }]
            .concat(page.openKey === "" ? [{ key: "y", label: "Mute someone" }] : [])
            .concat([{ key: "b", label: page.zone === "messages" ? "List" : "Back" }])
        onPrompt: k => page.handleButton(k)
    }

    function write() {
        if (openKey === "") {
            if (!Chat.nearbyOn) { Ui.say("Nearby chat is off. Turn it on in Settings › General.", true); return }
            Ui.ask("Message everyone nearby", "You're " + Chat.username, "", "Send", t => Chat.sendNearby(t), false, true)
        } else {
            const k = openKey, n = openName
            Ui.ask("Message " + n, "Private", "", "Send", t => Chat.sendPrivate(k, n, t), false, true)
        }
    }
    function choose() {
        const e = entries[row]
        if (!e) return
        if (e.kind === "nearby") { openKey = ""; openName = ""; return }
        if (e.kind === "request") {
            Ui.sure(e.name + " wants to chat privately", "Code: " + e.code + "\n\nAccept only if their screen shows the same code. That's how you know nobody is in between.",
                    "Codes match", () => { Chat.acceptPair(e.key); openKey = e.key; openName = e.name }, false, false, () => Chat.declinePair(e.key))
            return
        }
        if (Chat.isTrusted(e.key)) { openKey = e.key; openName = e.name; return }
        Ui.sure("Check this code with " + e.name, "Code: " + Chat.codeWith(e.key) + "\n\nTheir screen shows the same code when your first message arrives. Only continue if it matches.",
                "It matches", () => { openKey = e.key; openName = e.name; zone = "messages"; write() }, false, false)
    }
    function muteSomeone() {
        const people = []
        const seen = {}
        Chat.nearby.forEach(m => { if (!m.mine && !seen[m.peer]) { seen[m.peer] = true; people.push({ name: m.name, detail: "Hide their messages", url: m.peer }) } })
        if (!people.length) { Ui.say("Nobody to mute yet."); return }
        Ui.pickFrom("Mute someone in Nearby", [{ header: "People here", items: people }], key => Chat.mute(key))
    }

    function handleButton(b) {
        switch (b) {
        case "up": if (zone === "list") row = Math.max(0, row - 1); else list.flick(0, Theme.px(900)); break
        case "down": if (zone === "list") row = Math.min(entries.length - 1, row + 1); else list.flick(0, -Theme.px(900)); break
        case "right": zone = "messages"; break
        case "left": zone = "list"; break
        case "a": if (zone === "list") { choose(); if (entries[row] && (entries[row].kind === "nearby" || Chat.isTrusted(entries[row].key))) zone = "messages" } else write(); break
        case "x": write(); break
        case "y": if (openKey === "") muteSomeone(); break
        case "b": if (zone === "messages") zone = "list"; else Ui.closePage(); break
        }
        return true
    }
}

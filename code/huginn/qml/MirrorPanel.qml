import QtQuick
import Huginn

// On a companion (linked devices, section 15): the phone's 1:1 chats, over the conversation. Up and
// Down pick a chat, A answers through the phone, X asks it for everything again, B closes. Photos,
// voice and files show as words; they stay on the phone.
Rectangle {
    id: panel
    visible: false
    color: Theme.ground
    property int row: 0
    readonly property var chats: visible ? Linked.mirror : []
    readonly property var current: chats[row] || null
    readonly property var msgs: current ? (Linked.mirror, Linked.messages(current.peer)) : []

    function open() { row = 0; visible = true; Linked.sync() }
    function close() { visible = false }
    function reply() {
        if (!current) return
        const c = current
        Ui.ask("Message " + c.name, "Sent by " + Linked.phone.name + " as its own message, while it's online", "", "Send", t => {
            if (!Linked.send(c.peer, t)) Ui.say("No phone is linked any more.", true)
        }, false, true)
    }
    function handleButton(b) {
        switch (b) {
        case "up": row = Math.max(0, row - 1); return true
        case "down": row = Math.min(chats.length - 1, row + 1); return true
        case "a": reply(); return true
        case "x": Linked.sync(); Ui.say("Asked " + Linked.phone.name + " for its chats."); return true
        case "b": close(); return true
        }
        return true
    }

    PageHeader {
        id: head
        width: parent.width
        title: (Linked.phone.name || "Your phone") + "'s chats"
        HButton { anchors.verticalCenter: parent.verticalCenter; height: Theme.px(34); fontSize: 14; text: "Close"; onClicked: panel.close() }
    }
    ListView {
        id: side
        anchors.top: head.bottom; anchors.bottom: parent.bottom; anchors.left: parent.left
        width: Math.min(Theme.px(260), parent.width * 0.4)
        clip: true
        model: panel.chats
        delegate: Rectangle {
            width: side.width; height: Theme.px(56)
            color: index === panel.row ? Theme.tile : "transparent"
            Column {
                anchors.fill: parent; anchors.margins: Theme.px(10)
                Text { width: parent.width; elide: Text.ElideRight; text: modelData.name; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(16); font.weight: Font.DemiBold }
                Text { width: parent.width; elide: Text.ElideRight; text: modelData.last; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
            }
            TapHandler { onTapped: panel.row = index }
        }
        Text {
            visible: panel.chats.length === 0
            anchors.centerIn: parent; width: parent.width - Theme.px(20); wrapMode: Text.WordWrap
            text: "Nothing yet. The phone's chats show here while it's on and online."
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(14)
        }
    }
    ListView {
        id: talk
        anchors.top: head.bottom; anchors.bottom: send.top; anchors.left: side.right; anchors.right: parent.right
        anchors.margins: Theme.px(14)
        clip: true
        spacing: Theme.px(6)
        model: panel.msgs
        onCountChanged: positionViewAtEnd()
        delegate: Column {
            width: talk.width
            Text {
                text: (modelData.mine ? "You" : panel.current ? panel.current.name : "") + " · " + Qt.formatTime(new Date(modelData.time), "h:mm AP")
                color: modelData.mine ? Theme.accent : Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(13); font.weight: Font.DemiBold
            }
            Text {
                width: parent.width; wrapMode: Text.Wrap; textFormat: Text.PlainText
                text: modelData.text; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(16)
            }
        }
    }
    HButton {
        id: send
        anchors.right: parent.right; anchors.bottom: parent.bottom; anchors.margins: Theme.px(14)
        visible: !!panel.current
        kind: "accent"
        text: panel.current ? "Message " + panel.current.name : ""
        onClicked: panel.reply()
    }
}

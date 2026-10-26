import QtQuick
import Huginn

// Calls: a place held for voice calls, which arrive in 0.6. For now it only says so.
Rectangle {
    id: page
    color: Theme.ground
    function setArgs(a) {}

    readonly property var prompts: [{ key: "b", label: "Back" }]
    readonly property string status: ""

    Rectangle {
        id: middle
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        width: Theme.px(270)
        color: Theme.column
        PageHeader { id: head; width: parent.width; title: "Calls" }
        Text {
            anchors.top: head.bottom
            anchors.topMargin: Theme.px(16)
            x: Theme.px(20)
            width: parent.width - Theme.px(40)
            wrapMode: Text.WordWrap
            text: "No calls yet."
            color: Theme.textMuted
            font.family: Theme.body
            font.pixelSize: Theme.fs(15)
        }
    }

    Item {
        anchors.left: middle.right
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        Column {
            anchors.centerIn: parent
            width: Math.min(parent.width - Theme.px(60), Theme.px(520))
            spacing: Theme.px(14)
            Rectangle {
                anchors.horizontalCenter: parent.horizontalCenter
                width: Theme.px(84); height: width; radius: width / 2
                color: Theme.raised
                Icon { anchors.centerIn: parent; name: "call"; size: Theme.px(38); color: Theme.accent }
            }
            Text {
                width: parent.width
                horizontalAlignment: Text.AlignHCenter
                text: "Calls arrive in 0.6"
                color: Theme.text
                font.family: Theme.display
                font.pixelSize: Theme.fs(22)
                font.weight: Font.DemiBold
                font.letterSpacing: Theme.px(1)
            }
            Text {
                width: parent.width
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.WordWrap
                text: "Encrypted voice calls with the people you chat with, from Huginn or Muninn. Until then, hold Y in a conversation to send a voice message."
                color: Theme.textMuted
                font.family: Theme.body
                font.pixelSize: Theme.fs(16)
            }
        }
    }

    function handleButton(b) {
        // Nothing to move between yet: Left and B go back to the rail.
        if (b === "b" || b === "left") return false
        return true
    }
}

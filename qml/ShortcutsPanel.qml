import QtQuick
import Huginn

// Every keyboard shortcut in one place (Ctrl+/). B, Esc or a click outside closes it; the left stick
// or Up and Down scroll it.
Item {
    id: panel
    visible: false
    anchors.fill: parent
    function open() { visible = true; view.contentY = 0 }
    function close() { visible = false }
    function handleButton(b) {
        if (b === "b" || b === "a") close()
        else if (b === "up") view.contentY = Math.max(0, view.contentY - Theme.px(60))
        else if (b === "down") view.contentY = Math.min(Math.max(0, view.contentHeight - view.height), view.contentY + Theme.px(60))
        return true
    }

    readonly property var groups: [
        { title: "Moving around", keys: [
            ["Ctrl+K", "Jump to any chat or channel"],
            ["Ctrl+/", "This list"],
            ["Tab / Shift+Tab", "Next or previous section"],
            ["Arrow keys", "Move the highlight"],
            ["Enter", "Open, choose, or write a message"],
            ["Esc / Backspace", "Back"],
            ["Space", "Options"],
            ["Page Up / Page Down", "Scroll a screenful"],
            ["F1", "Settings"],
            ["Ctrl+,", "Settings › Appearance"] ] },
        { title: "Writing", keys: [
            ["Enter", "Send"],
            ["Shift+Enter", "A new line"],
            ["Up (empty box)", "Edit your last message"],
            ["Esc", "Stop replying or editing"],
            ["Ctrl+V", "Paste a picture to send it"],
            ["@name", "Mention someone in a group"] ] },
        { title: "Voice rooms", keys: [
            ["Ctrl+Shift+M", "Mute or unmute"],
            ["Ctrl+Shift+D", "Deafen or undeafen"],
            ["Ctrl+Shift+H", "Leave the voice room"],
            [TalkKey.keyName + " (hold)", Settings.pushToTalk ? "Push to talk" : "Push to talk (turn it on in Settings › Calls)"] ] },
        { title: "Panels and text", keys: [
            ["Ctrl+Shift+L", "Show or hide the list beside the rail"],
            ["Ctrl+Shift+U", "Show or hide a group's member list"],
            ["Ctrl+= / Ctrl+-", "Bigger or smaller message text"],
            ["Ctrl+0", "Message text back to normal"],
            ["Drag a panel's edge", "Resize it (double-click puts it back)"] ] }
    ]

    Rectangle { anchors.fill: parent; color: Qt.rgba(0.03, 0.035, 0.04, 0.85) }
    MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons; onClicked: panel.close() }

    Rectangle {
        anchors.centerIn: parent
        width: Math.min(parent.width - Theme.px(80), Theme.px(900))
        height: Math.min(parent.height - Theme.px(100), view.contentHeight + head.height + Theme.px(30))
        radius: Theme.radius + 1
        color: Theme.panel
        border.width: 1
        border.color: Theme.line
        Accessible.role: Accessible.Dialog
        Accessible.name: "Keyboard shortcuts"
        MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons }

        PageHeader {
            id: head
            width: parent.width
            title: "Keyboard shortcuts"
            subtitle: "A controller or TV remote works too"
            HButton { anchors.verticalCenter: parent.verticalCenter; text: "Close"; fontSize: 14; implicitHeight: Theme.px(36); onClicked: panel.close() }
        }
        Flickable {
            id: view
            anchors.top: head.bottom; anchors.topMargin: Theme.px(14)
            anchors.left: parent.left; anchors.leftMargin: Theme.px(24)
            anchors.right: parent.right; anchors.rightMargin: Theme.px(24)
            anchors.bottom: parent.bottom; anchors.bottomMargin: Theme.px(14)
            contentWidth: width
            contentHeight: grid.implicitHeight
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            Grid {
                id: grid
                width: view.width
                columns: view.width > Theme.px(640) ? 2 : 1
                columnSpacing: Theme.px(28)
                rowSpacing: Theme.px(18)
                Repeater {
                    model: panel.groups
                    Column {
                        width: (grid.width - grid.columnSpacing * (grid.columns - 1)) / grid.columns
                        spacing: Theme.px(6)
                        Text {
                            text: modelData.title.toUpperCase()
                            color: Theme.accent
                            font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold; font.letterSpacing: Theme.px(1.5)
                        }
                        Repeater {
                            model: modelData.keys
                            Item {
                                width: parent.width
                                height: Math.max(keyCap.height, what.implicitHeight) + Theme.px(2)
                                Rectangle {
                                    id: keyCap
                                    width: Math.min(parent.width * 0.48, keyText.implicitWidth + Theme.px(16))
                                    height: Theme.px(26)
                                    radius: Theme.px(5)
                                    color: Theme.raised
                                    border.width: 1; border.color: Theme.lineMuted
                                    Text {
                                        id: keyText
                                        anchors.centerIn: parent
                                        width: Math.min(implicitWidth, parent.width - Theme.px(12))
                                        elide: Text.ElideRight
                                        text: modelData[0]
                                        color: Theme.text
                                        font.family: Theme.body; font.pixelSize: Theme.fs(13); font.weight: Font.DemiBold
                                    }
                                }
                                Text {
                                    id: what
                                    anchors.left: parent.left; anchors.leftMargin: parent.width * 0.5
                                    anchors.right: parent.right
                                    anchors.verticalCenter: keyCap.verticalCenter
                                    wrapMode: Text.WordWrap
                                    text: modelData[1]
                                    color: Theme.textSoft
                                    font.family: Theme.body; font.pixelSize: Theme.fs(14)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

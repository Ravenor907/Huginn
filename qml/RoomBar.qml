import QtQuick
import Huginn

// In a voice room or a group call: along the bottom of every page while it lasts. Who's in it (a gold
// ring while someone talks, dimmed while they're muted), how long, and Mute, Output, Open and Leave.
// On a controller the same things are in the voice room's options on its group page (A on the room).
Rectangle {
    id: bar
    visible: Rooms.inRoom
    height: visible ? Theme.px(Theme.desktop ? 64 : 76) : 0
    color: Theme.column
    border.width: 1
    border.color: Theme.line

    function initial(name) { const n = String(name || "").trim(); return n.length ? n.charAt(0).toUpperCase() : "?" }

    Row {
        id: who
        anchors.left: parent.left; anchors.leftMargin: Theme.px(16)
        anchors.verticalCenter: parent.verticalCenter
        spacing: Theme.px(12)
        Icon { anchors.verticalCenter: parent.verticalCenter; name: "speaker"; size: Theme.px(22); color: Theme.accent }
        Column {
            anchors.verticalCenter: parent.verticalCenter
            width: Math.min(Theme.px(260), bar.width * 0.25)
            Text {
                width: parent.width
                elide: Text.ElideRight
                text: Rooms.title + (Rooms.groupName ? " · " + Rooms.groupName : "")
                color: Theme.text
                font.family: Theme.body; font.pixelSize: Theme.fs(16); font.weight: Font.DemiBold
            }
            Text {
                width: parent.width
                elide: Text.ElideRight
                text: Rooms.note !== "" ? Rooms.note : (Rooms.statusText + (Rooms.muted ? " · you're muted" : ""))
                color: Rooms.note !== "" ? Theme.danger : Theme.textMuted
                font.family: Theme.body; font.pixelSize: Theme.fs(13)
            }
        }
    }

    // Everyone in the room, you first.
    Row {
        anchors.left: who.right; anchors.leftMargin: Theme.px(16)
        anchors.right: buttons.left; anchors.rightMargin: Theme.px(12)
        anchors.verticalCenter: parent.verticalCenter
        spacing: Theme.px(8)
        clip: true
        Repeater {
            model: Rooms.people
            Item {
                width: Theme.px(40); height: width
                Accessible.role: Accessible.StaticText
                Accessible.name: modelData.name + (modelData.speaking ? ", speaking" : "") + (modelData.muted ? ", muted" : "")
                    + (modelData.state === "connecting" ? ", connecting" : modelData.state === "reconnecting" ? ", reconnecting" : "")
                Rectangle {
                    anchors.fill: parent
                    radius: width / 2
                    color: "transparent"
                    border.width: Theme.px(3)
                    border.color: Theme.accent
                    opacity: modelData.speaking ? 1 : 0
                    Behavior on opacity { NumberAnimation { duration: Theme.motion ? 120 : 0 } }
                }
                Rectangle {
                    anchors.centerIn: parent
                    width: Theme.px(32); height: width; radius: width / 2
                    color: Theme.avatar(modelData.name, modelData.me).bg
                    opacity: modelData.state === "live" ? (modelData.muted ? 0.55 : 1) : 0.4
                    Text {
                        anchors.centerIn: parent
                        text: bar.initial(modelData.name)
                        color: Theme.avatar(modelData.name, modelData.me).fg
                        font.family: Theme.body; font.pixelSize: Theme.fs(13); font.weight: Font.DemiBold
                    }
                }
                Rectangle {
                    visible: modelData.muted
                    anchors.right: parent.right; anchors.bottom: parent.bottom
                    width: Theme.px(16); height: width; radius: width / 2
                    color: Theme.dangerFill
                    border.width: 1; border.color: Theme.dangerLine
                    Icon { anchors.centerIn: parent; name: "mic"; size: Theme.px(11); color: Theme.text }
                }
            }
        }
    }

    Row {
        id: buttons
        anchors.right: parent.right; anchors.rightMargin: Theme.px(12)
        anchors.verticalCenter: parent.verticalCenter
        spacing: Theme.px(8)
        HButton { text: Rooms.muted ? "Unmute" : "Mute"; kind: Rooms.muted ? "accent" : "normal"; implicitHeight: Theme.px(44); onClicked: Rooms.toggleMute() }
        HButton { visible: bar.width > Theme.px(900); text: "Output: " + Rooms.outputName; implicitHeight: Theme.px(44); onClicked: Rooms.nextOutput() }
        HButton { text: "Open"; implicitHeight: Theme.px(44); onClicked: Ui.openPage("g:" + Rooms.group, { voice: true }) }
        HButton { text: "Leave"; kind: "danger"; implicitHeight: Theme.px(44); onClicked: Rooms.leave() }
    }
}

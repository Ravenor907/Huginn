import QtQuick
import Huginn

// Files going straight to or from one person over the internet (cap f1): what each is doing, with Stop
// while it runs and a close button once it's over. Empty (and no height) when there are none.
Column {
    id: strip
    property string peer: ""
    readonly property var items: peer === "" ? [] : FileLink.transfers.filter(t => t.peer === peer)
    visible: items.length > 0
    spacing: Theme.px(6)

    Repeater {
        model: strip.items
        delegate: Rectangle {
            readonly property bool running: modelData.state !== "done" && modelData.state !== "failed"
            width: strip.width
            height: Theme.px(52)
            radius: Theme.radius
            color: Theme.panel
            border.color: modelData.state === "failed" ? Theme.dangerLine : Theme.line
            border.width: 1
            Accessible.role: Accessible.StaticText
            Accessible.name: modelData.name + ", " + PeopleActions.fileText(modelData)
            // How far it got, as a bar along the bottom edge.
            Rectangle {
                anchors.left: parent.left; anchors.bottom: parent.bottom
                height: Theme.px(3); radius: height / 2
                width: modelData.size > 0 ? parent.width * Math.min(1, modelData.done / modelData.size) : 0
                color: Theme.accent
                visible: parent.running
            }
            Icon {
                id: fileIcon
                anchors.left: parent.left; anchors.leftMargin: Theme.px(12)
                anchors.verticalCenter: parent.verticalCenter
                name: "file"; size: Theme.px(18)
                color: modelData.state === "failed" ? Theme.danger : Theme.accent
            }
            Column {
                anchors.left: fileIcon.right; anchors.leftMargin: Theme.px(10)
                anchors.right: stop.left; anchors.rightMargin: Theme.px(10)
                anchors.verticalCenter: parent.verticalCenter
                Text {
                    width: parent.width; elide: Text.ElideMiddle
                    text: (modelData.mine ? "To them: " : "From them: ") + modelData.name
                    color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.DemiBold
                }
                Text {
                    width: parent.width; elide: Text.ElideRight
                    text: PeopleActions.fileText(modelData)
                    color: modelData.state === "failed" ? Theme.danger : Theme.textMuted
                    font.family: Theme.body; font.pixelSize: Theme.fs(13)
                }
            }
            HButton {
                id: stop
                anchors.right: parent.right; anchors.rightMargin: Theme.px(8)
                anchors.verticalCenter: parent.verticalCenter
                height: Theme.px(36); fontSize: 14
                text: parent.running ? "Stop" : "Close"
                kind: parent.running ? "danger" : "normal"
                onClicked: parent.running ? FileLink.cancel(modelData.id) : FileLink.dismiss(modelData.id)
            }
        }
    }
}

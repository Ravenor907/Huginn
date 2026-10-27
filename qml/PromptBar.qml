import QtQuick
import Huginn

// The bottom bar: status on the left, controller prompts on the right.
// Shows sending and receiving progress (LocalSend) wherever you are.
Rectangle {
    id: bar
    property string status
    property color statusColor: Theme.textMuted
    property var prompts: []          // [{ key, label }]
    signal prompt(string key)

    implicitHeight: Theme.px(50)
    color: Theme.ground
    Rectangle { anchors.top: parent.top; width: parent.width; height: 1; color: Theme.seam }

    Text {
        anchors.left: parent.left
        anchors.leftMargin: Theme.px(24)
        anchors.verticalCenter: parent.verticalCenter
        anchors.right: keys.left
        anchors.rightMargin: Theme.px(20)
        elide: Text.ElideRight
        text: LocalSend.busy ? LocalSend.status + (LocalSend.progress > 0 ? "  " + Math.round(LocalSend.progress * 100) + "%" : "")
            : bar.status
        color: LocalSend.busy ? Theme.accent : bar.statusColor
        font.family: Theme.body
        font.pixelSize: Theme.fs(15)
        font.weight: Font.DemiBold
    }

    // Progress for sending and receiving: a full-width bar along the top of the prompt bar.
    Rectangle {
        visible: LocalSend.busy
        anchors.top: parent.top
        width: parent.width
        height: Theme.px(5)
        color: Theme.line
        Rectangle {
            width: parent.width * LocalSend.progress
            height: parent.height
            color: Theme.accent
        }
    }

    Row {
        id: keys
        anchors.right: parent.right
        anchors.rightMargin: Theme.px(24)
        anchors.verticalCenter: parent.verticalCenter
        spacing: Theme.px(22)
        Repeater {
            model: bar.prompts
            PromptKey {
                key: modelData.key
                label: modelData.label
                onActivated: bar.prompt(modelData.key)
            }
        }
    }
}

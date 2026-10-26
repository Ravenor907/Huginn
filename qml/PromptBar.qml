import QtQuick
import Huginn

// The bottom bar: status on the left, controller prompts on the right.
Rectangle {
    id: bar
    property string status
    property color statusColor: Theme.textMuted
    property var prompts: []          // [{ key, label }]
    signal prompt(string key)

    implicitHeight: Theme.px(60)
    color: Theme.groundDeep
    Rectangle { anchors.top: parent.top; width: parent.width; height: 1; color: Theme.line }

    Text {
        anchors.left: parent.left
        anchors.leftMargin: Theme.px(28)
        anchors.verticalCenter: parent.verticalCenter
        anchors.right: keys.left
        anchors.rightMargin: Theme.px(20)
        elide: Text.ElideRight
        text: FileOps.busy ? FileOps.status + (FileOps.progress > 0 ? "  " + Math.round(FileOps.progress * 100) + "%" : "")
            : LocalSend.busy ? LocalSend.status + (LocalSend.progress > 0 ? "  " + Math.round(LocalSend.progress * 100) + "%" : "")
            : bar.status
        color: FileOps.busy || LocalSend.busy ? Theme.accent : bar.statusColor
        font.family: Theme.body
        font.pixelSize: Theme.fs(16)
        font.weight: Font.DemiBold
    }

    // Progress for copies and moves: a full-width bar along the top of the prompt bar.
    Rectangle {
        visible: FileOps.busy || LocalSend.busy
        anchors.top: parent.top
        width: parent.width
        height: Theme.px(6)
        color: Theme.line
        Rectangle {
            width: parent.width * (FileOps.busy ? FileOps.progress : LocalSend.progress)
            height: parent.height
            color: Theme.accent
        }
    }

    Row {
        id: keys
        anchors.right: parent.right
        anchors.rightMargin: Theme.px(28)
        anchors.verticalCenter: parent.verticalCenter
        spacing: Theme.px(24)
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

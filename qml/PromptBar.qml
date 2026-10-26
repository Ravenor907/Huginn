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
        text: FileOps.busy ? FileOps.status : bar.status
        color: FileOps.busy ? Theme.accent : bar.statusColor
        font.family: Theme.body
        font.pixelSize: Theme.px(16)
        font.weight: Font.DemiBold
    }

    Rectangle {
        visible: FileOps.busy
        anchors.bottom: parent.bottom
        height: 2
        width: parent.width * FileOps.progress
        color: Theme.accent
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

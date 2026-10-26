import QtQuick
import Huginn

// One file or folder in a pane. Uses the FolderModel roles: name, meta, kind, isDir, selected.
Rectangle {
    id: row
    property bool focused: false
    signal tapped()

    height: Theme.rowHeight
    radius: Theme.radius
    color: focused ? Theme.focusFill : model.selected ? Theme.raised : "transparent"
    border.width: focused ? Theme.focusWidth : 1
    border.color: focused ? Theme.accent : "transparent"
    Accessible.role: Accessible.ListItem
    Accessible.name: model.name + (model.isDir ? ", folder" : "") + (model.selected ? ", selected" : "")

    Row {
        anchors.left: parent.left
        anchors.leftMargin: Theme.px(14)
        anchors.verticalCenter: parent.verticalCenter
        spacing: Theme.px(14)

        Rectangle {
            anchors.verticalCenter: parent.verticalCenter
            width: Theme.px(12); height: width
            rotation: 45
            color: model.selected ? Theme.accent : "transparent"
            border.width: model.selected ? 0 : 1
            border.color: Theme.lineMuted
        }
        Icon {
            anchors.verticalCenter: parent.verticalCenter
            name: model.kind
            color: model.isDir ? Theme.textMuted : Theme.textFaint
        }
    }
    Text {
        anchors.left: parent.left
        anchors.leftMargin: Theme.px(14 + 12 + 14 + 24 + 14)
        anchors.right: meta.left
        anchors.rightMargin: Theme.px(12)
        anchors.verticalCenter: parent.verticalCenter
        elide: Text.ElideMiddle
        text: model.name
        color: model.hidden ? Theme.textMuted : Theme.text
        font.family: Theme.body
        font.pixelSize: Theme.px(19)
        font.weight: Font.Medium
    }
    Text {
        id: meta
        anchors.right: parent.right
        anchors.rightMargin: Theme.px(14)
        anchors.verticalCenter: parent.verticalCenter
        text: model.meta
        color: Theme.textMuted
        font.family: Theme.body
        font.pixelSize: Theme.px(15)
    }
    TapHandler { onTapped: row.tapped() }
}

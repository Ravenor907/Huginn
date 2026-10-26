import QtQuick
import Huginn

// A carved line with a diamond in the middle.
Item {
    implicitHeight: Theme.px(12)
    Rectangle {
        anchors.verticalCenter: parent.verticalCenter
        anchors.left: parent.left
        anchors.right: gem.left
        anchors.rightMargin: Theme.px(10)
        height: 1
        color: Theme.line
    }
    Rectangle {
        id: gem
        anchors.centerIn: parent
        width: Theme.px(8); height: width
        rotation: 45
        color: "transparent"
        border.color: Theme.accent
        border.width: 1
    }
    Rectangle {
        anchors.verticalCenter: parent.verticalCenter
        anchors.left: gem.right
        anchors.leftMargin: Theme.px(10)
        anchors.right: parent.right
        height: 1
        color: Theme.line
    }
}

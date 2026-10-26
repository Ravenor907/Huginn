import QtQuick
import Huginn

// Page title in carved capitals, a plain-language subtitle, and room for things on the right.
Item {
    id: header
    property string title
    property string subtitle
    default property alias trailing: right.data

    implicitHeight: Theme.px(72)

    Row {
        anchors.left: parent.left
        anchors.leftMargin: Theme.px(28)
        anchors.verticalCenter: parent.verticalCenter
        spacing: Theme.px(20)
        Text {
            anchors.verticalCenter: parent.verticalCenter
            text: header.title.toUpperCase()
            color: Theme.text
            font.family: Theme.display
            font.pixelSize: Theme.fs(24)
            font.weight: Font.Bold
            font.letterSpacing: Theme.px(4)
        }
        Text {
            anchors.verticalCenter: parent.verticalCenter
            text: header.subtitle
            color: Theme.textMuted
            font.family: Theme.body
            font.pixelSize: Theme.fs(17)
        }
    }
    Row {
        id: right
        anchors.right: parent.right
        anchors.rightMargin: Theme.px(28)
        anchors.verticalCenter: parent.verticalCenter
        spacing: Theme.px(12)
    }
}

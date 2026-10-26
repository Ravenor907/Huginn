import QtQuick
import Huginn

// A column header: the section or conversation name in carved capitals, an optional plain-language
// subtitle, room for things on the right, and a thin seam underneath.
Item {
    id: header
    property string title
    property string subtitle
    property bool carved: true        // Cinzel for section names; conversations use the body font
    default property alias trailing: right.data

    implicitHeight: Theme.px(60)

    Row {
        anchors.left: parent.left
        anchors.leftMargin: Theme.px(20)
        anchors.right: right.left
        anchors.rightMargin: Theme.px(12)
        anchors.verticalCenter: parent.verticalCenter
        spacing: Theme.px(12)
        clip: true
        Text {
            id: titleText
            anchors.verticalCenter: parent.verticalCenter
            text: header.title
            color: Theme.text
            font.family: header.carved ? Theme.display : Theme.body
            font.pixelSize: Theme.fs(19)
            font.weight: Font.DemiBold
            font.letterSpacing: header.carved ? Theme.px(1) : 0
        }
        Text {
            anchors.verticalCenter: parent.verticalCenter
            width: Math.max(0, parent.width - titleText.implicitWidth - parent.spacing)
            elide: Text.ElideRight
            visible: header.subtitle.length > 0
            text: header.subtitle
            color: Theme.textMuted
            font.family: Theme.body
            font.pixelSize: Theme.fs(14)
        }
    }
    Row {
        id: right
        anchors.right: parent.right
        anchors.rightMargin: Theme.px(20)
        anchors.verticalCenter: parent.verticalCenter
        spacing: Theme.px(12)
    }
    Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.seam }
}

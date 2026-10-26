import QtQuick
import Huginn

// One controller prompt: an outlined button glyph and what it does. Tapping it does the same.
Item {
    id: root
    property string key
    property string label
    signal activated()

    implicitWidth: row.implicitWidth
    implicitHeight: Theme.px(44)

    Row {
        id: row
        anchors.verticalCenter: parent.verticalCenter
        spacing: Theme.px(8)
        Rectangle {
            anchors.verticalCenter: parent.verticalCenter
            height: Theme.px(28)
            width: Math.max(height, glyph.implicitWidth + Theme.px(12))
            radius: height / 2
            color: "transparent"
            border.color: Theme.textFaint
            border.width: 1
            Text {
                id: glyph
                anchors.centerIn: parent
                text: Gamepad.glyph(root.key)
                color: Theme.text
                font.family: Theme.body
                font.pixelSize: Theme.px(13)
                font.weight: Font.DemiBold
            }
        }
        Text {
            anchors.verticalCenter: parent.verticalCenter
            text: root.label
            color: Theme.text
            font.family: Theme.body
            font.pixelSize: Theme.px(17)
        }
    }
    TapHandler { onTapped: root.activated() }
}

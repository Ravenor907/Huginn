import QtQuick
import Huginn

// A panel of emoji to put in a message or react with. [quick]: the five quick reactions on top (for
// reacting); tap one to pick it. Closes on Esc or a tap outside it (the owner decides: picked hides it).
Rectangle {
    id: picker
    property bool quick: false
    property string current: ""          // my reaction now, outlined (tap it again to take it back)
    signal picked(string e)

    visible: false
    width: Theme.px(360)
    height: Math.min(Theme.px(320), grid.contentHeight + quickRow.height + Theme.px(24))
    radius: Theme.px(10)
    color: Theme.panel
    border.color: Theme.lineStrong
    z: 20
    Accessible.role: Accessible.Pane
    Accessible.name: "Emoji"

    readonly property var all: Chat.emojiList()
    readonly property var quickOnes: Chat.quickReactions()

    Row {
        id: quickRow
        visible: picker.quick
        height: visible ? Theme.px(46) : 0
        x: Theme.px(10); y: Theme.px(8)
        spacing: Theme.px(6)
        Repeater {
            model: picker.quick ? picker.quickOnes : []
            Rectangle {
                width: Theme.px(40); height: width; radius: width / 2
                color: modelData === picker.current ? Theme.accentFill : qHover.hovered ? Theme.raised : Theme.tile
                border.width: modelData === picker.current ? 2 : 0
                border.color: Theme.accent
                Accessible.role: Accessible.Button
                Accessible.name: modelData === picker.current ? "Take back " + modelData : "React " + modelData
                Text { anchors.centerIn: parent; text: modelData; font.pixelSize: Theme.fs(20) }
                HoverHandler { id: qHover; cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: picker.picked(modelData) }
            }
        }
    }

    GridView {
        id: grid
        anchors.top: quickRow.bottom
        anchors.topMargin: Theme.px(8)
        anchors.bottom: parent.bottom
        anchors.bottomMargin: Theme.px(8)
        x: Theme.px(8)
        width: parent.width - Theme.px(16)
        cellWidth: Theme.px(38)
        cellHeight: Theme.px(38)
        clip: true
        model: picker.all
        boundsBehavior: Flickable.StopAtBounds
        delegate: Rectangle {
            width: grid.cellWidth - Theme.px(2); height: width; radius: Theme.px(6)
            color: eHover.hovered ? Theme.raised : "transparent"
            Accessible.role: Accessible.Button
            Accessible.name: modelData
            Text { anchors.centerIn: parent; text: modelData; font.pixelSize: Theme.fs(20) }
            HoverHandler { id: eHover; cursorShape: Qt.PointingHandCursor }
            TapHandler { onTapped: picker.picked(modelData) }
        }
    }
    Keys.onEscapePressed: picker.visible = false
}

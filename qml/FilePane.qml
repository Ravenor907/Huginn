import QtQuick
import Huginn

// One browser pane: a folder listing with a path header. Rows are big enough for touch and a TV.
Rectangle {
    id: pane
    property alias model: list.model
    property bool active: false
    property alias currentIndex: list.currentIndex
    property bool grid: false
    signal activated(int index)
    signal tapped(int index)

    radius: Theme.radius + 1
    color: active ? Theme.panel : Theme.panelLow
    border.width: 1
    border.color: active ? Theme.accent : Theme.line
    clip: true

    Rectangle {
        id: head
        width: parent.width
        height: Theme.px(46)
        color: pane.active ? Theme.focusFill : "transparent"
        Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.line }
        Text {
            anchors.left: parent.left
            anchors.leftMargin: Theme.px(16)
            anchors.right: count.left
            anchors.rightMargin: Theme.px(12)
            anchors.verticalCenter: parent.verticalCenter
            elide: Text.ElideMiddle
            text: pane.model ? pane.model.title : ""
            color: pane.active ? Theme.accent : Theme.textSoft
            font.family: Theme.body
            font.pixelSize: Theme.fs(17)
            font.weight: Font.DemiBold
        }
        Text {
            id: count
            anchors.right: parent.right
            anchors.rightMargin: Theme.px(16)
            anchors.verticalCenter: parent.verticalCenter
            text: !pane.model ? "" : pane.model.loading ? "Loading…" : pane.model.summary
            color: Theme.textMuted
            font.family: Theme.body
            font.pixelSize: Theme.fs(14)
        }
    }

    ListView {
        id: list
        anchors.top: head.bottom
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.margins: Theme.px(8)
        spacing: Theme.px(4)
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        highlightMoveDuration: 80
        keyNavigationEnabled: false
        currentIndex: 0
        delegate: FileRow {
            width: list.width
            focused: pane.active && ListView.isCurrentItem
            // Touch: the first tap focuses a row, a tap on the focused row opens it.
            onTapped: {
                if (pane.active && list.currentIndex === index) pane.activated(index)
                else { list.currentIndex = index; pane.tapped(index) }
            }
        }
    }

    Text {
        anchors.centerIn: list
        visible: pane.model && !pane.model.loading && list.count === 0
        width: list.width - Theme.px(40)
        horizontalAlignment: Text.AlignHCenter
        wrapMode: Text.WordWrap
        text: pane.model && pane.model.error ? pane.model.error : "This folder is empty"
        color: Theme.textMuted
        font.family: Theme.body
        font.pixelSize: Theme.fs(17)
    }

    // After going up a folder, land on the folder we came out of.
    Connections {
        target: pane.model
        function onRestoreIndex(i) { list.currentIndex = i; list.positionViewAtIndex(i, ListView.Center) }
    }

    function move(delta) {
        if (list.count === 0) return
        list.currentIndex = Math.max(0, Math.min(list.count - 1, list.currentIndex + delta))
    }
    function page(dir) { move(dir * Math.max(1, Math.floor(list.height / Theme.rowHeight) - 1)) }
}

import QtQuick
import Huginn

// A handle on the edge between two panels (the desktop only). Drag it to make the panel wider or
// narrower; Huginn keeps the width once it's let go. A double click puts it back as it was at first.
Item {
    id: split
    property int value: 0          // the panel's width now, in Normal-size pixels
    property int minimum: 0
    property int maximum: 1000
    property int fallback: 0       // what a double click goes back to
    property int direction: 1      // 1: dragging right widens the panel; -1: dragging right narrows it
    property string label: "panel"
    signal moved(int v)            // while it's dragged
    signal done(int v)             // let go: keep it
    property int startValue: 0

    width: Theme.px(8)
    visible: Theme.desktop
    Accessible.role: Accessible.Separator
    Accessible.name: "Drag to resize the " + label

    Rectangle {
        anchors.horizontalCenter: parent.horizontalCenter
        width: drag.active || hover.hovered ? 2 : 0
        height: parent.height
        color: Theme.accent
        opacity: drag.active ? 0.9 : 0.5
    }
    HoverHandler { id: hover; cursorShape: Qt.SplitHCursor }
    DragHandler {
        id: drag
        target: null
        yAxis.enabled: false
        cursorShape: Qt.SplitHCursor
        onActiveChanged: {
            if (active) split.startValue = split.value
            else split.done(split.value)
        }
        onActiveTranslationChanged: {
            if (!active) return
            const v = Math.round(split.startValue + split.direction * activeTranslation.x / Theme.s)
            split.moved(Math.max(split.minimum, Math.min(split.maximum, v)))
        }
    }
    TapHandler { onDoubleTapped: { split.moved(split.fallback); split.done(split.fallback) } }
}

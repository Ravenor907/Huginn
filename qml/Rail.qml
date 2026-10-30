import QtQuick
import QtQuick.Shapes
import Huginn

// The rail down the left edge, built from a list:
// items = [{ id, kind: "raven" | "group" | "add" | "icon", label, icon, letter, badge, unread, bottom }].
// Direct messages (the raven) sits on top, then the groups (round initials; the one on screen is a
// gold rounded square with a gold pill beside it, one with something unread a short pill, and its
// @mentions of me a number) and + for a new group. Calls, Files, Settings and
// Exit sit at the foot. LB steps through them (Main.qml); the D-pad moves the rail focus.
Rectangle {
    id: rail
    property var items: []
    property string current: ""       // the section on screen
    property int focusIndex: 0
    property bool focused: false      // the controller focus is on the rail
    signal activated(int index)

    readonly property var withIndex: items.map((e, i) => Object.assign({ idx: i }, e))
    readonly property var ravenItems: withIndex.filter(e => !e.bottom && e.kind === "raven")
    readonly property var groupItems: withIndex.filter(e => !e.bottom && e.kind !== "raven")
    readonly property var bottomItems: withIndex.filter(e => !!e.bottom)

    implicitWidth: Theme.railWidth
    color: Theme.rail
    Accessible.role: Accessible.PageTabList
    Accessible.name: "Sections"

    // Keep the focused group on screen when there are more than fit.
    onFocusIndexChanged: Qt.callLater(showFocused)
    function showFocused() {
        const at = groupItems.findIndex(e => e.idx === focusIndex)
        if (at < 0) return
        const y = at * (Theme.px(54) + groupCol.spacing)
        if (y < groupView.contentY) groupView.contentY = y
        else if (y + Theme.px(54) > groupView.contentY + groupView.height)
            groupView.contentY = Math.min(Math.max(0, groupView.contentHeight - groupView.height), y + Theme.px(54) - groupView.height)
    }

    Column {
        id: top
        anchors.top: parent.top
        anchors.topMargin: Theme.px(14)
        width: parent.width
        spacing: Theme.px(12)

        Text {
            anchors.horizontalCenter: parent.horizontalCenter
            text: "HUGINN"
            color: Theme.accent
            font.family: Theme.display
            font.pixelSize: Theme.fs(11)
            font.weight: Font.Bold
            font.letterSpacing: Theme.px(1.5)
        }
        Chip { visible: !Theme.desktop; key: "lb" }
        Repeater {
            model: rail.ravenItems
            RailButton { entry: modelData }
        }
        Rectangle {
            visible: rail.groupItems.length > 0
            anchors.horizontalCenter: parent.horizontalCenter
            width: Theme.px(32); height: 2
            color: Theme.seam
        }
    }

    // Groups and + scroll when there are many.
    Flickable {
        id: groupView
        anchors.top: top.bottom
        anchors.topMargin: Theme.px(12)
        anchors.bottom: bottomCol.top
        anchors.bottomMargin: Theme.px(10)
        width: parent.width
        contentWidth: width
        contentHeight: groupCol.implicitHeight
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        Column {
            id: groupCol
            width: groupView.width
            spacing: Theme.px(12)
            Repeater {
                model: rail.groupItems
                RailButton { entry: modelData }
            }
        }
    }
    Column {
        id: bottomCol
        anchors.bottom: parent.bottom
        anchors.bottomMargin: Theme.px(14)
        width: parent.width
        spacing: Theme.px(10)
        Rectangle { anchors.horizontalCenter: parent.horizontalCenter; width: Theme.px(30); height: 2; color: Theme.seam }
        Repeater {
            model: rail.bottomItems
            RailButton { entry: modelData }
        }
    }

    // LB, so the shoulder button that moves along the rail is easy to find on a handheld.
    component Chip: Rectangle {
        property string key
        anchors.horizontalCenter: parent.horizontalCenter
        height: Theme.px(22)
        width: chipText.implicitWidth + Theme.px(14)
        radius: Theme.px(5)
        color: Theme.hc ? "transparent" : "#23272C"
        border.width: Theme.hc ? 1 : 0
        border.color: Theme.textMuted
        Text {
            id: chipText
            anchors.centerIn: parent
            text: Gamepad.glyph(parent.key)
            color: Theme.textMuted
            font.family: Theme.body
            font.pixelSize: Theme.fs(12)
            font.weight: Font.DemiBold
        }
    }

    component RailButton: Item {
        id: rb
        property var entry: ({})
        readonly property bool here: entry.id === rail.current
        readonly property bool on: rail.focused && rail.focusIndex === entry.idx
        readonly property bool big: entry.kind === "raven" || entry.kind === "group" || entry.kind === "add"
        readonly property var tone: Theme.avatar(entry.label || "")
        width: rail.width
        height: Theme.px(big ? 54 : 46)
        Accessible.role: Accessible.PageTab
        Accessible.name: (entry.label || "") + (entry.kind === "group" ? (entry.badge > 0 ? ", " + entry.badge + (entry.badge === 1 ? " mention" : " mentions") : "")
                                                                         + (unread ? ", " + entry.unread + " unread" : "")
                                                 : entry.badge > 0 ? ", " + entry.badge + " new" : "")
        Accessible.selected: here
        Accessible.onPressAction: rail.activated(entry.idx)

        // The section on screen: a gold pill on the left edge. A group with something unread gets a
        // short one, and pointing at a group a middling one.
        readonly property bool unread: (entry.unread || 0) > 0
        Rectangle {
            visible: rb.here || (rb.entry.kind === "group" && (rb.unread || hover.hovered))
            anchors.left: parent.left
            anchors.verticalCenter: parent.verticalCenter
            width: Theme.px(4)
            height: rb.here ? Theme.px(rb.big ? 32 : 24) : hover.hovered ? Theme.px(18) : Theme.px(8)
            radius: width
            color: rb.here || rb.unread ? Theme.accent : Theme.textMuted
            Behavior on height { enabled: Theme.motion; NumberAnimation { duration: 120 } }
        }
        Rectangle {
            id: face
            anchors.centerIn: parent
            width: Math.min(parent.height, rail.width - Theme.px(16))
            height: width
            // Groups are round; the one on screen becomes a rounded square, like the raven.
            radius: rb.entry.kind === "raven" || (rb.entry.kind === "group" && rb.here) ? Theme.px(16) : width / 2
            color: rb.entry.kind === "raven" ? Theme.raised
                 : rb.entry.kind === "group" ? (rb.here ? Theme.accent : rb.tone.bg)
                 : rb.entry.kind === "add" ? (rb.on || hover.hovered ? Theme.raised : "transparent")
                 : rb.on || rb.here || hover.hovered ? Theme.raised : "transparent"
            border.width: rb.on ? Theme.focusWidth : 0
            border.color: rb.entry.kind === "group" && rb.here ? Theme.text : Theme.accent
            Behavior on radius { enabled: Theme.motion; NumberAnimation { duration: 120 } }
            Image {
                visible: rb.entry.kind === "raven"
                anchors.centerIn: parent
                source: "assets/raven-gold.png"
                width: Theme.px(34); height: width
                sourceSize.width: 128; sourceSize.height: 128
                smooth: true
            }
            Text {
                visible: rb.entry.kind === "group"
                anchors.centerIn: parent
                text: rb.entry.letter || ""
                color: rb.here ? Theme.ground : rb.tone.fg
                font.family: Theme.body
                font.pixelSize: Theme.fs(20)
                font.weight: Font.DemiBold
            }
            // New group: a dashed ring with a plus.
            Shape {
                visible: rb.entry.kind === "add"
                anchors.fill: parent
                preferredRendererType: Shape.CurveRenderer
                ShapePath {
                    strokeColor: Theme.lineMuted
                    strokeWidth: 1
                    strokeStyle: ShapePath.DashLine
                    dashPattern: [3, 3]
                    fillColor: "transparent"
                    PathAngleArc {
                        centerX: face.width / 2; centerY: face.height / 2
                        radiusX: face.width / 2 - 1; radiusY: face.height / 2 - 1
                        startAngle: 0; sweepAngle: 360
                    }
                }
            }
            // Settings: the valknut, black and grey.
            Image {
                visible: rb.entry.id === "settings"
                anchors.centerIn: parent
                source: "assets/valknut.png"
                width: Theme.px(26); height: width
                sourceSize.width: 128; sourceSize.height: 128
                smooth: true; mipmap: true
                opacity: rb.here || rb.on ? 1 : 0.85
            }
            Icon {
                visible: (rb.entry.kind === "icon" && rb.entry.id !== "settings") || rb.entry.kind === "add"
                anchors.centerIn: parent
                name: rb.entry.kind === "add" ? "plus" : (rb.entry.icon || "file")
                size: Theme.px(rb.entry.kind === "add" ? 20 : 22)
                color: rb.here || rb.on ? Theme.accent : rb.entry.id === "exit" ? Theme.danger : Theme.textMuted
            }
            // Something new: unread messages or people asking to chat.
            Rectangle {
                visible: rb.entry.badge > 0
                anchors.right: parent.right
                anchors.top: parent.top
                anchors.margins: -Theme.px(3)
                height: Theme.px(18)
                width: Math.max(height, badgeText.implicitWidth + Theme.px(8))
                radius: height / 2
                // A group's number is @mentions of me: the warning color, like Discord's red.
                color: rb.entry.kind === "group" ? Theme.danger : Theme.accent
                border.width: 2
                border.color: Theme.rail
                Text {
                    id: badgeText
                    anchors.centerIn: parent
                    text: rb.entry.badge > 99 ? "99+" : String(rb.entry.badge || 0)
                    color: Theme.ground
                    font.family: Theme.body
                    font.pixelSize: Theme.fs(11)
                    font.weight: Font.Bold
                }
            }
            HoverHandler { id: hover }
            TapHandler { onTapped: rail.activated(rb.entry.idx) }
        }
    }
}

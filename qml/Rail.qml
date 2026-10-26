import QtQuick
import Huginn

// The rail down the left edge. Built from a list so groups can join it later:
// items = [{ id, kind: "raven" | "group" | "icon", label, icon, letter, badge, bottom }].
// Top items stack under the raven; bottom ones (Calls, Files, Settings, Exit) sit at the foot.
// LB and RB step through them (Main.qml); the D-pad moves the rail focus when the rail has it.
Rectangle {
    id: rail
    property var items: []
    property string current: ""       // the section on screen
    property int focusIndex: 0
    property bool focused: false      // the controller focus is on the rail
    signal activated(int index)

    readonly property var topItems: items.map((e, i) => Object.assign({ idx: i }, e)).filter(e => !e.bottom)
    readonly property var bottomItems: items.map((e, i) => Object.assign({ idx: i }, e)).filter(e => !!e.bottom)

    implicitWidth: Theme.px(83)
    color: Theme.rail
    Accessible.role: Accessible.PageTabList
    Accessible.name: "Sections"

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
            model: rail.topItems
            RailButton { entry: modelData }
        }
        Chip { visible: !Theme.desktop; key: "rb" }
    }

    Column {
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

    // LB / RB, so the shoulder buttons are easy to find on a handheld.
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
        readonly property bool big: entry.kind === "raven" || entry.kind === "group"
        width: rail.width
        height: Theme.px(big ? 54 : 46)
        Accessible.role: Accessible.PageTab
        Accessible.name: entry.label + (entry.badge > 0 ? ", " + entry.badge + " new" : "")
        Accessible.selected: here
        Accessible.onPressAction: rail.activated(entry.idx)

        // The section on screen: a gold pill on the left edge.
        Rectangle {
            visible: rb.here
            anchors.left: parent.left
            anchors.verticalCenter: parent.verticalCenter
            width: Theme.px(4)
            height: Theme.px(rb.big ? 32 : 24)
            radius: width
            color: Theme.accent
        }
        Rectangle {
            id: face
            anchors.centerIn: parent
            width: parent.height
            height: width
            radius: rb.entry.kind === "raven" ? Theme.px(16) : width / 2
            color: rb.entry.kind === "raven" ? Theme.raised
                 : rb.entry.kind === "group" ? (rb.here ? Theme.accent : Theme.avatar(rb.entry.label).bg)
                 : rb.on || rb.here || hover.hovered ? Theme.raised : "transparent"
            border.width: rb.on ? Theme.focusWidth : 0
            border.color: Theme.accent
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
                color: rb.here ? Theme.ground : Theme.avatar(rb.entry.label).fg
                font.family: Theme.body
                font.pixelSize: Theme.fs(20)
                font.weight: Font.DemiBold
            }
            Icon {
                visible: rb.entry.kind === "icon"
                anchors.centerIn: parent
                name: rb.entry.icon || "file"
                size: Theme.px(22)
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
                color: Theme.accent
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

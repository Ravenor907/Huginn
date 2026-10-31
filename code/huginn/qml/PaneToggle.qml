import QtQuick
import Huginn

// Shows or hides a side panel on the desktop: "list" (the conversations or channels beside the rail,
// Ctrl+Shift+L) or "members" (a group's member list, Ctrl+Shift+U). Lit while the panel shows.
Rectangle {
    id: toggle
    property string which: "list"
    readonly property bool on: which === "list" ? Settings.showList : Settings.showMembers
    visible: !Theme.portrait
    anchors.verticalCenter: parent ? parent.verticalCenter : undefined
    width: Theme.px(32); height: width
    radius: Theme.radius
    color: hover.hovered ? Theme.raised : "transparent"
    border.width: on ? 1 : 0
    border.color: Theme.line
    Accessible.role: Accessible.Button
    Accessible.name: (on ? "Hide " : "Show ") + (which === "list" ? "the list" : "the member list")
    Accessible.onPressAction: Ui.main.togglePane(which)
    Icon {
        anchors.centerIn: parent
        name: toggle.which === "list" ? "sidebar" : "people"
        size: Theme.px(20)
        color: toggle.on ? Theme.accent : Theme.textMuted
    }
    HoverHandler { id: hover; cursorShape: Qt.PointingHandCursor }
    TapHandler { onTapped: Ui.main.togglePane(toggle.which) }
}

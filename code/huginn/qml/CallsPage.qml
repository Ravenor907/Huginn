import QtQuick
import Huginn

// Calls (on the rail): everyone you called or who called you, newest first, missed ones marked,
// and Call back. A calls back; LT has Delete and Clear all. A call is started from a chat's Call
// button or a contact's menu. embedded: no header of its own (for a page that has one).
Rectangle {
    id: page
    color: Theme.ground

    property bool tour: false
    property bool embedded: false
    property int row: 0
    function setArgs(a) { tour = !!(a && a.tour) }

    readonly property bool active: !Ui.main || Ui.main.focusZone === "content"
    // The CI screenshot tour has no real calls: show a few made-up ones.
    readonly property var entries: tour ? [
        { id: "t1", key: "", name: "Raven 4821", out: false, result: "missed", started: Date.now() - 600000, seconds: 0, missed: true },
        { id: "t2", key: "", name: "Raven 4821", out: true, result: "answered", started: Date.now() - 3600000, seconds: 312, missed: false },
        { id: "t3", key: "", name: "Mom", out: false, result: "answered", started: Date.now() - 90000000, seconds: 1245, missed: false }
    ] : Calls.history
    readonly property var picked: entries.length > 0 ? entries[Math.min(row, entries.length - 1)] : null

    readonly property var prompts: entries.length === 0 ? [{ key: "b", label: "Back" }]
        : [{ key: "a", label: "Call back" }, { key: "lt", label: "Menu" }, { key: "b", label: "Back" }]
    readonly property string status: Calls.available ? "" : "Calls aren't available here"

    Component.onCompleted: Calls.markSeen()

    function initial(name) { const n = String(name || "").trim(); return n.length ? n.charAt(0).toUpperCase() : "?" }
    function when(ms) {
        const d = new Date(ms), now = new Date()
        const t = Qt.formatTime(d, "h:mm AP")
        if (d.toDateString() === now.toDateString()) return t
        const y = new Date(now.getTime() - 86400000)
        if (d.toDateString() === y.toDateString()) return "Yesterday " + t
        return Qt.formatDate(d, "MMM d") + " " + t
    }
    function kind(e) {
        if (e.missed) return "Missed call"
        if (e.result === "answered") return e.out ? "Outgoing call" : "Incoming call"
        if (e.out) return e.result === "Declined" ? "Declined" : e.result === "Busy" ? "They were busy" : "No answer"
        return e.result === "Declined" ? "You declined" : "Incoming call"
    }
    function detail(e) {
        return kind(e) + (e.result === "answered" && e.seconds > 0 ? " · " + Calls.duration(e.seconds) : "") + " · " + when(e.started)
    }
    function nameOf(e) { return e.key !== "" ? (Chat.chats, Chat.nameOf(e.key)) || e.name : e.name }
    function callBack() {
        const e = picked
        if (!e || e.key === "") return
        const why = Calls.call(e.key)
        if (why !== "") Ui.say(why, true)
    }
    function options() {
        const e = picked
        if (!e) return
        Ui.pickFrom(nameOf(e), [{ header: "", items: [
            { name: "Call back", icon: "call", url: "call" },
            { name: "Delete from the list", url: "delete", danger: true },
            { name: "Clear all calls", url: "clear", danger: true } ] }], a => {
            if (a === "call") callBack()
            else if (a === "delete") Calls.deleteEntry(e.id)
            else if (a === "clear") Ui.sure("Clear all calls?", "Can't be undone.", "Clear", () => Calls.clearHistory(), false, true)
        })
    }

    // ============================================================= the list
    Rectangle {
        id: middle
        // Portrait: the list on top, the rest under it.
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        anchors.bottomMargin: Theme.portrait ? parent.height * 0.45 : page.embedded ? 0 : Ui.dock
        width: Theme.portrait ? parent.width : Theme.listWidth
        color: Theme.column
        PageHeader { id: head; visible: !page.embedded; height: visible ? implicitHeight : 0; width: parent.width; title: "Calls" }
        Text {
            visible: page.entries.length === 0
            anchors.top: head.bottom
            anchors.topMargin: Theme.px(16)
            x: Theme.px(20)
            width: parent.width - Theme.px(40)
            wrapMode: Text.WordWrap
            text: "No calls yet."
            color: Theme.textMuted
            font.family: Theme.body
            font.pixelSize: Theme.fs(15)
        }
        ListView {
            id: list
            anchors.top: head.bottom
            anchors.topMargin: Theme.px(10)
            anchors.bottom: parent.bottom
            x: Theme.px(10)
            width: parent.width - Theme.px(20)
            spacing: Theme.px(3)
            clip: true
            model: page.entries
            currentIndex: page.row
            onCurrentIndexChanged: positionViewAtIndex(currentIndex, ListView.Contain)
            delegate: Rectangle {
                readonly property bool on: page.row === index
                width: list.width
                height: Theme.px(56)
                radius: Theme.radius
                color: on ? Theme.tile : rowHover.hovered ? Theme.raised : "transparent"
                border.width: on && page.active ? Theme.focusWidth : 0
                border.color: Theme.accent
                Accessible.role: Accessible.ListItem
                Accessible.name: page.nameOf(modelData) + ", " + page.detail(modelData)
                Rectangle {
                    id: ava
                    anchors.left: parent.left; anchors.leftMargin: Theme.px(10)
                    anchors.verticalCenter: parent.verticalCenter
                    width: Theme.px(34); height: width; radius: width / 2
                    color: Theme.avatar(page.nameOf(modelData)).bg
                    Text {
                        anchors.centerIn: parent
                        text: page.initial(page.nameOf(modelData))
                        color: Theme.avatar(page.nameOf(modelData)).fg
                        font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.DemiBold
                    }
                }
                Column {
                    anchors.left: ava.right; anchors.leftMargin: Theme.px(10)
                    anchors.right: parent.right; anchors.rightMargin: Theme.px(8)
                    anchors.verticalCenter: parent.verticalCenter
                    Text {
                        width: parent.width; elide: Text.ElideRight; text: page.nameOf(modelData)
                        color: modelData.missed ? Theme.danger : parent.parent.on ? Theme.text : Theme.textSoft
                        font.family: Theme.body; font.pixelSize: Theme.fs(16); font.weight: Font.DemiBold
                    }
                    Text {
                        width: parent.width; elide: Text.ElideRight
                        text: page.kind(modelData) + " · " + page.when(modelData.started)
                        color: modelData.missed ? Theme.danger : Theme.textMuted
                        font.family: Theme.body; font.pixelSize: Theme.fs(13)
                    }
                }
                HoverHandler { id: rowHover }
                TapHandler {
                    acceptedButtons: Qt.LeftButton | Qt.RightButton
                    onTapped: (point, button) => { page.row = index; if (button === Qt.RightButton) page.options() }
                }
            }
        }
    }

    // ============================================================= the picked call
    Item {
        anchors.left: Theme.portrait ? parent.left : middle.right
        anchors.right: parent.right
        anchors.top: Theme.portrait ? middle.bottom : parent.top
        anchors.bottom: parent.bottom
        Column {
            anchors.centerIn: parent
            width: Math.min(parent.width - Theme.px(60), Theme.px(520))
            spacing: Theme.px(14)
            Rectangle {
                anchors.horizontalCenter: parent.horizontalCenter
                width: Theme.px(84); height: width; radius: width / 2
                color: page.picked ? Theme.avatar(page.nameOf(page.picked)).bg : Theme.raised
                Icon { visible: !page.picked; anchors.centerIn: parent; name: "call"; size: Theme.px(38); color: Theme.accent }
                Text {
                    visible: !!page.picked
                    anchors.centerIn: parent
                    text: page.picked ? page.initial(page.nameOf(page.picked)) : ""
                    color: page.picked ? Theme.avatar(page.nameOf(page.picked)).fg : Theme.text
                    font.family: Theme.body; font.pixelSize: Theme.fs(34); font.weight: Font.DemiBold
                }
            }
            Text {
                width: parent.width
                horizontalAlignment: Text.AlignHCenter
                elide: Text.ElideRight
                text: page.picked ? page.nameOf(page.picked) : "Calls"
                color: Theme.text
                font.family: Theme.display
                font.pixelSize: Theme.fs(22)
                font.weight: Font.DemiBold
                font.letterSpacing: Theme.px(1)
            }
            Text {
                width: parent.width
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.WordWrap
                visible: !!page.picked
                text: page.picked ? page.detail(page.picked) : ""
                color: page.picked && page.picked.missed ? Theme.danger : Theme.textMuted
                font.family: Theme.body
                font.pixelSize: Theme.fs(16)
            }
            HButton {
                visible: !!page.picked && page.picked.key !== ""
                anchors.horizontalCenter: parent.horizontalCenter
                text: "Call back"
                kind: "accent"
                focused: page.active
                onClicked: page.callBack()
            }
        }
    }

    function handleButton(b) {
        switch (b) {
        case "up": row = Math.max(0, row - 1); return true
        case "down": row = Math.min(Math.max(0, entries.length - 1), row + 1); return true
        case "a": callBack(); return true
        case "x": options(); return true
        }
        // Left and B go back to the rail.
        return false
    }
}

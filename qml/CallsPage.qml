import QtQuick
import Huginn

// Calls: everyone you called or who called you, newest first, missed ones marked. A calls back.
// A call is started from a conversation (LT there, then Call); this page is the history.
Rectangle {
    id: page
    color: Theme.ground

    property bool tour: false
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
        : [{ key: "a", label: "Call back" }, { key: "lt", label: "Options" }, { key: "b", label: "Back" }]
    readonly property string status: Calls.available ? "" : "Calls aren't available in this build"

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
            { name: "Call back", detail: "", url: "call" },
            { name: "Delete from the list", detail: "Only on this computer", url: "delete" },
            { name: "Clear all calls", detail: "Only on this computer", url: "clear" } ] }], a => {
            if (a === "call") callBack()
            else if (a === "delete") Calls.deleteEntry(e.id)
            else if (a === "clear") Ui.sure("Clear all calls?", "The list of calls on this computer is emptied. It doesn't change anyone else's.", "Clear", () => Calls.clearHistory(), false, true)
        })
    }

    // ============================================================= the list
    Rectangle {
        id: middle
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        width: Theme.px(270)
        color: Theme.column
        PageHeader { id: head; width: parent.width; title: "Calls" }
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
                radius: Theme.px(10)
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
                TapHandler { onTapped: page.row = index }
            }
        }
    }

    // ============================================================= the picked call
    Item {
        anchors.left: middle.right
        anchors.right: parent.right
        anchors.top: parent.top
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
                text: page.picked ? page.nameOf(page.picked) : "Encrypted voice calls"
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
                text: page.picked ? page.detail(page.picked)
                    : "Call the people you chat with, on Huginn or Muninn. Open their conversation, press LT and pick Call. "
                      + "Close by, the sound goes straight over Wi-Fi or Bluetooth; apart, it goes through a relay, still encrypted end to end."
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

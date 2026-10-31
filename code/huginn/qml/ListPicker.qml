import QtQuick
import Huginn

// A list to choose from, in sections: message options, pictures and files to send, backups.
Item {
    id: picker
    property string title
    property var entries: []          // flat list: { header } or { name, detail, url, icon }
    property int current: 0
    property var callback: null

    visible: false
    anchors.fill: parent

    // sections = [{ header, items: [{ name, detail, url, icon }] }].
    function openList(t, sections, cb) {
        title = t
        callback = cb
        const out = []
        sections.forEach(sec => {
            if (!sec.items.length) return
            if (sec.header) out.push({ header: sec.header })   // an untitled section is just its rows
            sec.items.forEach(e => out.push(e))
        })
        entries = out
        current = out.findIndex(e => !e.header)
        visible = true
        list.positionViewAtIndex(Math.max(0, current), ListView.Beginning)
    }
    function pick() {
        const e = entries[current]
        if (!e || e.header) return
        visible = false
        if (callback) callback(e.url)
    }
    function close() { visible = false }
    function step(dir) {
        let i = current
        do { i += dir } while (i >= 0 && i < entries.length && entries[i].header)
        if (i >= 0 && i < entries.length) current = i
    }
    function jumpSection(dir, wrap) {
        // Headers mark sections; land on the first entry of the next or previous one.
        const headers = []
        entries.forEach((e, i) => { if (e.header) headers.push(i) })
        if (!headers.length) return
        let here = 0
        headers.forEach((h, k) => { if (h < current) here = k })
        const target = wrap ? (here + dir + headers.length) % headers.length : Math.max(0, Math.min(headers.length - 1, here + dir))
        current = headers[target] + 1
    }

    Rectangle { anchors.fill: parent; color: Qt.rgba(0.03, 0.035, 0.04, 0.85) }
    MouseArea { anchors.fill: parent; onClicked: picker.close() }

    // Sized to its rows (no taller than the window) and about 480 px wide; a detail wraps under its name.
    Rectangle {
        id: box
        anchors.centerIn: parent
        width: Math.min(parent.width - Theme.px(32), Theme.px(480))
        height: Math.min(parent.height - Theme.px(48), head.height + Theme.px(28) + list.contentHeight + (keys.visible ? keys.height : Theme.px(8)))
        radius: Theme.radius
        color: Theme.panel
        border.color: Theme.line
        MouseArea { anchors.fill: parent }   // a click between rows doesn't close it

        Text {
            id: head
            x: Theme.px(20); y: Theme.px(18)
            width: parent.width - Theme.px(40)
            wrapMode: Text.WordWrap
            text: picker.title.toUpperCase()
            color: Theme.text
            font.family: Theme.display
            font.pixelSize: Theme.fs(16)
            font.weight: Font.Bold
            font.letterSpacing: Theme.px(2)
        }
        ListView {
            id: list
            anchors.top: head.bottom
            anchors.topMargin: Theme.px(8)
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: keys.visible ? keys.top : parent.bottom
            anchors.margins: Theme.px(8)
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            model: picker.entries
            currentIndex: picker.current
            highlightMoveDuration: 80
            preferredHighlightBegin: height / 3
            preferredHighlightEnd: height * 2 / 3
            highlightRangeMode: ListView.ApplyRange
            delegate: Item {
                width: list.width
                height: modelData.header ? headText.implicitHeight + Theme.px(14) : Math.max(Theme.px(40), rowCol.implicitHeight + Theme.px(14))
                Text {
                    id: headText
                    visible: !!modelData.header
                    anchors.left: parent.left; anchors.leftMargin: Theme.px(12)
                    anchors.right: parent.right; anchors.rightMargin: Theme.px(12)
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: Theme.px(4)
                    wrapMode: Text.WordWrap
                    text: modelData.header ? modelData.header.toUpperCase() : ""
                    color: Theme.textMuted
                    font.family: Theme.body
                    font.pixelSize: Theme.fs(11)
                    font.weight: Font.DemiBold
                    font.letterSpacing: Theme.px(0.5)
                }
                Rectangle {
                    visible: !modelData.header
                    anchors.fill: parent
                    radius: Theme.radius
                    readonly property bool on: index === picker.current
                    color: on ? (Ui.main && Ui.main.padInUse ? Theme.focusFill : Theme.tile) : rowHover.hovered ? Theme.raised : "transparent"
                    border.width: on && Ui.main && Ui.main.padInUse ? Theme.focusWidth : 0
                    border.color: Theme.accent
                    Accessible.role: Accessible.ListItem
                    Accessible.name: (modelData.name || "") + (modelData.detail ? ", " + modelData.detail : "")
                    Icon {
                        id: rowIcon
                        visible: !!modelData.icon
                        anchors.left: parent.left; anchors.leftMargin: Theme.px(12)
                        anchors.verticalCenter: parent.verticalCenter
                        name: modelData.icon || "file"; size: Theme.px(18); color: Theme.textMuted
                    }
                    Column {
                        id: rowCol
                        anchors.left: rowIcon.visible ? rowIcon.right : parent.left
                        anchors.leftMargin: Theme.px(12)
                        anchors.right: parent.right; anchors.rightMargin: Theme.px(12)
                        anchors.verticalCenter: parent.verticalCenter
                        spacing: Theme.px(1)
                        Text {
                            width: parent.width
                            wrapMode: Text.Wrap
                            text: modelData.name || ""
                            color: Theme.text
                            font.family: Theme.body
                            font.pixelSize: Theme.fs(15)
                            font.weight: Font.Medium
                        }
                        Text {
                            visible: !!modelData.detail
                            width: parent.width
                            wrapMode: Text.WordWrap
                            text: modelData.detail || ""
                            color: Theme.textMuted
                            font.family: Theme.body
                            font.pixelSize: Theme.fs(13)
                        }
                    }
                    HoverHandler { id: rowHover; onHoveredChanged: if (hovered && !(Ui.main && Ui.main.padInUse)) picker.current = index }
                    TapHandler { onTapped: { picker.current = index; picker.pick() } }
                }
            }
        }
        // The buttons only while a controller is in use; a mouse clicks a row or outside.
        PromptBar {
            id: keys
            visible: !!Ui.main && Ui.main.padInUse
            anchors.bottom: parent.bottom
            width: parent.width
            color: "transparent"
            prompts: [{ key: "a", label: "Choose" }, { key: "lb", label: "Next section" }, { key: "b", label: "Cancel" }]
            onPrompt: k => picker.handleButton(k)
        }
    }

    function handleButton(b) {
        switch (b) {
        case "up": step(-1); break
        case "down": step(1); break
        case "lb": jumpSection(1, true); break   // LB goes round the sections
        case "next": jumpSection(1, false); break
        case "prev": jumpSection(-1, false); break
        case "a": pick(); break
        case "b": close(); break
        }
        return true
    }
}

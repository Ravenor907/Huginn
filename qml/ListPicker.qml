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

    Rectangle {
        anchors.centerIn: parent
        width: Math.min(parent.width - Theme.px(80), Theme.px(760))
        height: parent.height - Theme.px(120)
        radius: Theme.radius + 1
        color: Theme.panel
        border.color: Theme.lineStrong

        Text {
            id: head
            x: Theme.px(24); y: Theme.px(20)
            text: picker.title.toUpperCase()
            color: Theme.text
            font.family: Theme.display
            font.pixelSize: Theme.fs(20)
            font.weight: Font.Bold
            font.letterSpacing: Theme.px(2)
        }
        ListView {
            id: list
            anchors.top: head.bottom
            anchors.topMargin: Theme.px(12)
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: keys.top
            anchors.margins: Theme.px(12)
            clip: true
            model: picker.entries
            currentIndex: picker.current
            highlightMoveDuration: 80
            preferredHighlightBegin: height / 3
            preferredHighlightEnd: height * 2 / 3
            highlightRangeMode: ListView.ApplyRange
            delegate: Item {
                width: list.width
                height: modelData.header ? Theme.px(40) : Theme.px(54)
                Text {
                    visible: !!modelData.header
                    anchors.left: parent.left
                    anchors.leftMargin: Theme.px(12)
                    anchors.bottom: parent.bottom
                    anchors.bottomMargin: Theme.px(6)
                    text: modelData.header ? modelData.header.toUpperCase() : ""
                    color: Theme.textMuted
                    font.family: Theme.display
                    font.pixelSize: Theme.fs(14)
                    font.weight: Font.DemiBold
                    font.letterSpacing: 2
                }
                Rectangle {
                    visible: !modelData.header
                    anchors.fill: parent
                    radius: Theme.radius
                    readonly property bool on: index === picker.current
                    color: on ? Theme.focusFill : "transparent"
                    border.width: on ? Theme.focusWidth : 0
                    border.color: Theme.accent
                    Accessible.role: Accessible.ListItem
                    Accessible.name: modelData.name || ""
                    Row {
                        anchors.left: parent.left
                        anchors.leftMargin: Theme.px(14)
                        anchors.verticalCenter: parent.verticalCenter
                        spacing: Theme.px(14)
                        Icon { visible: !!modelData.icon; anchors.verticalCenter: parent.verticalCenter; name: modelData.icon || "file"; color: Theme.textMuted }
                        Text {
                            anchors.verticalCenter: parent.verticalCenter
                            text: modelData.name || ""
                            color: Theme.text
                            font.family: Theme.body
                            font.pixelSize: Theme.fs(19)
                            font.weight: Font.Medium
                            elide: Text.ElideMiddle
                            width: list.width - Theme.px(modelData.detail ? 300 : 60)
                        }
                    }
                    Text {
                        anchors.right: parent.right
                        width: Theme.px(240)
                        horizontalAlignment: Text.AlignRight
                        elide: Text.ElideRight
                        anchors.rightMargin: Theme.px(14)
                        anchors.verticalCenter: parent.verticalCenter
                        text: modelData.detail || ""
                        color: Theme.textMuted
                        font.family: Theme.body
                        font.pixelSize: Theme.fs(15)
                    }
                    TapHandler { onTapped: { picker.current = index; picker.pick() } }
                }
            }
        }
        PromptBar {
            id: keys
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

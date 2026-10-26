import QtQuick
import Huginn

// "Copy to…", "Move to…", "Extract to…": pick where things go without setting up the other pane.
// Sections: the other pane, recent places, Places, emulator ROM folders and game folders.
Item {
    id: picker
    property string title
    property var entries: []          // flat list: { header } or { name, detail, url }
    property int current: 0
    property var callback: null

    visible: false
    anchors.fill: parent

    function open(t, firstChoices, cb) {
        title = t
        callback = cb
        const out = []
        const seen = {}
        const add = (e) => { if (!e.url || seen[e.url]) return; seen[e.url] = true; out.push(e) }
        if (firstChoices.length) {
            out.push({ header: "Quick" })
            firstChoices.forEach(add)
        }
        const recent = Settings.recentDestinations
        if (recent.length) {
            out.push({ header: "Recent" })
            recent.forEach(u => add({ name: System.prettyPath(u), detail: "", url: u }))
        }
        out.push({ header: "Places" })
        Places.list.forEach(p => { if (p.kind === "folder" && !p.url.startsWith("trash:")) add({ name: p.name, detail: "", url: p.url }) })
        const roms = Places.romFolders()
        if (roms.length) {
            out.push({ header: "ROM folders" })
            roms.forEach(add)
        }
        const games = Places.gameFolders()
        if (games.length) {
            out.push({ header: "Game folders (for mods)" })
            games.forEach(add)
        }
        entries = out
        current = out.findIndex(e => !e.header)
        visible = true
        list.positionViewAtIndex(current, ListView.Beginning)
    }
    function pick() {
        const e = entries[current]
        if (!e || e.header) return
        visible = false
        Settings.rememberDestination(e.url)
        if (callback) callback(e.url)
    }
    function close() { visible = false }
    function step(dir) {
        let i = current
        do { i += dir } while (i >= 0 && i < entries.length && entries[i].header)
        if (i >= 0 && i < entries.length) current = i
    }
    function jumpSection(dir) {
        // Headers mark sections; land on the first entry of the next or previous one.
        const headers = []
        entries.forEach((e, i) => { if (e.header) headers.push(i) })
        let here = 0
        headers.forEach((h, k) => { if (h < current) here = k })
        const target = Math.max(0, Math.min(headers.length - 1, here + dir))
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
            font.pixelSize: Theme.px(20)
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
                    font.pixelSize: Theme.px(14)
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
                        Icon { anchors.verticalCenter: parent.verticalCenter; name: "folder"; color: Theme.textMuted }
                        Text {
                            anchors.verticalCenter: parent.verticalCenter
                            text: modelData.name || ""
                            color: Theme.text
                            font.family: Theme.body
                            font.pixelSize: Theme.px(19)
                            font.weight: Font.Medium
                            elide: Text.ElideMiddle
                            width: list.width - Theme.px(260)
                        }
                    }
                    Text {
                        anchors.right: parent.right
                        anchors.rightMargin: Theme.px(14)
                        anchors.verticalCenter: parent.verticalCenter
                        text: modelData.detail || ""
                        color: Theme.textMuted
                        font.family: Theme.body
                        font.pixelSize: Theme.px(15)
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
            prompts: [{ key: "a", label: "Choose" }, { key: "lbrb", label: "Sections" }, { key: "b", label: "Cancel" }]
            onPrompt: k => picker.handleButton(k === "lbrb" ? "rb" : k)
        }
    }

    function handleButton(b) {
        switch (b) {
        case "up": step(-1); break
        case "down": step(1); break
        case "lt": for (let i = 0; i < 6; ++i) step(-1); break
        case "rt": for (let i = 0; i < 6; ++i) step(1); break
        case "lb": jumpSection(-1); break
        case "rb": jumpSection(1); break
        case "a": pick(); break
        case "b": close(); break
        }
        return true
    }
}

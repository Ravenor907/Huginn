import QtQuick
import Huginn

// The home screen: Places on the left, two panes side by side.
Rectangle {
    id: page
    color: Theme.ground

    property string zone: "left"          // "side", "left" or "right"
    property int placeIndex: 0
    readonly property var activePane: zone === "right" ? rightPane : leftPane
    readonly property var otherPane: zone === "right" ? leftPane : rightPane
    readonly property var activeModel: activePane.model
    readonly property var otherModel: otherPane.model

    FolderModel { id: leftModel; showHidden: Settings.showHidden; Component.onCompleted: setUrl(Places.startUrl(0)) }
    FolderModel { id: rightModel; showHidden: Settings.showHidden; Component.onCompleted: setUrl(Places.startUrl(1)) }

    // ---------------------------------------------------------- header
    Item {
        id: header
        width: parent.width
        height: Theme.px(72)
        Row {
            anchors.left: parent.left
            anchors.leftMargin: Theme.px(24)
            anchors.verticalCenter: parent.verticalCenter
            spacing: Theme.px(14)
            Image {
                anchors.verticalCenter: parent.verticalCenter
                source: "assets/raven-gold.png"
                width: Theme.px(48); height: width
                sourceSize.width: 128; sourceSize.height: 128
                smooth: true
            }
            Column {
                anchors.verticalCenter: parent.verticalCenter
                Text {
                    text: "HUGINN"
                    color: Theme.text
                    font.family: Theme.display
                    font.pixelSize: Theme.px(24)
                    font.weight: Font.Bold
                    font.letterSpacing: Theme.px(4)
                }
                Text {
                    text: "ᚺᚢᚷᛁᚾᚾ"
                    color: "#8E897F"
                    font.family: Theme.runic
                    font.pixelSize: Theme.px(13)
                    font.letterSpacing: Theme.px(6)
                }
            }
            Item { width: Theme.px(12); height: 1 }
            Text {
                anchors.verticalCenter: parent.verticalCenter
                width: page.width - Theme.px(760)
                elide: Text.ElideMiddle
                text: page.activeModel.title + (page.activeModel.filter ? "   ·   matching “" + page.activeModel.filter + "”" : "")
                color: Theme.textMuted
                font.family: Theme.body
                font.pixelSize: Theme.px(18)
            }
        }
        Row {
            anchors.right: parent.right
            anchors.rightMargin: Theme.px(24)
            anchors.verticalCenter: parent.verticalCenter
            spacing: Theme.px(12)
            HButton {
                implicitHeight: Theme.px(44)
                fontSize: 17
                text: "Sort: " + ({ name: "Name", newest: "Newest", size: "Size" })[page.activeModel.sortMode]
                onClicked: page.cycleSort()
            }
            HButton {
                implicitHeight: Theme.px(44)
                fontSize: 17
                text: page.activeModel.filter ? "Clear search" : "Search"
                onClicked: page.search()
            }
        }
    }
    Divider { id: divider; anchors.top: header.bottom; x: Theme.px(28); width: parent.width - Theme.px(56) }

    // ---------------------------------------------------------- places
    // Scrolls to keep the focused place in view when the list is taller than the screen.
    Flickable {
        id: sideFlick
        anchors.top: divider.bottom
        anchors.topMargin: Theme.px(14)
        anchors.left: parent.left
        anchors.leftMargin: Theme.px(14)
        anchors.bottom: bar.top
        width: Theme.px(224)
        contentHeight: side.implicitHeight
        clip: true
        boundsBehavior: Flickable.StopAtBounds
    Column {
        id: side
        width: Theme.px(224)
        spacing: Theme.px(2)
        Text {
            leftPadding: Theme.px(12)
            bottomPadding: Theme.px(10)
            text: page.zone === "side" ? "PLACES" : "PLACES  ◀"
            color: Theme.textMuted
            font.family: Theme.display
            font.pixelSize: Theme.px(13)
            font.weight: Font.DemiBold
            font.letterSpacing: Theme.px(2.5)
        }
        Repeater {
            model: Places.list
            Rectangle {
                readonly property bool here: modelData.kind === "folder" && page.activeModel.url.toString().startsWith(modelData.url) && modelData.url.length > 0
                readonly property bool focused: page.zone === "side" && page.placeIndex === index
                width: side.width
                height: Theme.px(44)
                radius: Theme.radius
                color: focused ? Theme.focusFill : here ? "#1D1C19" : "transparent"
                border.width: focused ? Theme.focusWidth : 0
                border.color: Theme.accent
                Row {
                    anchors.left: parent.left
                    anchors.leftMargin: Theme.px(12)
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: Theme.px(14)
                    Rectangle {
                        anchors.verticalCenter: parent.verticalCenter
                        width: Theme.px(7); height: width; rotation: 45
                        color: parent.parent.here ? Theme.accent : "transparent"
                        border.width: parent.parent.here ? 0 : 1
                        border.color: Theme.ornament
                    }
                    Text {
                        anchors.verticalCenter: parent.verticalCenter
                        text: modelData.name
                        color: parent.parent.here || parent.parent.focused ? Theme.accent : Theme.textSoft
                        font.family: Theme.body
                        font.pixelSize: Theme.px(18)
                        font.weight: parent.parent.here ? Font.DemiBold : Font.Normal
                    }
                }
                TapHandler { onTapped: { page.placeIndex = index; page.openPlace(index) } }
            }
        }
    }

    }
    Connections {
        target: page
        function onPlaceIndexChanged() {
            const rowH = Theme.px(46)
            const y = Theme.px(36) + page.placeIndex * rowH
            if (y < sideFlick.contentY) sideFlick.contentY = y
            else if (y + rowH > sideFlick.contentY + sideFlick.height) sideFlick.contentY = y + rowH - sideFlick.height
        }
    }

    // ---------------------------------------------------------- panes
    Row {
        id: panes
        anchors.top: divider.bottom
        anchors.topMargin: Theme.px(14)
        anchors.left: sideFlick.right
        anchors.leftMargin: Theme.px(4)
        anchors.right: parent.right
        anchors.rightMargin: Theme.px(20)
        anchors.bottom: bar.top
        anchors.bottomMargin: Theme.px(14)
        spacing: Theme.px(14)
        FilePane {
            id: leftPane
            width: (panes.width - panes.spacing) / 2
            height: panes.height
            model: leftModel
            active: page.zone === "left"
            onTapped: page.zone = "left"
            onActivated: index => { page.zone = "left"; page.openItem(index) }
        }
        FilePane {
            id: rightPane
            width: (panes.width - panes.spacing) / 2
            height: panes.height
            model: rightModel
            active: page.zone === "right"
            onTapped: page.zone = "right"
            onActivated: index => { page.zone = "right"; page.openItem(index) }
        }
    }

    PromptBar {
        id: bar
        anchors.bottom: parent.bottom
        width: parent.width
        status: page.activeModel.selectedCount > 0
            ? page.activeModel.selectedCount + " selected · " + page.activeModel.selectedSize
            : FileOps.canUndo ? "Undo: " + FileOps.undoText : ""
        statusColor: page.activeModel.selectedCount > 0 ? Theme.accent : Theme.textMuted
        prompts: page.zone === "side"
            ? [{ key: "a", label: "Open" }, { key: "right", label: "Files" }, { key: "start", label: "Settings" }]
            : [{ key: "a", label: "Open" }, { key: "b", label: "Up" }, { key: "x", label: "Select" },
               { key: "y", label: "Actions" }, { key: "lbrb", label: "Panes" }, { key: "back", label: "Search" },
               { key: "start", label: "Settings" }]
        onPrompt: k => k === "lbrb" ? page.handleButton("rb") : page.handleButton(k)
    }

    // ---------------------------------------------------------- behavior
    function openPlace(i) {
        const p = Places.list[i]
        if (!p) return
        if (p.kind === "folder") {
            activeModel.setUrl(p.url)
            activePane.currentIndex = 0
            if (zone === "side") zone = "left"
        } else {
            Ui.openPage(p.kind, {})
        }
    }

    function openItem(i) {
        const m = activeModel
        if (i < 0 || i >= m.count) return
        const url = m.urlAt(i)
        const kind = m.kindAt(i)
        if (m.isDirAt(i)) { m.setUrl(url); activePane.currentIndex = 0; return }
        if (kind === "image" || kind === "text") Ui.openPage("preview", { url: url, folder: m.url })
        else if (kind === "archive") Ui.openPage("archive", { url: url, dest: otherModel.url })
        else if (kind === "disc") Ui.openPage("usb", { iso: url })
        else Ui.say("Huginn can't open this kind of file yet. Use the action menu to copy or move it.")
    }

    function otherChoice() {
        return { name: "Other pane", detail: otherModel.title, url: otherModel.url.toString() }
    }

    function targets() {
        const m = activeModel
        if (m.selectedCount > 0) return m.selectedUrls()
        if (activePane.currentIndex >= 0 && activePane.currentIndex < m.count) return [m.urlAt(activePane.currentIndex)]
        return []
    }
    function targetLabel() {
        const m = activeModel
        if (m.selectedCount > 1) return m.selectedCount + " items"
        if (m.selectedCount === 1) return m.selectedNames()[0]
        return activePane.currentIndex >= 0 && activePane.currentIndex < m.count ? m.nameAt(activePane.currentIndex) : ""
    }

    function openWheel(fromHold) {
        const m = activeModel
        const has = targets().length > 0
        const single = has && targets().length === 1
        const isArchive = single && m.selectedCount <= 1 && activePane.currentIndex >= 0 && m.kindAt(activePane.currentIndex) === "archive"
        const acts = [
            { id: "copy", label: "Copy to…", icon: "copy", enabled: has },
            { id: "move", label: "Move to…", icon: "move", enabled: has },
            { id: "rename", label: "Rename", icon: "rename", enabled: single },
            { id: "extract", label: "Extract to…", icon: "extract", enabled: isArchive },
            { id: "delete", label: "Delete", icon: "delete", enabled: has },
            { id: "compress", label: "Compress", icon: "compress", enabled: has },
            { id: "newfolder", label: "New folder", icon: "newfolder", enabled: true },
            { id: "phone", label: "To phone", icon: "phone", enabled: has },
            { id: "undo", label: "Undo", icon: "undo", enabled: FileOps.canUndo }
        ]
        const det = m.selectedCount > 0 ? m.selectedSize : ""
        Ui.wheel.open(acts, has ? targetLabel() : m.title, det, fromHold)
    }

    Connections {
        target: Ui.wheel
        enabled: page.visible
        function onChosen(id) { page.doAction(id) }
    }

    function doAction(id) {
        const urls = targets()
        const m = activeModel
        switch (id) {
        case "copy":
            Ui.pick("Copy " + targetLabel() + " to…", [otherChoice()], d => { FileOps.copy(urls, d); m.clearSelection() })
            break
        case "move":
            Ui.pick("Move " + targetLabel() + " to…", [otherChoice()], d => { FileOps.move(urls, d); m.clearSelection() })
            break
        case "rename":
            Ui.ask("Rename", m.title, m.fileName(urls[0]), "Rename", t => FileOps.rename(urls[0], t))
            break
        case "extract":
            Ui.pick("Extract " + targetLabel() + " to…",
                    [{ name: "Next to the archive", detail: m.title, url: m.url.toString() }, otherChoice()],
                    d => FileOps.extract(urls[0], d))
            break
        case "delete":
            if (Settings.confirmDelete)
                Ui.sure("Move to trash?", targetLabel() + " will go to the trash. You can undo this right after.",
                        "Move to trash", () => { FileOps.trash(urls); m.clearSelection() }, false, true)
            else { FileOps.trash(urls); m.clearSelection() }
            break
        case "compress":
            Ui.ask("Compress to zip", m.title, (urls.length === 1 ? m.fileName(urls[0]) : "archive") + ".zip", "Compress",
                   t => FileOps.compress(urls, m.url, t))
            break
        case "newfolder":
            Ui.ask("New folder", m.title, "New folder", "Create", t => FileOps.mkdir(m.url, t))
            break
        case "phone":
            Transfer.share(urls)
            Ui.openPage("transfer", {})
            break
        case "undo":
            FileOps.undo()
            break
        }
    }

    function cycleSort() {
        const order = ["name", "newest", "size"]
        const m = activeModel
        m.sortMode = order[(order.indexOf(m.sortMode) + 1) % order.length]
        activePane.currentIndex = 0
        Ui.say("Sorted by " + ({ name: "name", newest: "newest first", size: "size, biggest first" })[m.sortMode])
    }

    function search() {
        const m = activeModel
        if (m.filter) { m.filter = ""; return }
        Ui.ask("Search this folder", m.title, "", "Search", t => { m.filter = t; activePane.currentIndex = 0 })
    }

    Connections {
        target: FileOps
        function onFinished(ok, message) {
            leftModel.refresh(); rightModel.refresh()
            if (message.length > 0) Ui.say(message, !ok)
        }
    }

    function handleButton(b) {
        if (zone === "side") {
            switch (b) {
            case "up": placeIndex = Math.max(0, placeIndex - 1); return true
            case "down": placeIndex = Math.min(Places.list.length - 1, placeIndex + 1); return true
            case "a": openPlace(placeIndex); return true
            case "right": case "b": zone = "left"; return true
            case "start": Ui.openPage("settings"); return true
            }
            return false
        }
        switch (b) {
        case "up": activePane.move(-1); return true
        case "down": activePane.move(1); return true
        case "lt": activePane.page(-1); return true
        case "rt": activePane.page(1); return true
        case "left": zone = zone === "right" ? "left" : "side"; return true
        case "right": zone = "right"; return true
        case "lb": case "rb": zone = zone === "left" ? "right" : "left"; return true
        case "a": openItem(activePane.currentIndex); return true
        case "b":
            if (activeModel.filter) activeModel.filter = ""
            else if (activeModel.selectedCount > 0) activeModel.clearSelection()
            else activeModel.up()
            return true
        case "x":
            activeModel.toggleSelected(activePane.currentIndex)
            activePane.move(1)
            return true
        case "y": openWheel(true); return true
        case "back": search(); return true
        case "ls": cycleSort(); return true
        case "start": Ui.openPage("settings"); return true
        }
        return false
    }
}

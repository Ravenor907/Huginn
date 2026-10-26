import QtQuick
import Huginn

// The start screen: what just landed in Downloads, how full each drive is, and one press to
// every tool. Tiles: 0 Downloads, 1 Storage, 2 Games, 3 Browse files, 4 Phone Transfer, 5 USB Tools.
Rectangle {
    id: page
    color: Theme.ground

    property int tile: 0
    property int row: 0
    property bool tour: false
    property var downloads: []
    property var drives: []

    readonly property var shaderBytes: {
        let total = 0, found = false
        Storage.drives.forEach(d => (d.categories || []).forEach(c => { if (c.action === "shader") { total += c.bytes; found = true } }))
        return found ? total : -1
    }
    readonly property var browseRows: {
        const out = []
        const seen = {}
        Settings.recentFolders.forEach(u => {
            if (out.length < 3 && !seen[u]) { seen[u] = true; out.push({ name: System.prettyPath(u), url: u }) }
        })
        Places.list.forEach(p => {
            if (out.length < 3 && p.kind === "folder" && !p.url.startsWith("trash:") && !seen[p.url]) { seen[p.url] = true; out.push({ name: p.name, url: p.url }) }
        })
        out.push({ name: "All files", url: "", all: true })
        return out
    }
    readonly property int downloadRows: Math.max(1, downloads.length)

    function setArgs(a) {
        tour = !!a.tour
        reload()
    }
    function reload() {
        let d = System.newestDownloads(5)
        if (d.length === 0 && tour)
            d = [{ name: "texture-pack-hd.zip", when: "Today", kind: "archive", url: "" },
                 { name: "Pokemon Emerald.gba", when: "Today", kind: "file", url: "" },
                 { name: "bazzite-stable.iso", when: "Yesterday", kind: "disc", url: "" },
                 { name: "reshade-presets.7z", when: "Oct 21", kind: "archive", url: "" }]
        downloads = d
        drives = System.driveSummary()
        if (tile === 0) row = Math.min(row, downloadRows - 1)
    }
    Component.onCompleted: {
        if (Games.count === 0 && !Games.loading) Games.refresh()
        if (Storage.drives.length === 0 && !Storage.measuring) Storage.refresh()
    }
    onVisibleChanged: if (visible) reload()
    Timer { interval: 5000; repeat: true; running: page.visible; onTriggered: page.reload() }
    Connections {
        target: FileOps
        function onFinished(ok, message) { page.reload() }
    }

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
                Text { text: "HUGINN"; color: Theme.text; font.family: Theme.display; font.pixelSize: Theme.px(24); font.weight: Font.Bold; font.letterSpacing: Theme.px(4) }
                Text { text: "ᚺᚢᚷᛁᚾᚾ"; color: "#8E897F"; font.family: Theme.runic; font.pixelSize: Theme.px(13); font.letterSpacing: Theme.px(6) }
            }
        }
        Text {
            anchors.right: parent.right
            anchors.rightMargin: Theme.px(28)
            anchors.verticalCenter: parent.verticalCenter
            width: parent.width * 0.5
            horizontalAlignment: Text.AlignRight
            elide: Text.ElideLeft
            text: page.drives.map(d => d.name + " " + d.freeText + " free").join("   ·   ")
            color: Theme.textMuted
            font.family: Theme.body
            font.pixelSize: Theme.px(17)
        }
    }
    Divider { id: divider; anchors.top: header.bottom; x: Theme.px(28); width: parent.width - Theme.px(56) }

    // ---------------------------------------------------------- tiles
    Item {
        id: grid
        anchors.top: divider.bottom
        anchors.topMargin: Theme.px(16)
        anchors.left: parent.left
        anchors.leftMargin: Theme.px(28)
        anchors.right: parent.right
        anchors.rightMargin: Theme.px(28)
        anchors.bottom: bar.top
        anchors.bottomMargin: Theme.px(14)
        readonly property real gap: Theme.px(14)
        readonly property real unit: (width - 2 * gap) / 3.3
        readonly property real c0: unit * 1.3
        readonly property real rowH: (height - gap) / 2

        // Downloads ------------------------------------------------
        Tile {
            id: dlTile
            index: 0
            on: page.tile === 0
            onActivated: { page.go(0); page.activate() }
            hasRows: true
            x: 0; y: 0; width: grid.c0; height: grid.height
            title: "New in Downloads"
            Column {
                id: dlRows
                width: parent.width
                Repeater {
                    model: page.downloads
                    Rectangle {
                        readonly property bool on: page.tile === 0 && page.row === index
                        width: dlRows.width
                        height: Theme.px(46)
                        radius: Theme.radius
                        color: on ? Theme.accentFill : "transparent"
                        border.width: on ? 1 : 0
                        border.color: Theme.accent
                        Accessible.role: Accessible.ListItem
                        Accessible.name: modelData.name + ", " + modelData.when
                        Rectangle { visible: !parent.on && index < page.downloads.length - 1; anchors.bottom: parent.bottom; width: parent.width; height: 1; color: "#24262A" }
                        Icon {
                            id: dlIcon
                            anchors.left: parent.left
                            anchors.leftMargin: Theme.px(8)
                            anchors.verticalCenter: parent.verticalCenter
                            name: modelData.kind === "folder" || modelData.kind === "archive" || modelData.kind === "disc" || modelData.kind === "image" || modelData.kind === "text" || modelData.kind === "video" || modelData.kind === "audio" ? modelData.kind : "file"
                            size: Theme.px(22)
                            color: parent.on ? Theme.accent : Theme.textMuted
                        }
                        Text {
                            anchors.left: dlIcon.right
                            anchors.leftMargin: Theme.px(12)
                            anchors.right: whenText.left
                            anchors.rightMargin: Theme.px(10)
                            anchors.verticalCenter: parent.verticalCenter
                            elide: Text.ElideMiddle
                            text: modelData.name
                            color: parent.on ? Theme.text : Theme.textSoft
                            font.family: Theme.body
                            font.pixelSize: Theme.px(17)
                            font.weight: parent.on ? Font.DemiBold : Font.Normal
                        }
                        Text {
                            id: whenText
                            anchors.right: parent.right
                            anchors.rightMargin: Theme.px(8)
                            anchors.verticalCenter: parent.verticalCenter
                            text: modelData.when
                            color: Theme.textMuted
                            font.family: Theme.body
                            font.pixelSize: Theme.px(14)
                        }
                        TapHandler { onTapped: { page.tile = 0; page.row = index; page.activate() } }
                    }
                }
                Text {
                    visible: page.downloads.length === 0
                    width: dlRows.width
                    wrapMode: Text.WordWrap
                    text: "Nothing in Downloads yet. Files you download in Desktop Mode or send from your phone show up here."
                    color: Theme.textMuted
                    font.family: Theme.body
                    font.pixelSize: Theme.px(16)
                }
            }
            Column {
                anchors.bottom: parent.bottom
                width: parent.width
                spacing: Theme.px(12)
                Text {
                    width: parent.width
                    wrapMode: Text.WordWrap
                    text: "Move to… lists your ROM folders and game folders, so each file lands where it belongs."
                    color: Theme.textSoft
                    font.family: Theme.body
                    font.pixelSize: Theme.px(15)
                }
                Row {
                    spacing: Theme.px(10)
                    HButton { text: "Move to…"; kind: "accent"; enabled: page.downloads.length > 0 && !page.tour; onClicked: { page.tile = 0; page.moveDownload() } }
                    HButton { text: "Open Downloads"; onClicked: page.openDownloads() }
                }
            }
        }

        // Storage --------------------------------------------------
        Tile {
            index: 1
            on: page.tile === 1
            onActivated: { page.go(1); page.activate() }
            x: grid.c0 + grid.gap; y: 0; width: grid.unit; height: grid.rowH
            title: "Storage"
            link: "Storage ▸"
            Column {
                width: parent.width
                spacing: Theme.px(8)
                Repeater {
                    model: page.drives
                    Column {
                        width: parent.width
                        spacing: Theme.px(6)
                        Item {
                            width: parent.width
                            height: Theme.px(22)
                            Text { text: modelData.name; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.px(16) }
                            Text { anchors.right: parent.right; text: modelData.freeText + " of " + modelData.totalText + " free"; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.px(15) }
                        }
                        Rectangle {
                            width: parent.width; height: Theme.px(8); color: Theme.line
                            Rectangle { width: parent.width * modelData.usedFraction; height: parent.height; color: modelData.usedFraction > 0.9 ? Theme.danger : Theme.accent }
                        }
                    }
                }
                Text {
                    width: parent.width
                    elide: Text.ElideRight
                    text: page.shaderBytes > 0 ? "Shader caches use " + System.sizeText(page.shaderBytes) : page.shaderBytes === 0 ? "No shader caches yet" : Storage.measuring ? "Measuring shader caches…" : ""
                    color: Theme.textSoft
                    font.family: Theme.body
                    font.pixelSize: Theme.px(15)
                }
            }
        }

        // Games ----------------------------------------------------
        Tile {
            index: 2
            on: page.tile === 2
            onActivated: { page.go(2); page.activate() }
            x: grid.c0 + grid.unit + 2 * grid.gap; y: 0; width: grid.unit; height: grid.rowH
            title: "Games"
            link: "Games ▸"
            Column {
                width: parent.width
                spacing: Theme.px(8)
                Text {
                    text: Games.count > 0 ? Games.count + " installed" : Games.loading ? "Looking…" : "No games found"
                    color: Theme.text
                    font.family: Theme.body
                    font.pixelSize: Theme.px(26)
                    font.weight: Font.DemiBold
                }
                Text {
                    width: parent.width
                    wrapMode: Text.WordWrap
                    text: "Saves, Proton prefixes and shader caches, by game name"
                    color: Theme.textSoft
                    font.family: Theme.body
                    font.pixelSize: Theme.px(15)
                }
            }
        }

        // Browse files ---------------------------------------------
        Tile {
            index: 3
            on: page.tile === 3
            onActivated: { page.go(3); page.activate() }
            hasRows: true
            x: grid.c0 + grid.gap; y: grid.rowH + grid.gap; width: grid.unit; height: grid.rowH
            title: "Browse files"
            Column {
                id: browseCol
                width: parent.width
                Repeater {
                    model: page.browseRows
                    Rectangle {
                        readonly property bool on: page.tile === 3 && page.row === index
                        width: browseCol.width
                        height: Theme.px(38)
                        radius: Theme.radius
                        color: on ? Theme.accentFill : "transparent"
                        border.width: on ? 1 : 0
                        border.color: Theme.accent
                        Accessible.role: Accessible.ListItem
                        Accessible.name: modelData.name
                        Rectangle { visible: !parent.on && !modelData.all; anchors.bottom: parent.bottom; width: parent.width; height: 1; color: "#24262A" }
                        Icon {
                            id: bIcon
                            visible: !modelData.all
                            anchors.left: parent.left
                            anchors.leftMargin: Theme.px(6)
                            anchors.verticalCenter: parent.verticalCenter
                            name: "folder"
                            size: Theme.px(20)
                            color: parent.on ? Theme.accent : Theme.textMuted
                        }
                        Text {
                            anchors.left: modelData.all ? parent.left : bIcon.right
                            anchors.leftMargin: modelData.all ? Theme.px(6) : Theme.px(10)
                            anchors.right: parent.right
                            anchors.rightMargin: Theme.px(6)
                            anchors.verticalCenter: parent.verticalCenter
                            horizontalAlignment: modelData.all ? Text.AlignRight : Text.AlignLeft
                            elide: Text.ElideLeft
                            text: modelData.all ? "All files ▸" : modelData.name
                            color: parent.on ? Theme.text : modelData.all ? Theme.textMuted : Theme.textSoft
                            font.family: Theme.body
                            font.pixelSize: Theme.px(modelData.all ? 15 : 16)
                        }
                        TapHandler { onTapped: { page.tile = 3; page.row = index; page.activate() } }
                    }
                }
            }
        }

        // Phone Transfer and USB Tools ------------------------------
        Tile {
            index: 4
            on: page.tile === 4
            onActivated: { page.go(4); page.activate() }
            x: grid.c0 + grid.unit + 2 * grid.gap; y: grid.rowH + grid.gap; width: grid.unit; height: (grid.rowH - grid.gap) / 2
            compact: true
            icon: "phone"
            title: "Phone Transfer"
            detail: "Scan, enter PIN, send"
        }
        Tile {
            index: 5
            on: page.tile === 5
            onActivated: { page.go(5); page.activate() }
            x: grid.c0 + grid.unit + 2 * grid.gap; y: grid.rowH + grid.gap + (grid.rowH + grid.gap) / 2; width: grid.unit; height: (grid.rowH - grid.gap) / 2
            compact: true
            icon: "usb"
            title: "USB Tools"
            detail: {
                const d = Usb.drives
                if (d.length === 0) return "Ventoy, boot sticks, formatting"
                if (d.some(x => x.ventoy)) return "Ventoy stick plugged in"
                return d.length === 1 ? "1 drive plugged in" : d.length + " drives plugged in"
            }
        }
    }

    PromptBar {
        id: bar
        anchors.bottom: parent.bottom
        width: parent.width
        status: "Huginn " + System.version
        prompts: {
            const p = []
            if (page.tile === 0) {
                if (page.downloads.length > 0) p.push({ key: "a", label: "Move to…" })
                p.push({ key: "x", label: "Open Downloads" })
            } else {
                p.push({ key: "a", label: "Open" })
            }
            p.push({ key: "back", label: "Search everything" })
            p.push({ key: "start", label: "Settings" })
            return p
        }
        onPrompt: k => page.handleButton(k)
    }

    // ---------------------------------------------------------- tile shell
    component Tile: Rectangle {
        id: t
        property int index
        property string title
        property string link
        property string icon
        property string detail
        property bool compact: false
        default property alias content: body.data
        property bool on: false
        // Tiles with rows inside show focus on the row; the tile keeps a quieter gold edge.
        property bool hasRows: false
        signal activated()

        radius: Theme.radius + 1
        clip: true
        color: on ? (hasRows ? Theme.panel : Theme.focusFill) : Theme.panel
        border.width: on ? (hasRows ? 1 : Theme.focusWidth) : 1
        border.color: on ? Theme.accent : Theme.line
        Accessible.role: Accessible.Button
        Accessible.name: title + (detail ? ", " + detail : "")

        Text {
            id: head
            visible: !t.compact
            x: Theme.px(20); y: Theme.px(18)
            text: t.title.toUpperCase()
            color: t.on ? Theme.accent : Theme.textMuted
            font.family: Theme.display
            font.pixelSize: Theme.px(14)
            font.weight: Font.Bold
            font.letterSpacing: Theme.px(2.5)
        }
        Item {
            id: body
            visible: !t.compact
            x: Theme.px(20)
            y: head.y + head.height + Theme.px(12)
            width: t.width - Theme.px(40)
            height: t.height - y - Theme.px(18) - (t.link ? Theme.px(20) : 0)
        }
        Text {
            visible: t.link.length > 0
            anchors.right: parent.right
            anchors.rightMargin: Theme.px(18)
            anchors.bottom: parent.bottom
            anchors.bottomMargin: Theme.px(14)
            text: t.link
            color: t.on ? Theme.accent : Theme.textMuted
            font.family: Theme.body
            font.pixelSize: Theme.px(14)
        }
        Row {
            visible: t.compact
            anchors.left: parent.left
            anchors.leftMargin: Theme.px(20)
            anchors.right: parent.right
            anchors.rightMargin: Theme.px(16)
            anchors.verticalCenter: parent.verticalCenter
            spacing: Theme.px(16)
            Icon { anchors.verticalCenter: parent.verticalCenter; name: t.icon || "file"; size: Theme.px(34); color: t.on ? Theme.accent : Theme.textMuted }
            Column {
                anchors.verticalCenter: parent.verticalCenter
                width: parent.width - Theme.px(50)
                Text { width: parent.width; elide: Text.ElideRight; text: t.title; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.px(20); font.weight: Font.DemiBold }
                Text { width: parent.width; elide: Text.ElideRight; text: t.detail; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.px(14) }
            }
        }
        TapHandler {
            enabled: !t.hasRows
            onTapped: t.activated()
        }
    }

    // ---------------------------------------------------------- behavior
    function rowsIn(t) { return t === 0 ? downloadRows : t === 3 ? browseRows.length : 1 }
    function go(t, r) { tile = t; row = Math.max(0, Math.min(r === undefined ? 0 : r, rowsIn(t) - 1)) }

    function moveDownload() {
        const d = downloads[row]
        if (!d || !d.url) return
        Ui.pick("Move " + d.name + " to…", [], dest => FileOps.move([d.url], dest))
    }
    function openDownloads() {
        Ui.main.openInBrowser(System.downloadsUrl())
    }
    function searchEverything() {
        Ui.ask("Search everything", "Home and every card or drive in Places", "", "Search", t => {
            if (!t.length) return
            Ui.main.openInBrowser("filenamesearch:?search=" + encodeURIComponent(t) + "&url=" + encodeURIComponent(System.homeUrl().toString()))
        })
    }
    function activate() {
        switch (tile) {
        case 0: moveDownload(); break
        case 1: Ui.openPage("storage", {}); break
        case 2: Ui.openPage("games", {}); break
        case 3: {
            const r = browseRows[row]
            if (!r) return
            if (r.all) Ui.openPage("browser", {})
            else Ui.main.openInBrowser(r.url)
            break
        }
        case 4: Ui.openPage("transfer", {}); break
        case 5: Ui.openPage("usb", {}); break
        }
    }

    function handleButton(b) {
        switch (b) {
        case "up":
            if ((tile === 0 || tile === 3) && row > 0) { row--; return true }
            if (tile === 3) go(1)
            else if (tile === 4) go(2)
            else if (tile === 5) go(4)
            return true
        case "down":
            if ((tile === 0 || tile === 3) && row < rowsIn(tile) - 1) { row++; return true }
            if (tile === 1) go(3)
            else if (tile === 2) go(4)
            else if (tile === 4) go(5)
            return true
        case "left":
            if (tile === 1 || tile === 3) go(0)
            else if (tile === 2) go(1)
            else if (tile === 4 || tile === 5) go(3)
            return true
        case "right":
            if (tile === 0) go(1)
            else if (tile === 1) go(2)
            else if (tile === 3) go(4)
            return true
        case "a": activate(); return true
        case "x": if (tile === 0) openDownloads(); return true
        case "back": searchEverything(); return true
        case "start": Ui.openPage("settings", {}); return true
        }
        return false
    }
}

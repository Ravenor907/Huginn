import QtQuick
import Huginn

// USB Tools. Left: what to put on the drive (Downloads by default, Browse all for anywhere).
// Right: the drive and what to do with it: write an image (Windows ISOs are made the way Rufus
// makes them), copy files, SteamOS recovery, Ventoy, format, eject.
Rectangle {
    id: page
    color: Theme.ground
    property int driveIndex: 0
    property string zone: "files"         // "files" or "actions"
    property int actionIndex: 0
    property bool browsingAll: false
    readonly property var drive: Usb.drives.length > 0 ? Usb.drives[Math.min(driveIndex, Usb.drives.length - 1)] : null

    FolderModel { id: files; showHidden: false }

    function setArgs(a) {
        if (a.iso) {
            // Opened from the browser on an image: show its folder with it picked.
            const folder = System.parentUrl(a.iso)
            browsingAll = folder.toString() !== System.downloadsUrl().toString()
            files.setUrl(folder)
            pendingImage = a.iso.toString()
        }
    }
    property string pendingImage: ""
    Component.onCompleted: { files.setUrl(System.downloadsUrl()); Usb.refresh() }

    readonly property var actions: {
        const out = []
        if (!Usb.rawReady) out.push({ id: "setup", label: "One-time setup", kind: "accent" })
        if (!drive) return out
        out.push({ id: "write", label: "Write to drive (erases it)", kind: "danger" })
        out.push({ id: "copy", label: drive.ventoy ? "Copy to Ventoy drive" : "Copy files to drive", kind: "accent" })
        out.push({ id: "steamos", label: "SteamOS recovery drive (erases it)", kind: "danger" })
        if (drive.ventoy) out.push({ id: "update", label: "Update Ventoy (keeps your files)", kind: "normal" })
        out.push({ id: "install", label: drive.ventoy ? "Reinstall Ventoy (erases it)" : "Install Ventoy (erases it)", kind: "danger" })
        out.push({ id: "format", label: "Format as a normal drive (erases it)", kind: "normal" })
        out.push({ id: "eject", label: "Eject", kind: "normal" })
        return out
    }

    PageHeader {
        id: header
        width: parent.width
        title: "USB Tools"
        subtitle: "Write or copy to a USB drive"
    }
    Divider { id: divider; anchors.top: header.bottom; x: Theme.px(28); width: parent.width - Theme.px(56) }

    // ---------------------------------------------------------- left: what to put on the drive
    Item {
        id: leftTop
        anchors.top: divider.bottom
        anchors.topMargin: Theme.px(12)
        anchors.left: parent.left
        anchors.leftMargin: Theme.px(28)
        width: (parent.width - Theme.px(72)) * 0.55
        height: Theme.px(44)
        Text {
            anchors.verticalCenter: parent.verticalCenter
            text: page.browsingAll ? "ALL FILES" : "DOWNLOADS"
            color: page.zone === "files" ? Theme.accent : Theme.textSoft
            font.family: Theme.display; font.pixelSize: Theme.fs(16); font.weight: Font.Bold; font.letterSpacing: Theme.px(2.5)
        }
        HButton {
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            implicitHeight: Theme.px(40)
            fontSize: 16
            text: page.browsingAll ? "Back to Downloads" : "Browse all"
            onClicked: page.toggleBrowse()
        }
    }
    FilePane {
        id: pane
        anchors.top: leftTop.bottom
        anchors.topMargin: Theme.px(8)
        anchors.left: leftTop.left
        width: leftTop.width
        anchors.bottom: bar.top
        anchors.bottomMargin: Theme.px(14)
        model: files
        active: page.zone === "files"
        onTapped: i => { page.zone = "files"; pane.currentIndex = i; page.openFile(i) }
    }

    // ---------------------------------------------------------- right: the drive
    Column {
        id: right
        anchors.top: divider.bottom
        anchors.topMargin: Theme.px(12)
        anchors.left: leftTop.right
        anchors.leftMargin: Theme.px(16)
        anchors.right: parent.right
        anchors.rightMargin: Theme.px(28)
        spacing: Theme.px(10)

        Rectangle {
            width: right.width
            height: driveCol.implicitHeight + Theme.px(28)
            radius: Theme.radius + 1
            color: Theme.panel
            border.color: page.drive ? Theme.accent : Theme.line
            Column {
                id: driveCol
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.top: parent.top
                anchors.margins: Theme.px(14)
                spacing: Theme.px(6)
                Row {
                    width: parent.width
                    spacing: Theme.px(12)
                    Icon { name: "usb"; size: Theme.px(28); color: page.drive ? Theme.accent : Theme.textMuted; anchors.verticalCenter: parent.verticalCenter }
                    Column {
                        width: parent.width - Theme.px(40)
                        Text {
                            width: parent.width; elide: Text.ElideRight
                            text: page.drive ? page.drive.title : "No USB drive"
                            color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(19); font.weight: Font.DemiBold
                        }
                        Text {
                            width: parent.width; elide: Text.ElideRight
                            text: !page.drive ? "Plug one in. The microSD card and internal drive never show up here, so they can't be erased by mistake."
                                : (page.drive.ventoy ? "Ventoy " + page.drive.ventoyVersion + (page.drive.secureBoot ? ", Secure Boot on" : "") + " · " : "")
                                  + page.drive.usage + (Usb.drives.length > 1 ? "   (LB / RB: " + (page.driveIndex + 1) + " of " + Usb.drives.length + ")" : "")
                            wrapMode: page.drive ? Text.NoWrap : Text.WordWrap
                            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(14)
                        }
                    }
                }
                Rectangle {
                    visible: !!page.drive && page.drive.usedFraction >= 0
                    width: parent.width; height: Theme.px(6); radius: 1; color: Theme.line
                    Rectangle { width: parent.width * Math.max(0, page.drive ? page.drive.usedFraction : 0); height: parent.height; color: Theme.accent }
                }
            }
        }

        Text {
            visible: !Usb.rawReady
            width: right.width
            wrapMode: Text.WordWrap
            text: System.gameMode
                ? "Writing and Ventoy need a one-time setup in Desktop Mode (it asks for your password once). Copying files works now."
                : "Writing and Ventoy need a one-time setup: press One-time setup and enter your password once."
            color: Theme.accent; font.family: Theme.body; font.pixelSize: Theme.fs(14)
        }

        Repeater {
            model: page.actions
            HButton {
                width: right.width
                implicitHeight: Theme.px(46)
                fontSize: 17
                text: modelData.label
                kind: modelData.kind
                focused: page.zone === "actions" && page.actionIndex === index
                onClicked: { page.zone = "actions"; page.actionIndex = index; page.act(modelData.id) }
            }
        }

        Rectangle {
            visible: Usb.busy || Usb.checking || Usb.log.length > 0
            width: right.width
            height: Theme.px(96)
            radius: Theme.radius + 1
            color: Theme.panelLow
            border.color: Theme.line
            Column {
                anchors.fill: parent
                anchors.margins: Theme.px(14)
                spacing: Theme.px(8)
                Text { width: parent.width; elide: Text.ElideRight; text: Usb.checking ? "Checking the download… " + Math.round(Usb.checkProgress * 100) + "%" : Usb.status + (Usb.busy && Usb.progress > 0 ? "  " + Math.round(Usb.progress * 100) + "%" : ""); color: Usb.failed && !Usb.checking ? Theme.danger : Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(17); font.weight: Font.DemiBold }
                Rectangle {
                    width: parent.width; height: Theme.px(6); radius: 1; color: Theme.line
                    Rectangle { width: parent.width * (Usb.checking ? Usb.checkProgress : Usb.progress); height: parent.height; color: Theme.accent }
                }
                Text { width: parent.width; elide: Text.ElideRight; text: Usb.log; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
            }
        }
    }

    // ---------------------------------------------------------- Windows: Rufus's questions
    Rectangle {
        id: winDialog
        visible: false
        anchors.fill: parent
        z: 10
        color: Qt.rgba(0.03, 0.035, 0.04, 0.88)
        property string iso: ""
        property int row: 0
        property var opts: ({ bypass: true, noOnline: true, useUser: false, user: Usb.defaultUser(), regional: true, noData: true, noBitlocker: false })
        readonly property var rows: [
            { key: "bypass", label: "Remove requirement for 4GB+ RAM, Secure Boot and TPM 2.0" },
            { key: "noOnline", label: "Remove requirement for an online Microsoft account" },
            { key: "useUser", label: "Create a local account with username: " + opts.user, user: true },
            { key: "regional", label: "Set regional options to the same values as this device's" },
            { key: "noData", label: "Disable data collection (Skip privacy questions)" },
            { key: "noBitlocker", label: "Disable BitLocker automatic device encryption" },
            { key: "ok", label: "OK", button: true },
            { key: "cancel", label: "Cancel", button: true }
        ]
        MouseArea { anchors.fill: parent }
        Rectangle {
            anchors.horizontalCenter: parent.horizontalCenter
            y: Theme.px(40)
            width: Math.min(parent.width - Theme.px(80), Theme.px(860))
            height: dlgCol.implicitHeight + Theme.px(48)
            radius: Theme.radius + 1
            color: Theme.panel
            border.color: Theme.accent
            Column {
                id: dlgCol
                anchors.left: parent.left; anchors.right: parent.right; anchors.top: parent.top
                anchors.margins: Theme.px(24)
                spacing: Theme.px(8)
                Text { text: "CUSTOMIZE WINDOWS INSTALLATION?"; color: Theme.accent; font.family: Theme.display; font.pixelSize: Theme.fs(20); font.weight: Font.Bold; font.letterSpacing: Theme.px(2) }
                Text { width: parent.width; wrapMode: Text.WordWrap; text: System.fileName(winDialog.iso) + " is a Windows installer. Huginn makes it the way Rufus does. Pick what Windows Setup should skip:"; color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(16) }
                Item { width: 1; height: Theme.px(4) }
                Repeater {
                    model: winDialog.rows
                    Rectangle {
                        readonly property bool on: winDialog.row === index
                        readonly property bool checked: modelData.button ? false : !!winDialog.opts[modelData.key]
                        visible: !modelData.button
                        width: dlgCol.width
                        height: visible ? Theme.px(50) : 0
                        radius: Theme.radius
                        color: on ? Theme.focusFill : "transparent"
                        border.width: on ? Theme.focusWidth : 0
                        border.color: Theme.accent
                        Accessible.role: Accessible.CheckBox
                        Accessible.name: modelData.label
                        Accessible.checked: checked
                        Rectangle {
                            id: box
                            anchors.left: parent.left; anchors.leftMargin: Theme.px(12)
                            anchors.verticalCenter: parent.verticalCenter
                            width: Theme.px(24); height: width; radius: 2
                            color: parent.checked ? Theme.accent : "transparent"
                            border.width: 2; border.color: parent.checked ? Theme.accent : Theme.lineMuted
                            Icon { anchors.centerIn: parent; visible: parent.parent.checked; name: "check"; size: Theme.px(18); color: Theme.ground; lineWidth: 2.4 }
                        }
                        Text {
                            anchors.left: box.right; anchors.leftMargin: Theme.px(14)
                            anchors.right: parent.right; anchors.rightMargin: Theme.px(12)
                            anchors.verticalCenter: parent.verticalCenter
                            elide: Text.ElideRight
                            text: modelData.label + (modelData.user && parent.on ? "   (X to change the name)" : "")
                            color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(17)
                        }
                        TapHandler { onTapped: { winDialog.row = index; winDialog.toggle() } }
                    }
                }
                Row {
                    spacing: Theme.px(12)
                    topPadding: Theme.px(8)
                    HButton { text: "OK"; kind: "accent"; focused: winDialog.row === 6; onClicked: { winDialog.row = 6; winDialog.toggle() } }
                    HButton { text: "Cancel"; focused: winDialog.row === 7; onClicked: winDialog.visible = false }
                }
            }
        }
        function open(iso) { winDialog.iso = iso; row = 6; visible = true }
        function toggle() {
            const r = rows[row]
            if (r.key === "cancel") { visible = false; return }
            if (r.key === "ok") { visible = false; page.confirmWindows(iso, opts); return }
            const o = Object.assign({}, opts)
            o[r.key] = !o[r.key]
            opts = o
            if (r.user && o.useUser) editUser()
        }
        function editUser() {
            Ui.ask("Local account name", "Windows", opts.user, "OK", t => { const o = Object.assign({}, opts); o.user = t; o.useUser = true; opts = o })
        }
        function handleButton(b) {
            switch (b) {
            case "up": row = Math.max(0, row - 1); break
            case "down": row = Math.min(rows.length - 1, row + 1); break
            case "left": if (row === 7) row = 6; break
            case "right": if (row === 6) row = 7; break
            case "a": toggle(); break
            case "x": if (rows[row].user) editUser(); break
            case "b": visible = false; break
            }
            return true
        }
    }

    PromptBar {
        id: bar
        anchors.bottom: parent.bottom
        width: parent.width
        status: page.zone === "files" && files.selectedCount > 0 ? files.selectedCount + " picked · " + files.selectedSize : "Anything that erases a drive needs A held for 2 seconds"
        statusColor: page.zone === "files" && files.selectedCount > 0 ? Theme.accent : Theme.textMuted
        prompts: winDialog.visible ? [{ key: "a", label: "Change" }, { key: "x", label: "Name" }, { key: "b", label: "Cancel" }]
            : Usb.busy || Usb.checking ? [{ key: "b", label: Usb.checking ? "Stop checking" : "Stop" }]
            : page.zone === "files"
              ? [{ key: "a", label: "Open / pick" }, { key: "x", label: "Pick" }, { key: "right", label: "Drive actions" }, { key: "y", label: page.browsingAll ? "Downloads" : "Browse all" }, { key: "b", label: "Back" }]
              : [{ key: "a", label: "Do it" }, { key: "left", label: "Files" }, { key: "lbrb", label: "Drives" }, { key: "b", label: "Files" }]
        onPrompt: k => page.handleButton(k === "lbrb" ? "rb" : k)
    }

    // ---------------------------------------------------------- behavior
    function toggleBrowse() {
        browsingAll = !browsingAll
        files.clearSelection()
        files.setUrl(browsingAll ? System.homeUrl() : System.downloadsUrl())
        pane.currentIndex = 0
    }
    function openFile(i) {
        if (i < 0 || i >= files.count) return
        if (files.isDirAt(i)) { files.setUrl(files.urlAt(i)); pane.currentIndex = 0; return }
        files.toggleSelected(i)
    }
    // The files to use: everything picked, or the highlighted file.
    function chosen() {
        if (files.selectedCount > 0) return files.selectedUrls().map(u => u.toString())
        if (pendingImage) return [pendingImage]
        const i = pane.currentIndex
        if (i >= 0 && i < files.count && !files.isDirAt(i)) return [files.urlAt(i).toString()]
        return []
    }

    property var pendingWrite: null
    Connections {
        target: Usb
        function onChecked(ok, message) {
            const p = page.pendingWrite
            page.pendingWrite = null
            if (!ok) { Ui.say(message, true); return }
            if (p) p.ask(message + " ")
        }
        function onFinished(ok, message) { Ui.say(message, !ok); Usb.refresh() }
    }
    Connections {
        target: FileOps
        function onFinished(ok, message) { Usb.refresh() }
    }

    function power() {
        const bat = System.battery()
        return bat.present && !bat.charging && bat.percent < 25
            ? "\n\nBattery is at " + bat.percent + "%. If it dies partway, the drive won't work until you start over. Plugging in first is safer." : ""
    }
    function needRaw() {
        if (Usb.rawReady) return false
        Ui.say(System.gameMode ? "Do the one-time setup first, in Desktop Mode." : "Do the one-time setup first: it's at the top of the list.", true)
        return true
    }

    function confirmWindows(iso, o) {
        const d = drive
        if (!d) return
        Ui.sure("Erase this drive?", "Everything on " + d.title + " will be erased and replaced by a Windows installer made from " + System.fileName(iso) + "." + power(),
                "Erase and make it", () => Usb.writeWindows(d.id, iso, { bypass: o.bypass, noOnline: o.noOnline, user: o.useUser ? o.user : "",
                                                                         regional: o.regional, noData: o.noData, noBitlocker: o.noBitlocker }), true, true)
    }

    function act(id) {
        const d = drive
        if (id === "setup") {
            if (System.gameMode) Ui.say("Switch to Desktop Mode, open Huginn there and press One-time setup.")
            else Usb.setupRule()
            return
        }
        if (!d) return
        const name = d.title
        switch (id) {
        case "write": {
            if (needRaw()) return
            const list = chosen()
            if (list.length !== 1) { Ui.say(list.length ? "Pick just one image to write." : "Pick an image on the left first (ISO, IMG, or a compressed .img).", true); return }
            const img = list[0]
            const info = Usb.imageInfo(img)
            if (info.windows) { winDialog.open(img); return }
            // Check the download first when it came with a checksum, so a broken download never makes a broken stick.
            const sum = Usb.checksumFor(img)
            const ask = note => Ui.sure("Erase this drive?", note + "Everything on " + name + " will be replaced by " + System.fileName(img) + "." + power(),
                                        "Erase and write", () => Usb.writeImage(d.id, img), true, true)
            if (sum.found) {
                pendingWrite = { ask: ask }
                Usb.verifyImage(img, sum.expected)
            } else {
                ask(info.compressed ? "It's compressed; Huginn unpacks it while writing.\n\n"
                                    : "There's no checksum file next to it (like " + System.fileName(img) + ".sha256), so Huginn can't check the download first.\n\n")
            }
            break
        }
        case "copy": {
            const list = chosen()
            if (list.length === 0) { Ui.say("Pick files on the left first: X picks, A opens folders.", true); return }
            if (!d.mounted || !d.dataUrl) { Ui.say("This drive isn't ready for files. Format it as a normal drive or install Ventoy first.", true); return }
            FileOps.copy(list, d.dataUrl)
            files.clearSelection()
            break
        }
        case "steamos":
            if (needRaw()) return
            Ui.sure("Make a SteamOS recovery drive?", "Huginn downloads Valve's SteamOS recovery image (about 3 GB, kept for next time) and writes it to " + name
                    + ". Everything on the drive is erased. Boot from it and choose Re-image to reinstall SteamOS." + power(),
                    "Erase and make it", () => Usb.writeSteamOS(d.id), true, true)
            break
        case "update":
            if (needRaw()) return
            Ui.sure("Update Ventoy?", "Huginn downloads the newest Ventoy and updates the boot files on " + name + ". Your files stay where they are." + power(),
                    "Update", () => Usb.updateVentoy(d.id), false, false)
            break
        case "install":
            if (needRaw()) return
            Ui.sure("Erase this drive?", "Everything on " + name + " will be erased and replaced by Ventoy (latest release, Secure Boot support "
                    + (Settings.ventoySecureBoot ? "on" : "off") + "). Then copy ISOs onto it and pick one at boot." + power(),
                    "Erase and install", () => Usb.installVentoy(d.id, Settings.ventoySecureBoot), true, true)
            break
        case "format":
            Ui.sure("Format this drive?", "Everything on " + name + " will be erased. It becomes a normal exFAT drive that works everywhere." + power(),
                    "Erase and format", () => Usb.formatDrive(d.id), true, true)
            break
        case "eject":
            Usb.eject(d.id)
            break
        }
    }

    function handleButton(b) {
        if (winDialog.visible) return winDialog.handleButton(b)
        if (Usb.checking) {
            if (b === "b") { pendingWrite = null; Usb.cancelCheck() }
            return true
        }
        if (Usb.busy) {
            if (b === "b")
                Ui.sure("Stop now?", "The drive is only partly written. If you stop, it won't work until you write or format it again.",
                        "Stop anyway", () => Usb.cancel(), true, true)
            return true
        }
        if (b === "lb" || b === "rb") {
            if (Usb.drives.length > 1) driveIndex = (driveIndex + (b === "rb" ? 1 : Usb.drives.length - 1)) % Usb.drives.length
            return true
        }
        if (zone === "files") {
            switch (b) {
            case "up": pane.move(-1); break
            case "down": pane.move(1); break
            case "lt": pane.page(-1); break
            case "rt": pane.page(1); break
            case "a": openFile(pane.currentIndex); break
            case "x": if (pane.currentIndex >= 0 && !files.isDirAt(pane.currentIndex)) { files.toggleSelected(pane.currentIndex); pane.move(1) } break
            case "y": toggleBrowse(); break
            case "right": if (actions.length) { zone = "actions"; actionIndex = Math.min(actionIndex, actions.length - 1) } break
            case "b":
                if (files.selectedCount > 0) files.clearSelection()
                else if (browsingAll && files.canGoUp() && files.url.toString() !== System.homeUrl().toString()) files.up()
                else if (!browsingAll && files.url.toString() !== System.downloadsUrl().toString()) files.setUrl(System.downloadsUrl())
                else Ui.closePage()
                break
            }
            return true
        }
        switch (b) {
        case "up": actionIndex = Math.max(0, actionIndex - 1); break
        case "down": actionIndex = Math.min(actions.length - 1, actionIndex + 1); break
        case "a": if (actions[actionIndex]) act(actions[actionIndex].id); break
        case "left": case "b": zone = "files"; break
        }
        return true
    }
}

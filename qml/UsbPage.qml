import QtQuick
import Huginn

// Bootable USB drives: Ventoy install and update, copying ISOs onto a Ventoy drive, writing one image.
Rectangle {
    id: page
    color: Theme.ground
    property int driveIndex: 0
    property int focusIndex: 0
    property string pendingImage: ""
    property var picked: ({})
    readonly property var drive: Usb.drives.length > 0 ? Usb.drives[Math.min(driveIndex, Usb.drives.length - 1)] : null

    function setArgs(a) { if (a.iso) pendingImage = a.iso }
    Component.onCompleted: Usb.refresh()

    // Everything the controller can land on, top to bottom.
    readonly property var items: {
        const out = []
        for (let i = 0; i < Usb.drives.length; ++i) out.push({ kind: "drive", index: i })
        if (!Usb.rawReady) out.push({ kind: "setup" })
        if (drive) {
            if (drive.ventoy) out.push({ kind: "update" })
            out.push({ kind: "install" })
            out.push({ kind: "write" })
            out.push({ kind: "format" })
            out.push({ kind: "eject" })
            if (drive.ventoy) for (let j = 0; j < Usb.downloadIsos.length; ++j) out.push({ kind: "iso", index: j })
            if (drive.ventoy && Usb.downloadIsos.length > 0) out.push({ kind: "copy" })
        }
        return out
    }
    function isFocused(kind, index) {
        const it = items[focusIndex]
        return !!it && it.kind === kind && (index === undefined || it.index === index)
    }

    PageHeader {
        id: header
        width: parent.width
        title: "USB Tools"
        subtitle: "Bootable drives and Ventoy"
    }
    Divider { id: divider; anchors.top: header.bottom; x: Theme.px(28); width: parent.width - Theme.px(56) }

    // ---------------------------------------------------------- left column
    Column {
        id: left
        anchors.top: divider.bottom
        anchors.topMargin: Theme.px(14)
        anchors.left: parent.left
        anchors.leftMargin: Theme.px(28)
        width: Theme.px(360)
        spacing: Theme.px(12)

        Repeater {
            model: Usb.drives
            Rectangle {
                width: left.width
                height: driveCol.implicitHeight + Theme.px(32)
                radius: Theme.radius + 1
                color: index === page.driveIndex ? Theme.panel : Theme.panelLow
                border.width: page.isFocused("drive", index) ? Theme.focusWidth : 1
                border.color: page.isFocused("drive", index) ? Theme.text : index === page.driveIndex ? Theme.accent : Theme.line
                Column {
                    id: driveCol
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.top: parent.top
                    anchors.margins: Theme.px(16)
                    spacing: Theme.px(8)
                    Row {
                        spacing: Theme.px(12)
                        Icon { name: "usb"; size: Theme.px(30); color: Theme.accent; anchors.verticalCenter: parent.verticalCenter }
                        Column {
                            Text { text: modelData.title; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.px(19); font.weight: Font.DemiBold }
                            Text { text: modelData.model; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.px(14) }
                        }
                    }
                    Rectangle {
                        visible: modelData.ventoy
                        width: badge.implicitWidth + Theme.px(20); height: Theme.px(26)
                        radius: Theme.radius; color: "transparent"; border.color: Theme.lineMuted
                        Text { id: badge; anchors.centerIn: parent; text: "Ventoy " + modelData.ventoyVersion + (modelData.secureBoot ? " · Secure Boot on" : ""); color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.px(14); font.weight: Font.DemiBold }
                    }
                    Rectangle {
                        visible: modelData.usedFraction >= 0
                        width: parent.width; height: Theme.px(6); radius: 1; color: Theme.line
                        Rectangle { width: parent.width * Math.max(0, modelData.usedFraction); height: parent.height; color: Theme.accent }
                    }
                    Text { text: modelData.usage; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.px(14) }
                }
                TapHandler { onTapped: { page.driveIndex = index; page.focusIndex = index } }
            }
        }
        Rectangle {
            visible: Usb.drives.length === 0
            width: left.width
            height: Theme.px(120)
            radius: Theme.radius + 1
            color: Theme.panelLow
            border.color: Theme.line
            Text {
                anchors.fill: parent
                anchors.margins: Theme.px(16)
                wrapMode: Text.WordWrap
                verticalAlignment: Text.AlignVCenter
                text: "Plug in a USB drive. Your microSD card and internal drive never show up here, so they can't be erased by mistake."
                color: Theme.textMuted
                font.family: Theme.body
                font.pixelSize: Theme.px(16)
            }
        }
    }

    Column {
        anchors.left: left.left
        anchors.bottom: bar.top
        anchors.bottomMargin: Theme.px(14)
        width: left.width
        spacing: Theme.px(10)
        visible: page.drive !== null
        HButton { width: parent.width; visible: page.drive && page.drive.ventoy; text: "Update Ventoy (keeps your ISOs)"; focused: page.isFocused("update"); onClicked: page.act("update") }
        HButton { width: parent.width; text: page.drive && page.drive.ventoy ? "Reinstall Ventoy (erases drive)" : "Install Ventoy (erases drive)"; kind: "danger"; focused: page.isFocused("install"); onClicked: page.act("install") }
        HButton { width: parent.width; text: "Write one image (erases drive)"; kind: "danger"; focused: page.isFocused("write"); onClicked: page.act("write") }
        HButton { width: parent.width; text: "Format as a normal drive"; focused: page.isFocused("format"); onClicked: page.act("format") }
        HButton { width: parent.width; text: "Eject"; focused: page.isFocused("eject"); onClicked: page.act("eject") }
    }

    // ---------------------------------------------------------- right column
    Column {
        anchors.top: left.top
        anchors.left: left.right
        anchors.leftMargin: Theme.px(16)
        anchors.right: parent.right
        anchors.rightMargin: Theme.px(28)
        spacing: Theme.px(12)

        Rectangle {
            visible: !Usb.rawReady
            width: parent.width
            height: setupCol.implicitHeight + Theme.px(36)
            radius: Theme.radius + 1
            color: Theme.panel
            border.width: page.isFocused("setup") ? Theme.focusWidth : 1
            border.color: page.isFocused("setup") ? Theme.text : Theme.accent
            Column {
                id: setupCol
                anchors.left: parent.left; anchors.right: parent.right; anchors.top: parent.top
                anchors.margins: Theme.px(18)
                spacing: Theme.px(8)
                Text { text: "ONE-TIME SETUP"; color: Theme.accent; font.family: Theme.display; font.pixelSize: Theme.px(15); font.weight: Font.DemiBold; font.letterSpacing: 2 }
                Text {
                    width: parent.width; wrapMode: Text.WordWrap
                    text: "Installing Ventoy and writing images needs direct access to the USB drive. Do this once in Desktop Mode: it asks for your password one time and adds a rule that lets Huginn open USB drives only. After that it works in Game Mode with no prompts. You can remove it in Settings."
                    color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.px(16); lineHeight: 1.1
                }
                Text {
                    visible: System.gameMode
                    width: parent.width; wrapMode: Text.WordWrap
                    text: "You're in Game Mode right now. Switch to Desktop Mode, open Huginn there and press Set up."
                    color: Theme.accent; font.family: Theme.body; font.pixelSize: Theme.px(15)
                }
                HButton { text: "Set up"; kind: "accent"; enabled: !System.gameMode; onClicked: page.act("setup") }
            }
        }

        Rectangle {
            visible: page.drive !== null && page.drive.ventoy
            width: parent.width
            height: Theme.px(400)
            radius: Theme.radius + 1
            color: Theme.panel
            border.color: Theme.line
            clip: true
            Text {
                id: onDriveTitle
                x: Theme.px(18); y: Theme.px(14)
                text: "ON THIS VENTOY DRIVE"
                color: Theme.text; font.family: Theme.display; font.pixelSize: Theme.px(16); font.weight: Font.DemiBold; font.letterSpacing: 1.5
            }
            Text {
                anchors.right: parent.right; anchors.rightMargin: Theme.px(18); y: Theme.px(16)
                text: "Each file shows up in Ventoy's boot menu"
                color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.px(14)
            }
            ListView {
                id: onDrive
                anchors.top: onDriveTitle.bottom; anchors.topMargin: Theme.px(10)
                anchors.left: parent.left; anchors.right: parent.right; anchors.margins: Theme.px(10)
                height: Theme.px(170)
                clip: true
                spacing: Theme.px(4)
                model: page.drive ? page.drive.images : []
                delegate: Row {
                    height: Theme.px(48); spacing: Theme.px(14); leftPadding: Theme.px(10)
                    Icon { name: "disc"; anchors.verticalCenter: parent.verticalCenter }
                    Text { anchors.verticalCenter: parent.verticalCenter; width: onDrive.width - Theme.px(200); elide: Text.ElideMiddle; text: modelData.name; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.px(18) }
                    Text { anchors.verticalCenter: parent.verticalCenter; text: modelData.size; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.px(15) }
                }
                Text {
                    anchors.centerIn: parent
                    visible: onDrive.count === 0
                    text: page.drive && page.drive.mounted ? "No images on the drive yet" : "Opening the drive…"
                    color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.px(16)
                }
            }
            Rectangle {
                anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom
                anchors.margins: Theme.px(16)
                height: Theme.px(180)
                radius: Theme.radius
                color: "transparent"
                border.color: Theme.lineMuted
                Column {
                    anchors.fill: parent
                    anchors.margins: Theme.px(14)
                    spacing: Theme.px(6)
                    Text {
                        text: Usb.downloadIsos.length > 0 ? "In your Downloads folder: press X to pick, then Copy to drive" : "No ISO files in your Downloads folder"
                        color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.px(16); font.weight: Font.DemiBold
                    }
                    Repeater {
                        model: Usb.downloadIsos
                        Rectangle {
                            width: parent.width; height: Theme.px(36); radius: Theme.radius
                            color: page.isFocused("iso", index) ? Theme.focusFill : "transparent"
                            border.width: page.isFocused("iso", index) ? Theme.focusWidth : 0; border.color: Theme.accent
                            Row {
                                anchors.verticalCenter: parent.verticalCenter; x: Theme.px(8); spacing: Theme.px(12)
                                Rectangle { anchors.verticalCenter: parent.verticalCenter; width: Theme.px(11); height: width; rotation: 45; color: page.picked[modelData.url] ? Theme.accent : "transparent"; border.width: page.picked[modelData.url] ? 0 : 1; border.color: Theme.lineMuted }
                                Text { text: modelData.name; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.px(16) }
                                Text { text: modelData.size; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.px(14) }
                            }
                            TapHandler { onTapped: page.togglePick(modelData.url) }
                        }
                    }
                    HButton {
                        visible: Usb.downloadIsos.length > 0
                        implicitHeight: Theme.px(44)
                        text: "Copy to drive"
                        kind: "accent"
                        focused: page.isFocused("copy")
                        onClicked: page.act("copy")
                    }
                }
            }
        }

        Rectangle {
            visible: Usb.busy || Usb.log.length > 0
            width: parent.width
            height: Theme.px(110)
            radius: Theme.radius + 1
            color: Theme.panelLow
            border.color: Theme.line
            Column {
                anchors.fill: parent
                anchors.margins: Theme.px(16)
                spacing: Theme.px(10)
                Text { width: parent.width; elide: Text.ElideRight; text: Usb.status; color: Usb.failed ? Theme.danger : Theme.text; font.family: Theme.body; font.pixelSize: Theme.px(18); font.weight: Font.DemiBold }
                Rectangle {
                    width: parent.width; height: Theme.px(6); radius: 1; color: Theme.line
                    Rectangle { width: parent.width * Usb.progress; height: parent.height; color: Theme.accent }
                }
                Text { width: parent.width; elide: Text.ElideRight; text: Usb.log; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.px(14) }
            }
        }
    }

    PromptBar {
        id: bar
        anchors.bottom: parent.bottom
        width: parent.width
        status: "Anything that erases a drive needs A held for 2 seconds"
        prompts: Usb.busy ? [{ key: "b", label: "Stop" }]
                          : [{ key: "a", label: "Open" }, { key: "x", label: "Pick ISO" }, { key: "b", label: "Back" }]
        onPrompt: k => page.handleButton(k)
    }

    function togglePick(url) {
        const p = Object.assign({}, picked)
        if (p[url]) delete p[url]; else p[url] = true
        picked = p
    }

    function act(kind) {
        const d = drive
        if (kind === "setup") { Usb.setupRule(); return }
        if (!d) return
        const name = d.title
        const needRaw = () => {
            if (Usb.rawReady) return false
            Ui.say("Do the one-time setup first (Desktop Mode).", true)
            return true
        }
        switch (kind) {
        case "update":
            if (needRaw()) return
            Ui.sure("Update Ventoy?", "Huginn downloads the newest Ventoy and updates the boot files on " + name + ". Your ISO files stay where they are.",
                    "Update", () => Usb.updateVentoy(d.id), false, false)
            break
        case "install":
            if (needRaw()) return
            Ui.sure("Erase this drive?", "Everything on " + name + " will be erased and replaced by Ventoy (latest release, Secure Boot support on).",
                    "Erase and install", () => Usb.installVentoy(d.id, Settings.ventoySecureBoot), true, true)
            break
        case "write": {
            if (needRaw()) return
            const img = pendingImage || Object.keys(picked)[0] || ""
            if (!img) { Ui.say("Pick an image with X first, or open one from the browser."); return }
            Ui.sure("Erase this drive?", "Everything on " + name + " will be replaced by " + System.fileName(img) + ".",
                    "Erase and write", () => Usb.writeImage(d.id, img), true, true)
            break
        }
        case "format":
            Ui.sure("Format this drive?", "Everything on " + name + " will be erased. It becomes a normal exFAT drive that works everywhere.",
                    "Erase and format", () => Usb.formatDrive(d.id), true, true)
            break
        case "eject":
            Usb.eject(d.id)
            break
        case "copy": {
            const urls = Object.keys(picked)
            if (urls.length === 0) { Ui.say("Press X on the ISOs you want first."); return }
            if (!d.mounted) { Ui.say("The drive isn't open yet. Try again in a moment.", true); return }
            FileOps.copy(urls, d.dataUrl)
            picked = ({})
            break
        }
        }
    }

    Connections {
        target: Usb
        function onFinished(ok, message) { Ui.say(message, !ok); Usb.refresh() }
    }
    Connections {
        target: FileOps
        function onFinished(ok, message) { Usb.refresh() }
    }

    function handleButton(b) {
        if (Usb.busy) { if (b === "b") Usb.cancel(); return true }
        const it = items[focusIndex]
        switch (b) {
        case "up": case "left": focusIndex = Math.max(0, focusIndex - 1); break
        case "down": case "right": focusIndex = Math.min(items.length - 1, focusIndex + 1); break
        case "a":
            if (!it) break
            if (it.kind === "drive") driveIndex = it.index
            else if (it.kind === "iso") togglePick(Usb.downloadIsos[it.index].url)
            else act(it.kind)
            break
        case "x": if (it && it.kind === "iso") togglePick(Usb.downloadIsos[it.index].url); break
        case "b": Ui.closePage(); break
        }
        if (items[focusIndex] && items[focusIndex].kind === "drive") driveIndex = items[focusIndex].index
        return true
    }
}

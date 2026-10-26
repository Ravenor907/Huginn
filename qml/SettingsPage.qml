import QtQuick
import Huginn

Rectangle {
    id: page
    color: Theme.ground
    property int section: 0
    property int row: 0
    property bool inSection: false
    function setArgs(a) {}

    readonly property var sections: ["General", "Safety", "Updates", "About"]

    // Each row: { kind: "segmented"|"toggle"|"accent"|"button", title, note, ... }
    readonly property var rows: {
        switch (section) {
        case 0: return [
            { kind: "segmented", key: "layout", title: "Layout", note: "Auto picks the TV layout when docked to a big screen",
              options: [{ value: "auto", label: "Auto" }, { value: "handheld", label: "Handheld" }, { value: "tv", label: "TV" }] },
            { kind: "toggle", key: "showHidden", title: "Show hidden files", note: "Dot files and folders like .steam" },
            { kind: "accent", key: "accent", title: "Accent", note: "Highlight color for focus and selected items" }
        ]
        case 1: return [
            { kind: "toggle", key: "protectLibrary", title: "Protect game library", note: "Blocks deleting, moving or renaming game installs from the browser" },
            { kind: "toggle", key: "confirmDelete", title: "Ask before moving to trash", note: "Trash can be undone either way" },
            { kind: "toggle", key: "ventoySecureBoot", title: "Ventoy with Secure Boot support", note: "On: the first boot asks you to enroll Ventoy's key once" },
            { kind: "button", key: "removeRule", title: "USB access rule", note: Usb.rawReady ? "Installed: Huginn can open USB drives without a password" : "Not installed: set it up from USB Tools in Desktop Mode",
              label: "Remove", enabled: Usb.rawReady && !System.gameMode }
        ]
        case 2: return [
            { kind: "segmented", key: "channel", title: "Update channel", note: "Testing gets new features first and can be rolled back",
              options: [{ value: "stable", label: "Stable" }, { value: "testing", label: "Testing" }] },
            { kind: "button", key: "check", title: Updater.status, note: "Huginn " + System.version + " · " + (Settings.channel === "testing" ? "Testing" : "Stable") + " channel",
              label: Updater.available ? "Install " + Updater.latest : "Check now", enabled: !Updater.busy },
            { kind: "button", key: "rollback", title: "Go back to stable", note: "Installs the newest stable release over a test build",
              label: "Roll back", enabled: System.version.indexOf("Test") >= 0 && !Updater.busy }
        ]
        default: return [
            { kind: "info", title: "Huginn " + System.version, note: "A controller-first file explorer for SteamOS. Made by Ravenor907." },
            { kind: "info", title: "Drive tools", note: "Ventoy install and image writing come from Handheld Forge, so both apps share one tested installer." },
            { kind: "info", title: "License", note: "GPL-3.0. Fonts: Cinzel, Barlow and Noto Sans Runic under the SIL Open Font License." }
        ]
        }
    }

    PageHeader {
        id: header
        width: parent.width
        title: "Settings"
        Text { text: "Huginn " + System.version; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.px(16) }
    }
    Divider { id: divider; anchors.top: header.bottom; x: Theme.px(28); width: parent.width - Theme.px(56) }

    Column {
        id: nav
        anchors.top: divider.bottom
        anchors.topMargin: Theme.px(14)
        anchors.left: parent.left
        anchors.leftMargin: Theme.px(14)
        width: Theme.px(224)
        spacing: Theme.px(2)
        Repeater {
            model: page.sections
            Rectangle {
                readonly property bool on: index === page.section
                width: nav.width; height: Theme.px(44); radius: Theme.radius
                color: on ? (page.inSection ? "#1D1C19" : Theme.focusFill) : "transparent"
                border.width: on && !page.inSection ? 1 : 0
                border.color: Theme.accent
                Row {
                    anchors.left: parent.left; anchors.leftMargin: Theme.px(12); anchors.verticalCenter: parent.verticalCenter
                    spacing: Theme.px(14)
                    Rectangle { anchors.verticalCenter: parent.verticalCenter; width: Theme.px(7); height: width; rotation: 45; color: parent.parent.on ? Theme.accent : "transparent"; border.width: parent.parent.on ? 0 : 1; border.color: Theme.ornament }
                    Text { text: modelData; color: parent.parent.on ? Theme.accent : Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.px(18); font.weight: parent.parent.on ? Font.DemiBold : Font.Normal }
                }
                TapHandler { onTapped: { page.section = index; page.row = 0; page.inSection = false } }
            }
        }
    }

    Column {
        anchors.top: nav.top
        anchors.left: nav.right
        anchors.leftMargin: Theme.px(4)
        anchors.right: parent.right
        anchors.rightMargin: Theme.px(28)
        spacing: Theme.px(10)
        Repeater {
            model: page.rows
            Rectangle {
                readonly property bool focused: page.inSection && page.row === index
                width: parent.width
                height: Math.max(Theme.px(72), texts.implicitHeight + Theme.px(24))
                radius: Theme.radius
                color: focused ? Theme.focusFill : Theme.panel
                border.color: focused ? Theme.accent : Theme.line
                Column {
                    id: texts
                    anchors.left: parent.left
                    anchors.leftMargin: Theme.px(20)
                    anchors.right: control.left
                    anchors.rightMargin: Theme.px(20)
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: Theme.px(2)
                    Text { width: parent.width; wrapMode: Text.WordWrap; text: modelData.title; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.px(19); font.weight: Font.DemiBold }
                    Text { width: parent.width; wrapMode: Text.WordWrap; text: modelData.note; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.px(14) }
                }
                Item {
                    id: control
                    anchors.right: parent.right
                    anchors.rightMargin: Theme.px(20)
                    anchors.verticalCenter: parent.verticalCenter
                    width: modelData.kind === "segmented" ? segCtl.implicitWidth
                         : modelData.kind === "toggle" ? toggleCtl.implicitWidth
                         : modelData.kind === "accent" ? accentCtl.implicitWidth
                         : modelData.kind === "button" ? buttonCtl.implicitWidth : 0
                    height: Theme.px(44)
                    Segmented {
                        id: segCtl
                        anchors.right: parent.right
                        anchors.verticalCenter: parent.verticalCenter
                        visible: modelData.kind === "segmented"
                        options: modelData.options || []
                        value: modelData.kind === "segmented" ? Settings[modelData.key] : ""
                        onPicked: v => Settings[modelData.key] = v
                    }
                    ToggleSwitch {
                        id: toggleCtl
                        anchors.right: parent.right
                        anchors.verticalCenter: parent.verticalCenter
                        visible: modelData.kind === "toggle"
                        checked: modelData.kind === "toggle" ? Settings[modelData.key] : false
                        onToggled: Settings[modelData.key] = !Settings[modelData.key]
                    }
                    Row {
                        id: accentCtl
                        anchors.right: parent.right
                        anchors.verticalCenter: parent.verticalCenter
                        visible: modelData.kind === "accent"
                        spacing: Theme.px(10)
                        Repeater {
                            model: [{ v: "gold", n: "Gold" }, { v: "iron", n: "Iron" }, { v: "copper", n: "Copper" }]
                            Rectangle {
                                width: Theme.px(44); height: width; radius: Theme.radius
                                color: Theme.accents[modelData.v]
                                border.width: Settings.accent === modelData.v ? 2 : 1
                                border.color: Settings.accent === modelData.v ? Theme.text : Theme.lineMuted
                                TapHandler { onTapped: Settings.accent = modelData.v }
                            }
                        }
                    }
                    HButton {
                        id: buttonCtl
                        anchors.right: parent.right
                        anchors.verticalCenter: parent.verticalCenter
                        visible: modelData.kind === "button"
                        implicitHeight: Theme.px(44)
                        text: modelData.label || ""
                        enabled: modelData.enabled !== false
                        onClicked: page.press(modelData.key)
                    }
                }
                TapHandler { onTapped: { page.inSection = true; page.row = index } }
            }
        }
    }

    PromptBar {
        id: bar
        anchors.bottom: parent.bottom
        width: parent.width
        status: "Changes save as you go"
        prompts: [{ key: "a", label: "Change" }, { key: "b", label: "Back" }, { key: "lbrb", label: "Sections" }]
        onPrompt: k => page.handleButton(k === "lbrb" ? "rb" : k)
    }

    function press(key) {
        if (key === "removeRule") Usb.removeRule()
        else if (key === "check") Updater.available ? Updater.install() : Updater.check()
        else if (key === "rollback") Updater.rollback()
    }

    function change(dir) {
        const r = rows[row]
        if (!r) return
        if (r.kind === "toggle") Settings[r.key] = !Settings[r.key]
        else if (r.kind === "segmented") {
            const opts = r.options
            let i = opts.findIndex(o => o.value === Settings[r.key])
            i = dir === 0 ? (i + 1) % opts.length : Math.max(0, Math.min(opts.length - 1, i + dir))
            Settings[r.key] = opts[i].value
        } else if (r.kind === "accent") {
            const order = ["gold", "iron", "copper"]
            let i = order.indexOf(Settings.accent)
            i = dir === 0 ? (i + 1) % 3 : Math.max(0, Math.min(2, i + dir))
            Settings.accent = order[i]
        } else if (r.kind === "button" && dir === 0 && r.enabled !== false) press(r.key)
    }

    function handleButton(b) {
        switch (b) {
        case "lb": section = (section + sections.length - 1) % sections.length; row = 0; return true
        case "rb": section = (section + 1) % sections.length; row = 0; return true
        }
        if (!inSection) {
            switch (b) {
            case "up": section = Math.max(0, section - 1); break
            case "down": section = Math.min(sections.length - 1, section + 1); break
            case "a": case "right": inSection = true; row = 0; break
            case "b": Ui.closePage(); break
            }
            return true
        }
        switch (b) {
        case "up": row = Math.max(0, row - 1); break
        case "down": row = Math.min(rows.length - 1, row + 1); break
        case "a": change(0); break
        case "left":
            if (rows[row] && (rows[row].kind === "segmented" || rows[row].kind === "accent")) change(-1)
            else inSection = false
            break
        case "right": change(1); break
        case "b": inSection = false; break
        }
        return true
    }
}

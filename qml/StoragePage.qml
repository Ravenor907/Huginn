import QtQuick
import Huginn

// What's using space on each drive, biggest first, with the safe cleanups one press away.
Rectangle {
    id: page
    color: Theme.ground
    property int driveIndex: 0
    property int row: 0
    readonly property var drive: Storage.drives.length > 0 ? Storage.drives[Math.min(driveIndex, Storage.drives.length - 1)] : null
    readonly property var cats: drive ? drive.categories : []

    function setArgs(a) {}
    Component.onCompleted: Storage.refresh()

    PageHeader {
        id: header
        width: parent.width
        title: "Storage"
        subtitle: Storage.measuring ? "Measuring… big libraries take a moment" : "What's using space, biggest first"
    }
    Divider { id: divider; anchors.top: header.bottom; x: Theme.px(28); width: parent.width - Theme.px(56) }

    Column {
        id: drives
        anchors.top: divider.bottom
        anchors.topMargin: Theme.px(14)
        anchors.left: parent.left
        anchors.leftMargin: Theme.px(28)
        width: Theme.px(320)
        spacing: Theme.px(10)
        Repeater {
            model: Storage.drives
            Rectangle {
                readonly property bool on: index === page.driveIndex
                width: drives.width
                height: Theme.px(108)
                radius: Theme.radius + 1
                color: on ? Theme.panel : Theme.panelLow
                border.width: 1
                border.color: on ? Theme.accent : Theme.line
                Column {
                    anchors.fill: parent
                    anchors.margins: Theme.px(16)
                    spacing: Theme.px(8)
                    Text { text: modelData.name; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(20); font.weight: Font.DemiBold }
                    Rectangle {
                        width: parent.width; height: Theme.px(8); radius: 1; color: Theme.line
                        Rectangle { width: parent.width * modelData.usedFraction; height: parent.height; color: modelData.usedFraction > 0.9 ? Theme.danger : Theme.accent }
                    }
                    Text { text: modelData.freeText + " free of " + modelData.totalText; color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(16) }
                }
                TapHandler { onTapped: { page.driveIndex = index; page.row = 0 } }
            }
        }
    }

    Column {
        anchors.top: drives.top
        anchors.left: drives.right
        anchors.leftMargin: Theme.px(16)
        anchors.right: parent.right
        anchors.rightMargin: Theme.px(28)
        spacing: Theme.px(8)
        Repeater {
            model: page.cats
            Rectangle {
                readonly property bool on: index === page.row
                readonly property real share: page.cats.length && page.cats[0].bytes > 0 ? modelData.bytes / page.cats[0].bytes : 0
                width: parent.width
                height: Theme.px(84)
                radius: Theme.radius
                color: on ? Theme.focusFill : Theme.panel
                border.width: on ? Theme.focusWidth : 1
                border.color: on ? Theme.accent : Theme.line
                Accessible.role: Accessible.ListItem
                Accessible.name: modelData.name + ", " + modelData.sizeText
                Column {
                    anchors.left: parent.left
                    anchors.leftMargin: Theme.px(18)
                    anchors.right: actionText.left
                    anchors.rightMargin: Theme.px(16)
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: Theme.px(6)
                    Row {
                        spacing: Theme.px(12)
                        Text { text: modelData.name; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(19); font.weight: Font.DemiBold }
                        Text { text: modelData.sizeText; color: Theme.accent; font.family: Theme.body; font.pixelSize: Theme.fs(19); font.weight: Font.DemiBold }
                    }
                    Rectangle {
                        width: parent.width; height: Theme.px(6); radius: 1; color: Theme.line
                        Rectangle { width: parent.width * share; height: parent.height; color: Theme.textMuted }
                    }
                    Text { visible: modelData.note.length > 0; text: modelData.note; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(14) }
                }
                Text {
                    id: actionText
                    anchors.right: parent.right
                    anchors.rightMargin: Theme.px(18)
                    anchors.verticalCenter: parent.verticalCenter
                    text: page.actionLabel(modelData)
                    color: Theme.textSoft
                    font.family: Theme.body
                    font.pixelSize: Theme.fs(16)
                }
                TapHandler { onTapped: { page.row = index; page.act() } }
            }
        }
        Text {
            visible: page.cats.length === 0
            text: Storage.measuring ? "Measuring…" : "Nothing to show yet"
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(17)
        }
    }

    PromptBar {
        id: bar
        anchors.bottom: parent.bottom
        width: parent.width
        status: "Clearing moves things to the trash first. Emptying the trash is for good."
        prompts: [{ key: "a", label: "Do it" }, { key: "lbrb", label: "Drives" }, { key: "x", label: "Measure again" }, { key: "b", label: "Back" }]
        onPrompt: k => page.handleButton(k === "lbrb" ? "rb" : k)
    }

    function actionLabel(c) {
        switch (c.action) {
        case "shader": return "Clear all ▸"
        case "trash": return "Empty ▸"
        case "games": return "Open Games ▸"
        default: return c.url ? "Open ▸" : ""
        }
    }
    function act() {
        const c = cats[row]
        if (!c) return
        switch (c.action) {
        case "shader":
            Ui.sure("Clear all shader caches?", "They move to the trash. Steam rebuilds each one the next time its game starts, which can stutter for a bit.",
                    "Clear " + c.sizeText, () => Storage.clearShaderCaches(), false, false)
            break
        case "trash":
            Ui.sure("Empty the trash?", "This frees " + c.sizeText + " for good. It can't be undone.", "Empty trash", () => Storage.emptyTrash(), true, true)
            break
        case "games":
            Ui.openPage("games", {})
            break
        default:
            if (c.url) Ui.main.openInBrowser(c.url)
        }
    }
    Connections {
        target: Storage
        function onFinished(ok, message) { Ui.say(message, !ok) }
    }

    function handleButton(b) {
        switch (b) {
        case "up": row = Math.max(0, row - 1); break
        case "down": row = Math.min(cats.length - 1, row + 1); break
        case "lb": case "left": driveIndex = Math.max(0, driveIndex - 1); row = 0; break
        case "rb": case "right": driveIndex = Math.min(Storage.drives.length - 1, driveIndex + 1); row = 0; break
        case "a": act(); break
        case "x": Storage.refresh(); break
        case "b": Ui.closePage(); break
        }
        return true
    }
}

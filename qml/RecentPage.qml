import QtQuick
import Huginn

// Files opened, copied or moved lately, newest first, from every folder.
Rectangle {
    id: page
    color: Theme.ground
    property int current: 0
    readonly property var files: Settings.recentFiles

    function setArgs(a) {}

    PageHeader {
        id: header
        width: parent.width
        title: "Recent files"
        subtitle: "Opened, copied or moved lately"
    }
    Divider { id: divider; anchors.top: header.bottom; x: Theme.px(28); width: parent.width - Theme.px(56) }

    ListView {
        id: list
        anchors.top: divider.bottom
        anchors.topMargin: Theme.px(12)
        anchors.left: parent.left
        anchors.leftMargin: Theme.px(28)
        anchors.right: parent.right
        anchors.rightMargin: Theme.px(28)
        anchors.bottom: bar.top
        anchors.bottomMargin: Theme.px(12)
        clip: true
        spacing: Theme.px(6)
        model: page.files
        currentIndex: page.current
        highlightMoveDuration: 0
        delegate: Rectangle {
            readonly property bool on: index === page.current
            readonly property string kind: modelData.isDir ? "folder" : System.kindOf(modelData.url)
            width: list.width
            height: Theme.rowHeight
            radius: Theme.radius
            color: on ? Theme.focusFill : Theme.panel
            border.width: on ? Theme.focusWidth : 1
            border.color: on ? Theme.accent : Theme.line
            Accessible.role: Accessible.ListItem
            Accessible.name: modelData.name + ", " + System.prettyPath(modelData.folder) + ", " + modelData.when
            Icon {
                id: icon
                anchors.left: parent.left
                anchors.leftMargin: Theme.px(16)
                anchors.verticalCenter: parent.verticalCenter
                name: ["folder", "image", "archive", "disc", "text", "video", "audio"].indexOf(parent.kind) >= 0 ? parent.kind : "file"
                size: Theme.px(24)
                color: parent.on ? Theme.accent : Theme.textMuted
            }
            Column {
                anchors.left: icon.right
                anchors.leftMargin: Theme.px(14)
                anchors.right: whenText.left
                anchors.rightMargin: Theme.px(14)
                anchors.verticalCenter: parent.verticalCenter
                Text { width: parent.width; elide: Text.ElideMiddle; text: modelData.name; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.px(18); font.weight: Font.Medium }
                Text { width: parent.width; elide: Text.ElideLeft; text: System.prettyPath(modelData.folder); color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.px(14) }
            }
            Text {
                id: whenText
                anchors.right: parent.right
                anchors.rightMargin: Theme.px(16)
                anchors.verticalCenter: parent.verticalCenter
                text: modelData.when
                color: Theme.textMuted
                font.family: Theme.body
                font.pixelSize: Theme.px(15)
            }
            TapHandler { onTapped: { page.current = index; page.open() } }
        }
    }
    Text {
        visible: page.files.length === 0
        anchors.centerIn: list
        width: list.width * 0.7
        horizontalAlignment: Text.AlignHCenter
        wrapMode: Text.WordWrap
        text: "Nothing yet. Files you open, copy or move show up here."
        color: Theme.textMuted
        font.family: Theme.body
        font.pixelSize: Theme.px(17)
    }

    PromptBar {
        id: bar
        anchors.bottom: parent.bottom
        width: parent.width
        prompts: [{ key: "a", label: "Open" }, { key: "x", label: "Show in folder" }, { key: "b", label: "Back" }]
        onPrompt: k => page.handleButton(k)
    }

    function open() {
        const f = files[current]
        if (!f) return
        const kind = f.isDir ? "folder" : System.kindOf(f.url)
        if (kind === "folder") Ui.main.openInBrowser(f.url)
        else if (kind === "image" || kind === "text") Ui.openPage("preview", { url: f.url, folder: f.folder })
        else if (kind === "archive") Ui.openPage("archive", { url: f.url, dest: f.folder })
        else if (kind === "disc") Ui.openPage("usb", { iso: f.url })
        else Ui.main.openInBrowser(f.folder)
    }

    function handleButton(b) {
        switch (b) {
        case "up": current = Math.max(0, current - 1); break
        case "down": current = Math.min(files.length - 1, current + 1); break
        case "lt": current = Math.max(0, current - 8); break
        case "rt": current = Math.min(files.length - 1, current + 8); break
        case "a": open(); break
        case "x": if (files[current]) Ui.main.openInBrowser(files[current].folder); break
        case "b": Ui.closePage(); break
        }
        return true
    }
}

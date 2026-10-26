import QtQuick
import Huginn

// Looks inside zip, 7z and tar files without extracting, then extracts all or just what's picked.
Rectangle {
    id: page
    color: Theme.ground
    property url destination
    property string zone: "list"          // "list" or "buttons"
    property int button: 0

    ArchiveModel { id: arc }

    function setArgs(a) {
        if (a.tour) { arc.openSample(); destination = System.homeUrl(); return }
        arc.open(a.url)
        destination = a.dest || System.parentUrl(a.url)
    }

    Item {
        id: header
        width: parent.width
        height: Theme.px(72)
        Row {
            anchors.left: parent.left; anchors.leftMargin: Theme.px(28); anchors.verticalCenter: parent.verticalCenter
            spacing: Theme.px(16)
            Icon { anchors.verticalCenter: parent.verticalCenter; name: "archive"; size: Theme.px(30); color: Theme.accent }
            Column {
                anchors.verticalCenter: parent.verticalCenter
                Text { text: arc.fileName; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(20); font.weight: Font.DemiBold }
                Text { text: arc.details; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(14) }
            }
        }
        Text {
            anchors.right: parent.right; anchors.rightMargin: Theme.px(28); anchors.verticalCenter: parent.verticalCenter
            text: "Browsing inside the archive. Nothing is extracted yet."
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(15)
        }
    }
    Divider { id: divider; anchors.top: header.bottom; x: Theme.px(28); width: parent.width - Theme.px(56) }

    FilePane {
        id: pane
        anchors.top: divider.bottom
        anchors.topMargin: Theme.px(14)
        anchors.left: parent.left
        anchors.leftMargin: Theme.px(28)
        anchors.right: side.left
        anchors.rightMargin: Theme.px(16)
        anchors.bottom: bar.top
        anchors.bottomMargin: Theme.px(14)
        model: arc
        active: page.zone === "list"
        onActivated: index => page.enter(index)
        onTapped: page.zone = "list"
    }

    Column {
        id: side
        anchors.top: pane.top
        anchors.right: parent.right
        anchors.rightMargin: Theme.px(28)
        width: Theme.px(340)
        spacing: Theme.px(12)
        Rectangle {
            width: parent.width
            height: sel.implicitHeight + Theme.px(36)
            radius: Theme.radius + 1
            color: Theme.panel
            border.color: Theme.line
            Column {
                id: sel
                anchors.left: parent.left; anchors.right: parent.right; anchors.top: parent.top
                anchors.margins: Theme.px(18)
                spacing: Theme.px(6)
                Text { text: "SELECTED"; color: Theme.textMuted; font.family: Theme.display; font.pixelSize: Theme.fs(14); font.weight: Font.DemiBold; font.letterSpacing: 2 }
                Text { text: arc.selectedCount > 0 ? arc.selectedCount + " items · " + arc.selectedSize : "Nothing"; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(22); font.weight: Font.DemiBold }
                Text { width: parent.width; wrapMode: Text.WordWrap; text: "Select nothing to extract everything"; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(14) }
            }
        }
        HButton { width: parent.width; implicitHeight: Theme.px(56); text: "Extract next to the archive"; kind: "accent"; focused: page.zone === "buttons" && page.button === 0; onClicked: page.extract(0) }
        HButton { width: parent.width; implicitHeight: Theme.px(56); text: "Extract to…"; focused: page.zone === "buttons" && page.button === 1; onClicked: page.extract(1) }
        HButton { width: parent.width; implicitHeight: Theme.px(56); text: "Extract to a new folder"; focused: page.zone === "buttons" && page.button === 2; onClicked: page.extract(2) }
        Text { width: parent.width; wrapMode: Text.WordWrap; text: "Works with zip, 7z and tar files (also .tar.gz, .tar.xz and .tar.bz2)"; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(14) }
    }

    PromptBar {
        id: bar
        anchors.bottom: parent.bottom
        width: parent.width
        status: arc.busy ? "Extracting…" : ""
        prompts: [{ key: "a", label: "Open" }, { key: "x", label: "Select" }, { key: "y", label: "Extract" }, { key: "b", label: "Back" }]
        onPrompt: k => page.handleButton(k)
    }

    function enter(i) { if (arc.isDirAt(i)) { arc.enter(i); pane.currentIndex = 0 } }
    function extract(where) {
        if (where === 0) arc.extract(System.parentUrl(arc.url), "")
        else if (where === 1) Ui.pick("Extract to…", [{ name: "Other pane", detail: System.prettyPath(destination), url: destination.toString() }], d => arc.extract(d, ""))
        else Ui.ask("Extract to a new folder", System.parentPath(arc.url), arc.baseName, "Extract", t => arc.extract(System.parentUrl(arc.url), t))
    }
    Connections {
        target: arc
        function onFinished(ok, message) { Ui.say(message, !ok); if (ok) Ui.closePage() }
    }

    function handleButton(b) {
        if (zone === "buttons") {
            switch (b) {
            case "up": button = Math.max(0, button - 1); break
            case "down": button = Math.min(2, button + 1); break
            case "a": extract(button); break
            case "left": case "b": zone = "list"; break
            }
            return true
        }
        switch (b) {
        case "up": pane.move(-1); break
        case "down": pane.move(1); break
        case "lt": pane.page(-1); break
        case "rt": pane.page(1); break
        case "a": enter(pane.currentIndex); break
        case "x": arc.toggleSelected(pane.currentIndex); pane.move(1); break
        case "y": case "right": zone = "buttons"; break
        case "b": if (arc.atRoot) Ui.closePage(); else arc.up(); break
        }
        return true
    }
}

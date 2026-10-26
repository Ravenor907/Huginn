import QtQuick
import Huginn

// Shared folders on the home network (Windows shares and SFTP). Saved shares remember the
// address; sign-in details stay with the system, not in Huginn's settings.
Rectangle {
    id: page
    color: Theme.ground
    property int focusIndex: 0
    function setArgs(a) {}
    Component.onCompleted: Network.discover()

    readonly property var rows: Network.saved.concat(Network.found)

    PageHeader {
        id: header
        width: parent.width
        title: "Network"
        subtitle: "SFTP shares on your home network (Windows shares come in 0.2)"
        HButton { implicitHeight: Theme.px(44); text: "Add by address"; kind: "accent"; onClicked: page.addByAddress() }
    }
    Divider { id: divider; anchors.top: header.bottom; x: Theme.px(28); width: parent.width - Theme.px(56) }

    Flickable {
        anchors.top: divider.bottom
        anchors.topMargin: Theme.px(14)
        anchors.left: parent.left
        anchors.leftMargin: Theme.px(28)
        anchors.right: parent.right
        anchors.rightMargin: Theme.px(28)
        anchors.bottom: bar.top
        contentHeight: col.implicitHeight
        clip: true
        Column {
            id: col
            width: parent.width
            spacing: Theme.px(8)
            Text { text: "SAVED"; color: Theme.textMuted; font.family: Theme.display; font.pixelSize: Theme.px(15); font.weight: Font.DemiBold; font.letterSpacing: 2 }
            Text {
                visible: Network.saved.length === 0
                text: "Nothing saved yet. Press Y to add an SFTP share by address; it's saved for next time."
                color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.px(16)
            }
            Repeater {
                model: Network.saved
                ShareRow { width: col.width; entry: modelData; focused: page.focusIndex === index; saved: true }
            }
            Text { visible: Network.found.length > 0; topPadding: Theme.px(10); text: "FOUND NEARBY"; color: Theme.textMuted; font.family: Theme.display; font.pixelSize: Theme.px(15); font.weight: Font.DemiBold; font.letterSpacing: 2 }
            Text {
                visible: false
                text: ""
                color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.px(16)
            }
            Repeater {
                model: Network.found
                ShareRow { width: col.width; entry: modelData; focused: page.focusIndex === Network.saved.length + index; saved: false }
            }
        }
    }

    component ShareRow: Rectangle {
        property var entry
        property bool focused
        property bool saved
        height: Theme.px(72)
        radius: Theme.radius
        color: focused ? Theme.focusFill : saved ? Theme.panel : Theme.panelLow
        border.width: focused ? Theme.focusWidth : 1
        border.color: focused ? Theme.accent : Theme.line
        Row {
            anchors.left: parent.left
            anchors.leftMargin: Theme.px(18)
            anchors.verticalCenter: parent.verticalCenter
            spacing: Theme.px(16)
            Icon { anchors.verticalCenter: parent.verticalCenter; name: saved ? "network" : "server"; size: Theme.px(28); color: saved ? Theme.textMuted : Theme.textFaint }
            Column {
                anchors.verticalCenter: parent.verticalCenter
                spacing: Theme.px(2)
                Text { text: entry.name; color: saved ? Theme.text : Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.px(19); font.weight: saved ? Font.DemiBold : Font.Medium }
                Text { text: entry.address; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.px(14) }
            }
        }
        Text {
            anchors.right: parent.right
            anchors.rightMargin: Theme.px(18)
            anchors.verticalCenter: parent.verticalCenter
            text: saved ? "Saved" : "Not saved"
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.px(15)
        }
        TapHandler { onTapped: Ui.main.openInBrowser(entry.url) }
    }

    PromptBar {
        id: bar
        anchors.bottom: parent.bottom
        width: parent.width
        status: "Sign-in windows come from the system and remember your password if you ask them to"
        prompts: [{ key: "a", label: "Open" }, { key: "x", label: "Save / forget" }, { key: "y", label: "Add by address" }, { key: "b", label: "Back" }]
        onPrompt: k => page.handleButton(k)
    }

    function addByAddress() {
        Ui.ask("Add a share", "sftp://user@host/folder", "sftp://", "Open", t => {
            const url = Network.normalize(t)
            if (!url) { Ui.say("That doesn't look like a share address. Try sftp://user@host/folder", true); return }
            Network.save(url, "")
            Ui.main.openInBrowser(url)
        })
    }

    function handleButton(b) {
        const r = rows[focusIndex]
        switch (b) {
        case "up": focusIndex = Math.max(0, focusIndex - 1); break
        case "down": focusIndex = Math.min(rows.length - 1, focusIndex + 1); break
        case "a": if (r) Ui.main.openInBrowser(r.url); break
        case "x":
            if (!r) break
            if (focusIndex < Network.saved.length) Network.forget(r.url)
            else Network.save(r.url, r.name)
            break
        case "y": addByAddress(); break
        case "b": Ui.closePage(); break
        }
        return true
    }
}

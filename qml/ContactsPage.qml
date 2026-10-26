import QtQuick
import Huginn

// Settings › Chat › Contacts: everyone you're in touch with or met close by, and who's blocked.
// Someone renamed here shows as "Nickname (their name)".
// Blocking lives here, not in the chat itself.
Rectangle {
    id: page
    color: Theme.ground
    property int row: 0
    function setArgs(a) {}

    readonly property var people: Chat.people

    PageHeader {
        id: header
        width: parent.width
        title: "Contacts"
        subtitle: "A: choose what to do · Blocked people can't reach you, and aren't told"
    }
    Divider { id: divider; anchors.top: header.bottom; x: Theme.px(28); width: parent.width - Theme.px(56) }

    ListView {
        id: list
        anchors.top: divider.bottom; anchors.topMargin: Theme.px(12)
        anchors.bottom: bar.top; anchors.bottomMargin: Theme.px(12)
        x: Theme.px(28); width: parent.width - Theme.px(56)
        spacing: Theme.px(8)
        clip: true
        model: page.people
        currentIndex: page.row
        onCurrentIndexChanged: positionViewAtIndex(currentIndex, ListView.Contain)
        delegate: Rectangle {
            readonly property bool on: page.row === index
            width: list.width
            height: Theme.px(64)
            radius: Theme.radius
            color: on ? Theme.focusFill : Theme.panel
            border.width: on ? Theme.focusWidth : 1
            border.color: on ? Theme.accent : Theme.line
            Icon { id: ico; anchors.left: parent.left; anchors.leftMargin: Theme.px(14); anchors.verticalCenter: parent.verticalCenter; name: modelData.blocked ? "lock" : "phone"; color: Theme.textMuted }
            Column {
                anchors.left: ico.right; anchors.leftMargin: Theme.px(12); anchors.right: parent.right; anchors.rightMargin: Theme.px(14)
                anchors.verticalCenter: parent.verticalCenter
                Text { width: parent.width; elide: Text.ElideRight; text: modelData.name; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(18); font.weight: Font.DemiBold }
                Text {
                    width: parent.width; elide: Text.ElideRight
                    text: modelData.blocked ? "Blocked · A to unblock" : (modelData.contact ? "In touch" : "Met close by") + (modelData.verified ? " · verified" : "")
                    color: modelData.blocked ? Theme.danger : Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(14)
                }
            }
            TapHandler { onTapped: { page.row = index; page.choose() } }
        }
    }
    Text {
        anchors.centerIn: list
        visible: list.count === 0
        text: "Nobody yet. People you chat with show up here."
        color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(17)
    }

    PromptBar {
        id: bar
        anchors.bottom: parent.bottom
        width: parent.width
        prompts: [{ key: "a", label: "Choose" }, { key: "b", label: "Back" }]
        onPrompt: k => page.handleButton(k)
    }

    function choose() {
        const p = people[row]
        if (!p) return
        if (p.blocked) {
            Ui.sure("Unblock " + p.name + "?", "Their messages reach you again, and your chat with them comes back.", "Unblock", () => Chat.block(p.key, false))
            return
        }
        const items = [{ name: "Message", url: "open" }, { name: "Rename", detail: "A name only you see", url: "rename" }]
        if (!p.verified) items.push({ name: "Check code", detail: "Make sure nobody is in between", url: "code" })
        items.push({ name: "Block", detail: "Nothing from them reaches you. They aren't told", url: "block" })
        if (p.contact) items.push({ name: "Remove contact", detail: "Your chat with them stays", url: "remove" })
        Ui.pickFrom(p.name, [{ header: "", items: items }], a => {
            if (a === "open") { Ui.closePage(); Ui.openPage("chat", {}); }
            else if (a === "code") Ui.sure("Check it's really " + p.name, "Code: " + Chat.codeWith(p.key) + "\n\nAsk them to open your chat and tap Check code. If their screen shows the same code, nobody is in between.",
                                           "It matches", () => Chat.verify(p.key))
            else if (a === "rename")
                Ui.ask("Rename " + Chat.realNameOf(p.key), "Only on this computer, they aren't told. Leave it empty to use their own name.", Chat.nicknameOf(p.key), "Save",
                       t => Chat.setNickname(p.key, t), false, true)
            else if (a === "block") { Chat.block(p.key, true); Ui.say(p.name + " blocked.") }
            else if (a === "remove") { Chat.removeContact(p.key); Ui.say(p.name + " removed. Your chat with them stays.") }
        })
    }
    function handleButton(b) {
        switch (b) {
        case "up": row = Math.max(0, row - 1); break
        case "down": row = Math.min(people.length - 1, row + 1); break
        case "a": choose(); break
        case "b": Ui.closePage(); break
        }
        return true
    }
}

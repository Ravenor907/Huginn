import QtQuick
import Huginn

// Settings › Chat › Contacts: everyone you're in touch with or met close by, and who's blocked.
// Someone renamed here shows as "Nickname (their name)".
// Blocking lives here, not in the chat itself.
Rectangle {
    id: page
    color: Theme.ground
    // False while the controller focus is on the rail, so only one thing looks focused.
    readonly property bool active: !Ui.main || Ui.main.focusZone === "content"
    property int row: 0
    function setArgs(a) {}

    readonly property var people: Chat.people
    readonly property var person: people[row] || null
    readonly property var prompts: [{ key: "a", label: "Choose" }, { key: "b", label: "Back" }]
    readonly property string status: "Blocked people can't reach you, and aren't told"

    function initial(name) { const n = String(name || "").trim(); return n.length ? n.charAt(0).toUpperCase() : "?" }
    function stateText(p) {
        return p.blocked ? "Blocked · A to unblock" : (p.contact ? "In touch" : "Met close by") + (p.verified ? " · verified" : "")
    }

    // ------------------------------------------------------------- middle column: people
    Rectangle {
        id: middle
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        width: Theme.px(270)
        color: Theme.column
        PageHeader { id: header; width: parent.width; title: "Contacts" }

        ListView {
            id: list
            anchors.top: header.bottom; anchors.topMargin: Theme.px(10)
            anchors.bottom: parent.bottom; anchors.bottomMargin: Theme.px(10)
            x: Theme.px(10); width: parent.width - Theme.px(20)
            spacing: Theme.px(3)
            clip: true
            model: page.people
            currentIndex: page.row
            onCurrentIndexChanged: positionViewAtIndex(currentIndex, ListView.Contain)
            delegate: Rectangle {
                readonly property bool on: page.active && page.row === index
                width: list.width
                height: Theme.px(56)
                radius: Theme.px(10)
                color: on ? Theme.tile : hover.hovered ? Theme.raised : "transparent"
                border.width: on ? Theme.focusWidth : 0
                border.color: Theme.accent
                Accessible.role: Accessible.ListItem
                Accessible.name: modelData.name + ", " + page.stateText(modelData)
                Rectangle {
                    id: ava
                    anchors.left: parent.left; anchors.leftMargin: Theme.px(10)
                    anchors.verticalCenter: parent.verticalCenter
                    width: Theme.px(34); height: width; radius: width / 2
                    color: modelData.blocked ? Theme.raised : Theme.avatar(modelData.name).bg
                    Text { anchors.centerIn: parent; text: page.initial(modelData.name); color: modelData.blocked ? Theme.textMuted : Theme.avatar(modelData.name).fg; font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.DemiBold }
                }
                Column {
                    anchors.left: ava.right; anchors.leftMargin: Theme.px(10); anchors.right: parent.right; anchors.rightMargin: Theme.px(10)
                    anchors.verticalCenter: parent.verticalCenter
                    Text { width: parent.width; elide: Text.ElideRight; text: modelData.name; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(16); font.weight: Font.DemiBold }
                    Text { width: parent.width; elide: Text.ElideRight; text: page.stateText(modelData); color: modelData.blocked ? Theme.danger : Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
                }
                HoverHandler { id: hover }
                TapHandler { onTapped: { page.row = index; page.choose() } }
            }
        }
        Text {
            anchors.top: header.bottom; anchors.topMargin: Theme.px(16)
            x: Theme.px(20); width: parent.width - Theme.px(40)
            wrapMode: Text.WordWrap
            visible: list.count === 0
            text: "Nobody yet. People you chat with show up here."
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(15)
        }
    }

    // ------------------------------------------------------------- main column: the person
    Item {
        anchors.left: middle.right
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        PageHeader { id: mainHead; width: parent.width; carved: false; title: page.person ? page.person.name : "" }
        Column {
            visible: !!page.person
            anchors.top: mainHead.bottom
            anchors.topMargin: Theme.px(28)
            anchors.horizontalCenter: parent.horizontalCenter
            width: Math.min(parent.width - Theme.px(48), Theme.px(520))
            spacing: Theme.px(12)
            Rectangle {
                anchors.horizontalCenter: parent.horizontalCenter
                width: Theme.px(84); height: width; radius: width / 2
                color: page.person && !page.person.blocked ? Theme.avatar(page.person.name).bg : Theme.raised
                Text { anchors.centerIn: parent; text: page.person ? page.initial(page.person.name) : ""; color: page.person && !page.person.blocked ? Theme.avatar(page.person.name).fg : Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(34); font.weight: Font.DemiBold }
            }
            Text {
                width: parent.width; horizontalAlignment: Text.AlignHCenter
                text: page.person ? page.stateText(page.person) : ""
                color: page.person && page.person.blocked ? Theme.danger : Theme.textSoft
                font.family: Theme.body; font.pixelSize: Theme.fs(17)
            }
            Text {
                width: parent.width; horizontalAlignment: Text.AlignHCenter; wrapMode: Text.WordWrap
                text: "A: message, rename, check their code, block or remove. Blocked people can't reach you, and aren't told."
                color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(15)
            }
        }
    }

    function choose() {
        const p = people[row]
        if (!p) return
        if (p.blocked) {
            Ui.sure("Unblock " + p.name + "?", "Their messages reach you again, and your chat with them comes back.", "Unblock", () => Chat.block(p.key, false))
            return
        }
        const ids = Chat.foldersOf(p.key)
        const inFolders = Chat.folders.filter(f => ids.indexOf(f.id) >= 0).map(f => f.name)
        const items = [{ name: "Message", url: "open" }, { name: "Rename", detail: "A name only you see", url: "rename" },
                       { name: "Folders", detail: inFolders.length ? "In " + inFolders.join(", ") : "Family, Work, Kindred or your own", url: "folders" }]
        if (!p.verified) items.push({ name: "Check code", detail: "Make sure nobody is in between", url: "code" })
        items.push({ name: "Block", detail: "Nothing from them reaches you. They aren't told", url: "block" })
        if (p.contact) items.push({ name: "Remove contact", detail: "Your chat with them stays", url: "remove" })
        Ui.pickFrom(p.name, [{ header: "", items: items }], a => {
            if (a === "open") Ui.openPage("dms", { open: p.key })
            else if (a === "code") Ui.sure("Check it's really " + p.name, "Code: " + Chat.codeWith(p.key) + "\n\nAsk them to open your chat and tap Check code. If their screen shows the same code, nobody is in between.",
                                           "It matches", () => Chat.verify(p.key))
            else if (a === "rename")
                Ui.ask("Rename " + Chat.realNameOf(p.key), "Only on this computer, they aren't told. Leave it empty to use their own name.", Chat.nicknameOf(p.key), "Save",
                       t => Chat.setNickname(p.key, t), false, true)
            else if (a === "folders") pickFolders(p)
            else if (a === "block") { Chat.block(p.key, true); Ui.say(p.name + " blocked.") }
            else if (a === "remove") { Chat.removeContact(p.key); Ui.say(p.name + " removed. Your chat with them stays.") }
        })
    }
    // Folders are only on this computer; Direct messages shows them as filters.
    function pickFolders(p) {
        const ids = Chat.foldersOf(p.key)
        const items = Chat.folders.map(f => ({ name: f.name, url: f.id, detail: ids.indexOf(f.id) >= 0 ? "In it · A takes them out" : "A puts them in" }))
        items.push({ name: "New folder", detail: "Make one and put them in it", url: "+" })
        Ui.pickFrom("Folders for " + p.name, [{ header: "Only on this computer", items: items }], id => {
            if (id === "+") {
                Ui.ask("New folder", "A name only you see", "", "Make it", t => { const f = Chat.addFolder(t); if (f !== "") Chat.setInFolder(f, p.key, true) }, false, true)
                return
            }
            const on = Chat.foldersOf(p.key).indexOf(id) < 0
            Chat.setInFolder(id, p.key, on)
            Ui.say(on ? "Added." : "Taken out.")
        })
    }
    function handleButton(b) {
        switch (b) {
        case "up": row = Math.max(0, row - 1); return true
        case "down": row = Math.min(people.length - 1, row + 1); return true
        case "a": choose(); return true
        case "b": Ui.closePage(); return true
        }
        return false
    }
}

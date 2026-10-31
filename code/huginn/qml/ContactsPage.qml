import QtQuick
import Huginn

// Contacts (on the rail): everyone you're in touch with or met close by, who's blocked, and who's
// in the Trash (kept 30 days). A right click opens the person panel.
// Someone renamed here shows as "Nickname (their name)".
// Blocking lives here, not in the chat itself.
Rectangle {
    id: page
    // Autumn: a frosted pane the leaves fall behind; the other looks are solid.
    color: Theme.sheet
    // False while the controller focus is on the rail, so only one thing looks focused.
    readonly property bool active: !Ui.main || Ui.main.focusZone === "content"
    property int row: 0
    function setArgs(a) {}

    readonly property var people: Chat.people.concat(Chat.trash.map(t => ({ key: t.key, name: t.name, trashed: true, daysLeft: t.daysLeft, contact: false, verified: false, blocked: false })))
    readonly property var person: people[row] || null
    readonly property var prompts: [{ key: "a", label: "Choose" }, { key: "b", label: "Back" }]
    readonly property string status: "Blocked people can't reach you, and aren't told"

    function stateText(p) {
        if (p.trashed) return "In the Trash · " + p.daysLeft + (p.daysLeft === 1 ? " day" : " days") + " left"
        return p.blocked ? "Blocked" : (p.contact ? "In touch" : "Met close by") + (p.verified ? " · verified" : "")
    }

    // ------------------------------------------------------------- middle column: people
    Rectangle {
        id: middle
        // Portrait: the list on top, the rest under it.
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        anchors.bottomMargin: Theme.portrait ? parent.height * 0.45 : Ui.dock
        width: Theme.portrait ? parent.width : Theme.listWidth
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
                height: Theme.px(44)
                radius: Theme.radius
                color: on ? Theme.tile : hover.hovered ? Theme.raised : "transparent"
                border.width: on ? Theme.focusWidth : 0
                border.color: Theme.accent
                Accessible.role: Accessible.ListItem
                Accessible.name: modelData.name + ", " + page.stateText(modelData)
                Avatar {
                    id: ava
                    anchors.left: parent.left; anchors.leftMargin: Theme.px(10)
                    anchors.verticalCenter: parent.verticalCenter
                    size: Theme.px(34)
                    key: modelData.blocked || modelData.trashed ? "" : modelData.key || ""
                    name: modelData.name
                    dim: !!modelData.blocked || !!modelData.trashed
                    showDot: !modelData.blocked && !modelData.trashed && Chat.activeStatus
                    backdrop: parent.on ? Theme.tile : Theme.column
                }
                Column {
                    anchors.left: ava.right; anchors.leftMargin: Theme.px(10); anchors.right: parent.right; anchors.rightMargin: Theme.px(10)
                    anchors.verticalCenter: parent.verticalCenter
                    Text { width: parent.width; elide: Text.ElideRight; text: modelData.name; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(16); font.weight: Font.DemiBold }
                    Text { width: parent.width; elide: Text.ElideRight; text: page.stateText(modelData); color: modelData.blocked ? Theme.danger : Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
                }
                HoverHandler { id: hover }
                TapHandler {
                    acceptedButtons: Qt.LeftButton | Qt.RightButton
                    onTapped: (point, button) => {
                        page.row = index
                        if (button === Qt.RightButton && !modelData.trashed) Ui.showPerson(modelData.key, modelData.name, {})
                        else page.choose()
                    }
                    onLongPressed: if (!modelData.trashed) Ui.showPerson(modelData.key, modelData.name, {})
                }
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
        anchors.left: Theme.portrait ? parent.left : middle.right
        anchors.right: parent.right
        anchors.top: Theme.portrait ? middle.bottom : parent.top
        anchors.bottom: parent.bottom
        PageHeader { id: mainHead; width: parent.width; carved: false; title: page.person ? page.person.name : "" }
        Column {
            visible: !!page.person
            anchors.top: mainHead.bottom
            anchors.topMargin: Theme.px(28)
            anchors.horizontalCenter: parent.horizontalCenter
            width: Math.min(parent.width - Theme.px(48), Theme.px(520))
            spacing: Theme.px(12)
            Avatar {
                anchors.horizontalCenter: parent.horizontalCenter
                size: Theme.px(84)
                key: page.person && !page.person.blocked ? page.person.key || "" : ""
                name: page.person ? page.person.name : ""
                dim: !!page.person && !!page.person.blocked
                showDot: !!page.person && !page.person.blocked && Chat.activeStatus
                backdrop: Theme.ground
            }
            Text {
                width: parent.width; horizontalAlignment: Text.AlignHCenter
                text: page.person ? page.stateText(page.person) : ""
                color: page.person && page.person.blocked ? Theme.danger : Theme.textSoft
                font.family: Theme.body; font.pixelSize: Theme.fs(17)
            }
            Text {
                width: parent.width; horizontalAlignment: Text.AlignHCenter; wrapMode: Text.WordWrap
                text: page.person && page.person.trashed ? "A: restore them and your chat, or delete them for good. Nothing from them reaches you meanwhile."
                    : "A: message, rename, check their code, block or remove. Blocked people can't reach you, and aren't told."
                color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(15)
            }
        }
    }

    function choose() {
        const p = people[row]
        if (!p) return
        if (p.trashed) {
            Ui.pickFrom(p.name, [{ header: "In the Trash · " + p.daysLeft + (p.daysLeft === 1 ? " day" : " days") + " left", items: [
                { name: "Restore", detail: "They and your chat come back", url: "restore" },
                { name: "Delete for good", detail: "Your chat with them goes too", url: "delete" }] }], a => {
                if (a === "restore") { Chat.restore(p.key); Ui.say(p.name + " is back.") }
                else if (a === "delete") Ui.sure("Delete " + p.name + " for good?", "They and your chat with them can't be brought back.", "Delete for good",
                                                 () => { Chat.deleteForGood(p.key); Ui.say(p.name + " deleted for good.") }, false, true)
            })
            return
        }
        if (p.blocked) {
            Ui.sure("Unblock " + p.name + "?", "Their messages reach you again, and your chat with them comes back.", "Unblock", () => Chat.block(p.key, false))
            return
        }
        const ids = Chat.foldersOf(p.key)
        const inFolders = Chat.folders.filter(f => ids.indexOf(f.id) >= 0).map(f => f.name)
        const items = [{ name: "Message", url: "open" }, { name: "Nickname", detail: "A name only you see", url: "rename" },
                       { name: "Role and permissions", detail: "Everything about them in one place", url: "person" },
                       { name: "Folders", detail: inFolders.length ? "In " + inFolders.join(", ") : "Family, Work, Folk or your own", url: "folders" }]
        if (!p.verified) items.push({ name: "Check code", detail: "Make sure nobody is in between", url: "code" })
        items.push({ name: "Faster calls with " + p.name + ": " + Calls.trustText(p.key), detail: Calls.trusted(p.key) ? "Calls go straight between you when they choose this too. They can see your internet address"
                                                                                                : "Off: calls go the long way round, so neither of you sees the other's internet address", url: "trust" })
        items.push({ name: "Block", detail: "Nothing from them reaches you. They aren't told", url: "block" })
        items.push({ name: "Move to trash", detail: "Your chat goes too. Restore brings both back within 30 days", url: "remove" })
        Ui.pickFrom(p.name, [{ header: "", items: items }], a => {
            if (a === "open") Ui.openPage("dms", { open: p.key })
            else if (a === "person") Ui.showPerson(p.key, p.name, {})
            else if (a === "code") PeopleActions.checkCode(p.key)
            else if (a === "rename")
                Ui.ask("Nickname for " + Chat.realNameOf(p.key), "Only on this computer; they aren't told. Leave it empty to use their own name.", Chat.nicknameOf(p.key), "Save",
                       t => Chat.setNickname(p.key, t), false, true)
            else if (a === "folders") pickFolders(p)
            else if (a === "trust") {
                const on = !Calls.trusted(p.key)
                Calls.setTrusted(p.key, on)
                Ui.say(on ? "Calls with " + p.name + " go straight between you when they choose this too." : "Calls with " + p.name + " go the long way round.")
            }
            else if (a === "block") { Chat.block(p.key, true); Ui.say(p.name + " blocked.") }
            else if (a === "remove") Ui.sure("Move " + p.name + " to trash?", "They go to the Trash with your chat for 30 days, then are deleted for good. Meanwhile nothing from them reaches you. Restore brings everything back.",
                                         "Move to trash", () => { Chat.removeContact(p.key); Ui.say(p.name + " is in the Trash.") }, false, true)
        })
    }
    // Folders are only on this computer; Direct messages shows them as filters.
    function pickFolders(p) {
        const ids = Chat.foldersOf(p.key)
        const items = Chat.folders.map(f => ({ name: f.name, url: f.id, detail: ids.indexOf(f.id) >= 0 ? "In it · choose to take them out" : "Choose to put them in" }))
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

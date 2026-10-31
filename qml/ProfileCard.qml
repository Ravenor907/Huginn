import QtQuick
import Huginn

// Someone's profile card (a click on a name, or A on a member): their avatar and name, the nickname
// and note you gave them (only on this computer, never sent), the folders they're in, how they are
// right now, and Message, Call and Video. From a group it also says their role there, and Group
// options opens what an admin can do. D-pad moves along the buttons, A chooses, B closes.
Item {
    id: card
    property string key: ""
    property string name: ""
    property var extra: ({})
    property int focusIndex: 0
    visible: false
    anchors.fill: parent

    readonly property bool me: key === "me"
    readonly property bool tour: !!extra.tour
    // Chat.chats and Chat.people make these follow renames, notes and folder changes.
    readonly property bool contact: !me && !tour && (Chat.people, Chat.isContact(key))
    readonly property string nick: me || tour ? "" : (Chat.chats, Chat.nicknameOf(key))
    readonly property string realName: me ? Chat.username : tour ? name : (Chat.chats, Chat.realNameOf(key)) || name
    readonly property string shownName: me ? Chat.username : nick !== "" ? nick : realName
    readonly property string note: me ? "" : tour ? (extra.note || "") : (Chat.people, Chat.noteOf(key))
    readonly property var folderNames: {
        if (me || tour) return tour ? ["Folk"] : []
        const mine = (Chat.chats, Chat.foldersOf(key))
        return Chat.folders.filter(f => mine.indexOf(f.id) >= 0).map(f => f.name)
    }
    readonly property string status: {
        if (extra.room) return "In " + extra.room
        if (me) return "Online (you)"
        if (tour) return extra.status === "online" ? "Online" : ""
        const p = Ui.main ? Ui.main.presenceOf(key) : "offline"
        return p === "online" ? "Online" : p === "idle" ? "Idle" : ""
    }
    readonly property bool canCall: !me && !tour && Calls.canCall(key)
    readonly property bool canVideo: !me && !tour && Calls.canVideo(key)

    // Every button, in the order the controller steps through them.
    readonly property var actions: {
        const a = []
        if (me) {
            a.push({ id: "name", label: "Change your name", on: true })
        } else {
            a.push({ id: "message", label: "Message", icon: "thread", on: contact, main: true })
            a.push({ id: "call", label: "Call", icon: "call", on: canCall, main: true })
            a.push({ id: "video", label: "Video", icon: "video", on: canVideo, main: true })
            a.push({ id: "nick", label: nick !== "" ? "Change nickname" : "Nickname", on: !tour })
            a.push({ id: "note", label: note !== "" ? "Change note" : "Add a note", on: !tour })
            if (contact) a.push({ id: "folders", label: "Folders", on: true })
            // Cap i1: introduce a contact to another one, or ask someone from a group to be contacts.
            if (contact && !tour) a.push({ id: "introduce", label: "Introduce", on: true })
            else if (!contact && !tour && extra.g) a.push({ id: "addme", label: "Add as a contact", on: true })
        }
        if (extra.more) a.push({ id: "more", label: "Group options", on: true })
        return a
    }
    readonly property var mainActions: actions.filter(x => !!x.main)
    readonly property var otherActions: actions.filter(x => !x.main)

    function show(k, n, x) {
        key = k; name = n; extra = x || {}
        focusIndex = Math.max(0, actions.findIndex(a => a.on))
        visible = true
    }
    function close() { visible = false }
    function run(id) {
        const k = key, n = shownName
        const a = actions.find(x => x.id === id)
        if (!a || !a.on) {
            if (id === "message") Ui.say(n + " isn't a contact yet. Add them first, then you can message them.", true)
            else if (id === "call" || id === "video") Ui.say(n + " can't be called from here yet.", true)
            return
        }
        if (id === "message") { close(); Ui.openPage("dms", { open: k }) }
        else if (id === "call") { const w = Calls.call(k); if (w !== "") Ui.say(w, true); else close() }
        else if (id === "video") { const w = Calls.callVideo(k); if (w !== "") Ui.say(w, true); else close() }
        else if (id === "name") Ui.ask("Your chat name", "Pick a name, not your real one", Chat.username, "Save", t => Chat.username = t)
        else if (id === "nick") Ui.ask("Nickname for " + realName, "Only on this computer. Leave it empty for their own name", nick, "Save", t => Chat.setNickname(k, t), false, true)
        else if (id === "note") Ui.ask("Note about " + n, "Only on this computer, never sent", note, "Save", t => Chat.setNote(k, t), false, true)
        else if (id === "folders") pickFolders()
        else if (id === "introduce") { close(); PeopleActions.introduce(k) }
        else if (id === "addme") { const g = extra.g; close(); PeopleActions.askToAdd(g, { key: k, name: n }) }
        else if (id === "more") { const f = extra.more; close(); f() }
    }
    function pickFolders() {
        const k = key
        const mine = Chat.foldersOf(k)
        const items = Chat.folders.map(f => ({ name: f.name, url: f.id, detail: mine.indexOf(f.id) >= 0 ? "In it · choose to take them out" : "Choose to put them in" }))
        if (!items.length) { Ui.say("No folders yet. Make one from Direct messages.", true); return }
        Ui.pickFrom("Folders for " + shownName, [{ header: "", items: items }], id => {
            Chat.setInFolder(id, k, Chat.foldersOf(k).indexOf(id) < 0)
            Qt.callLater(card.pickFolders)
        })
    }
    function handleButton(b) {
        const n = actions.length
        if (b === "b") close()
        else if (b === "a") { const a = actions[focusIndex]; if (a) run(a.id) }
        else if (b === "left" || b === "up") focusIndex = Math.max(0, focusIndex - 1)
        else if (b === "right" || b === "down") focusIndex = Math.min(n - 1, focusIndex + 1)
        return true
    }

    Rectangle { anchors.fill: parent; color: Qt.rgba(0.03, 0.035, 0.04, 0.6) }
    MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons; onClicked: card.close() }

    Rectangle {
        id: box
        anchors.centerIn: parent
        width: Math.min(parent.width - Theme.px(60), Theme.px(420))
        height: body.implicitHeight + banner.height + Theme.px(24)
        radius: Theme.radius
        color: Theme.panel
        border.width: 1
        border.color: Theme.line
        clip: true
        Accessible.role: Accessible.Dialog
        Accessible.name: card.shownName
        MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons }

        Rectangle {
            id: banner
            width: parent.width
            height: Theme.px(74)
            color: Theme.accentFill
            Image {
                anchors.right: parent.right; anchors.rightMargin: Theme.px(14)
                anchors.verticalCenter: parent.verticalCenter
                source: "assets/raven-gold.png"
                width: Theme.px(44); height: width
                sourceSize.width: 128; sourceSize.height: 128
                opacity: 0.35
            }
        }
        // The avatar sits over the banner's edge.
        Rectangle {
            id: face
            x: Theme.px(20)
            y: banner.height - height / 2
            width: Theme.px(84); height: width; radius: width / 2
            color: Theme.panel
            Rectangle {
                anchors.centerIn: parent
                width: parent.width - Theme.px(10); height: width; radius: width / 2
                color: Theme.avatar(card.realName, card.me).bg
                Text {
                    anchors.centerIn: parent
                    text: String(card.shownName || "?").trim().charAt(0).toUpperCase() || "?"
                    color: Theme.avatar(card.realName, card.me).fg
                    font.family: Theme.body; font.pixelSize: Theme.fs(30); font.weight: Font.DemiBold
                }
            }
            Rectangle {
                visible: card.status !== ""
                anchors.right: parent.right; anchors.bottom: parent.bottom
                anchors.rightMargin: Theme.px(4); anchors.bottomMargin: Theme.px(4)
                width: Theme.px(20); height: width; radius: width / 2
                color: card.status.startsWith("In ") ? Theme.accent : card.status === "Idle" ? Theme.idle : Theme.online
                border.width: Theme.px(4); border.color: Theme.panel
            }
        }
        HButton {
            anchors.right: parent.right; anchors.rightMargin: Theme.px(14)
            y: banner.height + Theme.px(10)
            text: "Close"; fontSize: 13
            implicitHeight: Theme.px(32)
            onClicked: card.close()
        }

        Column {
            id: body
            anchors.top: face.bottom; anchors.topMargin: Theme.px(10)
            x: Theme.px(20)
            width: parent.width - Theme.px(40)
            spacing: Theme.px(10)

            Column {
                width: parent.width
                spacing: Theme.px(2)
                Text {
                    width: parent.width
                    elide: Text.ElideRight
                    text: card.shownName
                    color: Theme.text
                    font.family: Theme.display; font.pixelSize: Theme.fs(22); font.weight: Font.DemiBold
                }
                Text {
                    visible: text !== ""
                    width: parent.width
                    elide: Text.ElideRight
                    text: card.nick !== "" ? "Calls themselves " + card.realName : ""
                    color: Theme.textMuted
                    font.family: Theme.body; font.pixelSize: Theme.fs(14)
                }
                Text {
                    visible: text !== ""
                    width: parent.width
                    elide: Text.ElideRight
                    text: [card.status, card.extra.group ? (card.extra.owner ? "Owner of " : card.extra.admin ? "Admin in " : "In ") + card.extra.group : ""].filter(t => t !== "").join(" · ")
                    color: card.status.startsWith("In ") ? Theme.accent : Theme.textSoft
                    font.family: Theme.body; font.pixelSize: Theme.fs(14)
                }
            }
            Rectangle { width: parent.width; height: 1; color: Theme.line; visible: !card.me }
            Column {
                visible: !card.me
                width: parent.width
                spacing: Theme.px(3)
                Label { text: "NOTE" }
                Text {
                    width: parent.width
                    wrapMode: Text.WordWrap
                    maximumLineCount: 5
                    elide: Text.ElideRight
                    text: card.note !== "" ? card.note : "None yet. Only you see it."
                    color: card.note !== "" ? Theme.text : Theme.textFaint
                    font.family: Theme.body; font.pixelSize: Theme.fs(15)
                }
            }
            Column {
                visible: !card.me && (card.contact || card.tour)
                width: parent.width
                spacing: Theme.px(3)
                Label { text: "FOLDERS" }
                Text {
                    width: parent.width
                    wrapMode: Text.WordWrap
                    text: card.folderNames.length ? card.folderNames.join(", ") : "In no folder"
                    color: card.folderNames.length ? Theme.text : Theme.textFaint
                    font.family: Theme.body; font.pixelSize: Theme.fs(15)
                }
            }
            // Message, Call and Video.
            Row {
                visible: card.mainActions.length > 0
                width: parent.width
                spacing: Theme.px(8)
                Repeater {
                    model: card.mainActions
                    ActionButton {
                        width: (parent.width - Theme.px(8) * (card.mainActions.length - 1)) / card.mainActions.length
                        action: modelData || ({})
                        primary: true
                    }
                }
            }
            Flow {
                width: parent.width
                spacing: Theme.px(8)
                Repeater {
                    model: card.otherActions
                    ActionButton { action: modelData || ({}) }
                }
            }
        }
    }

    component Label: Text {
        color: Theme.textMuted
        font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold; font.letterSpacing: Theme.px(1.5)
    }
    component ActionButton: Rectangle {
        id: ab
        property var action: ({})
        property bool primary: false
        readonly property bool focused: !!card.actions[card.focusIndex] && card.actions[card.focusIndex].id === action.id
        width: abRow.implicitWidth + Theme.px(24)
        height: Theme.px(primary ? 46 : 36)
        radius: Theme.radius
        opacity: action.on ? 1 : 0.45
        color: primary && action.on ? (abHover.hovered ? Theme.accentFill : Theme.raised) : abHover.hovered ? Theme.tile : Theme.raised
        border.width: focused ? Theme.focusWidth : 1
        border.color: focused ? Theme.accent : primary && action.on ? Theme.accent : Theme.lineMuted
        Accessible.role: Accessible.Button
        Accessible.name: action.label || ""
        Accessible.onPressAction: card.run(action.id)
        Row {
            id: abRow
            anchors.centerIn: parent
            spacing: Theme.px(6)
            Icon { visible: !!ab.action.icon; anchors.verticalCenter: parent.verticalCenter; name: ab.action.icon || "file"; size: Theme.px(18); color: ab.primary && ab.action.on ? Theme.accent : Theme.textSoft }
            Text { anchors.verticalCenter: parent.verticalCenter; text: ab.action.label || ""; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(ab.primary ? 15 : 13); font.weight: Font.DemiBold }
        }
        HoverHandler { id: abHover; cursorShape: Qt.PointingHandCursor }
        TapHandler { onTapped: { card.focusIndex = card.actions.findIndex(a => a.id === ab.action.id); card.run(ab.action.id) } }
    }
}

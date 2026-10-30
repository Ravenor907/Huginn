import QtQuick
import Huginn

// The person panel: a right click (or a hold) on someone in the chat list, Contacts or a member
// list. Their role (which of the Family, Work and Folk folders they're in), what they can do, where
// their chat sits, and nickname, note, chat color, introduce, the 4-emoji check and block, in one
// place, the same everywhere. The D-pad moves along the choices, A picks, B closes.
//
// HOOKS (n95-trust, chatpeople.cpp): what they can do and where the chat sits call these, each
// through has() so this screen still works on a build without them:
//   Chat.callsAllowed / setCallsAllowed, Chat.filesAllowed / setFilesAllowed, Chat.showsActive / setShowsActive
//   Chat.pinChat, isPinnedChat, archiveChat, isArchived, muteChat (until, ms), isMuted, markUnread, isMarkedUnread
// Not on Huginn yet (shown, and say so when used): Always right away (Chat.alwaysRightAway /
// setAlwaysRightAway) and the chat color (Chat.chatColor / setChatColor).
Item {
    id: panel
    property string key: ""
    property string name: ""
    property var extra: ({})          // { section, keys (the section's order as shown), tour }
    property int focusIndex: 0
    property int revision: 0          // bumps after a change so the bindings below read again
    visible: false
    anchors.fill: parent

    readonly property bool tour: !!extra.tour
    readonly property bool contact: !tour && (revision, Chat.people, Chat.isContact(key))
    readonly property string shownName: tour ? name : (revision, Chat.chats, Chat.nameOf(key)) || name
    readonly property string nick: tour ? "" : (revision, Chat.chats, Chat.nicknameOf(key))
    readonly property string note: tour ? "" : (revision, Chat.people, Chat.noteOf(key))
    readonly property bool verified: tour || (revision, Chat.chats, Chat.isVerified(key))
    readonly property bool blocked: !tour && (revision, Chat.chats, Chat.isBlocked(key))
    readonly property string presence: tour ? "online" : Ui.main ? Ui.main.presenceOf(key) : "offline"

    // ------------------------------------------------------------- hooks for n95-trust
    function has(fn) { return typeof Chat[fn] === "function" }
    function hook(fn, args, fallback) {
        if (!has(fn)) return fallback
        return Chat[fn].apply(Chat, args || [])
    }
    readonly property var perms: tour ? { always: true, calls: true, files: true, active: true } : (revision, Chat.chats, {
        always: hook("alwaysRightAway", [key], false), calls: hook("callsAllowed", [key], true),
        files: hook("filesAllowed", [key], true), active: hook("showsActive", [key], true) })
    readonly property var flags: tour ? { muted: false, archived: false, unread: false, pinned: false, color: "" } : (revision, Chat.chats, {
        muted: hook("isMuted", [key], false), archived: hook("isArchived", [key], false), unread: hook("isMarkedUnread", [key], false),
        pinned: hook("isPinnedChat", [key], false), color: hook("chatColor", [key], "") })
    readonly property var setters: ({ always: "setAlwaysRightAway", calls: "setCallsAllowed", files: "setFilesAllowed", active: "setShowsActive" })

    // ------------------------------------------------------------- role: the three starting folders
    readonly property var roles: ["Family", "Work", "Folk", "None"]
    function folderId(n) { const f = Chat.folders.find(x => String(x.name).toLowerCase() === n.toLowerCase()); return f ? f.id : "" }
    readonly property string role: {
        if (tour) return "Family"
        revision; Chat.chats
        const mine = Chat.foldersOf(key)
        for (const r of ["Family", "Work", "Folk"]) { const id = folderId(r); if (id !== "" && mine.indexOf(id) >= 0) return r }
        return "None"
    }
    function setRole(r) {
        if (tour || !contact) return
        for (const n of ["Family", "Work", "Folk"]) {
            let id = folderId(n)
            if (id === "" && n === r) id = Chat.addFolder(n)
            if (id !== "") Chat.setInFolder(id, key, n === r)
        }
        revision++
        Ui.say(r === "None" ? shownName + " has no role now." : shownName + " is in " + r + " now.")
    }

    // ------------------------------------------------------------- what they can do
    readonly property var permRows: [
        { id: "always", label: "Always right away", note: "Their messages reach you at once, even when you're saving battery" },
        { id: "calls", label: "Calls and video", note: "They can call you" },
        { id: "files", label: "Send me files", note: "Files straight from them over the internet" },
        { id: "active", label: "See when I'm active", note: "They see the green dot while Huginn is open" }
    ]
    function permOn(id) { return !!perms[id] }
    function togglePerm(id) {
        if (tour) return
        if (!has(setters[id])) { Ui.say("This comes with the next update.", true); return }
        Chat[setters[id]](key, !permOn(id))
        revision++
    }

    // ------------------------------------------------------------- where the chat sits
    readonly property var keys: extra.keys || []
    readonly property int at: keys.indexOf(key)
    readonly property bool pinned: !!flags.pinned
    function place(how) {
        if (tour) return
        const section = extra.section || ""
        if (how === "pin" && has("pinChat")) {
            Chat.pinChat(key, !pinned)
            Ui.say(pinned ? "Unpinned." : shownName + " is pinned to the top.")
        } else if (how === "pin" || how === "up" || how === "down") {
            if (at < 0) return
            const next = keys.slice()
            const to = how === "pin" ? 0 : how === "up" ? Math.max(0, at - 1) : Math.min(keys.length - 1, at + 1)
            next.splice(at, 1)
            next.splice(to, 0, key)
            Chat.placeIn(key, section, section, next)
            const k = Object.assign({}, extra); k.keys = next; extra = k
            Ui.say(how === "pin" ? shownName + " is at the top." : "Moved.")
        } else if (how === "mute") {
            if (!has("muteChat")) { Ui.say("This comes with the next update.", true); return }
            // For good until unmuted (a huge time, as the trust work expects).
            Chat.muteChat(key, flags.muted ? 0 : 8640000000000000)
        } else if (how === "archive") {
            if (!has("archiveChat")) { Ui.say("This comes with the next update.", true); return }
            Chat.archiveChat(key, !flags.archived)
        } else if (how === "unread") {
            if (!has("markUnread")) { Ui.say("This comes with the next update.", true); return }
            Chat.markUnread(key, !flags.unread)
        }
        revision++
    }
    readonly property var placeRows: [
        { id: "pin", label: pinned ? "Unpin" : "Pin to the top", lit: pinned, on: has("pinChat") || at > 0 },
        { id: "up", label: "Move up", on: at > 0 },
        { id: "down", label: "Move down", on: at >= 0 && at < keys.length - 1 },
        { id: "mute", label: flags.muted ? "Unmute" : "Mute", lit: !!flags.muted, on: true },
        { id: "archive", label: flags.archived ? "Take out of archive" : "Archive", lit: !!flags.archived, on: true },
        { id: "unread", label: flags.unread ? "Mark read" : "Mark unread", lit: !!flags.unread, on: true }
    ]
    readonly property var colors: ["", "#C2A25E", "#6E9CC8", "#6FAE8C", "#CF6F54", "#A58BC6"]
    function setColor(c) {
        if (tour) return
        if (!has("setChatColor")) { Ui.say("This comes with the next update.", true); return }
        Chat.setChatColor(key, c)
        revision++
    }

    // ------------------------------------------------------------- the rest
    function act(id) {
        const k = key, n = shownName
        if (tour) return
        if (id === "nick") Ui.ask("Nickname for " + Chat.realNameOf(k), "Only on this computer; they aren't told. Leave it empty to use their own name.", Chat.nicknameOf(k), "Save",
                                  t => { Chat.setNickname(k, t); revision++ }, false, true)
        else if (id === "note") Ui.ask("Note about " + n, "Only on this computer, never sent.", Chat.noteOf(k), "Save", t => { Chat.setNote(k, t); revision++ }, false, true)
        else if (id === "introduce") { close(); PeopleActions.introduce(k) }
        else if (id === "check") { close(); PeopleActions.checkCode(k) }
        else if (id === "block") {
            if (blocked) { Chat.block(k, false); revision++; Ui.say(n + " is unblocked.") }
            else Ui.sure("Block " + n + "?", "Nothing from them reaches you until you unblock them. They aren't told.", "Block", () => { Chat.block(k, true); panel.revision++ }, false, true)
        }
        else if (id === "trash") Ui.sure("Move " + n + " to trash?", "They go to the Trash with your chat for 30 days, then are deleted for good. Restore brings everything back.",
                                         "Move to trash", () => { Chat.removeContact(k); panel.close(); Ui.say(n + " is in the Trash.") }, false, true)
    }

    // Every choice, in the order the controller steps through them.
    readonly property var order: ["nick", "note"].concat(roles.map(r => "role:" + r)).concat(permRows.map(p => "perm:" + p.id))
                                 .concat(placeRows.map(p => "place:" + p.id)).concat(colors.map((c, i) => "color:" + i))
                                 .concat(["introduce", "check", "block", "trash"])
    function choose(id) {
        if (id.indexOf("role:") === 0) setRole(id.substring(5))
        else if (id.indexOf("perm:") === 0) togglePerm(id.substring(5))
        else if (id.indexOf("place:") === 0) place(id.substring(6))
        else if (id.indexOf("color:") === 0) setColor(colors[Number(id.substring(6))])
        else act(id)
    }
    function focused(id) { return !!Ui.main && Ui.main.padInUse && order[focusIndex] === id }

    function show(k, n, x) {
        key = k; name = n; extra = x || {}; focusIndex = 0; revision++
        visible = true
    }
    function close() { visible = false; if (Ui.main) Ui.main.restoreFocus() }

    Rectangle { anchors.fill: parent; color: Qt.rgba(0.03, 0.035, 0.04, 0.7) }
    MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons; onClicked: panel.close() }

    Rectangle {
        id: box
        readonly property bool narrow: width < Theme.px(560)
        anchors.centerIn: parent
        width: Math.min(parent.width - Theme.px(32), Theme.px(640))
        height: Math.min(parent.height - Theme.px(32), col.implicitHeight + Theme.px(44))
        radius: Theme.radius
        color: Theme.panel
        border.color: Theme.line
        clip: true
        MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons }
        Flickable {
            anchors.fill: parent
            anchors.margins: Theme.px(22)
            contentHeight: col.implicitHeight
            boundsBehavior: Flickable.StopAtBounds
            clip: true
            Column {
                id: col
                width: parent.width
                spacing: Theme.px(18)

                // Who: avatar, name, how they are, Nickname and Note.
                Row {
                    width: parent.width
                    spacing: Theme.px(14)
                    Rectangle {
                        id: face
                        width: Theme.px(60); height: width; radius: width / 2
                        color: Theme.avatar(panel.shownName).bg
                        Text { anchors.centerIn: parent; text: String(panel.shownName || "?").trim().charAt(0).toUpperCase(); color: Theme.avatar(panel.shownName).fg; font.family: Theme.body; font.pixelSize: Theme.fs(24); font.weight: Font.DemiBold }
                    }
                    Column {
                        anchors.verticalCenter: parent.verticalCenter
                        width: parent.width - face.width - nickBtn.width - noteBtn.width - Theme.px(42)
                        Text { width: parent.width; elide: Text.ElideRight; text: panel.shownName; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(19); font.weight: Font.DemiBold }
                        Text {
                            width: parent.width; elide: Text.ElideRight
                            text: (panel.presence === "online" ? "Online" : panel.presence === "idle" ? "Idle" : "Offline")
                                  + (panel.verified ? " · Checked with the 4 emoji" : " · Not checked yet") + (panel.blocked ? " · Blocked" : "")
                            color: panel.presence === "online" ? Theme.online : Theme.textMuted
                            font.family: Theme.body; font.pixelSize: Theme.fs(13)
                        }
                    }
                    HButton { id: nickBtn; anchors.verticalCenter: parent.verticalCenter; kind: "plain"; text: "Nickname"; fontSize: 13; focused: panel.focused("nick"); onClicked: panel.act("nick") }
                    HButton { id: noteBtn; anchors.verticalCenter: parent.verticalCenter; kind: "plain"; text: "Note"; fontSize: 13; focused: panel.focused("note"); onClicked: panel.act("note") }
                }
                Text { visible: panel.note !== ""; width: parent.width; wrapMode: Text.WordWrap; text: panel.note; color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(13); font.italic: true }

                // Role.
                Column {
                    width: parent.width
                    spacing: Theme.px(8)
                    Label { text: "ROLE" }
                    Rectangle {
                        width: parent.width; height: Theme.px(48)
                        radius: Theme.radius
                        color: Theme.panelLow
                        Row {
                            anchors.fill: parent; anchors.margins: Theme.px(4)
                            spacing: Theme.px(6)
                            Repeater {
                                model: panel.roles
                                Rectangle {
                                    readonly property bool on: panel.role === modelData
                                    width: (parent.width - Theme.px(18)) / 4; height: parent.height
                                    radius: Theme.radius
                                    color: on ? Theme.accent : roleHover.hovered ? Theme.raised : "transparent"
                                    border.width: panel.focused("role:" + modelData) ? Theme.focusWidth : 0
                                    border.color: Theme.text
                                    opacity: panel.contact || panel.tour ? 1 : 0.5
                                    Accessible.role: Accessible.RadioButton
                                    Accessible.name: modelData
                                    Accessible.checked: on
                                    Text { anchors.centerIn: parent; text: modelData; color: parent.on ? Theme.ground : Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: parent.on ? Font.DemiBold : Font.Normal }
                                    HoverHandler { id: roleHover; cursorShape: Qt.PointingHandCursor }
                                    TapHandler { onTapped: panel.setRole(modelData) }
                                }
                            }
                        }
                    }
                }

                // What they can do, and where their chat sits (side by side when there's room).
                Grid {
                    width: parent.width
                    columns: box.narrow ? 1 : 2
                    columnSpacing: Theme.px(18)
                    rowSpacing: Theme.px(18)
                    readonly property real cellW: box.narrow ? width : (width - columnSpacing) / 2
                    Rectangle {
                        width: parent.cellW
                        height: permCol.implicitHeight
                        radius: Theme.radius
                        color: Theme.panelLow
                        border.color: Theme.line
                        Column {
                            id: permCol
                            width: parent.width
                            Label { text: "WHAT THEY CAN DO"; leftPadding: Theme.px(12); topPadding: Theme.px(10); bottomPadding: Theme.px(4) }
                            Repeater {
                                model: panel.permRows
                                Rectangle {
                                    width: permCol.width; height: Math.max(Theme.px(40), permText.implicitHeight + Theme.px(12))
                                    color: panel.focused("perm:" + modelData.id) ? Theme.focusFill : "transparent"
                                    Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.seam; visible: index < panel.permRows.length - 1 }
                                    Text {
                                        id: permText
                                        anchors.left: parent.left; anchors.leftMargin: Theme.px(12)
                                        anchors.right: permSw.left; anchors.rightMargin: Theme.px(10)
                                        anchors.verticalCenter: parent.verticalCenter
                                        wrapMode: Text.WordWrap
                                        text: modelData.label
                                        color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(14)
                                    }
                                    ToggleSwitch {
                                        id: permSw
                                        anchors.right: parent.right; anchors.rightMargin: Theme.px(12)
                                        anchors.verticalCenter: parent.verticalCenter
                                        checked: panel.permOn(modelData.id)
                                        onToggled: panel.togglePerm(modelData.id)
                                    }
                                }
                            }
                        }
                    }
                    Column {
                        width: parent.cellW
                        spacing: Theme.px(8)
                        Label { text: "WHERE THIS CHAT SITS" }
                        Grid {
                            width: parent.width
                            columns: 2
                            spacing: Theme.px(6)
                            Repeater {
                                model: panel.placeRows
                                Rectangle {
                                    width: (parent.width - Theme.px(6)) / 2; height: Theme.px(40)
                                    radius: Theme.radius
                                    opacity: modelData.on ? 1 : 0.45
                                    color: modelData.lit ? Theme.accentFill : placeHover.hovered && modelData.on ? Theme.raised : "transparent"
                                    border.width: panel.focused("place:" + modelData.id) ? Theme.focusWidth : 1
                                    border.color: panel.focused("place:" + modelData.id) ? Theme.accent : Theme.line
                                    Accessible.role: Accessible.Button
                                    Accessible.name: modelData.label
                                    Text { anchors.centerIn: parent; width: parent.width - Theme.px(8); horizontalAlignment: Text.AlignHCenter; elide: Text.ElideRight; text: modelData.label; color: modelData.lit ? Theme.accent : Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
                                    HoverHandler { id: placeHover; cursorShape: modelData.on ? Qt.PointingHandCursor : Qt.ArrowCursor }
                                    TapHandler { enabled: modelData.on; onTapped: panel.place(modelData.id) }
                                }
                            }
                        }
                        Label { text: "CHAT COLOR"; topPadding: Theme.px(6) }
                        Row {
                            spacing: Theme.px(8)
                            Repeater {
                                model: panel.colors
                                Rectangle {
                                    readonly property bool on: (panel.flags.color || "") === modelData
                                    width: Theme.px(28); height: width; radius: Theme.radius
                                    color: modelData === "" ? Theme.raised : modelData
                                    border.width: on || panel.focused("color:" + index) ? Theme.px(2) : 1
                                    border.color: on || panel.focused("color:" + index) ? Theme.text : Theme.line
                                    Accessible.role: Accessible.RadioButton
                                    Accessible.name: modelData === "" ? "No color" : "Color " + modelData
                                    Icon { visible: modelData === ""; anchors.centerIn: parent; name: "close"; size: Theme.px(14); color: Theme.textMuted }
                                    HoverHandler { cursorShape: Qt.PointingHandCursor }
                                    TapHandler { onTapped: panel.setColor(modelData) }
                                }
                            }
                        }
                    }
                }

                // Introduce, check again, block, trash.
                Flow {
                    width: parent.width
                    spacing: Theme.px(8)
                    HButton { visible: panel.contact || panel.tour; text: "Introduce to someone"; fontSize: 13; focused: panel.focused("introduce"); onClicked: panel.act("introduce") }
                    HButton { visible: panel.contact || panel.tour; text: panel.verified ? "Check 4 emoji again" : "Check the 4 emoji"; fontSize: 13; focused: panel.focused("check"); onClicked: panel.act("check") }
                    HButton { text: panel.blocked ? "Unblock" : "Block"; kind: "danger"; fontSize: 13; focused: panel.focused("block"); onClicked: panel.act("block") }
                    HButton { visible: panel.contact || panel.tour; text: "Move to trash"; kind: "danger"; fontSize: 13; focused: panel.focused("trash"); onClicked: panel.act("trash") }
                    HButton { visible: !!panel.extra.more; text: "Group options"; fontSize: 13; onClicked: { const f = panel.extra.more; panel.close(); f() } }
                }
            }
        }
    }

    component Label: Text {
        color: Theme.textMuted
        font.family: Theme.body; font.pixelSize: Theme.fs(12); font.letterSpacing: Theme.px(1)
    }

    function handleButton(b) {
        switch (b) {
        case "up": case "left": focusIndex = Math.max(0, focusIndex - 1); return true
        case "down": case "right": focusIndex = Math.min(order.length - 1, focusIndex + 1); return true
        case "a": choose(order[focusIndex]); return true
        case "b": close(); return true
        }
        return true
    }
}

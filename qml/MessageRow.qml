import QtQuick
import Huginn

// Shared by groups and 1:1 chats (threads and pins).
// One message: name, time (and "(edited)"), for mine a check mark with how many have read it in a
// circle; the quoted message of a reply, the text or picture or voice, reactions, "N replies" (a
// thread), and the tools over its corner (pointed at, or picked with the controller). An @mention
// of me is lit up.
Rectangle {
    id: msg
    // The page it sits on (GroupPage or ChatPage): its clock, actions, pins and photo viewer.
    property var host: null
    property var m: ({})
    property int at: 0
    property bool picked: false
    property int lineH: 0
    property bool hoverTools: false
    property bool inThread: false
    // Under the one before from the same person within 7 minutes: no name or picture again (Discord).
    property bool grouped: false
    signal options()
    signal chosen()          // clicked: the page picks it (its tools show)
    property string who: m.name || "Someone"
    // Safe while a list takes the row away (its message is gone by then) and the theme changes.
    readonly property bool mine: !!m && !!m.mine
    // Settings › Look: Compact drops the pictures and the room between messages; Spacious adds room.
    readonly property int pad: grouped ? Theme.px(1) : Theme.px(Theme.compact ? 2 : Theme.spacious ? 8 : 5)
    height: m.system ? note.height + Theme.px(12) : lineH + Math.max(face.visible ? face.height : 0, col.implicitHeight) + pad * 2
    radius: 0
    color: picked ? Theme.focusFill : m.mention ? Theme.accentFill : msgHover.hovered ? Theme.panelLow : "transparent"
    border.width: picked ? Theme.focusWidth : 0
    border.color: Theme.accent
    Rectangle { visible: !!msg.m.mention && !msg.picked; x: 0; y: msg.lineH; width: Theme.px(3); height: parent.height - msg.lineH; color: Theme.accent }

    Item {
        visible: msg.lineH > 0
        width: parent.width; height: msg.lineH
        Rectangle { anchors.verticalCenter: parent.verticalCenter; width: parent.width; height: 1; color: Theme.danger }
        Rectangle {
            anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
            width: newText.implicitWidth + Theme.px(14); height: Theme.px(20); radius: Theme.radius; color: Theme.danger
            Text { id: newText; anchors.centerIn: parent; text: "NEW MESSAGES"; color: Theme.ground; font.family: Theme.body; font.pixelSize: Theme.fs(11); font.weight: Font.Bold }
        }
    }
    // A note from Huginn itself (someone joined, the group was renamed).
    Rectangle {
        id: note
        visible: !!msg.m.system
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.verticalCenter: parent.verticalCenter
        width: Math.min(parent.width * 0.9, noteText.implicitWidth + Theme.px(28))
        height: noteText.implicitHeight + Theme.px(14)
        radius: Theme.radius
        color: Theme.panel
        border.width: 1
        border.color: Theme.line
        Text {
            id: noteText
            anchors.centerIn: parent
            width: Math.min(implicitWidth, msg.width * 0.9 - Theme.px(28))
            wrapMode: Text.Wrap
            text: msg.m.system ? (msg.m.text || "") : ""
            color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(14)
        }
    }
    Rectangle {
        id: face
        visible: !msg.m.system && !Theme.compact && !msg.grouped
        x: Theme.px(16); y: msg.lineH + msg.pad
        width: visible ? Theme.px(40) : 0; height: width; radius: width / 2
        color: Theme.avatar(msg.who, msg.mine).bg
        Text {
            anchors.centerIn: parent
            text: host.initial(msg.who)
            color: Theme.avatar(msg.who, msg.mine).fg
            font.family: Theme.body; font.pixelSize: Theme.ms(16); font.weight: Font.DemiBold
        }
    }
    Column {
        id: col
        visible: !msg.m.system
        x: Theme.px(Theme.compact ? 16 : 72)
        width: parent.width - x - Theme.px(16)
        y: msg.lineH + msg.pad
        spacing: Theme.px(2)
        Row {
            visible: !msg.grouped
            spacing: Theme.px(8)
            Text {
                id: whoText
                text: msg.who
                color: Theme.avatar(msg.who, msg.mine).fg
                font.family: Theme.body; font.pixelSize: Theme.ms(16); font.weight: Font.Medium
                // A click on the name opens their profile card.
                readonly property string key: msg.m.mine ? "me" : (msg.m.from || (msg.host && msg.host.openKey) || "")
                HoverHandler { cursorShape: whoText.key !== "" ? Qt.PointingHandCursor : Qt.ArrowCursor }
                TapHandler {
                    gesturePolicy: TapHandler.ReleaseWithinBounds
                    onTapped: if (whoText.key !== "" && !(msg.host && msg.host.isTour)) Ui.showProfile(whoText.key, msg.who, {})
                }
            }
            Text {
                anchors.baseline: whoText.baseline
                text: host.clock(msg.m.time) + (msg.m.isEdited ? " · (edited)" : "") + host.stateLabel(msg.m)
                color: msg.m.failed ? Theme.danger : msg.m.mine && msg.m.state === "read" ? Theme.accent : Theme.textMuted
                font.family: Theme.body; font.pixelSize: Theme.fs(12)
            }
            // Mine and read: a check mark and how many have read it, in a circle.
            Row {
                visible: !!msg.m.mine && !msg.m.unsent && (msg.m.readCount || 0) > 0
                anchors.verticalCenter: whoText.verticalCenter
                spacing: Theme.px(3)
                Accessible.role: Accessible.StaticText
                Accessible.name: "Read by " + (msg.m.readCount || 0)
                Icon { anchors.verticalCenter: parent.verticalCenter; name: "check"; size: Theme.px(15); color: Theme.accent }
                Rectangle {
                    anchors.verticalCenter: parent.verticalCenter
                    width: Math.max(Theme.px(18), readN.implicitWidth + Theme.px(8)); height: Theme.px(18); radius: height / 2
                    color: "transparent"; border.color: Theme.accent; border.width: 1
                    Text { id: readN; anchors.centerIn: parent; text: msg.m.readCount || 0; color: Theme.accent; font.family: Theme.body; font.pixelSize: Theme.fs(11); font.weight: Font.DemiBold }
                }
            }
        }
        QuoteLine {
            visible: !!msg.m.reName && !msg.m.unsent
            who: msg.m.reName || ""
            what: msg.m.reText || ""
            onJump: host.jumpTo(msg.m.re)
        }
        Image {
            visible: !!msg.m.photo && !msg.m.voice && !msg.m.file && !msg.m.unsent && !msg.m.video && !msg.m.album
            width: Theme.px(300); height: Theme.px(170)
            fillMode: Image.PreserveAspectCrop
            asynchronous: true
            source: visible && !host.isTour ? Chat.photoUrl(msg.m.photo) : ""
        }
        Row {
            visible: !!msg.m.voice && !msg.m.unsent
            spacing: Theme.px(10)
            Icon { name: Voice.playing === msg.m.id ? "stop" : "play"; color: Theme.accent }
            Column {
                anchors.verticalCenter: parent.verticalCenter
                Rectangle {
                    width: Theme.px(180); height: Theme.px(4); color: Theme.line
                    Rectangle { width: parent.width * (Voice.playing === msg.m.id ? Voice.progress : 0); height: parent.height; color: Theme.accent }
                }
                Text { text: "Voice message · " + Voice.length(msg.m.voice || 0); color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
            }
        }
        Text {
            visible: (!msg.m.photo || !!msg.m.file || !!msg.m.unsent) && !(msg.m.pollView && !msg.m.unsent)
            width: parent.width; wrapMode: Text.Wrap
            textFormat: Text.PlainText
            text: msg.m.unsent ? (msg.m.mine ? "Message deleted by you" : "Message deleted by " + msg.m.unsent) : (msg.m.text || "")
            font.italic: !!msg.m.unsent
            lineHeight: 1.2
            color: msg.m.unsent ? Theme.textFaint : Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.ms(16)
        }
        // Cap m2: an album, a video message, a poll or a link preview.
        MoreParts { width: parent.width; host: msg.host; m: msg.m; where: msg.host && msg.host.more ? msg.host.more.where : "" }
        Reactions {
            width: parent.width
            list: msg.m.rxList || []
            add: msg.hoverTools
            onToggled: (e, mine) => host.reactTo(msg.m.id, mine ? "" : e)
            onMore: host.openReact(msg.m.id, msg.m.myReaction || "")
        }
        // A thread: how many replies, opening them beside the channel.
        Text {
            visible: !msg.inThread && (msg.m.replies || 0) > 0 && !msg.m.unsent
            text: (msg.m.replies === 1 ? "1 reply" : msg.m.replies + " replies") + " ›"
            color: Theme.accent; font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.DemiBold
            Accessible.role: Accessible.Button
            Accessible.name: "Open the thread"
            HoverHandler { cursorShape: Qt.PointingHandCursor }
            TapHandler { onTapped: host.openThread(msg.m.id) }
        }
    }
    HoverBar {
        id: tools
        visible: msg.hoverTools && (msgHover.hovered || msg.picked) && !msg.m.system && !msg.m.unsent
        anchors.right: parent.right; anchors.rightMargin: Theme.px(10)
        y: msg.lineH - Theme.px(6)
        z: 2
        canEdit: !!msg.m.mine && !msg.m.photo && !msg.m.failed && !msg.m.file
        canThread: !msg.inThread
        canPin: !msg.inThread
        pinnedNow: host.pins.indexOf(msg.m.id) >= 0
        onReact: host.openReact(msg.m.id, msg.m.myReaction || "")
        // In a thread its own box answers; from the pins, the chat's box does.
        onReply: msg.inThread && host.threadCompose.visible ? host.threadCompose.focusField() : host.startReply(msg.m.id)
        onThread: host.openThread(msg.m.id)
        onEdit: host.startEdit(msg.m)
        onPin: host.togglePin(msg.m.id)
        onMore: msg.options()
    }
    HoverHandler { id: msgHover }
    // A click (a mouse or the right stick's pointer) picks it and opens a photo; a right click opens
    // the options. A finger has no right click, so a tap opens them.
    TapHandler {
        acceptedDevices: PointerDevice.Mouse | PointerDevice.TouchPad | PointerDevice.Stylus
        acceptedButtons: Qt.LeftButton | Qt.RightButton
        onTapped: (point, button) => {
            // The tools over the corner answer their own clicks.
            if (tools.visible && tools.contains(tools.mapFromItem(msg, point.position.x, point.position.y))) return
            msg.chosen()
            if (button === Qt.RightButton) msg.options()
            else if (msg.m.photo && !msg.m.voice && !msg.m.file && !msg.m.unsent && !msg.m.video && !msg.m.album) host.viewing = msg.m.photo
        }
    }
    TapHandler {
        acceptedDevices: PointerDevice.TouchScreen
        onTapped: { msg.chosen(); msg.options() }
    }
}

import QtQuick
import Huginn

// In a voice room or group call on the desktop: at the foot of the list column, like Discord. Which
// room, who you're talking with (a gold ring while they talk, dimmed while muted), and Mute, Deafen,
// Share screen, Open and Leave. With push to talk on, it says which key to hold and lights up while
// it's held. A handheld (and a hidden list) uses the bar along the bottom instead (RoomBar).
Rectangle {
    id: panel
    property bool tour: false              // the screenshot tour shows how it looks
    property var roomBar: null             // shows someone's shared screen above the conversation
    readonly property bool shown: Rooms.inRoom || tour
    readonly property var people: tour ? [{ key: "me", name: Chat.username, me: true, speaking: false, muted: false, state: "live" },
                                          { key: "j", name: "Jake", speaking: true, state: "live" },
                                          { key: "s", name: "Sam", muted: true, state: "live" }]
                                       : Rooms.people
    readonly property var others: people.filter(p => !p.me)
    readonly property bool muted: !tour && Rooms.muted
    readonly property bool deafened: !tour && Rooms.deafened
    height: col.implicitHeight + Theme.px(20)
    color: Theme.groundDeep
    Rectangle { width: parent.width; height: 1; color: Theme.seam }
    // Clicks here never reach the page underneath.
    MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons }

    function initial(name) { const n = String(name || "").trim(); return n.length ? n.charAt(0).toUpperCase() : "?" }

    Column {
        id: col
        x: Theme.px(14); y: Theme.px(10)
        width: parent.width - Theme.px(28)
        spacing: Theme.px(8)

        Row {
            width: parent.width
            spacing: Theme.px(10)
            Icon { anchors.verticalCenter: parent.verticalCenter; name: "speaker"; size: Theme.px(20); color: Theme.online }
            Column {
                anchors.verticalCenter: parent.verticalCenter
                width: parent.width - Theme.px(30)
                Text {
                    width: parent.width
                    elide: Text.ElideRight
                    text: Rooms.note !== "" && !panel.tour ? Rooms.note : "Voice connected"
                    color: Rooms.note !== "" && !panel.tour ? Theme.danger : Theme.online
                    font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.DemiBold
                }
                Text {
                    width: parent.width
                    elide: Text.ElideRight
                    text: panel.tour ? "Lounge · Gaming Crew" : Rooms.title + (Rooms.groupName ? " · " + Rooms.groupName : "")
                    color: Theme.textSoft
                    font.family: Theme.body; font.pixelSize: Theme.fs(13)
                    HoverHandler { cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: if (!panel.tour) Ui.openPage("g:" + Rooms.group, { voice: true }) }
                }
            }
        }

        // Who you're talking with.
        Row {
            spacing: Theme.px(6)
            visible: panel.people.length > 0
            Repeater {
                model: panel.people
                Item {
                    width: Theme.px(34); height: width
                    Accessible.role: Accessible.StaticText
                    Accessible.name: modelData.name + (modelData.speaking ? ", speaking" : "") + (modelData.muted ? ", muted" : "")
                    Rectangle {
                        anchors.fill: parent
                        radius: width / 2
                        color: "transparent"
                        border.width: Theme.px(2.5)
                        border.color: Theme.accent
                        opacity: modelData.speaking ? 1 : 0
                        Behavior on opacity { NumberAnimation { duration: Theme.motion ? 120 : 0 } }
                    }
                    Rectangle {
                        anchors.centerIn: parent
                        width: Theme.px(28); height: width; radius: width / 2
                        color: Theme.avatar(modelData.name, !!modelData.me).bg
                        opacity: modelData.state === "live" ? (modelData.muted ? 0.55 : 1) : 0.4
                        Text {
                            anchors.centerIn: parent
                            text: panel.initial(modelData.name)
                            color: Theme.avatar(modelData.name, !!modelData.me).fg
                            font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold
                        }
                    }
                    HoverHandler { cursorShape: modelData.me ? Qt.ArrowCursor : Qt.PointingHandCursor }
                    TapHandler { onTapped: if (!modelData.me && !panel.tour) Ui.showProfile(modelData.key, modelData.name, {}) }
                }
            }
        }
        Text {
            width: parent.width
            elide: Text.ElideRight
            text: panel.others.length === 0 ? "Nobody else here yet"
                : "With " + panel.others.map(p => p.name).join(", ")
            color: Theme.textMuted
            font.family: Theme.body; font.pixelSize: Theme.fs(13)
        }
        // Push to talk: the key to hold, lit while it's held.
        Rectangle {
            visible: Settings.pushToTalk
            width: parent.width
            height: Theme.px(26)
            radius: Theme.px(6)
            color: !panel.tour && Rooms.talking ? Theme.accentFill : "transparent"
            border.width: 1
            border.color: !panel.tour && Rooms.talking ? Theme.accent : Theme.line
            Text {
                anchors.centerIn: parent
                width: parent.width - Theme.px(12)
                horizontalAlignment: Text.AlignHCenter
                elide: Text.ElideRight
                text: !panel.tour && Rooms.talking ? "Talking" : "Push to talk: hold " + TalkKey.keyName
                color: !panel.tour && Rooms.talking ? Theme.accent : Theme.textMuted
                font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold
            }
        }
        Row {
            id: buttons
            spacing: Theme.px(6)
            PanelButton {
                icon: panel.muted ? "micoff" : "mic"; label: panel.muted ? "Unmute" : "Mute"; lit: panel.muted
                onClicked: Rooms.toggleMute()
            }
            PanelButton {
                icon: panel.deafened ? "deafened" : "headphones"; label: panel.deafened ? "Undeafen" : "Deafen"; lit: panel.deafened
                onClicked: Rooms.toggleDeafen()
            }
            PanelButton {
                visible: Rooms.canShare || panel.tour
                icon: "screen"; label: Rooms.sharing ? "Stop" : "Share"; lit: Rooms.sharing
                onClicked: Rooms.toggleShare()
            }
            PanelButton {
                visible: !panel.tour && Rooms.sharerName !== "" && !!panel.roomBar
                icon: "screen"; label: panel.roomBar && panel.roomBar.screenShown ? "Hide" : "Watch"
                lit: !!panel.roomBar && panel.roomBar.screenShown
                onClicked: panel.roomBar.screenShown = !panel.roomBar.screenShown
            }
            PanelButton {
                icon: "hangup"; label: "Leave"; danger: true
                onClicked: Rooms.leave()
            }
        }
    }

    component PanelButton: Rectangle {
        id: pb
        property string icon
        property string label
        property bool lit: false
        property bool danger: false
        signal clicked()
        width: Math.max(Theme.px(56), pbText.implicitWidth + Theme.px(14))
        height: Theme.px(46)
        radius: Theme.px(8)
        color: danger ? (pbHover.hovered ? Theme.dangerLine : Theme.dangerFill) : lit ? Theme.accentFill : pbHover.hovered ? Theme.tile : Theme.raised
        border.width: lit ? 1 : 0
        border.color: Theme.accent
        Accessible.role: Accessible.Button
        Accessible.name: label
        Accessible.onPressAction: if (!panel.tour) clicked()
        Column {
            anchors.centerIn: parent
            spacing: Theme.px(1)
            Icon { anchors.horizontalCenter: parent.horizontalCenter; name: pb.icon; size: Theme.px(18); color: pb.danger ? Theme.danger : pb.lit ? Theme.accent : Theme.textSoft }
            Text { id: pbText; anchors.horizontalCenter: parent.horizontalCenter; text: pb.label; color: pb.danger ? Theme.danger : pb.lit ? Theme.accent : Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(11); font.weight: Font.DemiBold }
        }
        HoverHandler { id: pbHover; cursorShape: Qt.PointingHandCursor }
        TapHandler { onTapped: if (!panel.tour) pb.clicked() }
    }
}

import QtQuick
import QtQuick.Shapes
import Huginn

// You, at the bottom left under the rail and the list, like Discord: your name and status with Mute,
// Deafen and Settings. In a voice room or group call it grows a top part: which room, who you're
// talking with (a gold ring while they talk, dimmed while muted), push to talk, Share and Leave.
Rectangle {
    id: panel
    property bool tour: false              // the screenshot tour shows how it looks
    property var roomBar: null             // shows someone's shared screen above the conversation
    readonly property bool inVoice: Rooms.inRoom || tour
    readonly property var people: tour ? [{ key: "me", name: Chat.username, me: true, speaking: false, muted: false, state: "live" },
                                          { key: "j", name: "Jake", speaking: true, state: "live" },
                                          { key: "s", name: "Sam", muted: true, state: "live" }]
                                       : Rooms.people
    readonly property var others: people.filter(p => !p.me)
    readonly property bool muted: !tour && (Rooms.inRoom ? Rooms.muted : Calls.inCall && Calls.muted)
    readonly property bool deafened: !tour && Rooms.deafened
    readonly property bool talking: tour || Rooms.inRoom || Calls.inCall
    height: (voice.visible ? voice.implicitHeight : 0) + me.height
    radius: Theme.radius
    color: Theme.raised
    border.color: Theme.line
    // Clicks here never reach the page underneath.
    MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons }
    Brushed {}

    function initial(name) { const n = String(name || "").trim(); return n.length ? n.charAt(0).toUpperCase() : "?" }

    Column {
        id: voice
        visible: panel.inVoice
        x: Theme.px(10)
        width: parent.width - Theme.px(20)
        spacing: Theme.px(6)
        topPadding: Theme.px(8)
        bottomPadding: Theme.px(8)

        Row {
            width: parent.width
            spacing: Theme.px(8)
            Column {
                anchors.verticalCenter: parent.verticalCenter
                width: parent.width - leaveBtn.width - Theme.px(8)
                Text {
                    width: parent.width
                    elide: Text.ElideRight
                    text: Rooms.note !== "" && !panel.tour ? Rooms.note : "Voice connected"
                    color: Rooms.note !== "" && !panel.tour ? Theme.danger : Theme.online
                    font.family: Theme.body; font.pixelSize: Theme.fs(13); font.weight: Font.DemiBold
                }
                Text {
                    width: parent.width
                    elide: Text.ElideRight
                    text: panel.tour ? "Lounge / Gaming Crew" : Rooms.title + (Rooms.groupName ? " / " + Rooms.groupName : "")
                    color: Theme.textMuted
                    font.family: Theme.body; font.pixelSize: Theme.fs(12)
                    HoverHandler { cursorShape: Qt.PointingHandCursor }
                    TapHandler { onTapped: if (!panel.tour) Ui.openPage("g:" + Rooms.group, { voice: true }) }
                }
            }
            HButton {
                id: leaveBtn
                anchors.verticalCenter: parent.verticalCenter
                implicitHeight: Theme.px(28)
                fontSize: 12
                kind: "danger"
                text: "Leave"
                onClicked: if (!panel.tour) Rooms.leave()
            }
        }

        // Who you're talking with.
        Row {
            spacing: Theme.px(6)
            visible: panel.people.length > 0
            Repeater {
                model: panel.people
                Item {
                    id: who
                    // Safe while the list takes the face away.
                    readonly property var p: modelData || ({})
                    width: Theme.px(34); height: width
                    Accessible.role: Accessible.StaticText
                    Accessible.name: who.p.name + (who.p.speaking ? ", speaking" : "") + (who.p.muted ? ", muted" : "")
                    Rectangle {
                        anchors.fill: parent
                        radius: width / 2
                        color: "transparent"
                        border.width: Theme.px(2.5)
                        border.color: Theme.accent
                        opacity: who.p.speaking ? 1 : 0
                        Behavior on opacity { NumberAnimation { duration: Theme.motion ? 120 : 0 } }
                    }
                    Rectangle {
                        anchors.centerIn: parent
                        width: Theme.px(28); height: width; radius: width / 2
                        color: Theme.avatar(who.p.name, !!who.p.me).bg
                        opacity: who.p.state === "live" ? (who.p.muted ? 0.55 : 1) : 0.4
                        Text {
                            anchors.centerIn: parent
                            text: panel.initial(who.p.name)
                            color: Theme.avatar(who.p.name, !!who.p.me).fg
                            font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold
                        }
                    }
                    HoverHandler { cursorShape: who.p.me ? Qt.ArrowCursor : Qt.PointingHandCursor }
                    TapHandler { onTapped: if (!who.p.me && !panel.tour) Ui.showProfile(who.p.key, who.p.name, {}) }
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
            height: Theme.px(24)
            radius: Theme.radius
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
            visible: Rooms.canShare || panel.tour || (Rooms.sharerName !== "" && !!panel.roomBar)
            spacing: Theme.px(6)
            HButton {
                visible: Rooms.canShare || panel.tour
                implicitHeight: Theme.px(28); fontSize: 12
                text: Rooms.sharing ? "Stop sharing" : "Share screen"
                onClicked: if (!panel.tour) Rooms.toggleShare()
            }
            HButton {
                visible: !panel.tour && Rooms.sharerName !== "" && !!panel.roomBar
                implicitHeight: Theme.px(28); fontSize: 12
                text: panel.roomBar && panel.roomBar.screenShown ? "Hide their screen" : "Watch their screen"
                onClicked: panel.roomBar.screenShown = !panel.roomBar.screenShown
            }
        }
        Rectangle { width: parent.width; height: 1; color: Theme.line }
    }

    // You: name and status, then Mute, Deafen and Settings.
    Item {
        id: me
        anchors.bottom: parent.bottom
        width: parent.width
        height: Theme.px(52)
        Rectangle {
            id: meFace
            x: Theme.px(10)
            anchors.verticalCenter: parent.verticalCenter
            width: Theme.px(32); height: width; radius: width / 2
            color: Theme.avatar(Chat.username, true).bg
            Text {
                anchors.centerIn: parent
                text: panel.initial(Chat.username)
                color: Theme.avatar(Chat.username, true).fg
                font.family: Theme.body; font.pixelSize: Theme.fs(13); font.weight: Font.DemiBold
            }
            Rectangle {
                anchors.right: parent.right; anchors.bottom: parent.bottom
                anchors.margins: -Theme.px(2)
                width: Theme.px(12); height: width; radius: width / 2
                color: Vault.screenLocked ? Theme.textFaint : Theme.online
                border.width: Theme.px(2); border.color: Theme.raised
            }
        }
        Column {
            anchors.left: meFace.right; anchors.leftMargin: Theme.px(8)
            anchors.right: meButtons.left; anchors.rightMargin: Theme.px(4)
            anchors.verticalCenter: parent.verticalCenter
            Text {
                width: parent.width; elide: Text.ElideRight
                text: Chat.username
                color: Theme.text
                font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.DemiBold
            }
            Text {
                width: parent.width; elide: Text.ElideRight
                text: Vault.screenLocked ? "Locked" : Settings.pushToTalk && panel.inVoice ? "Push to talk: " + TalkKey.keyName : "Online"
                color: Theme.textMuted
                font.family: Theme.body; font.pixelSize: Theme.fs(12)
            }
        }
        Row {
            id: meButtons
            anchors.right: parent.right; anchors.rightMargin: Theme.px(6)
            anchors.verticalCenter: parent.verticalCenter
            spacing: Theme.px(2)
            PanelButton {
                icon: panel.muted ? "micoff" : "mic"; label: (panel.muted ? "Unmute" : "Mute") + " (Ctrl+Shift+M)"; lit: panel.muted
                enabled: panel.talking
                onClicked: Rooms.inRoom ? Rooms.toggleMute() : Calls.toggleMute()
            }
            PanelButton {
                icon: panel.deafened ? "deafened" : "headphones"; label: (panel.deafened ? "Undeafen" : "Deafen") + " (Ctrl+Shift+D)"; lit: panel.deafened
                enabled: panel.inVoice
                onClicked: Rooms.toggleDeafen()
            }
            // Settings: the valknut.
            Rectangle {
                width: Theme.px(32); height: width
                radius: Theme.radius
                color: "transparent"
                Accessible.role: Accessible.Button
                Accessible.name: "Settings (Ctrl+,)"
                Accessible.onPressAction: Ui.openPage("settings", {})
                // Settings: no tile behind the knot. Open, a soft glow comes from its centre (a touch below the middle, where the knot's weight is).
                Shape {
                    visible: setHover.hovered || (Ui.main && Ui.main.section === "settings")
                    opacity: Ui.main && Ui.main.section === "settings" ? 1 : 0.6
                    anchors.centerIn: parent
                    anchors.verticalCenterOffset: Theme.px(1)
                    width: Theme.px(36); height: width
                    preferredRendererType: Shape.CurveRenderer
                    ShapePath {
                        strokeColor: "transparent"
                        fillGradient: RadialGradient {
                            centerX: Theme.px(18); centerY: centerX; centerRadius: centerX; focalX: centerX; focalY: centerX
                            GradientStop { position: 0.0; color: Qt.rgba(Theme.accent.r, Theme.accent.g, Theme.accent.b, 0.35) }
                            GradientStop { position: 0.5; color: Qt.rgba(Theme.accent.r, Theme.accent.g, Theme.accent.b, 0.12) }
                            GradientStop { position: 1.0; color: "transparent" }
                        }
                        PathAngleArc { centerX: Theme.px(18); centerY: centerX; radiusX: centerX; radiusY: centerX; startAngle: 0; sweepAngle: 360 }
                    }
                }
                Image {
                    anchors.centerIn: parent
                    anchors.verticalCenterOffset: -Theme.px(1.5)
                    source: "assets/valknut.png"
                    width: Theme.px(20); height: width
                    sourceSize.width: 64; sourceSize.height: 64
                    smooth: true; mipmap: true
                }
                HoverHandler { id: setHover; cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: Ui.openPage("settings", {}) }
                Tip { shown: setHover.hovered; text: "Settings (Ctrl+,)" }
            }
        }
    }

    component PanelButton: Rectangle {
        id: pb
        property string icon
        property string label
        property bool lit: false
        property bool enabled: true
        signal clicked()
        width: Theme.px(32); height: width
        radius: Theme.radius
        opacity: enabled ? 1 : 0.4
        color: lit ? Theme.dangerFill : pbHover.hovered && enabled ? Theme.tile : "transparent"
        Accessible.role: Accessible.Button
        Accessible.name: label
        Accessible.onPressAction: if (!panel.tour && enabled) clicked()
        Icon { anchors.centerIn: parent; name: pb.icon; size: Theme.px(18); color: pb.lit ? Theme.danger : Theme.textSoft }
        HoverHandler { id: pbHover; cursorShape: pb.enabled ? Qt.PointingHandCursor : Qt.ArrowCursor }
        TapHandler { enabled: pb.enabled; onTapped: if (!panel.tour) pb.clicked() }
        Tip { shown: pbHover.hovered; text: pb.label }
    }
    // A small label over a button while the pointer is on it.
    component Tip: Rectangle {
        property bool shown: false
        property string text
        visible: shown
        z: 10
        anchors.bottom: parent.top; anchors.bottomMargin: Theme.px(4)
        anchors.horizontalCenter: parent.horizontalCenter
        width: tipText.implicitWidth + Theme.px(12); height: tipText.implicitHeight + Theme.px(8)
        radius: Theme.radius
        color: Theme.groundDeep
        border.color: Theme.line
        Text { id: tipText; anchors.centerIn: parent; text: parent.text; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(12) }
    }
}

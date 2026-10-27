import QtQuick
import QtMultimedia
import Huginn

// The call on screen, over everything else while it lasts: who, how long, which ways the sound is
// travelling (never an address), and mute, output and end. Controller: A mute, Y output, B or X end
// (asks first), X camera on or off in a video call (docs/protocol-0.6.md 5.8.5). Their camera fills the
// page while it is on; yours is the small picture in the corner. Main.qml shows it while Calls.inCall.
Rectangle {
    id: page
    anchors.fill: parent
    visible: Calls.inCall
    color: Theme.ground

    readonly property bool ended: Calls.state === "ended"
    readonly property bool videoUp: Calls.peerCamera && !ended
    readonly property var prompts: ended ? [] : [{ key: "a", label: Calls.muted ? "Unmute" : "Mute" }, { key: "y", label: "Output" }]
                                               .concat(Calls.videoPossible ? [{ key: "x", label: Calls.cameraOn ? "Camera off" : "Camera on" }] : [])
                                               .concat([{ key: "b", label: "End call" }])
    readonly property string status: ended ? "" : Calls.muted ? "You're muted" : ""

    function initial(name) { const n = String(name || "").trim(); return n.length ? n.charAt(0).toUpperCase() : "?" }
    function endCall() {
        Ui.sure("End the call?", "Your call with " + Calls.peerName + " ends for both of you.", "End call", () => Calls.hangup(), false, true)
    }

    MouseArea { anchors.fill: parent }   // nothing underneath takes clicks during a call

    // Their camera, the whole page, turned upright.
    VideoOutput {
        id: remote
        anchors.fill: parent
        visible: page.videoUp
        fillMode: VideoOutput.PreserveAspectFit
        // The sender says how far to turn it clockwise (5.8.5); VideoOutput's orientation turns clockwise too
        // (Qt 6 docs: "the angle, in degrees, at which the displayed video is rotated clockwise").
        orientation: Calls.peerRotation
        Component.onCompleted: Calls.setRemoteSink(remote.videoSink)
        Accessible.role: Accessible.Graphic
        Accessible.name: "Their camera"
    }
    // Yours, small in the corner, like a mirror.
    Rectangle {
        visible: Calls.cameraOn && !page.ended
        anchors.right: parent.right; anchors.top: parent.top
        anchors.margins: Theme.px(24)
        width: Theme.px(220); height: Theme.px(165)
        radius: Theme.radius
        color: Theme.tile
        border.width: 1; border.color: Theme.line
        clip: true
        VideoOutput {
            id: self
            anchors.fill: parent
            anchors.margins: 1
            fillMode: VideoOutput.PreserveAspectCrop
            transform: Scale { origin.x: self.width / 2; xScale: -1 }
            Component.onCompleted: Calls.setLocalSink(self.videoSink)
        }
        Accessible.role: Accessible.Graphic
        Accessible.name: "Your camera"
    }

    Column {
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.verticalCenter: parent.verticalCenter
        // With their camera on, the controls sit low, over the picture.
        anchors.verticalCenterOffset: page.videoUp ? (parent.height - height) / 2 - Theme.px(30) : 0
        width: Math.min(parent.width - Theme.px(60), Theme.px(620))
        spacing: Theme.px(18)

        // Avatar, with a ring while they talk.
        Item {
            visible: !page.videoUp
            anchors.horizontalCenter: parent.horizontalCenter
            width: Theme.px(132); height: width
            Rectangle {
                anchors.centerIn: parent
                width: parent.width; height: width; radius: width / 2
                color: "transparent"
                border.width: Theme.px(4)
                border.color: Theme.accent
                opacity: Calls.peerSpeaking && !page.ended ? 1 : 0
                Behavior on opacity { NumberAnimation { duration: Theme.motion ? 160 : 0 } }
            }
            Rectangle {
                anchors.centerIn: parent
                width: Theme.px(116); height: width; radius: width / 2
                color: Theme.avatar(Calls.peerName).bg
                Text {
                    anchors.centerIn: parent
                    text: page.initial(Calls.peerName)
                    color: Theme.avatar(Calls.peerName).fg
                    font.family: Theme.body; font.pixelSize: Theme.fs(46); font.weight: Font.DemiBold
                }
            }
        }
        Text {
            width: parent.width
            horizontalAlignment: Text.AlignHCenter
            elide: Text.ElideRight
            text: Calls.peerName
            color: Theme.text
            font.family: Theme.display; font.pixelSize: Theme.fs(28); font.weight: Font.DemiBold; font.letterSpacing: Theme.px(1)
        }
        Text {
            width: parent.width
            horizontalAlignment: Text.AlignHCenter
            text: Calls.statusText + (Calls.peerMuted && !page.ended ? "  ·  they're muted" : "")
            color: page.ended || Calls.state === "reconnecting" ? Theme.danger : Theme.textMuted
            font.family: Theme.body; font.pixelSize: Theme.fs(19)
        }

        // Which ways the sound goes right now.
        Row {
            anchors.horizontalCenter: parent.horizontalCenter
            spacing: Theme.px(10)
            visible: !page.ended
            Repeater {
                model: page.videoUp ? [] : (Calls.directTried ? [{ label: "Internet (direct)", on: Calls.directLive }] : [])
                       .concat([{ label: "Internet (relay)", on: Calls.relayLive && !(Calls.directLive || Calls.wifiLive) }, { label: "Wi-Fi", on: Calls.wifiLive },
                                { label: "Bluetooth", on: Calls.bleLive }])
                Rectangle {
                    height: Theme.px(32)
                    width: chip.implicitWidth + Theme.px(34)
                    radius: height / 2
                    color: modelData.on ? Theme.accentFill : Theme.tile
                    border.width: 1
                    border.color: modelData.on ? Theme.accent : Theme.line
                    Accessible.role: Accessible.StaticText
                    Accessible.name: modelData.label + (modelData.on ? ", in use" : ", not in use")
                    Rectangle {
                        anchors.left: parent.left; anchors.leftMargin: Theme.px(12)
                        anchors.verticalCenter: parent.verticalCenter
                        width: Theme.px(7); height: width; radius: width / 2
                        color: modelData.on ? (Theme.hc ? Theme.text : "#7FB08A") : Theme.textFaint
                    }
                    Text {
                        id: chip
                        anchors.left: parent.left; anchors.leftMargin: Theme.px(25)
                        anchors.verticalCenter: parent.verticalCenter
                        text: modelData.label
                        color: modelData.on ? Theme.text : Theme.textFaint
                        font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: Font.DemiBold
                    }
                }
            }
        }

        Item { width: 1; height: Theme.px(8) }

        Row {
            anchors.horizontalCenter: parent.horizontalCenter
            spacing: Theme.px(16)
            visible: !page.ended
            HButton {
                text: Calls.muted ? "Unmute" : "Mute"
                kind: Calls.muted ? "accent" : "normal"
                onClicked: Calls.toggleMute()
            }
            HButton {
                text: "Output: " + Calls.outputName
                onClicked: Calls.nextOutput()
            }
            HButton {
                visible: Calls.videoPossible
                text: Calls.cameraOn ? "Camera off" : "Camera on"
                kind: Calls.cameraOn ? "accent" : "normal"
                onClicked: Calls.toggleCamera()
            }
            HButton {
                visible: Calls.cameraOn
                text: "Switch camera"
                onClicked: Calls.nextCamera()
            }
            HButton {
                text: "End call"
                kind: "danger"
                onClicked: page.endCall()
            }
        }
    }

    function handleButton(b) {
        if (ended) return true
        switch (b) {
        case "a": Calls.toggleMute(); break
        case "y": Calls.nextOutput(); break
        case "x": if (Calls.videoPossible) Calls.toggleCamera(); else endCall(); break
        case "b": endCall(); break
        }
        return true
    }
    function handleRelease(b) {}
}

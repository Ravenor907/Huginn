import QtQuick
import Huginn

// Asks before something that can't be undone. With `hold` set, A has to be held for 2 seconds
// (a touch also works by holding the button), so a stray press can't erase a drive.
Item {
    id: dlg
    property string title
    property string message
    property string confirmText: "Continue"
    property bool hold: false
    property bool danger: false
    property var callback: null
    property real held: 0

    visible: false
    anchors.fill: parent

    function open(t, msg, confirm, cb, needsHold, isDanger) {
        title = t; message = msg; confirmText = confirm || "Continue"
        callback = cb || null; hold = !!needsHold; danger = !!isDanger
        held = 0; visible = true
    }
    function confirm() {
        visible = false
        holdTimer.stop()
        if (callback) callback()
    }
    function cancel() { visible = false; holdTimer.stop(); held = 0 }

    Timer {
        id: holdTimer
        interval: 40; repeat: true
        onTriggered: {
            dlg.held = Math.min(1, dlg.held + interval / 2000)
            if (dlg.held >= 1) dlg.confirm()
        }
    }
    function startHold() { if (hold) { held = 0; holdTimer.start() } else confirm() }
    function stopHold() { if (hold && held < 1) { holdTimer.stop(); held = 0 } }

    Rectangle { anchors.fill: parent; color: Qt.rgba(0.03, 0.035, 0.04, 0.82) }
    MouseArea { anchors.fill: parent }

    Rectangle {
        anchors.centerIn: parent
        width: Math.min(parent.width - Theme.px(80), Theme.px(720))
        height: col.implicitHeight + Theme.px(48)
        radius: Theme.radius + 1
        color: Theme.panel
        border.color: dlg.danger ? Theme.dangerLine : Theme.lineStrong
        Column {
            id: col
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            anchors.margins: Theme.px(24)
            spacing: Theme.px(16)
            Text {
                text: dlg.title.toUpperCase()
                color: dlg.danger ? Theme.danger : Theme.text
                font.family: Theme.display
                font.pixelSize: Theme.px(20)
                font.weight: Font.Bold
                font.letterSpacing: Theme.px(2)
            }
            Text {
                width: parent.width
                wrapMode: Text.WordWrap
                text: dlg.message
                color: Theme.textSoft
                font.family: Theme.body
                font.pixelSize: Theme.px(18)
                lineHeight: 1.15
            }
            Row {
                anchors.right: parent.right
                spacing: Theme.px(14)
                HButton { text: "Cancel"; onClicked: dlg.cancel() }
                HButton {
                    id: go
                    text: dlg.hold ? (dlg.held > 0 ? "Keep holding…" : "Hold A: " + dlg.confirmText) : dlg.confirmText
                    kind: dlg.danger ? "danger" : "accent"
                    TapHandler {
                        onPressedChanged: pressed ? dlg.startHold() : dlg.stopHold()
                    }
                    Rectangle {
                        visible: dlg.hold
                        anchors.left: parent.left
                        anchors.bottom: parent.bottom
                        height: 3
                        width: parent.width * dlg.held
                        color: dlg.danger ? Theme.danger : Theme.accent
                    }
                }
            }
        }
    }

    function handleButton(b) {
        if (b === "a") startHold()
        else if (b === "b") cancel()
        return true
    }
    function handleRelease(b) { if (b === "a") stopHold() }
}

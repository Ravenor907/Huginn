import QtQuick
import Huginn

// Someone is calling: over whatever is on screen, wherever you are in Huginn. The raven call rings
// (Calls plays it) until you answer, decline, or they give up. A answers, B declines.
Item {
    id: dlg
    anchors.fill: parent
    visible: Calls.state === "incoming"

    readonly property var prompts: [{ key: "a", label: "Answer" }, { key: "b", label: "Decline" }]
    readonly property string status: "Incoming call"
    function initial(name) { const n = String(name || "").trim(); return n.length ? n.charAt(0).toUpperCase() : "?" }
    // A locked Chat doesn't say who it is on screen.
    readonly property string who: Vault.screenLocked ? "Someone" : Calls.peerName

    Rectangle { anchors.fill: parent; color: Qt.rgba(0.03, 0.035, 0.04, 0.82) }
    MouseArea { anchors.fill: parent }

    Rectangle {
        anchors.centerIn: parent
        width: Math.min(parent.width - Theme.px(80), Theme.px(560))
        height: col.implicitHeight + Theme.px(56)
        radius: Theme.radius + 1
        color: Theme.panel
        border.color: Theme.accent
        Column {
            id: col
            anchors.left: parent.left; anchors.right: parent.right; anchors.top: parent.top
            anchors.margins: Theme.px(28)
            spacing: Theme.px(16)
            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                text: "INCOMING CALL"
                color: Theme.accent
                font.family: Theme.display; font.pixelSize: Theme.fs(18); font.weight: Font.Bold; font.letterSpacing: Theme.px(3)
            }
            Rectangle {
                id: avatar
                anchors.horizontalCenter: parent.horizontalCenter
                width: Theme.px(96); height: width; radius: width / 2
                color: Theme.avatar(dlg.who).bg
                Text {
                    anchors.centerIn: parent
                    text: dlg.initial(dlg.who)
                    color: Theme.avatar(dlg.who).fg
                    font.family: Theme.body; font.pixelSize: Theme.fs(38); font.weight: Font.DemiBold
                }
                // A slow pulse while it rings.
                SequentialAnimation on scale {
                    running: dlg.visible && Theme.motion
                    loops: Animation.Infinite
                    NumberAnimation { from: 1; to: 1.06; duration: 700; easing.type: Easing.InOutQuad }
                    NumberAnimation { from: 1.06; to: 1; duration: 700; easing.type: Easing.InOutQuad }
                }
            }
            Text {
                width: parent.width
                horizontalAlignment: Text.AlignHCenter
                elide: Text.ElideRight
                text: dlg.who + " is calling"
                color: Theme.text
                font.family: Theme.body; font.pixelSize: Theme.fs(22); font.weight: Font.DemiBold
            }
            Row {
                anchors.horizontalCenter: parent.horizontalCenter
                spacing: Theme.px(16)
                HButton { text: "Decline"; kind: "danger"; onClicked: Calls.decline() }
                HButton { text: "Answer"; kind: "accent"; onClicked: Calls.answer() }
            }
        }
    }

    function handleButton(b) {
        if (b === "a") Calls.answer()
        else if (b === "b") Calls.decline()
        return true
    }
    function handleRelease(b) {}
}

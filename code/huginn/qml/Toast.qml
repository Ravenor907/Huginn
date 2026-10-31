import QtQuick
import Huginn

// A short message near the top that fades on its own.
Rectangle {
    id: toast
    property bool bad: false
    function show(text, isBad) {
        msg.text = text
        bad = !!isBad
        opacity = 1
        hide.restart()
    }
    anchors.horizontalCenter: parent.horizontalCenter
    y: Theme.px(84)
    z: 50
    // As wide as the words, up to the window; longer ones wrap onto a few lines.
    width: Math.min(msg.implicitWidth + Theme.px(48), parent.width - Theme.px(32))
    height: Math.max(Theme.px(52), msg.height + Theme.px(24))
    radius: Theme.radius
    color: Theme.panel
    border.color: bad ? Theme.dangerLine : Theme.accent
    opacity: 0
    visible: opacity > 0
    Behavior on opacity { NumberAnimation { duration: Theme.motion ? 180 : 0 } }
    Text {
        id: msg
        anchors.centerIn: parent
        width: Math.max(0, toast.width - Theme.px(48))
        horizontalAlignment: Text.AlignHCenter
        wrapMode: Text.Wrap
        maximumLineCount: 4
        elide: Text.ElideRight
        color: toast.bad ? Theme.danger : Theme.text
        font.family: Theme.body
        font.pixelSize: Theme.fs(17)
    }
    Timer { id: hide; interval: 3200; onTriggered: toast.opacity = 0 }
}

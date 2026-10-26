import QtQuick
import Huginn

// The action wheel: hold Y, aim with the stick (or the d-pad, or touch), release or press A.
// The full Elder Futhark runs around it in order as a carved band; it spells nothing.
Item {
    id: wheel
    property var actions: []              // [{ id, label, icon, enabled }]
    property string subject
    property string detail
    property int current: 0
    property bool holdToPick: false        // opened by holding Y: releasing Y picks
    signal chosen(string id)
    signal closed()

    visible: false
    anchors.fill: parent

    function open(acts, subj, det, fromHold) {
        actions = acts
        subject = subj
        detail = det
        holdToPick = fromHold
        current = Math.max(0, acts.findIndex(a => a.enabled !== false))
        visible = true
    }
    function close() { visible = false; closed() }
    function pick() {
        const a = actions[current]
        if (!a || a.enabled === false) return
        visible = false
        chosen(a.id)
    }

    readonly property real cx: width / 2
    readonly property real cy: (height - Theme.px(60)) / 2
    readonly property real ring: Math.min(width, height - Theme.px(60)) * 0.42
    readonly property real itemRadius: ring * 0.6
    readonly property string futhark: "ᚠᚢᚦᚨᚱᚲᚷᚹᚺᚾᛁᛃᛇᛈᛉᛊᛏᛒᛖᛗᛚᛜᛞᛟ"

    Rectangle { anchors.fill: parent; color: Qt.rgba(0.03, 0.035, 0.04, 0.82) }
    MouseArea { anchors.fill: parent; onClicked: wheel.close() }

    Rectangle {
        x: wheel.cx - width / 2; y: wheel.cy - height / 2
        width: wheel.ring * 2; height: width; radius: width / 2
        color: "transparent"; border.color: Theme.lineStrong
    }
    Rectangle {
        x: wheel.cx - width / 2; y: wheel.cy - height / 2
        width: wheel.ring * 1.7; height: width; radius: width / 2
        color: Qt.rgba(0.09, 0.098, 0.106, 0.94); border.color: Theme.line
    }
    Repeater {
        model: 24
        Text {
            readonly property real a: index / 24 * Math.PI * 2 - Math.PI / 2
            x: wheel.cx + wheel.ring * 0.925 * Math.cos(a) - width / 2
            y: wheel.cy + wheel.ring * 0.925 * Math.sin(a) - height / 2
            rotation: index / 24 * 360
            text: wheel.futhark[index]
            color: Theme.textFaint
            font.family: Theme.runic
            font.pixelSize: Theme.px(17)
        }
    }

    Repeater {
        model: wheel.actions
        Rectangle {
            readonly property real a: index / Math.max(1, wheel.actions.length) * Math.PI * 2 - Math.PI / 2
            readonly property bool on: index === wheel.current
            readonly property bool usable: modelData.enabled !== false
            width: Theme.px(108); height: Theme.px(82)
            x: wheel.cx + wheel.itemRadius * Math.cos(a) - width / 2
            y: wheel.cy + wheel.itemRadius * Math.sin(a) - height / 2
            radius: Theme.radius
            opacity: usable ? 1 : 0.35
            color: on ? Theme.accentFill : Theme.raised
            border.color: on ? Theme.accent : Theme.lineStrong
            Column {
                anchors.centerIn: parent
                spacing: Theme.px(4)
                Icon {
                    anchors.horizontalCenter: parent.horizontalCenter
                    name: modelData.icon
                    size: Theme.px(28)
                    color: parent.parent.on ? Theme.accent : Theme.textSoft
                }
                Text {
                    anchors.horizontalCenter: parent.horizontalCenter
                    text: modelData.label
                    color: parent.parent.on ? Theme.accent : Theme.textSoft
                    font.family: Theme.body
                    font.pixelSize: Theme.px(15)
                    font.weight: Font.DemiBold
                }
            }
            TapHandler {
                enabled: parent.usable
                onTapped: { wheel.current = index; wheel.pick() }
            }
        }
    }

    Rectangle {
        x: wheel.cx - width / 2; y: wheel.cy - height / 2
        width: Theme.px(180); height: width; radius: width / 2
        color: Theme.ground; border.color: Theme.accent
        Column {
            anchors.centerIn: parent
            width: parent.width - Theme.px(24)
            spacing: Theme.px(6)
            Text {
                width: parent.width
                horizontalAlignment: Text.AlignHCenter
                text: (wheel.actions[wheel.current] || {}).label ? wheel.actions[wheel.current].label.toUpperCase() : ""
                color: Theme.accent
                font.family: Theme.display
                font.pixelSize: Theme.px(24)
                font.weight: Font.Bold
                font.letterSpacing: Theme.px(2)
                fontSizeMode: Text.HorizontalFit
            }
            Text {
                width: parent.width
                horizontalAlignment: Text.AlignHCenter
                elide: Text.ElideMiddle
                text: wheel.subject
                color: Theme.textSoft
                font.family: Theme.body
                font.pixelSize: Theme.px(15)
            }
            Text {
                width: parent.width
                horizontalAlignment: Text.AlignHCenter
                text: wheel.detail
                color: Theme.textMuted
                font.family: Theme.body
                font.pixelSize: Theme.px(14)
            }
        }
    }

    PromptBar {
        anchors.bottom: parent.bottom
        width: parent.width
        status: "Aim with the left stick or touch"
        prompts: wheel.holdToPick
            ? [{ key: "a", label: "Confirm" }, { key: "b", label: "Close" }, { key: "y", label: "Release to pick" }]
            : [{ key: "a", label: "Confirm" }, { key: "b", label: "Close" }]
        onPrompt: k => k === "a" ? wheel.pick() : wheel.close()
    }

    function step(dir) {
        const n = actions.length
        for (let i = 1; i <= n; ++i) {
            const c = (current + dir * i + n * 4) % n
            if (actions[c].enabled !== false) { current = c; return }
        }
    }
    function aim(x, y) {
        if (Math.hypot(x, y) < 0.5 || actions.length === 0) return
        let ang = Math.atan2(y, x) + Math.PI / 2
        if (ang < 0) ang += Math.PI * 2
        const c = Math.round(ang / (Math.PI * 2) * actions.length) % actions.length
        if (actions[c].enabled !== false) current = c
    }
    function handleButton(b) {
        switch (b) {
        case "a": pick(); return true
        case "b": close(); return true
        case "right": case "down": case "rb": step(1); return true
        case "left": case "up": case "lb": step(-1); return true
        }
        return true
    }
    function handleRelease(b) {
        if (b === "y" && holdToPick) pick()
    }
    Connections {
        target: Gamepad
        enabled: wheel.visible
        function onStickChanged() { wheel.aim(Gamepad.stickX, Gamepad.stickY) }
    }
}

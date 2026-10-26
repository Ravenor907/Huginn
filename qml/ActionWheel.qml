import QtQuick
import QtQuick.Shapes
import Huginn

// The action wheel: hold Y, aim with the stick (or the d-pad, or touch), release or press A.
// The full Elder Futhark runs around it in order as a carved band; it spells nothing.
Item {
    id: wheel
    property var actions: []              // [{ id, label, icon, enabled }]
    property string subject
    property string detail
    property int current: -1           // -1: nothing picked, the safe default
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
        current = -1
        stickOut = false
        visible = true
    }
    function close() { visible = false; closed() }
    function pick() {
        const a = actions[current]
        if (!a || a.enabled === false) { close(); return }
        visible = false
        chosen(a.id)
    }

    readonly property real cx: width / 2
    readonly property real cy: (height - Theme.px(60)) / 2
    readonly property real ring: Math.min(width, height - Theme.px(60)) * 0.42
    readonly property real itemRadius: ring * 0.6
    readonly property string futhark: "ᚠᚢᚦᚨᚱᚲᚷᚹᚺᚾᛁᛃᛇᛈᛉᛊᛏᛒᛖᛗᛚᛜᛞᛟ"

    Rectangle { anchors.fill: parent; color: Qt.rgba(0.03, 0.035, 0.04, 0.82) }

    // The wheel is a pie: one slice per action, the picked slice filled with gold.
    readonly property real rOut: ring * 0.84
    readonly property real rIn: ring * 0.34
    readonly property real sliceStep: Math.PI * 2 / Math.max(1, actions.length)

    function wedge(i, r0, r1) {
        const a0 = i * sliceStep - Math.PI / 2 - sliceStep / 2
        const a1 = a0 + sliceStep
        const p = (r, a) => (cx + r * Math.cos(a)).toFixed(1) + " " + (cy + r * Math.sin(a)).toFixed(1)
        return "M" + p(r0, a0) + " L" + p(r1, a0) + " A" + r1 + " " + r1 + " 0 0 1 " + p(r1, a1)
             + " L" + p(r0, a1) + " A" + r0 + " " + r0 + " 0 0 0 " + p(r0, a0) + " Z"
    }

    Rectangle {
        x: wheel.cx - width / 2; y: wheel.cy - height / 2
        width: wheel.ring * 2; height: width; radius: width / 2
        color: "transparent"; border.color: Theme.lineStrong
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
            font.pixelSize: Theme.fs(17)
        }
    }

    Repeater {
        model: wheel.actions
        Shape {
            anchors.fill: parent
            preferredRendererType: Shape.CurveRenderer
            readonly property bool on: index === wheel.current
            readonly property bool usable: modelData.enabled !== false
            ShapePath {
                fillColor: parent.on ? Theme.accentFill : parent.usable ? "#1B1D1F" : "#151617"
                strokeColor: Theme.ground
                strokeWidth: Theme.px(4)
                joinStyle: ShapePath.RoundJoin
                PathSvg { path: wheel.wedge(index, wheel.rIn, wheel.rOut) }
            }
        }
    }
    Shape {
        anchors.fill: parent
        visible: wheel.current >= 0
        preferredRendererType: Shape.CurveRenderer
        ShapePath {
            fillColor: "transparent"
            strokeColor: Theme.accent
            strokeWidth: Math.max(2, Theme.px(2))
            joinStyle: ShapePath.RoundJoin
            PathSvg { path: wheel.current >= 0 ? wheel.wedge(wheel.current, wheel.rIn, wheel.rOut) : "" }
        }
    }

    Repeater {
        model: wheel.actions
        Column {
            readonly property real a: index * wheel.sliceStep - Math.PI / 2
            readonly property real mid: (wheel.rIn + wheel.rOut) / 2
            readonly property bool on: index === wheel.current
            readonly property bool usable: modelData.enabled !== false
            x: wheel.cx + mid * Math.cos(a) - width / 2
            y: wheel.cy + mid * Math.sin(a) - height / 2
            spacing: Theme.px(4)
            Accessible.role: Accessible.MenuItem
            Accessible.name: modelData.label
            Icon {
                anchors.horizontalCenter: parent.horizontalCenter
                name: modelData.icon
                size: Theme.px(28)
                color: parent.on ? Theme.accent : parent.usable ? Theme.textSoft : Theme.textFaint
            }
            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                text: modelData.label
                color: parent.on ? Theme.accent : parent.usable ? Theme.textSoft : Theme.textFaint
                font.family: Theme.body
                font.pixelSize: Theme.fs(15)
                font.weight: Font.DemiBold
            }
        }
    }

    // Touch: tapping a slice picks it; tapping the hub or outside closes.
    MouseArea {
        anchors.fill: parent
        onClicked: (mouse) => {
            const dx = mouse.x - wheel.cx, dy = mouse.y - wheel.cy
            const r = Math.hypot(dx, dy)
            if (r < wheel.rIn || r > wheel.rOut) { wheel.close(); return }
            let ang = Math.atan2(dy, dx) + Math.PI / 2
            if (ang < 0) ang += Math.PI * 2
            const i = Math.round(ang / wheel.sliceStep) % wheel.actions.length
            if (wheel.actions[i].enabled === false) return
            wheel.current = i
            wheel.pick()
        }
    }

    Rectangle {
        x: wheel.cx - width / 2; y: wheel.cy - height / 2
        width: (wheel.rIn - Theme.px(4)) * 2; height: width; radius: width / 2
        color: Theme.ground; border.color: wheel.current >= 0 ? Theme.accent : Theme.lineMuted
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
                font.pixelSize: Theme.fs(24)
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
                font.pixelSize: Theme.fs(15)
            }
            Text {
                width: parent.width
                horizontalAlignment: Text.AlignHCenter
                text: wheel.detail
                color: Theme.textMuted
                font.family: Theme.body
                font.pixelSize: Theme.fs(14)
            }
        }
    }

    PromptBar {
        anchors.bottom: parent.bottom
        width: parent.width
        status: "Aim with the left stick or touch. Let the stick go back to the middle to pick nothing."
        prompts: wheel.holdToPick
            ? [{ key: "a", label: "Confirm" }, { key: "b", label: "Close" }, { key: "y", label: "Release to pick" }]
            : [{ key: "a", label: "Confirm" }, { key: "b", label: "Close" }]
        onPrompt: k => k === "a" ? wheel.pick() : wheel.close()
    }

    function step(dir) {
        const n = actions.length
        if (current < 0) current = dir > 0 ? -1 : 0
        for (let i = 1; i <= n; ++i) {
            const c = (current + dir * i + n * 4) % n
            if (actions[c].enabled !== false) { current = c; return }
        }
    }
    // Holding Y ties up the right thumb, so the way out has to be on the left stick: let it
    // spring back to center and nothing is picked, then letting go of Y just closes the wheel.
    property bool stickOut: false
    function aim(x, y) {
        const r = Math.hypot(x, y)
        if (r < 0.3) { if (stickOut) { stickOut = false; current = -1 } return }
        if (r < 0.5 || actions.length === 0) return
        stickOut = true
        let ang = Math.atan2(y, x) + Math.PI / 2
        if (ang < 0) ang += Math.PI * 2
        const c = Math.round(ang / (Math.PI * 2) * actions.length) % actions.length
        if (actions[c].enabled !== false) current = c
    }
    function handleButton(b) {
        // One press moves one slice. Holding a direction must not spin the wheel.
        if (Gamepad.repeating() && ["up", "down", "left", "right", "lb", "rb"].indexOf(b) >= 0) return true
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

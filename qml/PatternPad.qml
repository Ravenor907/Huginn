import QtQuick
import Huginn

// The 9-dot pattern, like Android's, in Huginn's colors. Draw with a finger on the touchscreen,
// or with the controller: the D-pad moves, A connects the dot, X starts over, Y is done.
// Passing straight over a dot in between picks it up too, as on Android.
Item {
    id: pad
    property bool error: false
    property bool enabled: true
    property var dots: []
    property int cursor: 4
    property point finger: Qt.point(-1, -1)
    signal done(var dots)

    implicitWidth: Theme.px(300)
    implicitHeight: implicitWidth

    function center(i) { return Qt.point(width * ((i % 3) * 2 + 1) / 6, height * (Math.floor(i / 3) * 2 + 1) / 6) }
    function hit(x, y) {
        for (let i = 0; i < 9; i++) {
            const c = center(i)
            if (Math.hypot(c.x - x, c.y - y) < width / 9) return i
        }
        return -1
    }
    function add(i) {
        if (i < 0 || dots.indexOf(i) >= 0) return
        const d = dots.slice()
        if (d.length) {
            const last = d[d.length - 1]
            const r1 = Math.floor(last / 3), c1 = last % 3, r2 = Math.floor(i / 3), c2 = i % 3
            if ((r1 + r2) % 2 === 0 && (c1 + c2) % 2 === 0) {
                const mid = (r1 + r2) / 2 * 3 + (c1 + c2) / 2
                if (d.indexOf(mid) < 0) d.push(mid)
            }
        }
        d.push(i)
        dots = d
        canvas.requestPaint()
    }
    function finish() {
        const d = dots
        dots = []
        finger = Qt.point(-1, -1)
        canvas.requestPaint()
        if (d.length) done(d)
    }
    function clear() { dots = []; canvas.requestPaint() }

    function handleButton(b) {
        if (!enabled) return false
        switch (b) {
        case "up": cursor = cursor >= 3 ? cursor - 3 : cursor; break
        case "down": cursor = cursor < 6 ? cursor + 3 : cursor; break
        case "left": cursor = cursor % 3 ? cursor - 1 : cursor; break
        case "right": cursor = cursor % 3 < 2 ? cursor + 1 : cursor; break
        case "a": add(cursor); break
        case "x": clear(); break
        case "y": finish(); break
        default: return false
        }
        canvas.requestPaint()
        return true
    }
    onErrorChanged: canvas.requestPaint()

    Canvas {
        id: canvas
        anchors.fill: parent
        onPaint: {
            const ctx = getContext("2d")
            ctx.reset()
            const col = pad.error ? Theme.danger : Theme.accent
            ctx.strokeStyle = col
            ctx.lineWidth = width / 40
            ctx.lineCap = "round"
            if (pad.dots.length) {
                ctx.beginPath()
                const s = pad.center(pad.dots[0])
                ctx.moveTo(s.x, s.y)
                for (let k = 1; k < pad.dots.length; k++) { const c = pad.center(pad.dots[k]); ctx.lineTo(c.x, c.y) }
                if (pad.finger.x >= 0) ctx.lineTo(pad.finger.x, pad.finger.y)
                ctx.stroke()
            }
            for (let i = 0; i < 9; i++) {
                const c = pad.center(i)
                const on = pad.dots.indexOf(i) >= 0
                ctx.fillStyle = on ? col : Theme.textFaint
                ctx.beginPath()
                ctx.arc(c.x, c.y, width / (on ? 22 : 34), 0, Math.PI * 2)
                ctx.fill()
                if (i === pad.cursor && Gamepad.connected && pad.enabled) {
                    // The controller's cursor: a ring around the dot it's on.
                    ctx.strokeStyle = Theme.accent
                    ctx.lineWidth = Math.max(2, width / 120)
                    ctx.beginPath()
                    ctx.arc(c.x, c.y, width / 14, 0, Math.PI * 2)
                    ctx.stroke()
                    ctx.strokeStyle = col
                    ctx.lineWidth = width / 40
                }
            }
        }
    }
    MouseArea {
        anchors.fill: parent
        enabled: pad.enabled
        onPressed: m => { pad.dots = []; pad.finger = Qt.point(m.x, m.y); pad.add(pad.hit(m.x, m.y)); canvas.requestPaint() }
        onPositionChanged: m => { pad.finger = Qt.point(m.x, m.y); pad.add(pad.hit(m.x, m.y)); canvas.requestPaint() }
        onReleased: pad.finish()
    }
}

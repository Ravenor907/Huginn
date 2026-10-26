import QtQuick
import QtQuick.Shapes
import Huginn

// Line icons drawn on a 24-unit grid, so they stay sharp at any size and take any color.
Item {
    id: icon
    property string name: "file"
    property color color: Theme.textMuted
    property real lineWidth: 1.6
    property int size: Theme.px(24)

    width: size
    height: size

    readonly property var paths: ({
        "folder": "M3 6h7l2 2h9v11H3z",
        "file": "M6 3h8l4 4v14H6z M14 3v4h4",
        "image": "M3 5h18v14H3z M3 16l5-5 4 4 3-3 6 6",
        "network": "M3 5h18v10H3z M8 19h8 M12 15v4",
        "phone": "M8 3h8v18H8z M11 18h2",
        "call": "M3 15c5-5 13-5 18 0l-2.5 2.5-3.5-1.5v-2.5a10 10 0 0 0-6 0V16l-3.5 1.5z",
        "gear": "M12 9a3 3 0 1 0 0.01 0 M12 2v3 M12 19v3 M2 12h3 M19 12h3 M4.9 4.9l2.1 2.1 M17 17l2.1 2.1 M4.9 19.1L7 17 M17 7l2.1-2.1",
        "power": "M12 3v8 M7 6.5a7.5 7.5 0 1 0 10 0",
        "send": "M4 12l16-8-6 16-2-6z M12 14l8-10",
        "shield": "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z",
        "attach": "M20 12l-8 8a5 5 0 0 1-7-7l9-9a3.5 3.5 0 0 1 5 5l-9 9a2 2 0 0 1-3-3l8-8",
        "people": "M9 11a3.5 3.5 0 1 0 0.01 0 M3 20a6 6 0 0 1 12 0 M16 11a3 3 0 1 0 0.01 0 M16 15a5 5 0 0 1 5 5",
        "check": "M5 12l5 5 9-10",
        "back": "M15 5l-7 7 7 7",
        "forward": "M9 5l7 7-7 7",
        "lock": "M6 11h12v9H6z M9 11V8a3 3 0 0 1 6 0v3",
        "info": "M12 3l9 9-9 9-9-9z M12 10v6 M12 7v1",
        "plus": "M12 5v14 M5 12h14",
        "qr": "M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h2v2h-2z M18 18h2v2h-2z M14 18h2 M18 14h2",
        "play": "M7 4l13 8-13 8z",
        "stop": "M7 7h10v10H7z",
        "mic": "M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z M5 11a7 7 0 0 0 14 0 M12 18v3"
    })

    Shape {
        anchors.fill: parent
        preferredRendererType: Shape.CurveRenderer
        ShapePath {
            strokeColor: icon.color
            strokeWidth: icon.lineWidth * Math.max(1, icon.size / 28)
            fillColor: "transparent"
            capStyle: ShapePath.SquareCap
            joinStyle: ShapePath.MiterJoin
            scale: Qt.size(icon.size / 24, icon.size / 24)
            PathSvg { path: icon.paths[icon.name] || icon.paths["file"] }
        }
    }
}

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
        "folder": "M3 6h6l2 2h10v11H3z",
        "file": "M6 3h8l4 4v14H6z M14 3v4h4",
        "image": "M3 5h18v14H3z M3 16l5-5 4 4 3-3 6 6",
        "archive": "M4 7h16v13H4z M4 7l2-3h12l2 3 M12 7v5 M10 12h4v3h-4z",
        "disc": "M12 3a9 9 0 1 0 0.01 0 M12 9.5a2.5 2.5 0 1 0 0.01 0",
        "text": "M6 3h8l4 4v14H6z M14 3v4h4 M9 12h6 M9 15h6 M9 18h4",
        "video": "M3 6h13v12H3z M16 10l5-3v10l-5-3",
        "audio": "M9 18V5l11-2v13 M6 18a3 3 0 1 0 0.01 0 M17 16a3 3 0 1 0 0.01 0",
        "home": "M3 11l9-7 9 7 M5 10v10h14V10 M10 20v-6h4v6",
        "sd": "M7 3h8l4 4v14H7z M10 3v4 M13 3v4",
        "steam": "M4 6h16v12H4z M8 10h8 M8 14h5",
        "game": "M6 8h12a4 4 0 0 1 4 4v1a4 4 0 0 1-7 2.6H9A4 4 0 0 1 2 13v-1a4 4 0 0 1 4-4z M7 11v4 M5 13h4 M16 12h0.01 M18 14h0.01",
        "screenshot": "M4 8V4h4 M16 4h4v4 M20 16v4h-4 M8 20H4v-4 M12 9a3 3 0 1 0 0.01 0",
        "download": "M12 4v11 M7 10l5 5 5-5 M5 20h14",
        "network": "M3 5h18v10H3z M8 19h8 M12 15v4",
        "server": "M4 6h16v5H4z M4 13h16v5H4z M7 8.5h0.01 M7 15.5h0.01",
        "phone": "M8 3h8v18H8z M11 18h2",
        "usb": "M7 9h10v12H7z M9 3h6v6H9z",
        "gear": "M12 9a3 3 0 1 0 0.01 0 M12 2v3 M12 19v3 M2 12h3 M19 12h3 M4.9 4.9l2.1 2.1 M17 17l2.1 2.1 M4.9 19.1L7 17 M17 7l2.1-2.1",
        "search": "M11 4a7 7 0 1 0 0.01 0 M20 20l-3.5-3.5",
        "copy": "M8 8h11v11H8z M5 16V5h11",
        "move": "M4 12h14 M13 6l6 6-6 6",
        "rename": "M4 20h4L19 9l-4-4L4 16z",
        "extract": "M4 7h16v13H4z M12 10v7 M9 14l3 3 3-3",
        "compress": "M4 7h16v13H4z M12 10v7 M9 13l3-3 3 3",
        "delete": "M5 7h14 M9 7V4h6v3 M7 7l1 13h8l1-13",
        "info": "M12 3l9 9-9 9-9-9z M12 10v6 M12 7v1",
        "newfolder": "M3 6h6l2 2h10v11H3z M12 11v5 M9.5 13.5h5",
        "check": "M5 12l5 5 9-10",
        "back": "M15 5l-7 7 7 7",
        "forward": "M9 5l7 7-7 7",
        "undo": "M9 14L4 9l5-5 M4 9h11a5 5 0 0 1 0 10h-3",
        "link": "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1 M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1",
        "lock": "M6 11h12v9H6z M9 11V8a3 3 0 0 1 6 0v3",
        "open": "M5 12h14 M12 5l7 7-7 7",
        "clock": "M12 3a9 9 0 1 0 0.01 0 M12 7v5l3 3"
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

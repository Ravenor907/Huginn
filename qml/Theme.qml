pragma Singleton
import QtQuick
import Huginn

QtObject {
    id: theme

    // Stone, iron and aged gold.
    readonly property color ground: "#101112"
    readonly property color groundDeep: "#0C0D0E"
    readonly property color panel: "#17191B"
    readonly property color panelLow: "#141617"
    readonly property color raised: "#1D1E20"
    readonly property color tile: "#24262A"
    readonly property color line: "#2C2F33"
    readonly property color lineStrong: "#34373B"
    readonly property color lineMuted: "#45484D"
    readonly property color text: "#E4E1DA"
    readonly property color textSoft: "#C4C0B7"
    readonly property color textMuted: "#9C978D"
    readonly property color textFaint: "#6E6A62"
    readonly property color ornament: "#5E5A53"
    readonly property color danger: "#D9A79E"
    readonly property color dangerLine: "#5A3A34"
    readonly property color dangerFill: "#1E1716"

    readonly property var accents: ({ "gold": "#C2A25E", "iron": "#A9B4BE", "copper": "#B07A5A" })
    readonly property color accent: accents[Settings.accent] || accents.gold
    readonly property color accentFill: Qt.rgba(accent.r * 0.25 + 0.06, accent.g * 0.25 + 0.06, accent.b * 0.25 + 0.06, 1)
    readonly property color focusFill: Qt.rgba(accent.r * 0.12 + 0.11, accent.g * 0.12 + 0.11, accent.b * 0.12 + 0.1, 1)

    // TV layout scales everything up for the couch.
    readonly property bool tv: System.tvLayout
    readonly property real s: tv ? 1.45 : 1.0
    function px(v) { return Math.round(v * s) }

    readonly property string display: "Cinzel"
    readonly property string body: "Barlow"
    readonly property string runic: "Noto Sans Runic"

    readonly property int radius: 3
    readonly property int rowHeight: px(56)
}

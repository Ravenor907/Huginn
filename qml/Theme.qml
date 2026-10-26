pragma Singleton
import QtQuick
import Huginn

QtObject {
    id: theme

    // Settings › General › Accessibility.
    readonly property bool hc: Settings.highContrast
    readonly property real textScale: Number(Settings.textSize) / 100
    readonly property bool motion: !Settings.reduceMotion

    // Stone, iron and aged gold.
    readonly property color ground: hc ? "#000000" : "#101112"
    readonly property color groundDeep: hc ? "#000000" : "#0C0D0E"
    readonly property color panel: hc ? "#0A0A0A" : "#17191B"
    readonly property color panelLow: hc ? "#050505" : "#141617"
    readonly property color raised: hc ? "#1A1A1A" : "#1D1E20"
    readonly property color tile: hc ? "#1F1F1F" : "#24262A"
    readonly property color line: hc ? "#9A9A9A" : "#2C2F33"
    readonly property color lineStrong: hc ? "#B0B0B0" : "#34373B"
    readonly property color lineMuted: hc ? "#BDBDBD" : "#45484D"
    readonly property color text: hc ? "#FFFFFF" : "#E4E1DA"
    readonly property color textSoft: hc ? "#FFFFFF" : "#C4C0B7"
    readonly property color textMuted: hc ? "#E0E0E0" : "#9C978D"
    readonly property color textFaint: hc ? "#C8C8C8" : "#6E6A62"
    readonly property color ornament: hc ? "#A0A0A0" : "#5E5A53"
    // Color-blind friendly: amber instead of rose, so warnings never depend on telling red from green.
    readonly property color danger: Settings.colorBlind ? "#F2B33D" : hc ? "#FFB4A8" : "#D9A79E"
    readonly property color dangerLine: Settings.colorBlind ? "#7A5A1E" : "#5A3A34"
    readonly property color dangerFill: Settings.colorBlind ? "#221B0E" : "#1E1716"

    readonly property var accents: ({ "gold": "#C2A25E", "iron": "#A9B4BE", "copper": "#B07A5A" })
    readonly property color accent: accents[Settings.accent] || accents.gold
    readonly property color accentFill: Qt.rgba(accent.r * 0.25 + 0.06, accent.g * 0.25 + 0.06, accent.b * 0.25 + 0.06, 1)
    readonly property color focusFill: Qt.rgba(accent.r * 0.12 + 0.11, accent.g * 0.12 + 0.11, accent.b * 0.12 + 0.1, 1)

    // Everything scales with the screen and the Size setting (see System::uiScale).
    readonly property bool tv: System.tvLayout
    readonly property real s: System.uiScale
    function px(v) { return Math.round(v * s) }
    // Text sizes: the overall Size, times the separate Text size.
    function fs(v) { return Math.round(v * s * textScale) }

    readonly property string display: "Cinzel"
    readonly property string body: "Barlow"
    readonly property string runic: "Noto Sans Runic"

    readonly property int radius: 3
    // Controller focus: thick enough to spot at arm's length on a 7 inch screen.
    readonly property int focusWidth: Math.max(hc ? 4 : 3, px(hc ? 3.5 : 2.5))
    readonly property int rowHeight: px(56)
}

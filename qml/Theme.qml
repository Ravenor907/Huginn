pragma Singleton
import QtQuick
import Huginn

QtObject {
    id: theme

    // Settings › General › Accessibility.
    readonly property bool hc: Settings.highContrast
    readonly property real textScale: Number(Settings.textSize) / 100
    readonly property bool motion: !Settings.reduceMotion

    // Settings › Appearance: "dark" (stone, iron and aged gold), "black" (true black, for OLED
    // screens) or "light" (parchment). High contrast wins over all three.
    readonly property string mode: Settings.theme === "black" || Settings.theme === "light" ? Settings.theme : "dark"
    readonly property bool light: mode === "light" && !hc
    readonly property bool black: mode === "black" && !hc
    function pick(dark, deep, parchment, contrast) { return hc ? contrast : light ? parchment : black ? deep : dark }

    readonly property color ground: pick("#101112", "#000000", "#F3EFE6", "#000000")
    readonly property color groundDeep: pick("#0C0D0E", "#000000", "#E7E1D4", "#000000")
    readonly property color panel: pick("#17191B", "#0B0C0D", "#FBF9F4", "#0A0A0A")
    readonly property color panelLow: pick("#141617", "#060607", "#ECE7DC", "#050505")
    readonly property color raised: pick("#1D1E20", "#141516", "#E2DCCF", "#1A1A1A")
    readonly property color tile: pick("#24262A", "#1B1C1E", "#D8D1C2", "#1F1F1F")
    readonly property color line: pick("#2C2F33", "#232528", "#CEC6B6", "#9A9A9A")
    readonly property color lineStrong: pick("#34373B", "#2B2E31", "#BDB4A2", "#B0B0B0")
    readonly property color lineMuted: pick("#45484D", "#3E4146", "#A69D8B", "#BDBDBD")
    readonly property color text: pick("#E4E1DA", "#E4E1DA", "#1F1C17", "#FFFFFF")
    readonly property color textSoft: pick("#C4C0B7", "#C4C0B7", "#3B362E", "#FFFFFF")
    readonly property color textMuted: pick("#9C978D", "#9C978D", "#615A4E", "#E0E0E0")
    readonly property color textFaint: pick("#6E6A62", "#6E6A62", "#877F71", "#C8C8C8")
    readonly property color ornament: pick("#5E5A53", "#5E5A53", "#958B79", "#A0A0A0")
    // The three columns: the rail on the far left, the list beside it, and the open conversation.
    readonly property color rail: groundDeep
    readonly property color column: panelLow
    readonly property color seam: pick("#1F2125", "#16181A", "#D9D2C4", "#9A9A9A")
    // Color-blind friendly: amber instead of rose, so warnings never depend on telling red from green.
    readonly property color danger: Settings.colorBlind ? (light ? "#8A5A00" : "#F2B33D") : hc ? "#FFB4A8" : light ? "#A23B2C" : "#D9A79E"
    readonly property color dangerLine: Settings.colorBlind ? (light ? "#C79A45" : "#7A5A1E") : light ? "#D9A79E" : "#5A3A34"
    readonly property color dangerFill: Settings.colorBlind ? (light ? "#F6EBD4" : "#221B0E") : light ? "#F5E2DD" : "#1E1716"
    // Someone's status in the member list: online, idle, and in a voice room (always said in words too).
    readonly property color online: light ? "#2F7D46" : "#5FB872"
    readonly property color idle: light ? "#9A6B00" : "#D9A440"

    // The accent: a named one or any #RRGGBB from the picker. On parchment it's darkened to stay readable.
    readonly property var accents: ({ "gold": "#C2A25E", "iron": "#A9B4BE", "copper": "#B07A5A", "jade": "#6FAE8C",
                                      "fjord": "#6E9CC8", "heather": "#A58BC6", "ember": "#CF6F54" })
    readonly property var accentNames: ["gold", "iron", "copper", "jade", "fjord", "heather", "ember"]
    function validColor(v) { return /^#[0-9A-Fa-f]{6}$/.test(String(v || "")) }
    readonly property color accentBase: accents[Settings.accent] || (validColor(Settings.accent) ? Settings.accent : accents.gold)
    readonly property color accent: light ? Qt.darker(accentBase, 1.7) : accentBase
    readonly property color accentFill: light ? Qt.rgba(accent.r * 0.18 + 0.8, accent.g * 0.18 + 0.78, accent.b * 0.18 + 0.72, 1)
                                              : Qt.rgba(accent.r * 0.25 + 0.06, accent.g * 0.25 + 0.06, accent.b * 0.25 + 0.06, 1)
    readonly property color focusFill: light ? Qt.rgba(accent.r * 0.1 + 0.86, accent.g * 0.1 + 0.84, accent.b * 0.1 + 0.79, 1)
                                             : Qt.rgba(accent.r * 0.12 + 0.11, accent.g * 0.12 + 0.11, accent.b * 0.12 + 0.1, 1)

    // Everything scales with the screen, the layout density and the Size setting (see System::uiScale).
    // Desktop gets a boost on top: System's desktop density was far too small to read on a 1080p or
    // 1440p monitor at arm's length (body text near 14 px). With it, 1080p reads like the mockups
    // (16 to 18 px) and 1440p grows with the screen.
    readonly property bool desktop: System.desktop
    readonly property real s: System.uiScale * (desktop ? 1.3 : 1)
    function px(v) { return Math.round(v * s) }
    // Text sizes: the overall Size, times the separate Text size.
    function fs(v) { return Math.round(v * s * textScale) }
    // Messages (Settings › Appearance): compact or cozy, and their own text size on top of the rest.
    readonly property bool compact: Settings.density === "compact"
    readonly property real messageScale: Math.max(0.7, Math.min(1.6, Number(Settings.messageSize) / 100 || 1))
    function ms(v) { return Math.round(v * s * textScale * messageScale) }

    // Panel widths (Settings › Appearance; dragged on the desktop). A handheld keeps the fixed ones.
    readonly property int railWidth: px(desktop ? Ui.railW : 83)
    readonly property int listWidth: px(desktop ? Ui.listW : 270)
    readonly property int memberWidth: px(desktop ? Ui.memberW : 230)
    // Hidden side panels (the desktop only; a handheld always shows them).
    readonly property bool listShown: !desktop || Settings.showList
    readonly property bool membersShown: !desktop || Settings.showMembers

    readonly property string display: "Cinzel"
    readonly property string body: "Barlow"
    readonly property string runic: "Noto Sans Runic"

    readonly property int radius: 3
    // Controller focus: thick enough to spot at arm's length on a 7 inch screen.
    readonly property int focusWidth: Math.max(hc ? 4 : 3, px(hc ? 3.5 : 2.5))
    readonly property int rowHeight: px(56)

    // Avatars: a soft color per person, picked from their name so it never changes. Your own is gold.
    readonly property var avatarTones: hc ? [{ bg: "#1F1F1F", fg: "#FFFFFF" }]
        : light ? [{ bg: "#D5E4D3", fg: "#2E5A33" }, { bg: "#D6DDEB", fg: "#2F4A78" }, { bg: "#EED5CF", fg: "#86392A" }, { bg: "#E2D6EA", fg: "#5D3D78" }, { bg: "#D0E5E7", fg: "#235A60" }]
        : [{ bg: "#2C3A2C", fg: "#B6D6B6" }, { bg: "#2D3340", fg: "#B9C6E0" }, { bg: "#3A2724", fg: "#E6A596" }, { bg: "#33293A", fg: "#D2BCE0" }, { bg: "#24363A", fg: "#AED4D8" }]
    readonly property var avatarMine: hc ? ({ bg: "#1F1F1F", fg: "#FFFFFF" }) : light ? ({ bg: "#EADDBB", fg: "#6B5216" }) : ({ bg: "#3A3220", fg: "#E0C27F" })
    function avatar(name, mine) {
        // Never undefined, even while a theme change is still settling.
        const plain = { bg: raised, fg: text }
        if (mine) return avatarMine || plain
        const tones = avatarTones || []
        let h = 0
        const n = String(name || "")
        for (let i = 0; i < n.length; ++i) h = (h * 31 + n.charCodeAt(i)) % 9973
        return tones[h % Math.max(1, tones.length)] || plain
    }

}

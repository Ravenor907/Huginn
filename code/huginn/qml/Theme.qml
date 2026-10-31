pragma Singleton
import QtQuick
import Huginn

QtObject {
    id: theme

    // Settings › General › Accessibility.
    readonly property bool hc: Settings.highContrast
    readonly property real textScale: Number(Settings.textSize) / 100
    readonly property bool motion: !Settings.reduceMotion

    // Settings › Look: "dark" (stone, iron and aged gold), "black" (true black, for OLED screens),
    // "light" (parchment) or "autumn" (warm bark and leaves falling behind frosted panes).
    // High contrast wins over all four.
    readonly property string mode: ["black", "light", "autumn"].indexOf(Settings.theme) >= 0 ? Settings.theme : "dark"
    readonly property bool light: mode === "light" && !hc
    readonly property bool black: mode === "black" && !hc
    readonly property bool autumn: mode === "autumn" && !hc
    function pick(dark, deep, parchment, contrast, leaf) { return hc ? contrast : light ? parchment : black ? deep : autumn ? (leaf || dark) : dark }
    // A saved look that no longer exists falls back to Dark.
    Component.onCompleted: if (["dark", "black", "light", "autumn"].indexOf(Settings.theme) < 0) Settings.theme = "dark"

    readonly property color ground: pick("#101112", "#000000", "#F3EFE6", "#000000", "#221710")
    readonly property color groundDeep: pick("#0C0D0E", "#000000", "#E7E1D4", "#000000", "#1A110B")
    readonly property color panel: pick("#17191B", "#0B0C0D", "#FBF9F4", "#0A0A0A", "#2B1D13")
    readonly property color panelLow: pick("#141617", "#060607", "#ECE7DC", "#050505", "#241911")
    readonly property color raised: pick("#1D1E20", "#141516", "#E2DCCF", "#1A1A1A", "#4A3421")
    readonly property color tile: pick("#24262A", "#1B1C1E", "#D8D1C2", "#1F1F1F", "#573E28")
    readonly property color line: pick("#2C2F33", "#232528", "#CEC6B6", "#9A9A9A", "#3E2B1B")
    readonly property color lineStrong: pick("#34373B", "#2B2E31", "#BDB4A2", "#B0B0B0", "#4A3421")
    readonly property color lineMuted: pick("#45484D", "#3E4146", "#A69D8B", "#BDBDBD", "#6B4D33")
    readonly property color text: pick("#E4E1DA", "#E4E1DA", "#1F1C17", "#FFFFFF", "#ECDCC4")
    readonly property color textSoft: pick("#C4C0B7", "#C4C0B7", "#3B362E", "#FFFFFF", "#D8C3A5")
    readonly property color textMuted: pick("#9C978D", "#9C978D", "#615A4E", "#E0E0E0", "#A88C6C")
    readonly property color textFaint: pick("#6E6A62", "#6E6A62", "#877F71", "#C8C8C8", "#7D6549")
    readonly property color ornament: pick("#5E5A53", "#5E5A53", "#958B79", "#A0A0A0", "#8A6D4C")
    // The brightest text (a title, your own name) and section headings.
    readonly property color textBright: autumn ? "#F3E2C7" : text
    readonly property color section: autumn ? "#B08A5E" : textMuted
    // The three columns: the rail on the far left, the list beside it, and the open conversation.
    // Autumn: the rail and the lists are panes the leaves show through faintly (about 80% solid),
    // and the open conversation is frosted glass (about 38%), over the ground Leaves.qml draws.
    readonly property color rail: autumn ? Qt.rgba(0.102, 0.067, 0.043, 0.85) : groundDeep
    readonly property color column: autumn ? Qt.rgba(0.141, 0.098, 0.067, 0.8) : panelLow
    readonly property color sheet: autumn ? Qt.rgba(0.133, 0.090, 0.063, 0.38) : ground
    readonly property color seam: pick("#1F2125", "#16181A", "#D9D2C4", "#9A9A9A", "#35251A")
    // Color-blind friendly: amber instead of rose, so warnings never depend on telling red from green.
    readonly property color danger: Settings.colorBlind ? (light ? "#8A5A00" : "#F2B33D") : hc ? "#FFB4A8" : light ? "#A23B2C" : autumn ? "#E08A7A" : "#D9A79E"
    readonly property color dangerLine: Settings.colorBlind ? (light ? "#C79A45" : "#7A5A1E") : light ? "#D9A79E" : autumn ? "#6E3A30" : "#5A3A34"
    readonly property color dangerFill: Settings.colorBlind ? (light ? "#F6EBD4" : "#221B0E") : light ? "#F5E2DD" : autumn ? "#2E1A14" : "#1E1716"
    // Someone's status: online, idle or away, do not disturb, and in a voice room (always said in words too).
    readonly property color online: light ? "#2F7D46" : autumn ? "#3FB27F" : "#5FB872"
    readonly property color idle: light ? "#9A6B00" : autumn ? "#E0B44C" : "#D9A440"
    readonly property color away: idle
    readonly property color dnd: Settings.colorBlind ? (light ? "#5A3C8C" : "#B59BE0") : light ? "#B23A32" : "#D9534F"
    readonly property color offline: textFaint

    // The accent: a named one or any #RRGGBB from the picker. On parchment it's darkened to stay readable.
    // Autumn's own gold and ember are warmer than the others'.
    readonly property var accents: ({ "gold": autumn ? "#E9B56A" : "#C2A25E", "iron": "#A9B4BE", "copper": autumn ? "#D9853B" : "#B07A5A", "jade": "#6FAE8C",
                                      "fjord": "#6E9CC8", "heather": "#A58BC6", "ember": "#CF6F54" })
    readonly property var accentNames: ["gold", "iron", "copper", "jade", "fjord", "heather", "ember"]
    function validColor(v) { return /^#[0-9A-Fa-f]{6}$/.test(String(v || "")) }
    readonly property color accentBase: accents[Settings.accent] || (validColor(Settings.accent) ? Settings.accent : accents.gold)
    readonly property color accent: light ? Qt.darker(accentBase, 1.7) : accentBase
    // Text on a filled accent button.
    readonly property color onAccent: autumn ? "#2A1608" : ground
    readonly property color accentFill: light ? Qt.rgba(accent.r * 0.18 + 0.8, accent.g * 0.18 + 0.78, accent.b * 0.18 + 0.72, 1)
                                              : Qt.rgba(accent.r * 0.25 + 0.06, accent.g * 0.25 + 0.06, accent.b * 0.25 + 0.06, 1)
    readonly property color focusFill: light ? Qt.rgba(accent.r * 0.1 + 0.86, accent.g * 0.1 + 0.84, accent.b * 0.1 + 0.79, 1)
                                             : Qt.rgba(accent.r * 0.12 + 0.11, accent.g * 0.12 + 0.11, accent.b * 0.12 + 0.1, 1)

    // Everything is drawn at its real size (Qt and the system already handle high-DPI screens), times
    // the Size setting (50% to 200%, Ctrl+= and Ctrl+-). See System::uiScale.
    readonly property bool desktop: true
    readonly property real s: System.uiScale
    function px(v) { return Math.round(v * s) }
    // Text sizes: the overall Size, times the separate Text size.
    function fs(v) { return Math.round(v * s * textScale) }
    // Message density (Settings › Look): compact, default or spacious.
    readonly property bool compact: Settings.density === "compact"
    readonly property bool spacious: Settings.density === "spacious"
    function ms(v) { return Math.round(v * s * textScale) }

    // A window taller than it is wide gets the one-column layout, like Muninn on a phone.
    readonly property bool portrait: Ui.portrait
    // Panel widths at 100% (the list and the member list are dragged on a wide window). Portrait:
    // the list takes the whole width beside the rail.
    readonly property int railWidth: px(72)
    readonly property int listWidth: portrait ? Math.max(0, Ui.windowWidth - railWidth) : px(Ui.listW)
    readonly property int memberWidth: px(Ui.memberW)
    readonly property int headerHeight: px(48)
    // Hidden side panels (Ctrl+Shift+L and Ctrl+Shift+U). Portrait shows one column at a time.
    readonly property bool listShown: !portrait && Settings.showList
    readonly property bool membersShown: !portrait && Settings.showMembers

    readonly property string display: "Cinzel"
    readonly property string body: "Barlow"
    readonly property string runic: "Noto Sans Runic"

    // Corners: sharp is the house style. Autumn rounds them: cards and buttons at 14 px, a pill
    // for the composer and the bigger buttons, a small one for knobs and badges.
    readonly property int radius: autumn ? px(14) : 3
    readonly property int pill: autumn ? px(22) : 3
    readonly property int radiusSmall: autumn ? px(8) : 2
    // Controller focus: thick enough to spot from the couch.
    readonly property int focusWidth: Math.max(hc ? 3 : 2, px(hc ? 3 : 2))
    readonly property int rowHeight: px(42)

    // Avatars: a soft color per person, picked from their name so it never changes. Your own is gold.
    readonly property var avatarTones: hc ? [{ bg: "#1F1F1F", fg: "#FFFFFF" }]
        : autumn ? [{ bg: "#3E4A34", fg: "#CFE0B8" }, { bg: "#3A4458", fg: "#C4D2EC" }, { bg: "#5A3326", fg: "#F0B8A4" }, { bg: "#4A3650", fg: "#DEC6EC" }, { bg: "#2F4A4A", fg: "#B8E0E0" }]
        : light ? [{ bg: "#D5E4D3", fg: "#2E5A33" }, { bg: "#D6DDEB", fg: "#2F4A78" }, { bg: "#EED5CF", fg: "#86392A" }, { bg: "#E2D6EA", fg: "#5D3D78" }, { bg: "#D0E5E7", fg: "#235A60" }]
        : [{ bg: "#2C3A2C", fg: "#B6D6B6" }, { bg: "#2D3340", fg: "#B9C6E0" }, { bg: "#3A2724", fg: "#E6A596" }, { bg: "#33293A", fg: "#D2BCE0" }, { bg: "#24363A", fg: "#AED4D8" }]
    readonly property var avatarMine: hc ? ({ bg: "#1F1F1F", fg: "#FFFFFF" }) : light ? ({ bg: "#EADDBB", fg: "#6B5216" }) : autumn ? ({ bg: "#5A4020", fg: "#F0CF8A" }) : ({ bg: "#3A3220", fg: "#E0C27F" })
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
    // The initial-and-colour choice someone sent with their profile: their colour as the disc, and
    // light or dark text on it, whichever reads better.
    readonly property var pictureColours: ["#C9A35E", "#3D6A9E", "#8A5A3E", "#4E8A4E", "#7A4E9E", "#8A8A8A"]
    function avatarFrom(hex) {
        const c = Qt.color(hex)
        const bright = c.r * 0.299 + c.g * 0.587 + c.b * 0.114 > 0.55
        return { bg: hex, fg: bright ? "#1E1A14" : "#FFFFFF" }
    }

}

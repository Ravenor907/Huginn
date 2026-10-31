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
    // "light" (parchment) or "metal" (brushed iron and steel greys). High contrast wins over all four.
    readonly property string mode: ["black", "light", "metal"].indexOf(Settings.theme) >= 0 ? Settings.theme : "dark"
    readonly property bool light: mode === "light" && !hc
    readonly property bool black: mode === "black" && !hc
    readonly property bool metal: mode === "metal" && !hc
    function pick(dark, deep, parchment, contrast, steel) { return hc ? contrast : light ? parchment : black ? deep : metal ? (steel || dark) : dark }

    readonly property color ground: pick("#101112", "#000000", "#F3EFE6", "#000000", "#2A2E33")
    readonly property color groundDeep: pick("#0C0D0E", "#000000", "#E7E1D4", "#000000", "#1C1F22")
    readonly property color panel: pick("#17191B", "#0B0C0D", "#FBF9F4", "#0A0A0A", "#33383E")
    readonly property color panelLow: pick("#141617", "#060607", "#ECE7DC", "#050505", "#24282C")
    readonly property color raised: pick("#1D1E20", "#141516", "#E2DCCF", "#1A1A1A", "#3A4047")
    readonly property color tile: pick("#24262A", "#1B1C1E", "#D8D1C2", "#1F1F1F", "#454C54")
    readonly property color line: pick("#2C2F33", "#232528", "#CEC6B6", "#9A9A9A", "#4A5058")
    readonly property color lineStrong: pick("#34373B", "#2B2E31", "#BDB4A2", "#B0B0B0", "#565D66")
    readonly property color lineMuted: pick("#45484D", "#3E4146", "#A69D8B", "#BDBDBD", "#68707A")
    readonly property color text: pick("#E4E1DA", "#E4E1DA", "#1F1C17", "#FFFFFF", "#ECEEF0")
    readonly property color textSoft: pick("#C4C0B7", "#C4C0B7", "#3B362E", "#FFFFFF", "#D2D6DA")
    readonly property color textMuted: pick("#9C978D", "#9C978D", "#615A4E", "#E0E0E0", "#AEB4BB")
    readonly property color textFaint: pick("#6E6A62", "#6E6A62", "#877F71", "#C8C8C8", "#858C94")
    readonly property color ornament: pick("#5E5A53", "#5E5A53", "#958B79", "#A0A0A0", "#7A828B")
    // The three columns: the rail on the far left, the list beside it, and the open conversation.
    readonly property color rail: groundDeep
    readonly property color column: panelLow
    readonly property color seam: pick("#1F2125", "#16181A", "#D9D2C4", "#9A9A9A", "#3F454C")
    // Brushed metal: a fine streaked texture over the rail, the lists and the headers.
    readonly property string brushed: metal ? "assets/brushed.png" : ""
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

    // Sharp corners: the house style.
    readonly property int radius: 3
    // Controller focus: thick enough to spot from the couch.
    readonly property int focusWidth: Math.max(hc ? 3 : 2, px(hc ? 3 : 2))
    readonly property int rowHeight: px(42)

    // Avatars: a soft color per person, picked from their name so it never changes. Your own is gold.
    readonly property var avatarTones: hc ? [{ bg: "#1F1F1F", fg: "#FFFFFF" }]
        : metal ? [{ bg: "#3E4A3E", fg: "#C6DEC6" }, { bg: "#3D4454", fg: "#C9D4EA" }, { bg: "#4A3734", fg: "#EDB6A8" }, { bg: "#433948", fg: "#DCC8EA" }, { bg: "#34464A", fg: "#BEDFE2" }]
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

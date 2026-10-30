pragma Singleton
import QtQuick
import Huginn

// Shared handles to the window-wide overlays, set by Main.qml, so any page can use them.
QtObject {
    property var main: null
    property var prompt: null
    property var confirm: null
    property var toast: null
    property var picker: null
    property var profile: null

    // Panel widths while they're dragged (Settings keeps them once let go), and how much room the
    // voice panel takes at the foot of the list column (0 when it isn't there).
    property int railW: Settings.railWidth
    property int listW: Settings.listWidth
    property int memberW: Settings.memberWidth
    property int dock: 0
    function resetPanels() {
        Settings.railWidth = 83; Settings.listWidth = 270; Settings.memberWidth = 230
        railW = 83; listW = 270; memberW = 230
        Settings.showList = true; Settings.showMembers = true
    }

    function openPage(name, args) { main.openPage(name, args || {}) }
    // Back out of a page: to the rail, or from Contacts back to Settings.
    function closePage() { main.closePage() }
    function pickFrom(title, sections, cb) { picker.openList(title, sections, cb) }
    function say(text, bad) { if (toast) toast.show(text, bad) }
    function ask(title, ctx, initial, confirmText, cb, isPassword, keepSpaces) { prompt.open(title, ctx, initial, confirmText, cb, isPassword, keepSpaces) }
    // Someone's profile card: avatar, nickname, note, folders, and Message, Call and Video.
    // extra: { group, admin, owner, member } when opened from a group's member list.
    function showProfile(key, name, extra) { if (profile && key) profile.show(key, name || "", extra || {}) }
    function sure(title, message, confirmText, cb, hold, danger, onCancel, tag) { confirm.open(title, message, confirmText, cb, hold, danger, onCancel, tag) }
}

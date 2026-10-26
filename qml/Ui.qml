pragma Singleton
import QtQuick
import Huginn

// Shared handles to the window-wide overlays, set by Main.qml, so any page can use them.
QtObject {
    property var main: null
    property var wheel: null
    property var prompt: null
    property var confirm: null
    property var toast: null
    property var picker: null

    function openPage(name, args) { main.openPage(name, args || {}) }
    function closePage() { main.closePage() }
    function pick(title, quick, cb) { picker.open(title, quick, cb) }
    function say(text, bad) { if (toast) toast.show(text, bad) }
    function ask(title, ctx, initial, confirmText, cb, isPassword, keepSpaces) { prompt.open(title, ctx, initial, confirmText, cb, isPassword, keepSpaces) }
    function sure(title, message, confirmText, cb, hold, danger, onCancel, tag) { confirm.open(title, message, confirmText, cb, hold, danger, onCancel, tag) }
}

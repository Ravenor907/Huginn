import QtQuick
import Huginn

// Chat's lock, required like Muninn's. "setup": first time, pick a 9-dot pattern or a password.
// "unlock": draw or type it. "change": Settings › Chat › Change lock (already unlocked).
// Everything chat keeps is encrypted with it; there's no way to recover it.
Rectangle {
    id: panel
    property string mode: Vault.exists ? "unlock" : "setup"
    property bool usePattern: mode === "unlock" ? Vault.usesPattern : true
    property var first: null
    property string note: ""
    property bool bad: false
    property int wait: Vault.waitSeconds
    signal finished(string how)   // "setup", "unlock" or "change": what just finished
    signal cancelled()

    color: Theme.panel
    radius: Theme.radius + 1
    border.width: 1
    border.color: Theme.line
    implicitWidth: Theme.px(560)
    implicitHeight: col.implicitHeight + Theme.px(40)

    Timer { interval: 500; repeat: true; running: panel.wait > 0; onTriggered: panel.wait = Vault.waitSeconds }

    function saveSecret(secret, pattern) {
        if (Vault.setSecret(secret, pattern)) {
            Ui.say(pattern ? "Pattern set. Don't forget it: it can't be recovered." : "Password set. Don't lose it: it can't be recovered.")
            first = null; note = ""; bad = false
            finished(mode === "setup" ? "setup" : "change")
        } else { note = "That didn't save. Try again."; bad = true }
    }
    function tryUnlock(secret) {
        if (Vault.waitSeconds > 0) { wait = Vault.waitSeconds; return }
        if (Vault.unlock(secret)) { note = ""; bad = false; finished("unlock") }
        else { note = "That's not it. Try again."; bad = true; wait = Vault.waitSeconds }
    }
    function onPattern(dots) {
        const secret = "pattern:" + dots.join(",")
        if (mode === "unlock") { tryUnlock(secret); return }
        if (dots.length < 4) { note = "Connect at least 4 dots."; bad = true; return }
        if (first === null) { first = dots; note = "Draw it again to confirm."; bad = false; return }
        if (first.join(",") !== dots.join(",")) { first = null; note = "That didn't match. Start over."; bad = true; return }
        saveSecret(secret, true)
    }
    function typePassword() {
        if (mode === "unlock") { Ui.ask("Chat password", "Unlocks your messages", "", "Unlock", t => tryUnlock(t), true); return }
        Ui.ask("New password", "At least 4 characters. It can't be recovered.", "", "Next", t => {
            if (t.length < 4) { note = "At least 4 characters."; bad = true; return }
            Ui.ask("Type it again", "To be sure", "", "Save", t2 => { if (t2 === t) saveSecret(t, false); else { note = "They didn't match. Try again."; bad = true } }, true)
        }, true)
    }

    Column {
        id: col
        x: Theme.px(20); y: Theme.px(20)
        width: parent.width - Theme.px(40)
        spacing: Theme.px(10)
        Text {
            width: parent.width; wrapMode: Text.WordWrap
            text: panel.mode === "setup" ? "Protect your messages" : panel.mode === "change" ? "Change lock" : "Chat is locked"
            color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(22); font.weight: Font.DemiBold
        }
        Text {
            width: parent.width; wrapMode: Text.WordWrap
            text: panel.mode === "unlock"
                  ? (panel.usePattern ? "Draw your pattern. Your messages, photos, voice and contacts stay encrypted until you do." : "Press A to type your password.")
                  : "Chat opens with a pattern or password. Everything it keeps is encrypted with it: without it nobody can read your messages, not even Huginn. There's no way to recover it."
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(16)
        }
        Row {
            visible: panel.mode !== "unlock"
            spacing: Theme.px(8)
            HButton { text: "Pattern"; kind: panel.usePattern ? "accent" : "normal"; onClicked: { panel.usePattern = true; panel.first = null; panel.note = "" } }
            HButton { text: "Password"; kind: !panel.usePattern ? "accent" : "normal"; onClicked: { panel.usePattern = false; panel.first = null; panel.note = "" } }
        }
        Text {
            width: parent.width; wrapMode: Text.WordWrap
            visible: text !== ""
            text: panel.wait > 0 ? "Too many tries. Try again in " + panel.wait + " s."
                : panel.note !== "" ? panel.note
                : panel.usePattern && panel.mode !== "unlock" ? "Draw a pattern: connect at least 4 dots."
                : ""
            color: panel.bad || panel.wait > 0 ? Theme.danger : Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(16)
        }
        PatternPad {
            id: pad
            visible: panel.usePattern
            anchors.horizontalCenter: parent.horizontalCenter
            width: Theme.px(280); height: width
            error: panel.bad
            enabled: panel.wait === 0
            onDone: d => panel.onPattern(d)
        }
        HButton {
            visible: !panel.usePattern
            text: panel.mode === "unlock" ? "Type password" : "Set a password"
            kind: "accent"
            onClicked: panel.typePassword()
        }
        Text {
            width: parent.width; wrapMode: Text.WordWrap
            visible: Gamepad.connected && panel.usePattern
            text: "Controller: D-pad to move, A to connect a dot, X to start over, Y when done."
            color: Theme.textFaint; font.family: Theme.body; font.pixelSize: Theme.fs(14)
        }
    }

    function handleButton(b) {
        if (usePattern && pad.handleButton(b)) return true
        if (b === "a" && !usePattern) { typePassword(); return true }
        if ((b === "lb" || b === "rb") && mode !== "unlock") { usePattern = !usePattern; first = null; note = ""; return true }
        if (b === "b") { cancelled(); return true }
        return false
    }
}

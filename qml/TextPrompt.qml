import QtQuick
import QtQuick.Controls.Basic
import Huginn

// Asks for a line of text with Steam's own keyboard. The dialog sits in the top half so the
// keyboard never covers the field. Enter confirms, Escape or B cancels, Y reopens the keyboard.
Item {
    id: prompt
    property string title
    property string context
    property string hint: "Type with Steam's keyboard, then press Enter"
    property string confirmText: "Done"
    property bool password: false
    property var acceptCallback: null
    signal accepted(string text)
    signal cancelled()

    visible: false
    anchors.fill: parent

    property bool raw: false   // keep spaces as typed (config file lines)
    function open(t, ctx, initial, confirm, callback, isPassword, keepSpaces) {
        raw = !!keepSpaces
        title = t
        context = ctx || ""
        confirmText = confirm || "Done"
        password = !!isPassword
        acceptCallback = callback || null
        field.text = initial || ""
        visible = true
        field.forceActiveFocus()
        // Select the name but not the extension, like a desktop file manager.
        const dot = field.text.lastIndexOf(".")
        field.select(0, dot > 0 && !isPassword ? dot : field.text.length)
        System.showSteamKeyboard()
    }
    function accept() {
        const t = raw ? field.text : field.text.trim()
        if (!raw && t.length === 0) return
        visible = false
        System.hideSteamKeyboard()
        if (Ui.main) Ui.main.restoreFocus()
        if (acceptCallback) acceptCallback(t)
        accepted(t)
    }
    function cancel() {
        visible = false
        System.hideSteamKeyboard()
        if (Ui.main) Ui.main.restoreFocus()
        cancelled()
    }

    Rectangle { anchors.fill: parent; color: Qt.rgba(0.03, 0.035, 0.04, 0.82) }
    MouseArea { anchors.fill: parent }

    Rectangle {
        id: box
        anchors.horizontalCenter: parent.horizontalCenter
        y: Theme.px(36)
        width: Math.min(parent.width - Theme.px(80), Theme.px(880))
        height: col.implicitHeight + Theme.px(44)
        radius: Theme.radius + 1
        color: Theme.panel
        border.color: Theme.lineStrong

        Column {
            id: col
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            anchors.margins: Theme.px(22)
            spacing: Theme.px(12)

            Item {
                width: parent.width
                height: Theme.px(24)
                Text {
                    anchors.left: parent.left
                    text: prompt.title.toUpperCase()
                    color: Theme.textMuted
                    font.family: Theme.display
                    font.pixelSize: Theme.px(17)
                    font.weight: Font.DemiBold
                    font.letterSpacing: Theme.px(2)
                }
                Text {
                    anchors.right: parent.right
                    width: parent.width / 2
                    horizontalAlignment: Text.AlignRight
                    elide: Text.ElideLeft
                    text: prompt.context
                    color: Theme.textMuted
                    font.family: Theme.body
                    font.pixelSize: Theme.px(14)
                }
            }
            TextField {
                id: field
                width: parent.width
                height: Theme.px(64)
                leftPadding: Theme.px(20)
                color: Theme.text
                selectionColor: Theme.accentFill
                selectedTextColor: Theme.text
                echoMode: prompt.password ? TextInput.Password : TextInput.Normal
                font.family: Theme.body
                font.pixelSize: Theme.px(28)
                font.weight: Font.Medium
                inputMethodHints: Qt.ImhNoPredictiveText
                background: Rectangle {
                    radius: Theme.radius
                    color: Theme.ground
                    border.color: Theme.accent
                }
                Keys.onReturnPressed: prompt.accept()
                Keys.onEnterPressed: prompt.accept()
                Keys.onEscapePressed: prompt.cancel()
            }
            Row {
                width: parent.width
                spacing: Theme.px(14)
                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    width: parent.width - cancelBtn.width - okBtn.width - Theme.px(28)
                    elide: Text.ElideRight
                    text: prompt.hint
                    color: Theme.textSoft
                    font.family: Theme.body
                    font.pixelSize: Theme.px(16)
                }
                HButton { id: cancelBtn; text: "Cancel"; implicitHeight: Theme.px(44); fontSize: 16; onClicked: prompt.cancel() }
                HButton { id: okBtn; text: prompt.confirmText; kind: "accent"; implicitHeight: Theme.px(44); fontSize: 16; onClicked: prompt.accept() }
            }
        }
    }

    function handleButton(b) {
        switch (b) {
        case "a": case "start": accept(); return true
        case "b": cancel(); return true
        case "y": field.forceActiveFocus(); System.showSteamKeyboard(); return true
        }
        return true
    }
}

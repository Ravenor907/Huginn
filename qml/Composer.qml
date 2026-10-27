import QtQuick
import QtQuick.Controls.Basic
import Huginn

// Desktop (mouse and keyboard): the message box docked at the bottom of a chat, typed into directly.
// Enter sends, Shift+Enter starts a new line, Ctrl+V pastes a picture, Up in an empty box edits your last
// message, Esc cancels a reply or an edit (or marks the chat read). Game Mode and controllers keep TextPrompt.
Item {
    id: box
    property string placeholder: ""
    property string target: ""             // where pasted pictures go: a contact's key or "g:<group>:<channel>"
    property string bannerTitle: ""        // "Replying to Raven" or "Editing your message"; empty for none
    property string bannerText: ""
    property bool editing: false
    property var mentionNames: []          // group members, for @ suggestions
    property alias text: field.text
    signal send(string text)
    signal typing(bool on)
    signal editLast()
    signal escape()
    signal cancelBanner()
    signal attach()

    function focusField() { field.forceActiveFocus() }
    function setText(t) { field.text = t; field.cursorPosition = t.length; field.forceActiveFocus() }
    function insert(t) { field.insert(field.cursorPosition, t); field.forceActiveFocus() }

    // The @word being typed at the cursor, for suggestions ("" when there's none).
    readonly property string atWord: {
        const before = field.text.substring(0, field.cursorPosition)
        const m = before.match(/(^|\s)@([^\s@]{0,20})$/)
        return m ? m[2] : (before.endsWith("@") ? "" : "\u0000")
    }
    readonly property var suggestions: atWord === "\u0000" || !mentionNames.length ? []
        : mentionNames.filter(n => n.toLowerCase().startsWith(atWord.toLowerCase())).slice(0, 6)
    function pickMention(n) {
        const before = field.text.substring(0, field.cursorPosition)
        const after = field.text.substring(field.cursorPosition)
        const cut = before.lastIndexOf("@")
        const head = before.substring(0, cut) + "@" + n + " "
        field.text = head + after
        field.cursorPosition = head.length
        field.forceActiveFocus()
    }
    function submit() {
        const t = field.text.trim()
        if (t.length === 0) return
        box.send(t)
        field.text = ""
        box.typing(false)
    }

    implicitHeight: col.implicitHeight

    Column {
        id: col
        width: parent.width
        spacing: Theme.px(6)

        // @ suggestions: names in the group that start with what's typed.
        Rectangle {
            visible: box.suggestions.length > 0
            width: Math.min(parent.width, Theme.px(320))
            height: sugCol.implicitHeight + Theme.px(8)
            radius: Theme.px(8)
            color: Theme.panel
            border.color: Theme.line
            Column {
                id: sugCol
                x: Theme.px(4); y: Theme.px(4)
                width: parent.width - Theme.px(8)
                Repeater {
                    model: box.suggestions
                    Rectangle {
                        width: sugCol.width
                        height: Theme.px(32)
                        radius: Theme.px(6)
                        color: sugHover.hovered ? Theme.raised : "transparent"
                        Text { anchors.verticalCenter: parent.verticalCenter; x: Theme.px(10); text: "@" + modelData; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15) }
                        HoverHandler { id: sugHover; cursorShape: Qt.PointingHandCursor }
                        TapHandler { onTapped: box.pickMention(modelData) }
                    }
                }
            }
        }

        // Replying to, or editing: what, with an X.
        Rectangle {
            visible: box.bannerTitle !== ""
            width: parent.width
            height: Theme.px(40)
            radius: Theme.px(8)
            color: Theme.panel
            Icon { id: bannerIcon; anchors.left: parent.left; anchors.leftMargin: Theme.px(12); anchors.verticalCenter: parent.verticalCenter; name: box.editing ? "edit" : "reply"; size: Theme.px(16); color: Theme.accent }
            Text {
                anchors.left: bannerIcon.right; anchors.leftMargin: Theme.px(10)
                anchors.right: bannerClose.left; anchors.rightMargin: Theme.px(10)
                anchors.verticalCenter: parent.verticalCenter
                elide: Text.ElideRight
                textFormat: Text.PlainText
                text: box.bannerTitle + (box.bannerText !== "" ? ": " + box.bannerText.replace(/\n/g, " ") : "")
                color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(14)
            }
            Icon {
                id: bannerClose
                anchors.right: parent.right; anchors.rightMargin: Theme.px(12); anchors.verticalCenter: parent.verticalCenter
                name: "close"; size: Theme.px(16); color: Theme.textMuted
                Accessible.role: Accessible.Button
                Accessible.name: "Cancel"
                TapHandler { onTapped: { box.cancelBanner(); field.forceActiveFocus() } }
            }
        }

        Rectangle {
            id: frame
            width: parent.width
            height: Math.min(Math.max(field.implicitHeight, Theme.px(46)), Theme.px(180))
            radius: Theme.px(11)
            color: Theme.raised
            border.width: field.activeFocus ? 1 : 0
            border.color: Theme.lineMuted
            Icon {
                id: attachIcon
                anchors.left: parent.left; anchors.leftMargin: Theme.px(14)
                anchors.bottom: parent.bottom; anchors.bottomMargin: (Theme.px(46) - size) / 2
                name: "attach"; size: Theme.px(20); color: attachHover.hovered ? Theme.text : Theme.textMuted
                Accessible.role: Accessible.Button
                Accessible.name: "Send a picture"
                HoverHandler { id: attachHover; cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: box.attach() }
            }
            Flickable {
                id: flick
                anchors.left: attachIcon.right; anchors.leftMargin: Theme.px(6)
                anchors.right: emojiIcon.left; anchors.rightMargin: Theme.px(6)
                anchors.top: parent.top; anchors.bottom: parent.bottom
                contentHeight: field.implicitHeight
                clip: true
                boundsBehavior: Flickable.StopAtBounds
                TextArea.flickable: TextArea {
                    id: field
                    placeholderText: box.placeholder
                    placeholderTextColor: Theme.textMuted
                    color: Theme.text
                    selectionColor: Theme.accentFill
                    selectedTextColor: Theme.text
                    wrapMode: TextEdit.Wrap
                    textFormat: TextEdit.PlainText
                    font.family: Theme.body
                    font.pixelSize: Theme.fs(16)
                    topPadding: Theme.px(12); bottomPadding: Theme.px(12); leftPadding: Theme.px(8); rightPadding: Theme.px(8)
                    background: null
                    Accessible.name: box.placeholder
                    onTextChanged: box.typing(text.trim().length > 0)
                    Keys.onPressed: event => {
                        if ((event.key === Qt.Key_Return || event.key === Qt.Key_Enter) && !(event.modifiers & Qt.ShiftModifier)) {
                            if (box.suggestions.length > 0) box.pickMention(box.suggestions[0])
                            else box.submit()
                            event.accepted = true
                        } else if (event.key === Qt.Key_Up && field.text.length === 0) {
                            box.editLast()
                            event.accepted = true
                        } else if (event.key === Qt.Key_Escape) {
                            if (box.bannerTitle !== "") box.cancelBanner()
                            else box.escape()
                            event.accepted = true
                        } else if (event.matches(StandardKey.Paste) && box.target !== "" && Chat.clipboardHasImage()) {
                            if (Chat.pasteImage(box.target)) Ui.say("Sending the picture.")
                            event.accepted = true
                        }
                    }
                }
            }
            Icon {
                id: emojiIcon
                anchors.right: parent.right; anchors.rightMargin: Theme.px(14)
                anchors.bottom: parent.bottom; anchors.bottomMargin: (Theme.px(46) - size) / 2
                name: "smile"; size: Theme.px(20); color: emojiHover.hovered || picker.visible ? Theme.text : Theme.textMuted
                Accessible.role: Accessible.Button
                Accessible.name: "Emoji"
                HoverHandler { id: emojiHover; cursorShape: Qt.PointingHandCursor }
                TapHandler { onTapped: picker.visible = !picker.visible }
            }
        }
    }

    EmojiPicker {
        id: picker
        anchors.right: parent.right
        anchors.bottom: parent.top
        anchors.bottomMargin: Theme.px(6)
        onPicked: e => { box.insert(e); picker.visible = false }
    }
}

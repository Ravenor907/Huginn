import QtQuick
import Huginn

// Your card, above the panel at the bottom left (a click on your name there): your picture and
// name, a custom status, Online / Away / Do not disturb / Invisible, then Edit name and colour,
// Change picture, My code and Link a phone. Only your contacts see any of it. Up and Down move
// along the rows, A chooses, B or Escape closes; a click anywhere else closes it too.
Item {
    id: card
    property Item above: null          // the panel the card sits over
    property int focusIndex: 1
    property string view: "main"       // "main" or "edit" (name and colour)
    visible: false
    anchors.fill: parent

    readonly property bool padFocus: !!Ui.main && Ui.main.padInUse
    readonly property var swatches: ["#C2A25E", "#6E9CC8", "#6FAE8C", "#CF6F54", "#A58BC6", "#B07A5A"]
    readonly property color myTone: Theme.validColor(Profile.myColour) ? Profile.myColour : Theme.accent
    property int swatch: Math.max(0, swatches.indexOf(String(Profile.myColour).toUpperCase()))

    // The rows, in the order the controller steps through them.
    readonly property var statusRows: [
        { id: "online", label: "Online", note: "" },
        { id: "away", label: "Away", note: "after 10 min idle" },
        { id: "dnd", label: "Do not disturb", note: "no sounds" },
        { id: "invisible", label: "Invisible", note: "look offline" }
    ]
    readonly property var actionRows: [
        { id: "edit", label: "Edit name and colour", icon: "edit" },
        { id: "picture", label: "Change picture", icon: "image" },
        { id: "code", label: "My code", icon: "qr" },
        { id: "phone", label: "Link a phone", icon: "phone" }
    ]
    readonly property var editRows: [
        { id: "name", label: "Name: " + Chat.username, icon: "edit" },
        { id: "colour", label: "Colour", icon: "smile", swatches: true },
        { id: "back", label: "Back", icon: "back" }
    ]
    readonly property var rows: view === "edit" ? editRows : [{ id: "custom", label: "Custom status" }].concat(statusRows).concat(actionRows)

    function open() { view = "main"; focusIndex = 1; visible = true; list.contentY = 0 }
    function close() { visible = false; if (Ui.main) Ui.main.restoreFocus() }
    function toggle() { if (visible) close(); else open() }
    function dotColour(id) { return id === "online" ? Theme.online : id === "away" ? Theme.away : id === "dnd" ? Theme.dnd : Theme.offline }
    function statusName(s) { return s === "away" ? "Away" : s === "dnd" ? "Do not disturb" : s === "invisible" ? "Invisible" : "Online" }

    function run(id) {
        if (id === "custom") {
            Ui.ask("Custom status", "A few words your contacts see under your name. Leave it empty for none", Profile.myCustomStatus, "Save",
                   t => Profile.setCustomStatus(t.slice(0, 80)), false, true)
        } else if (id === "online" || id === "away" || id === "dnd" || id === "invisible") {
            Profile.setStatus(id)
            if (Ui.main) Ui.main.autoAway = false
        } else if (id === "edit") { view = "edit"; focusIndex = 0; list.contentY = 0 }
        else if (id === "name") Ui.ask("Your chat name", "Pick a name, not your real one", Chat.username, "Save", t => Chat.username = t)
        else if (id === "colour") Profile.setMyColour(swatches[swatch])
        else if (id === "back") { view = "main"; focusIndex = 5; Qt.callLater(showFocused) }
        else if (id === "picture") { close(); if (Ui.profile) Ui.profile.pickPicture() }
        else if (id === "code") {
            if (!Chat.ready) { Ui.say("Unlock Huginn first.", true); return }
            close()
            Ui.openPage("dms", {})
            Qt.callLater(() => { const p = Ui.main.page(); if (p && p.showCode !== undefined) { Chat.newLink(); p.showCode = true } })
        } else if (id === "phone") { close(); PeopleActions.linkedDevices() }
    }
    // Scrolls the list so the row the controller is on is in view.
    function showFocused() {
        const r = rows[focusIndex]
        if (!r) return
        let item = null
        for (let i = 0; i < body.children.length; ++i) {
            const c = body.children[i]
            if (c.visible && (c.rowId === r.id || (r.id === "custom" && c === customBox))) { item = c; break }
        }
        if (!item) return
        const top = item.y, bottom = item.y + item.height
        if (top < list.contentY) list.contentY = top
        else if (bottom > list.contentY + list.height) list.contentY = bottom - list.height
    }
    function pickSwatch(i) { swatch = i; Profile.setMyColour(swatches[i]) }
    function handleButton(b) {
        const n = rows.length
        const r = rows[focusIndex]
        if (b === "b") { if (view === "edit") { view = "main"; focusIndex = 5; Qt.callLater(showFocused) } else close() }
        else if (b === "a") { if (r) run(r.id) }
        else if (b === "up") { focusIndex = Math.max(0, focusIndex - 1); Qt.callLater(showFocused) }
        else if (b === "down") { focusIndex = Math.min(n - 1, focusIndex + 1); Qt.callLater(showFocused) }
        else if (b === "left" || b === "right") {
            if (r && r.swatches) pickSwatch(Math.max(0, Math.min(swatches.length - 1, swatch + (b === "left" ? -1 : 1))))
            else if (b === "left") close()
        }
        return true
    }

    // A click anywhere else closes the card.
    MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons; onClicked: card.close() }

    Rectangle {
        id: box
        // Always above the panel and inside the window: as tall as the space above it allows,
        // and the rows scroll when they don't all fit.
        readonly property real bottomEdge: card.above && card.above.visible ? card.above.y - Theme.px(8) : parent.height - Theme.px(8)
        readonly property real fullHeight: list.y + body.implicitHeight + Theme.px(8)
        x: Math.max(0, Math.min(card.above && card.above.visible ? card.above.x : Theme.px(8), parent.width - width))
        y: Math.max(Theme.px(8), bottomEdge - height)
        width: Math.min(Theme.px(300), parent.width - Theme.px(16))
        height: Math.max(0, Math.min(fullHeight, bottomEdge - Theme.px(8)))
        radius: Theme.radius
        color: Theme.panel
        border.width: 1
        border.color: Theme.line
        clip: true
        Accessible.role: Accessible.Dialog
        Accessible.name: "You: " + Chat.username + ", " + card.statusName(Profile.myStatus)
        MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons }

        // The banner in your colour, and your picture over its edge.
        Rectangle {
            id: banner
            width: parent.width
            height: Theme.px(36)
            color: Qt.rgba(card.myTone.r, card.myTone.g, card.myTone.b, 0.35)
        }
        Rectangle {
            id: faceRing
            x: Theme.px(16); y: banner.height - Theme.px(26)
            width: Theme.px(60); height: width; radius: width / 2
            color: Theme.panel
            Avatar { anchors.centerIn: parent; key: "me"; name: Chat.username; size: Theme.px(54); showDot: true; backdrop: Theme.panel }
        }
        // Your name and who sees it, beside the picture.
        Column {
            id: nameBlock
            anchors.left: faceRing.right; anchors.leftMargin: Theme.px(10)
            anchors.right: parent.right; anchors.rightMargin: Theme.px(16)
            y: banner.height + Theme.px(4)
            Text { width: parent.width; elide: Text.ElideRight; text: Chat.username; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(18); font.weight: Font.Bold }
            Text { width: parent.width; elide: Text.ElideRight; text: "Only your contacts see this"; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(12) }
        }

        // The rows: they scroll when the window is short or the Size is large.
        Flickable {
            id: list
            x: Theme.px(16); y: Math.max(faceRing.y + faceRing.height, nameBlock.y + nameBlock.height) + Theme.px(8)
            width: parent.width - Theme.px(32)
            height: Math.max(0, parent.height - y - Theme.px(8))
            contentWidth: width
            contentHeight: body.implicitHeight
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            interactive: contentHeight > height
            Column {
                id: body
                width: parent.width
                spacing: Theme.px(4)

                // Custom status: type it here, Enter keeps it (the controller's A asks for it).
                Rectangle {
                    id: customBox
                    visible: card.view === "main"
                    readonly property bool on: card.padFocus && card.rows[card.focusIndex] && card.rows[card.focusIndex].id === "custom"
                    width: parent.width
                    height: Theme.px(36)
                    radius: Theme.radius
                    color: Theme.groundDeep
                    border.width: on ? Theme.focusWidth : 1
                    border.color: on || customInput.activeFocus ? Theme.accent : Theme.line
                    Accessible.role: Accessible.EditableText
                    Accessible.name: "Custom status"
                    Icon { id: customIcon; anchors.left: parent.left; anchors.leftMargin: Theme.px(10); anchors.verticalCenter: parent.verticalCenter; name: "thread"; size: Theme.px(14); color: Theme.textMuted }
                    TextInput {
                        id: customInput
                        anchors.left: customIcon.right; anchors.leftMargin: Theme.px(8)
                        anchors.right: parent.right; anchors.rightMargin: Theme.px(10)
                        anchors.verticalCenter: parent.verticalCenter
                        text: Profile.myCustomStatus
                        maximumLength: 80
                        color: Theme.text
                        selectionColor: Theme.accentFill
                        font.family: Theme.body; font.pixelSize: Theme.fs(14)
                        clip: true
                        function keep() { Profile.setCustomStatus(text.trim()); if (Ui.main) Ui.main.restoreFocus() }
                        Keys.onReturnPressed: keep()
                        Keys.onEnterPressed: keep()
                        Keys.onEscapePressed: { text = Profile.myCustomStatus; if (Ui.main) Ui.main.restoreFocus() }
                        onActiveFocusChanged: if (!activeFocus && text.trim() !== Profile.myCustomStatus) Profile.setCustomStatus(text.trim())
                    }
                    Text {
                        visible: customInput.text.length === 0 && !customInput.activeFocus
                        anchors.left: customIcon.right; anchors.leftMargin: Theme.px(8)
                        anchors.verticalCenter: parent.verticalCenter
                        text: "Set a custom status..."
                        color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(14)
                    }
                    HoverHandler { cursorShape: Qt.IBeamCursor }
                    TapHandler { onTapped: customInput.forceActiveFocus() }
                }
                Rectangle { visible: card.view === "main"; width: parent.width; height: 1; color: Theme.seam }

                // Online, Away, Do not disturb, Invisible.
                Repeater {
                    model: card.view === "main" ? card.statusRows : []
                    CardRow {
                        rowId: modelData.id
                        label: modelData.label
                        note: modelData.note
                        picked: Profile.myStatus === modelData.id
                        Rectangle { anchors.verticalCenter: parent.verticalCenter; x: Theme.px(8); width: Theme.px(12); height: width; radius: width / 2; color: card.dotColour(modelData.id) }
                    }
                }
                Rectangle { visible: card.view === "main"; width: parent.width; height: 1; color: Theme.seam }
                Repeater {
                    model: card.view === "main" ? card.actionRows : []
                    CardRow {
                        rowId: modelData.id
                        label: modelData.label
                        Icon { anchors.verticalCenter: parent.verticalCenter; x: Theme.px(6); name: modelData.icon; size: Theme.px(16); color: Theme.textSoft }
                    }
                }

                // Edit name and colour.
                Repeater {
                    model: card.view === "edit" ? card.editRows : []
                    CardRow {
                        rowId: modelData.id
                        label: modelData.swatches ? "" : modelData.label
                        tall: !!modelData.swatches
                        Icon { anchors.verticalCenter: parent.verticalCenter; x: Theme.px(6); name: modelData.icon; size: Theme.px(16); color: Theme.textSoft; visible: !modelData.swatches }
                        Row {
                            visible: !!modelData.swatches
                            anchors.verticalCenter: parent.verticalCenter
                            x: Theme.px(30)
                            spacing: Theme.px(8)
                            Repeater {
                                model: card.swatches
                                Rectangle {
                                    readonly property bool mine: String(Profile.myColour).toUpperCase() === modelData
                                    width: Theme.px(28); height: width; radius: width / 2
                                    color: modelData
                                    border.width: mine ? Theme.px(2) : card.swatch === index && card.padFocus ? Theme.px(2) : 1
                                    border.color: mine ? Theme.text : card.swatch === index && card.padFocus ? Theme.accent : Theme.lineMuted
                                    Accessible.role: Accessible.RadioButton
                                    Accessible.name: "Colour " + (index + 1)
                                    Accessible.checked: mine
                                    Accessible.onPressAction: card.pickSwatch(index)
                                    Icon { visible: parent.mine; anchors.centerIn: parent; name: "check"; size: Theme.px(14); color: Theme.ground }
                                    HoverHandler { cursorShape: Qt.PointingHandCursor }
                                    TapHandler { onTapped: card.pickSwatch(index) }
                                }
                            }
                        }
                    }
                }
            }
        }
        // A thin bar on the right while the rows scroll.
        Rectangle {
            visible: list.interactive
            anchors.right: parent.right; anchors.rightMargin: Theme.px(4)
            y: list.y + list.visibleArea.yPosition * list.height
            width: Theme.px(3); height: Math.max(Theme.px(16), list.visibleArea.heightRatio * list.height)
            radius: width / 2
            color: Theme.lineMuted
        }
    }

    // One row of the card: what's on the left is given as a child; the label and a note follow.
    component CardRow: Rectangle {
        id: row
        property string rowId: ""
        property string label: ""
        property string note: ""
        property bool picked: false
        property bool tall: false
        default property alias lead: leadItem.data
        readonly property bool on: card.padFocus && card.rows[card.focusIndex] && card.rows[card.focusIndex].id === rowId
        width: parent.width
        height: Theme.px(tall ? 40 : 32)
        radius: Theme.radius
        color: on || rowHover.hovered ? Theme.raised : "transparent"
        border.width: on ? Theme.focusWidth : 0
        border.color: Theme.accent
        Accessible.role: card.statusRows.some(s => s.id === rowId) ? Accessible.RadioButton : Accessible.Button
        Accessible.name: label + (note !== "" ? ", " + note : "")
        Accessible.checked: picked
        Accessible.onPressAction: card.run(rowId)
        Item { id: leadItem; anchors.left: parent.left; anchors.top: parent.top; anchors.bottom: parent.bottom; width: Theme.px(28) }
        Text {
            anchors.left: parent.left; anchors.leftMargin: Theme.px(30)
            anchors.right: noteText.left; anchors.rightMargin: Theme.px(6)
            anchors.verticalCenter: parent.verticalCenter
            elide: Text.ElideRight
            text: row.label
            color: row.picked ? Theme.text : Theme.textSoft
            font.family: Theme.body; font.pixelSize: Theme.fs(14); font.weight: row.picked ? Font.DemiBold : Font.Normal
        }
        Text {
            id: noteText
            anchors.right: parent.right; anchors.rightMargin: Theme.px(8)
            anchors.verticalCenter: parent.verticalCenter
            text: row.note
            width: text === "" ? 0 : implicitWidth
            color: Theme.textMuted
            font.family: Theme.body; font.pixelSize: Theme.fs(12)
        }
        HoverHandler { id: rowHover; cursorShape: Qt.PointingHandCursor }
        TapHandler { onTapped: if (row.rowId !== "colour") card.run(row.rowId) }
    }
}

import QtQuick
import Huginn

// Full-screen preview for pictures and text files. LB/RB flip through the folder.
Rectangle {
    id: page
    color: "#0A0B0C"
    property url url
    property var siblings: []
    property int index: 0
    property bool showDetails: true
    property real zoom: 1
    readonly property bool isText: System.kindOf(url) === "text"
    property var textLines: []
    property bool editable: false
    property int line: 0
    onUrlChanged: loadText()
    function loadText() {
        line = 0
        if (!isText) { textLines = []; return }
        const r = System.readLines(url)
        textLines = r.lines
        editable = r.editable
    }
    function editLine() {
        if (!isText) return
        if (!editable) { Ui.say("This file is too big or not plain text, so it's view only.", true); return }
        Ui.ask("Edit line " + (line + 1), System.fileName(url), textLines[line], "Save", t => {
            const copy = textLines.slice()
            copy[line] = t
            const err = System.writeLines(url, copy)
            if (err) { Ui.say(err, true); return }
            textLines = copy
            Ui.say("Saved. The original is kept as " + System.fileName(url) + ".bak")
        }, false, true)
    }
    readonly property var info: System.fileInfo(url)

    function setArgs(a) {
        if (a.tour) { siblings = System.sampleImages(); url = siblings.length ? siblings[0] : ""; return }
        url = a.url
        siblings = System.imagesBeside(a.url)
        index = Math.max(0, siblings.indexOf(a.url.toString()))
    }
    function go(d) {
        if (siblings.length < 2) return
        index = (index + d + siblings.length) % siblings.length
        url = siblings[index]
        zoom = 1; img.x = 0; img.y = 0
    }

    Rectangle {
        id: top
        width: parent.width
        height: Theme.px(60)
        color: Theme.ground
        Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.line }
        Row {
            anchors.left: parent.left; anchors.leftMargin: Theme.px(28); anchors.verticalCenter: parent.verticalCenter
            spacing: Theme.px(16)
            Text { text: page.info.name || ""; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.px(19); font.weight: Font.DemiBold }
            Text { text: page.info.summary || ""; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.px(15) }
        }
        Text {
            anchors.right: parent.right; anchors.rightMargin: Theme.px(28); anchors.verticalCenter: parent.verticalCenter
            visible: page.siblings.length > 1
            text: (page.index + 1) + " OF " + page.siblings.length
            color: Theme.textMuted; font.family: Theme.display; font.pixelSize: Theme.px(16); font.weight: Font.DemiBold; font.letterSpacing: 1.5
        }
    }

    Item {
        id: stage
        anchors.top: top.bottom
        anchors.left: parent.left
        anchors.right: details.visible ? details.left : parent.right
        anchors.bottom: strip.visible ? strip.top : bar.top
        clip: true

        Image {
            id: img
            visible: !page.isText
            width: stage.width - Theme.px(40)
            height: stage.height - Theme.px(40)
            x: (stage.width - width) / 2
            y: (stage.height - height) / 2
            scale: page.zoom
            source: page.isText ? "" : page.url
            fillMode: Image.PreserveAspectFit
            asynchronous: true
            autoTransform: true
            sourceSize.width: 2560
            sourceSize.height: 2560
        }
        Rectangle {
            visible: page.isText
            anchors.fill: parent
            anchors.margins: Theme.px(20)
            color: Theme.panelLow
            border.color: Theme.line
            radius: Theme.radius
            // Text files show line by line; A edits the focused line with Steam's keyboard.
            ListView {
                id: lines
                anchors.fill: parent
                anchors.margins: Theme.px(10)
                clip: true
                model: page.textLines
                currentIndex: page.line
                highlightMoveDuration: 60
                preferredHighlightBegin: height / 3
                preferredHighlightEnd: height * 2 / 3
                highlightRangeMode: ListView.ApplyRange
                delegate: Rectangle {
                    readonly property bool on: index === page.line
                    width: lines.width
                    height: lineText.implicitHeight + Theme.px(8)
                    radius: 2
                    color: on ? Theme.focusFill : "transparent"
                    border.width: on ? Theme.focusWidth : 0
                    border.color: Theme.accent
                    Text {
                        width: Theme.px(56)
                        x: Theme.px(4); y: Theme.px(4)
                        horizontalAlignment: Text.AlignRight
                        text: index + 1
                        color: Theme.textFaint
                        font.family: "monospace"
                        font.pixelSize: Theme.px(14)
                    }
                    Text {
                        id: lineText
                        x: Theme.px(72); y: Theme.px(4)
                        width: parent.width - Theme.px(80)
                        wrapMode: Text.WrapAnywhere
                        text: modelData.length ? modelData : " "
                        color: Theme.text
                        font.family: "monospace"
                        font.pixelSize: Theme.px(16)
                        textFormat: Text.PlainText
                    }
                    TapHandler { onTapped: { if (page.line === index) page.editLine(); else page.line = index } }
                }
            }
        }
        HButton { visible: page.siblings.length > 1; anchors.left: parent.left; anchors.leftMargin: Theme.px(20); anchors.verticalCenter: parent.verticalCenter; implicitWidth: Theme.px(48); implicitHeight: Theme.px(64); text: "‹"; fontSize: 28; onClicked: page.go(-1) }
        HButton { visible: page.siblings.length > 1; anchors.right: parent.right; anchors.rightMargin: Theme.px(20); anchors.verticalCenter: parent.verticalCenter; implicitWidth: Theme.px(48); implicitHeight: Theme.px(64); text: "›"; fontSize: 28; onClicked: page.go(1) }
        PinchHandler { target: img; minimumScale: 1; maximumScale: 6; onScaleChanged: page.zoom = img.scale }
    }

    Rectangle {
        id: details
        visible: page.showDetails
        anchors.top: top.bottom
        anchors.right: parent.right
        anchors.bottom: stage.bottom
        width: Theme.px(280)
        color: Theme.ground
        Rectangle { anchors.left: parent.left; height: parent.height; width: 1; color: Theme.line }
        Column {
            anchors.fill: parent
            anchors.margins: Theme.px(20)
            spacing: Theme.px(14)
            Text { text: "DETAILS"; color: Theme.textMuted; font.family: Theme.display; font.pixelSize: Theme.px(15); font.weight: Font.DemiBold; font.letterSpacing: 2 }
            Repeater {
                model: page.info.rows || []
                Column {
                    spacing: 2
                    width: parent.width
                    Text { text: modelData.k; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.px(13) }
                    Text { width: parent.width; wrapMode: Text.WrapAtWordBoundaryOrAnywhere; text: modelData.v; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.px(17) }
                }
            }
        }
        HButton {
            anchors.bottom: parent.bottom; anchors.bottomMargin: Theme.px(20)
            anchors.left: parent.left; anchors.leftMargin: Theme.px(20)
            anchors.right: parent.right; anchors.rightMargin: Theme.px(20)
            text: "Send to phone"; kind: "accent"
            onClicked: { Transfer.share([page.url]); Ui.openPage("transfer", {}) }
        }
    }

    Rectangle {
        id: strip
        visible: !page.isText && page.siblings.length > 1
        anchors.bottom: bar.top
        width: parent.width
        height: Theme.px(96)
        color: Theme.ground
        Rectangle { anchors.top: parent.top; width: parent.width; height: 1; color: Theme.line }
        ListView {
            anchors.fill: parent
            anchors.leftMargin: Theme.px(28)
            anchors.topMargin: Theme.px(16)
            anchors.bottomMargin: Theme.px(16)
            orientation: ListView.Horizontal
            spacing: Theme.px(10)
            model: page.siblings
            currentIndex: page.index
            highlightRangeMode: ListView.ApplyRange
            preferredHighlightBegin: width / 2 - Theme.px(56)
            preferredHighlightEnd: width / 2 + Theme.px(56)
            delegate: Rectangle {
                width: Theme.px(112); height: Theme.px(63)
                radius: 2
                color: Theme.tile
                border.width: index === page.index ? 2 : 1
                border.color: index === page.index ? Theme.accent : Theme.line
                opacity: index === page.index ? 1 : 0.7
                Image { anchors.fill: parent; anchors.margins: 2; source: modelData; fillMode: Image.PreserveAspectCrop; asynchronous: true; sourceSize.width: 224; sourceSize.height: 126 }
                TapHandler { onTapped: { page.index = index; page.url = page.siblings[index] } }
            }
        }
    }

    PromptBar {
        id: bar
        anchors.bottom: parent.bottom
        width: parent.width
        prompts: page.isText
            ? [{ key: "updown", label: "Move" }, { key: "a", label: page.editable ? "Edit line" : "View only" }, { key: "x", label: "Details" }, { key: "b", label: "Close" }]
            : [{ key: "lbrb", label: "Previous / next" }, { key: "lt", label: "Zoom out" }, { key: "rt", label: "Zoom in" }, { key: "x", label: "Details" }, { key: "b", label: "Close" }]
        onPrompt: k => page.handleButton(k === "lbrb" ? "rb" : k)
    }

    function handleButton(b) {
        switch (b) {
        case "lb": case "left": go(-1); break
        case "rb": case "right": go(1); break
        case "rt": if (isText) { line = Math.min(textLines.length - 1, line + 12); break } zoom = Math.min(6, zoom * 1.4); break
        case "lt": if (isText) { line = Math.max(0, line - 12); break } zoom = Math.max(1, zoom / 1.4); if (zoom === 1) { img.x = (stage.width - img.width) / 2; img.y = (stage.height - img.height) / 2 } break
        case "up": if (isText) line = Math.max(0, line - 1); else img.y += Theme.px(80); break
        case "down": if (isText) line = Math.min(textLines.length - 1, line + 1); else img.y -= Theme.px(80); break
        case "a": editLine(); break
        case "x": showDetails = !showDetails; break
        case "y": Transfer.share([url]); Ui.openPage("transfer", {}); break
        case "b": Ui.closePage(); break
        }
        return true
    }
}

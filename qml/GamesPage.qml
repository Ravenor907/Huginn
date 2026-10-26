import QtQuick
import Huginn

// Steam's folders by game name instead of app ID, with saves, prefix and shader cache in one place.
Rectangle {
    id: page
    color: Theme.ground
    property int current: 0
    property string zone: "list"          // "list" or "actions"
    property int actionIndex: 0
    readonly property var game: Games.count > 0 ? Games.details(current) : null

    function setArgs(a) {}
    Component.onCompleted: Games.refresh()

    PageHeader {
        id: header
        width: parent.width
        title: "Games"
        subtitle: "Steam folders by game name, not app ID"
        HButton {
            implicitHeight: Theme.px(44)
            text: Games.sortBySize ? "Sort: Size" : "Sort: Name"
            onClicked: Games.sortBySize = !Games.sortBySize
        }
    }
    Divider { id: divider; anchors.top: header.bottom; x: Theme.px(28); width: parent.width - Theme.px(56) }

    ListView {
        id: list
        anchors.top: divider.bottom
        anchors.topMargin: Theme.px(14)
        anchors.left: parent.left
        anchors.leftMargin: Theme.px(28)
        anchors.bottom: bar.top
        anchors.bottomMargin: Theme.px(14)
        width: Theme.px(500)
        spacing: Theme.px(6)
        clip: true
        model: Games
        currentIndex: page.current
        highlightMoveDuration: 80
        delegate: Rectangle {
            readonly property bool focused: index === page.current
            width: list.width
            height: Theme.px(74)
            radius: Theme.radius
            color: focused ? Theme.focusFill : Theme.panel
            border.width: focused && page.zone === "list" ? Theme.focusWidth : 1
            border.color: focused && page.zone === "list" ? Theme.accent : focused ? Theme.lineMuted : "transparent"
            Rectangle {
                id: tileBox
                anchors.left: parent.left
                anchors.leftMargin: Theme.px(14)
                anchors.verticalCenter: parent.verticalCenter
                width: Theme.px(54); height: width
                radius: Theme.radius
                color: Theme.tile
                border.color: Theme.lineStrong
                clip: true
                Image {
                    id: art
                    anchors.fill: parent
                    source: model.art
                    fillMode: Image.PreserveAspectCrop
                    asynchronous: true
                    visible: status === Image.Ready
                }
                Text {
                    anchors.centerIn: parent
                    visible: !art.visible
                    text: model.initials
                    color: Theme.textSoft
                    font.family: Theme.display
                    font.pixelSize: Theme.px(19)
                    font.weight: Font.Bold
                }
            }
            Column {
                anchors.left: tileBox.right
                anchors.leftMargin: Theme.px(14)
                anchors.right: sizeText.left
                anchors.rightMargin: Theme.px(12)
                anchors.verticalCenter: parent.verticalCenter
                spacing: Theme.px(2)
                Text {
                    width: parent.width
                    elide: Text.ElideRight
                    text: model.name
                    color: Theme.text
                    font.family: Theme.body
                    font.pixelSize: Theme.px(19)
                    font.weight: Font.DemiBold
                }
                Text {
                    text: "App ID " + model.appId
                    color: Theme.textMuted
                    font.family: Theme.body
                    font.pixelSize: Theme.px(14)
                }
            }
            Text {
                id: sizeText
                anchors.right: parent.right
                anchors.rightMargin: Theme.px(14)
                anchors.verticalCenter: parent.verticalCenter
                text: model.sizeText
                color: Theme.textSoft
                font.family: Theme.body
                font.pixelSize: Theme.px(16)
            }
            TapHandler { onTapped: { page.current = index; page.zone = "list" } }
        }
        Text {
            anchors.centerIn: parent
            width: parent.width - Theme.px(40)
            wrapMode: Text.WordWrap
            horizontalAlignment: Text.AlignHCenter
            visible: Games.count === 0
            text: Games.loading ? "Looking through your Steam libraries…" : "No Steam games found. Huginn looks in ~/.local/share/Steam and every library folder Steam knows about."
            color: Theme.textMuted
            font.family: Theme.body
            font.pixelSize: Theme.px(17)
        }
    }

    Rectangle {
        id: detail
        visible: page.game !== null
        anchors.top: list.top
        anchors.bottom: list.bottom
        anchors.left: list.right
        anchors.leftMargin: Theme.px(16)
        anchors.right: parent.right
        anchors.rightMargin: Theme.px(28)
        radius: Theme.radius + 1
        color: Theme.panel
        border.color: Theme.line

        Column {
            anchors.fill: parent
            anchors.margins: Theme.px(24)
            spacing: Theme.px(18)
            Row {
                spacing: Theme.px(18)
                Rectangle {
                    width: Theme.px(80); height: width
                    radius: Theme.radius
                    color: Theme.tile
                    border.color: Theme.accent
                    Text {
                        anchors.centerIn: parent
                        text: page.game ? page.game.initials : ""
                        color: Theme.accent
                        font.family: Theme.display
                        font.pixelSize: Theme.px(28)
                        font.weight: Font.Bold
                    }
                }
                Column {
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: Theme.px(4)
                    Text {
                        text: page.game ? page.game.name : ""
                        color: Theme.text
                        font.family: Theme.display
                        font.pixelSize: Theme.px(28)
                        font.weight: Font.Bold
                        font.letterSpacing: 1
                    }
                    Text {
                        text: page.game ? page.game.where : ""
                        color: Theme.textMuted
                        font.family: Theme.body
                        font.pixelSize: Theme.px(15)
                    }
                }
            }

            Grid {
                columns: 2
                spacing: Theme.px(12)
                width: parent.width
                Repeater {
                    model: page.game ? page.game.parts : []
                    Rectangle {
                        readonly property bool focused: page.zone === "actions" && page.actionIndex === index
                        width: (parent.width - Theme.px(12)) / 2
                        height: Theme.px(96)
                        radius: Theme.radius
                        color: focused ? Theme.focusFill : Theme.raised
                        border.width: focused ? Theme.focusWidth : 1
                        border.color: focused ? Theme.accent : Theme.line
                        opacity: modelData.exists ? 1 : 0.5
                        Column {
                            anchors.left: parent.left
                            anchors.leftMargin: Theme.px(16)
                            anchors.verticalCenter: parent.verticalCenter
                            spacing: Theme.px(4)
                            Text { text: modelData.label.toUpperCase(); color: Theme.textMuted; font.family: Theme.display; font.pixelSize: Theme.px(13); font.weight: Font.DemiBold; font.letterSpacing: 2 }
                            Text { text: modelData.sizeText; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.px(26); font.weight: Font.DemiBold }
                            Text { text: modelData.exists ? "Open in browser" : "Not created yet"; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.px(14) }
                        }
                        TapHandler { onTapped: { page.zone = "actions"; page.actionIndex = index; page.activate() } }
                    }
                }
            }

            Flow {
                width: parent.width
                spacing: Theme.px(12)
                HButton { text: "Back up saves"; kind: "accent"; focused: page.zone === "actions" && page.actionIndex === 4; onClicked: { page.actionIndex = 4; page.activate() } }
                HButton { text: "Clear shader cache"; focused: page.zone === "actions" && page.actionIndex === 5; onClicked: { page.actionIndex = 5; page.activate() } }
                HButton { text: "Reset Proton prefix"; kind: "danger"; focused: page.zone === "actions" && page.actionIndex === 6; onClicked: { page.actionIndex = 6; page.activate() } }
            }
            Text {
                width: parent.width
                wrapMode: Text.WordWrap
                text: "Backups go to Home / Huginn Backups. Resetting the prefix backs up your saves first and moves the old prefix to the trash."
                color: Theme.textMuted
                font.family: Theme.body
                font.pixelSize: Theme.px(14)
            }
        }
    }

    PromptBar {
        id: bar
        anchors.bottom: parent.bottom
        width: parent.width
        status: Games.summary
        prompts: page.zone === "list"
            ? [{ key: "a", label: "Choose" }, { key: "b", label: "Back" }, { key: "x", label: "Sort" }]
            : [{ key: "a", label: "Open" }, { key: "b", label: "Back to list" }]
        onPrompt: k => page.handleButton(k)
    }

    function activate() {
        if (!game) return
        const p = game.parts
        if (actionIndex < 4) {
            if (!p[actionIndex].exists) { Ui.say("That folder doesn't exist yet. Start the game once and it will."); return }
            Ui.closePage()
            Ui.main.openInBrowser(p[actionIndex].url)
        } else if (actionIndex === 4) {
            Games.backupSaves(current)
        } else if (actionIndex === 5) {
            Ui.sure("Clear shader cache?", "Steam rebuilds it the next time " + game.name + " starts, which can stutter for a bit.",
                    "Clear " + p[2].sizeText, () => Games.clearShaderCache(current), false, false)
        } else if (actionIndex === 6) {
            Ui.sure("Reset Proton prefix?", "Huginn backs up the saves in it first, then moves the prefix to the trash. Steam builds a fresh one next launch. Use this when a game won't start.",
                    "Back up and reset", () => Games.resetPrefix(current), false, true)
        }
    }

    Connections {
        target: Games
        function onFinished(ok, message) { Ui.say(message, !ok) }
    }

    function handleButton(b) {
        if (zone === "list") {
            switch (b) {
            case "up": current = Math.max(0, current - 1); return true
            case "down": current = Math.min(Games.count - 1, current + 1); return true
            case "right": case "a": if (game) { zone = "actions"; actionIndex = 0 } return true
            case "x": Games.sortBySize = !Games.sortBySize; return true
            case "b": Ui.closePage(); return true
            }
            return true
        }
        switch (b) {
        case "b": zone = "list"; return true
        case "a": activate(); return true
        case "left": if (actionIndex === 0 || actionIndex === 2) zone = "list"; else actionIndex = Math.max(0, actionIndex - 1); return true
        case "right": actionIndex = Math.min(6, actionIndex + 1); return true
        case "up": actionIndex = actionIndex >= 4 ? 2 : Math.max(0, actionIndex - 2); return true
        case "down": actionIndex = actionIndex < 2 ? actionIndex + 2 : 4; return true
        }
        return true
    }
}

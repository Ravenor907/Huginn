import QtQuick
import Huginn

// Phone transfer: a small web page on the home network. Scan the code, enter the PIN, drop files.
Rectangle {
    id: page
    color: Theme.ground
    function setArgs(a) {}
    Component.onCompleted: Transfer.start()
    Component.onDestruction: Transfer.stop()

    PageHeader {
        id: header
        width: parent.width
        title: "Phone Transfer"
        subtitle: Transfer.running ? "Receiving on your home network" : Transfer.error
    }
    Divider { id: divider; anchors.top: header.bottom; x: Theme.px(28); width: parent.width - Theme.px(56) }

    Rectangle {
        id: connect
        anchors.top: divider.bottom
        anchors.topMargin: Theme.px(14)
        anchors.left: parent.left
        anchors.leftMargin: Theme.px(28)
        anchors.bottom: bar.top
        anchors.bottomMargin: Theme.px(14)
        width: Theme.px(420)
        radius: Theme.radius + 1
        color: Theme.panel
        border.color: Theme.line
        Column {
            anchors.centerIn: parent
            spacing: Theme.px(14)
            Text { anchors.horizontalCenter: parent.horizontalCenter; text: "Scan with your phone camera"; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.px(19); font.weight: Font.DemiBold }
            Rectangle {
                anchors.horizontalCenter: parent.horizontalCenter
                width: Theme.px(259); height: width
                radius: Theme.radius
                color: Theme.text
                Image {
                    anchors.fill: parent
                    anchors.margins: Theme.px(14)
                    source: Transfer.running ? "image://qr/" + encodeURIComponent(Transfer.address) : ""
                    smooth: false
                    cache: false
                }
            }
            Text { anchors.horizontalCenter: parent.horizontalCenter; text: "or open in any browser"; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.px(15) }
            Text { anchors.horizontalCenter: parent.horizontalCenter; text: Transfer.address.replace("http://", ""); color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.px(22); font.weight: Font.DemiBold }
            Row {
                anchors.horizontalCenter: parent.horizontalCenter
                spacing: Theme.px(10)
                Text { anchors.verticalCenter: parent.verticalCenter; text: "PIN"; color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.px(16) }
                Text { anchors.verticalCenter: parent.verticalCenter; text: Transfer.pin; color: Theme.accent; font.family: Theme.display; font.pixelSize: Theme.px(26); font.weight: Font.Bold; font.letterSpacing: Theme.px(6) }
            }
        }
    }

    Column {
        anchors.top: connect.top
        anchors.left: connect.right
        anchors.leftMargin: Theme.px(24)
        anchors.right: parent.right
        anchors.rightMargin: Theme.px(28)
        spacing: Theme.px(12)
        Item {
            width: parent.width
            height: Theme.px(44)
            Text { anchors.verticalCenter: parent.verticalCenter; text: "INCOMING"; color: Theme.text; font.family: Theme.display; font.pixelSize: Theme.px(18); font.weight: Font.DemiBold; font.letterSpacing: 1.5 }
            HButton {
                anchors.right: parent.right
                implicitHeight: Theme.px(44)
                text: "Save to: " + Transfer.saveName
                onClicked: Ui.say("Files from your phone go to " + Transfer.saveName + ". Change it in Settings.")
            }
        }
        Repeater {
            model: Transfer.items
            Rectangle {
                width: parent.width
                height: Theme.px(74)
                radius: Theme.radius + 1
                color: Theme.panel
                border.color: Theme.line
                Column {
                    anchors.fill: parent
                    anchors.margins: Theme.px(16)
                    spacing: Theme.px(10)
                    Item {
                        width: parent.width; height: Theme.px(22)
                        Text { width: parent.width - st.width - 12; elide: Text.ElideMiddle; text: modelData.name; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.px(18); font.weight: Font.Medium }
                        Text { id: st; anchors.right: parent.right; text: modelData.status; color: modelData.failed ? Theme.danger : Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.px(15) }
                    }
                    Rectangle {
                        width: parent.width; height: Theme.px(6); radius: 1; color: Theme.line
                        Rectangle { width: parent.width * modelData.fraction; height: parent.height; color: modelData.fraction >= 1 ? Theme.textFaint : Theme.accent }
                    }
                }
            }
        }
        Text {
            visible: Transfer.items.length === 0
            width: parent.width
            wrapMode: Text.WordWrap
            text: "Nothing yet. Files you send from your phone show up here as they arrive."
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.px(16)
        }
        Text {
            width: parent.width
            wrapMode: Text.WordWrap
            topPadding: Theme.px(10)
            text: Transfer.sharedCount > 0
                ? Transfer.sharedCount + " file(s) are waiting on the phone page under \"From Huginn\"."
                : "Sending works too: select files in the browser, open Actions, pick To phone, and they appear on the phone page."
            color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.px(15)
        }
    }

    PromptBar {
        id: bar
        anchors.bottom: parent.bottom
        width: parent.width
        status: "Stops receiving when you leave this screen"
        prompts: [{ key: "x", label: "New PIN" }, { key: "b", label: "Stop and go back" }]
        onPrompt: k => page.handleButton(k)
    }

    function handleButton(b) {
        if (b === "x") Transfer.newPin()
        else if (b === "b") Ui.closePage()
        return true
    }
}

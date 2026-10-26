import QtQuick
import Huginn

// Phone Transfer. Nearby devices with the free LocalSend app come first: pick one and send, or
// send from the phone and accept on screen. The QR page is the backup for phones without the app.
Rectangle {
    id: page
    color: Theme.ground
    property int current: 0
    readonly property var devices: LocalSend.devices
    readonly property var incoming: LocalSend.items.concat(Transfer.items)

    function setArgs(a) {}
    Component.onCompleted: { Transfer.start(); LocalSend.refresh() }
    Component.onDestruction: Transfer.stop()

    PageHeader {
        id: header
        width: parent.width
        title: "Phone Transfer"
        subtitle: !Settings.nearbySharing ? "Nearby sharing is off in Settings"
                : LocalSend.running ? "Shows up in LocalSend as “" + LocalSend.alias + "”" : LocalSend.error
    }
    Divider { id: divider; anchors.top: header.bottom; x: Theme.px(28); width: parent.width - Theme.px(56) }

    // ---------------------------------------------------------- nearby (LocalSend)
    Rectangle {
        id: nearby
        anchors.top: divider.bottom
        anchors.topMargin: Theme.px(14)
        anchors.left: parent.left
        anchors.leftMargin: Theme.px(28)
        anchors.bottom: bar.top
        anchors.bottomMargin: Theme.px(14)
        width: (parent.width - Theme.px(80)) * 0.52
        radius: Theme.radius + 1
        color: Theme.panel
        border.color: Theme.line

        Item {
            id: nearbyHead
            x: Theme.px(20); y: Theme.px(16)
            width: parent.width - Theme.px(40)
            height: Theme.px(44)
            Text {
                anchors.verticalCenter: parent.verticalCenter
                text: "NEARBY"
                color: Theme.accent
                font.family: Theme.display; font.pixelSize: Theme.px(16); font.weight: Font.Bold; font.letterSpacing: Theme.px(2.5)
            }
            HButton {
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                implicitHeight: Theme.px(40)
                fontSize: 16
                enabled: LocalSend.running && !LocalSend.scanning
                text: LocalSend.scanning ? "Looking…" : "Look again"
                onClicked: LocalSend.refresh()
            }
        }

        Column {
            id: deviceList
            anchors.top: nearbyHead.bottom
            anchors.topMargin: Theme.px(12)
            x: Theme.px(20)
            width: parent.width - Theme.px(40)
            spacing: Theme.px(8)
            Repeater {
                model: page.devices
                Rectangle {
                    readonly property bool on: index === page.current
                    width: deviceList.width
                    height: Theme.px(64)
                    radius: Theme.radius
                    color: on ? Theme.focusFill : Theme.raised
                    border.width: on ? Theme.focusWidth : 1
                    border.color: on ? Theme.accent : Theme.line
                    Accessible.role: Accessible.Button
                    Accessible.name: "Send to " + modelData.alias
                    Icon {
                        id: devIcon
                        anchors.left: parent.left
                        anchors.leftMargin: Theme.px(16)
                        anchors.verticalCenter: parent.verticalCenter
                        name: modelData.type === "mobile" ? "phone" : "network"
                        size: Theme.px(28)
                        color: parent.on ? Theme.accent : Theme.textMuted
                    }
                    Column {
                        anchors.left: devIcon.right
                        anchors.leftMargin: Theme.px(14)
                        anchors.right: parent.right
                        anchors.rightMargin: Theme.px(16)
                        anchors.verticalCenter: parent.verticalCenter
                        Text { width: parent.width; elide: Text.ElideRight; text: modelData.alias; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.px(19); font.weight: Font.DemiBold }
                        Text { width: parent.width; elide: Text.ElideRight; text: (modelData.model ? modelData.model + " · " : "") + modelData.ip; color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.px(14) }
                    }
                    TapHandler { onTapped: { page.current = index; page.sendTo() } }
                }
            }
            Text {
                visible: page.devices.length === 0
                width: deviceList.width
                wrapMode: Text.WordWrap
                text: !Settings.nearbySharing ? "Turn on Nearby sharing in Settings › General to use LocalSend."
                    : !LocalSend.running ? LocalSend.error
                    : LocalSend.scanning ? "Looking for devices on your Wi-Fi…"
                    : "No devices yet. Open the free LocalSend app on your phone, on the same Wi-Fi, then press Look again. It shows this handheld as “" + LocalSend.alias + "”."
                color: Theme.textSoft
                font.family: Theme.body
                font.pixelSize: Theme.px(17)
                lineHeight: 1.1
            }
        }

        Text {
            anchors.left: parent.left
            anchors.leftMargin: Theme.px(20)
            anchors.right: parent.right
            anchors.rightMargin: Theme.px(20)
            anchors.bottom: parent.bottom
            anchors.bottomMargin: Theme.px(18)
            wrapMode: Text.WordWrap
            text: Transfer.sharedCount > 0
                ? "Ready to send: " + (Transfer.sharedCount === 1 ? "1 file" : Transfer.sharedCount + " files") + ". Pick a device and press A."
                : "To send from here, pick files in the browser, open the Y menu and choose To phone. To receive, send from LocalSend on the phone and accept on this screen."
            color: Transfer.sharedCount > 0 ? Theme.accent : Theme.textMuted
            font.family: Theme.body
            font.pixelSize: Theme.px(15)
            font.weight: Transfer.sharedCount > 0 ? Font.DemiBold : Font.Normal
        }
    }

    // ---------------------------------------------------------- incoming + backup
    Item {
        anchors.top: nearby.top
        anchors.bottom: nearby.bottom
        anchors.left: nearby.right
        anchors.leftMargin: Theme.px(24)
        anchors.right: parent.right
        anchors.rightMargin: Theme.px(28)

        Text {
            id: inHead
            text: "RECEIVED"
            color: Theme.text
            font.family: Theme.display; font.pixelSize: Theme.px(16); font.weight: Font.Bold; font.letterSpacing: Theme.px(2.5)
        }
        Text {
            anchors.right: parent.right
            anchors.baseline: inHead.baseline
            text: "Saved in " + Transfer.saveName
            color: Theme.textMuted
            font.family: Theme.body
            font.pixelSize: Theme.px(14)
        }
        Column {
            id: inList
            anchors.top: inHead.bottom
            anchors.topMargin: Theme.px(10)
            anchors.bottom: backup.top
            anchors.bottomMargin: Theme.px(12)
            width: parent.width
            spacing: Theme.px(8)
            clip: true
            Repeater {
                model: page.incoming.slice(0, 6)
                Rectangle {
                    width: inList.width
                    height: Theme.px(56)
                    radius: Theme.radius
                    color: Theme.panel
                    border.color: Theme.line
                    Column {
                        anchors.fill: parent
                        anchors.margins: Theme.px(12)
                        spacing: Theme.px(8)
                        Item {
                            width: parent.width; height: Theme.px(20)
                            Text { width: parent.width - st.width - 12; elide: Text.ElideMiddle; text: modelData.name; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.px(16); font.weight: Font.Medium }
                            Text { id: st; anchors.right: parent.right; text: modelData.status; color: modelData.failed ? Theme.danger : Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.px(14) }
                        }
                        Rectangle {
                            width: parent.width; height: Theme.px(5); radius: 1; color: Theme.line
                            Rectangle { width: parent.width * modelData.fraction; height: parent.height; color: modelData.fraction >= 1 ? Theme.textFaint : Theme.accent }
                        }
                    }
                }
            }
            Text {
                visible: page.incoming.length === 0
                width: inList.width
                wrapMode: Text.WordWrap
                text: "Nothing yet. Files show up here as they arrive."
                color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.px(15)
            }
        }

        // No LocalSend app: scan a code instead.
        Rectangle {
            id: backup
            anchors.bottom: parent.bottom
            width: parent.width
            height: Theme.px(188)
            radius: Theme.radius + 1
            color: Theme.panelLow
            border.color: Theme.line
            Rectangle {
                id: qr
                anchors.left: parent.left
                anchors.leftMargin: Theme.px(14)
                anchors.verticalCenter: parent.verticalCenter
                width: Theme.px(160); height: width
                radius: Theme.radius
                color: Theme.text
                Image {
                    anchors.fill: parent
                    anchors.margins: Theme.px(10)
                    source: Transfer.running ? "image://qr/" + encodeURIComponent(Transfer.address) : ""
                    smooth: false
                    cache: false
                }
            }
            Column {
                anchors.left: qr.right
                anchors.leftMargin: Theme.px(16)
                anchors.right: parent.right
                anchors.rightMargin: Theme.px(14)
                anchors.verticalCenter: parent.verticalCenter
                spacing: Theme.px(6)
                Text { width: parent.width; wrapMode: Text.WordWrap; text: "No LocalSend app? Scan this with the phone camera"; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.px(16); font.weight: Font.DemiBold }
                Text { width: parent.width; elide: Text.ElideRight; text: Transfer.running ? Transfer.address.replace("http://", "") : Transfer.error; color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.px(17) }
                Row {
                    spacing: Theme.px(10)
                    Text { anchors.verticalCenter: parent.verticalCenter; text: "PIN"; color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.px(15) }
                    Text { anchors.verticalCenter: parent.verticalCenter; text: Transfer.pin; color: Theme.accent; font.family: Theme.display; font.pixelSize: Theme.px(24); font.weight: Font.Bold; font.letterSpacing: Theme.px(6) }
                }
            }
        }
    }

    PromptBar {
        id: bar
        anchors.bottom: parent.bottom
        width: parent.width
        status: "The backup page stops when you leave this screen"
        prompts: LocalSend.sending ? [{ key: "b", label: "Stop sending" }]
            : [{ key: "a", label: "Send" }, { key: "y", label: "Look again" }, { key: "x", label: "New PIN" }, { key: "b", label: "Back" }]
        onPrompt: k => page.handleButton(k)
    }

    function sendTo() {
        const d = devices[current]
        if (!d) return
        if (Transfer.sharedCount === 0) { Ui.say("Pick files first: in the browser, open the Y menu and choose To phone."); return }
        LocalSend.send(d.id, Transfer.shared)
    }

    function handleButton(b) {
        if (LocalSend.sending) {
            if (b === "b") LocalSend.cancelSend()
            return true
        }
        switch (b) {
        case "up": current = Math.max(0, current - 1); break
        case "down": current = Math.min(devices.length - 1, current + 1); break
        case "a": sendTo(); break
        case "y": LocalSend.refresh(); break
        case "x": Transfer.newPin(); break
        case "b": Ui.closePage(); break
        }
        return true
    }
}

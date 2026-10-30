import QtQuick
import Huginn

// Files: share with phones and PCs. Middle column: pick files to send, then the devices nearby with
// Muninn or the free LocalSend app (A sends to one). Main column: what's ready to send, what came in,
// and the QR + PIN page for any phone without the app (it runs only while this screen is open).
Rectangle {
    id: page
    color: Theme.ground
    // False while the controller focus is on the rail, so only one thing looks focused.
    readonly property bool active: !Ui.main || Ui.main.focusZone === "content"
    property int current: 0
    property bool tour: false
    readonly property var devices: LocalSend.devices
    readonly property var incoming: LocalSend.items.concat(Transfer.items)
    // Rows in the middle column: the two actions, then one per device.
    readonly property var rows: [{ kind: "pick", name: "Pick files to send", detail: Transfer.sharedCount > 0 ? (Transfer.sharedCount === 1 ? "1 file ready" : Transfer.sharedCount + " files ready") : "From Downloads, Documents, Pictures and more" },
                                 { kind: "clear", name: "Clear the list", detail: Transfer.sharedCount > 0 ? "Start over" : "Nothing picked yet" }]
        .concat(devices.map(d => ({ kind: "device", id: d.id, name: d.alias, type: d.type,
                                    detail: d.model ? d.model : d.app === "muninn" ? "Muninn" : d.app === "huginn" ? "Huginn" : d.type === "mobile" ? "Phone" : "Computer" })))
    readonly property var sharedNames: {
        const out = []
        const list = Transfer.shared
        for (let i = 0; i < list.length; ++i) out.push(String(list[i]).substring(String(list[i]).lastIndexOf("/") + 1))
        return out
    }

    function setArgs(a) { tour = !!a.tour }
    Component.onCompleted: { Transfer.start(); LocalSend.refresh() }
    Component.onDestruction: Transfer.stop()

    readonly property var prompts: LocalSend.sending ? [{ key: "b", label: "Stop sending" }]
        : [{ key: "a", label: page.rows[page.current] && page.rows[page.current].kind === "device" ? "Send" : "Choose" },
           { key: "y", label: "Look again" }, { key: "back", label: Direct.on ? "Stop Direct connect" : "Direct connect" },
           { key: "x", label: "New PIN" }, { key: "b", label: "Back" }]
    readonly property string status: "The QR page stops when you leave Files"

    // ============================================================= middle column
    Rectangle {
        id: middle
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        anchors.bottomMargin: Ui.dock
        width: Theme.listWidth
        color: Theme.column

        PageHeader { id: head; width: parent.width; title: "Files" }

        Column {
            id: rowList
            anchors.top: head.bottom
            anchors.topMargin: Theme.px(10)
            x: Theme.px(10)
            width: parent.width - Theme.px(20)
            spacing: Theme.px(3)

            Repeater {
                model: page.rows
                Item {
                    width: rowList.width
                    // "Nearby" label above the first device.
                    height: (index === 2 ? label.height : 0) + tile.height
                    Text {
                        id: label
                        visible: index === 2
                        height: visible ? Theme.px(34) : 0
                        x: Theme.px(10)
                        verticalAlignment: Text.AlignBottom
                        bottomPadding: Theme.px(6)
                        text: "NEARBY"
                        color: Theme.textMuted
                        font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold; font.letterSpacing: Theme.px(1.5)
                    }
                    Rectangle {
                        id: tile
                        readonly property bool on: page.active && index === page.current
                        y: label.height
                        width: parent.width
                        height: Theme.px(56)
                        radius: Theme.px(10)
                        opacity: modelData.kind === "clear" && Transfer.sharedCount === 0 ? 0.55 : 1
                        color: on ? Theme.tile : tileHover.hovered ? Theme.raised : "transparent"
                        border.width: on ? Theme.focusWidth : 0
                        border.color: Theme.accent
                        Accessible.role: Accessible.Button
                        Accessible.name: (modelData.kind === "device" ? "Send to " : "") + modelData.name
                        Rectangle {
                            id: ava
                            anchors.left: parent.left; anchors.leftMargin: Theme.px(10)
                            anchors.verticalCenter: parent.verticalCenter
                            width: Theme.px(34); height: width; radius: width / 2
                            color: Theme.raised
                            Icon {
                                anchors.centerIn: parent
                                size: Theme.px(18)
                                name: modelData.kind === "pick" ? "plus" : modelData.kind === "clear" ? "back" : modelData.type === "mobile" ? "phone" : "network"
                                color: tile.on ? Theme.accent : Theme.textMuted
                            }
                        }
                        Column {
                            anchors.left: ava.right; anchors.leftMargin: Theme.px(10)
                            anchors.right: parent.right; anchors.rightMargin: Theme.px(10)
                            anchors.verticalCenter: parent.verticalCenter
                            Text { width: parent.width; elide: Text.ElideRight; text: modelData.name; color: tile.on ? Theme.text : Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(16); font.weight: Font.DemiBold }
                            Text { width: parent.width; elide: Text.ElideRight; text: modelData.detail; color: modelData.kind === "pick" && Transfer.sharedCount > 0 ? Theme.accent : Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
                        }
                        HoverHandler { id: tileHover }
                        TapHandler { onTapped: { page.current = index; page.choose() } }
                    }
                }
            }
        }
        Text {
            visible: page.devices.length === 0
            anchors.top: rowList.bottom
            anchors.topMargin: Theme.px(14)
            x: Theme.px(20)
            width: parent.width - Theme.px(40)
            wrapMode: Text.WordWrap
            text: !Settings.nearbySharing ? "Nearby sharing is off. Turn it on in Settings › General."
                : !LocalSend.running ? (LocalSend.error || "Nearby sharing is starting…")
                : LocalSend.scanning ? "Looking for devices on your Wi-Fi…"
                : "No devices yet. Open Muninn or the free LocalSend app on the same Wi-Fi, then press Y to look again. This device shows up as “" + LocalSend.alias + "”."
            color: Theme.textMuted
            font.family: Theme.body
            font.pixelSize: Theme.fs(14)
        }
    }

    // ============================================================= main column
    Item {
        id: main
        anchors.left: middle.right
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.bottom: parent.bottom

        PageHeader {
            id: mainHead
            width: parent.width
            carved: false
            title: "Sharing"
            subtitle: !Settings.nearbySharing ? "Nearby sharing is off in Settings"
                    : LocalSend.running ? "Shows up in LocalSend as “" + LocalSend.alias + "”" : (LocalSend.error || "Starting nearby sharing…")
            HButton {
                implicitHeight: Theme.px(36)
                fontSize: 15
                enabled: LocalSend.running && !LocalSend.scanning
                text: LocalSend.scanning ? "Looking…" : "Look again"
                onClicked: LocalSend.refresh()
            }
        }

        Column {
            id: lists
            anchors.top: mainHead.bottom
            anchors.topMargin: Theme.px(16)
            anchors.left: parent.left; anchors.leftMargin: Theme.px(24)
            anchors.right: parent.right; anchors.rightMargin: Theme.px(24)
            spacing: Theme.px(8)

            SectionLabel { text: "READY TO SEND"; detail: Transfer.sharedCount > 0 ? "Pick a device and press A" : "" }
            Text {
                visible: Transfer.sharedCount === 0
                width: parent.width
                wrapMode: Text.WordWrap
                text: "Nothing picked yet. Choose Pick files to send, then pick a device. To receive, send from Muninn or LocalSend and accept on screen."
                color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(15)
            }
            Repeater {
                model: page.sharedNames.slice(0, 4)
                Text { width: lists.width; elide: Text.ElideMiddle; text: modelData; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(16) }
            }
            Text {
                visible: page.sharedNames.length > 4
                text: "and " + (page.sharedNames.length - 4) + " more"
                color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(14)
            }

            Item { width: 1; height: Theme.px(8) }
            SectionLabel { text: "RECEIVED"; detail: "Saved in " + Transfer.saveName }
            Repeater {
                model: page.incoming.slice(0, 4)
                Rectangle {
                    width: lists.width
                    height: Theme.px(52)
                    radius: Theme.px(8)
                    color: Theme.panel
                    border.color: Theme.line
                    Column {
                        anchors.fill: parent
                        anchors.margins: Theme.px(11)
                        spacing: Theme.px(7)
                        Item {
                            width: parent.width; height: Theme.px(19)
                            Text { width: parent.width - st.width - Theme.px(12); elide: Text.ElideMiddle; text: modelData.name; color: Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(15); font.weight: Font.Medium }
                            Text { id: st; anchors.right: parent.right; text: modelData.status; color: modelData.failed ? Theme.danger : Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(13) }
                        }
                        Rectangle {
                            width: parent.width; height: Theme.px(4); radius: 1; color: Theme.line
                            Rectangle { width: parent.width * modelData.fraction; height: parent.height; color: modelData.fraction >= 1 ? Theme.textFaint : Theme.accent }
                        }
                    }
                }
            }
            Text {
                visible: page.incoming.length === 0
                width: parent.width
                wrapMode: Text.WordWrap
                text: "Nothing yet. Files show up here as they arrive."
                color: Theme.textMuted; font.family: Theme.body; font.pixelSize: Theme.fs(15)
            }
        }

        // No app on the phone: scan a code instead, then type the PIN.
        Rectangle {
            id: backup
            anchors.bottom: parent.bottom
            anchors.bottomMargin: Theme.px(18)
            anchors.left: parent.left; anchors.leftMargin: Theme.px(24)
            anchors.right: parent.right; anchors.rightMargin: Theme.px(24)
            height: Theme.px(176)
            radius: Theme.px(10)
            color: Theme.panelLow
            border.color: Theme.line
            Rectangle {
                id: qr
                anchors.left: parent.left
                anchors.leftMargin: Theme.px(14)
                anchors.verticalCenter: parent.verticalCenter
                width: Theme.px(148); height: width
                radius: Theme.px(6)
                color: Theme.text
                Image {
                    anchors.fill: parent
                    anchors.margins: Theme.px(10)
                    source: Direct.on ? "image://qr/" + encodeURIComponent(Direct.qr) : Transfer.running ? "image://qr/" + encodeURIComponent(Transfer.address) : ""
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
                Text { width: parent.width; wrapMode: Text.WordWrap; text: Direct.on ? "Direct connect is on. Scan with the phone camera to join this device's Wi-Fi" : "No app on the phone? Scan this with its camera"; color: Direct.on ? Theme.accent : Theme.text; font.family: Theme.body; font.pixelSize: Theme.fs(16); font.weight: Font.DemiBold }
                Text { visible: Direct.on; width: parent.width; elide: Text.ElideRight; text: "Wi-Fi " + Direct.ssid + "  ·  password " + Direct.password; color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(15) }
                // Never an address on screen: the camera opens the page, then the PIN lets it in.
                Text { visible: !Direct.on; width: parent.width; elide: Text.ElideRight; text: Transfer.running ? "Opens a page from “" + LocalSend.alias + "”, then type the PIN" : Transfer.error; color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(16) }
                Text { visible: Direct.error.length > 0 && !Direct.on; width: parent.width; wrapMode: Text.WordWrap; text: Direct.error; color: Theme.danger; font.family: Theme.body; font.pixelSize: Theme.fs(14) }
                Row {
                    visible: !Direct.on
                    spacing: Theme.px(10)
                    Text { anchors.verticalCenter: parent.verticalCenter; text: "PIN"; color: Theme.textSoft; font.family: Theme.body; font.pixelSize: Theme.fs(15) }
                    Text { anchors.verticalCenter: parent.verticalCenter; text: Transfer.pin; color: Theme.accent; font.family: Theme.display; font.pixelSize: Theme.fs(23); font.weight: Font.Bold; font.letterSpacing: Theme.px(6) }
                }
            }
        }
    }

    component SectionLabel: Item {
        property alias text: labelText.text
        property string detail
        width: parent ? parent.width : 0
        height: Theme.px(22)
        Text {
            id: labelText
            anchors.verticalCenter: parent.verticalCenter
            color: Theme.textMuted
            font.family: Theme.body; font.pixelSize: Theme.fs(12); font.weight: Font.DemiBold; font.letterSpacing: Theme.px(1.5)
        }
        Text {
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            text: parent.detail
            color: Theme.textMuted
            font.family: Theme.body; font.pixelSize: Theme.fs(13)
        }
    }

    // ============================================================= actions
    function pickFiles() {
        const sections = System.sendableFiles(15)
        if (!sections.length) { Ui.say("No files found in Downloads, Documents, Pictures, Videos or Music.", true); return }
        Ui.pickFrom("Add a file to send", sections, url => {
            Transfer.share([url])
            Ui.say(Transfer.sharedCount === 1 ? "1 file ready. Pick more, or pick a device." : Transfer.sharedCount + " files ready. Pick more, or pick a device.")
        })
    }
    function sendTo(d) {
        if (Transfer.sharedCount === 0) { Ui.say("Pick files first: choose Pick files to send.", true); return }
        LocalSend.send(d.id, Transfer.shared)
    }
    function choose() {
        const r = rows[current]
        if (!r) return
        if (r.kind === "pick") pickFiles()
        else if (r.kind === "clear") { if (Transfer.sharedCount > 0) { Transfer.clearShared(); Ui.say("The list is clear.") } }
        else sendTo(r)
    }

    // No shared Wi-Fi: the handheld makes its own for the phone to join.
    function toggleDirect() {
        if (Direct.on) { Direct.stop(); Ui.say("Direct connect is off. This device goes back to your usual Wi-Fi."); return }
        Ui.sure("Start Direct connect?", "This device makes its own private Wi-Fi (no internet) and shows a code for the phone's camera. It leaves your usual Wi-Fi until you stop it.",
                "Start", () => Direct.start(), false, false)
    }
    function handleButton(b) {
        if (LocalSend.sending) {
            if (b === "b") { LocalSend.cancelSend(); return true }
            return b !== "left"
        }
        switch (b) {
        case "up": current = Math.max(0, current - 1); return true
        case "down": current = Math.min(rows.length - 1, current + 1); return true
        case "a": choose(); return true
        case "y": LocalSend.refresh(); return true
        case "back": toggleDirect(); return true
        case "x": Transfer.newPin(); return true
        }
        return false   // Left and B: back to the rail
    }
}

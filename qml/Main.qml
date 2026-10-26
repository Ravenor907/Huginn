import QtQuick
import QtQuick.Window
import Huginn

// The window: the rail on the left, the open section beside it (its own list and main columns),
// and on a handheld the button hints along the bottom. Overlays sit on top.
Window {
    id: app
    width: System.tourDir.length > 0 ? 1920 : 1280
    height: System.tourDir.length > 0 ? 1080 : 800
    visible: true
    visibility: System.windowed ? Window.Windowed : Window.FullScreen
    title: "Huginn"
    color: Theme.ground

    // ------------------------------------------------------------- layout density
    // Automatic: the desktop density for a wide window when no controller is in use (Desktop Mode
    // with a mouse and keyboard); handheld sizes otherwise. Game Mode always gets handheld sizes.
    readonly property bool touring: System.tourDir.length > 0
    property bool tourDesktop: false
    property bool padInUse: Gamepad.connected
    readonly property bool wide: width >= 1100
    readonly property bool desktopLayout: touring ? tourDesktop
        : Settings.layout === "desktop" ? true
        : Settings.layout === "handheld" ? false
        : !System.gameMode && wide && !padInUse
    Binding { target: System; property: "desktop"; value: app.desktopLayout }

    // ------------------------------------------------------------- the rail
    // Groups join this list in a later update, between Direct messages and the bottom buttons.
    readonly property var railItems: [
        { id: "dms", kind: "raven", label: "Direct messages", badge: Vault.screenLocked ? 0 : Chat.unreadCount + Chat.requests.length },
        { id: "calls", kind: "icon", icon: "call", label: "Calls", bottom: true },
        { id: "files", kind: "icon", icon: "folder", label: "Files", bottom: true, badge: LocalSend.request.active ? 1 : 0 },
        { id: "settings", kind: "icon", icon: "gear", label: "Settings", bottom: true },
        { id: "exit", kind: "icon", icon: "power", label: "Exit Huginn", bottom: true, action: true }
    ]
    // LB and RB step through the sections (Exit is reached with the D-pad only).
    readonly property var cycle: railItems.filter(e => !e.action).map(e => e.id)

    // ------------------------------------------------------------- pages
    property string pageName: "dms"
    property var pageArgs: ({})
    property string focusZone: "content"     // "rail" or "content"
    property int railIndex: 0
    // The rail item for the page on screen (Contacts belongs to Settings).
    readonly property string section: pageName === "contacts" ? "settings" : pageName

    function openPage(name, args) {
        if (name === "chat") name = "dms"
        pageArgs = args || {}
        focusZone = "content"
        if (name === pageName) {
            if (pageLoader.item && pageLoader.item.setArgs) pageLoader.item.setArgs(pageArgs)
            return
        }
        pageName = name
    }
    function openRail(index) {
        const e = railItems[index]
        if (!e) return
        railIndex = index
        if (e.id === "exit") { requestExit(); return }
        openPage(e.id, {})
    }
    function stepSection(dir) {
        const i = cycle.indexOf(section)
        openPage(cycle[(i + dir + cycle.length) % cycle.length], {})
        railIndex = railItems.findIndex(e => e.id === section)
    }
    function focusRail() {
        railIndex = Math.max(0, railItems.findIndex(e => e.id === section))
        focusZone = "rail"
        keys.forceActiveFocus()
    }
    // Back out of a page: Contacts goes back to Settings, everything else to the rail.
    function closePage() {
        if (pageName === "contacts") openPage("settings", { section: 1 })
        else focusRail()
        keys.forceActiveFocus()
    }
    function restoreFocus() { keys.forceActiveFocus() }
    // Every Exit goes through here, so a transfer is never cut off by surprise.
    function requestExit() {
        if (LocalSend.busy)
            Ui.sure("Exit while this runs?", LocalSend.status + " Closing now stops it partway.", "Exit anyway", () => Qt.quit(), true, true)
        else
            Ui.sure("Exit Huginn?", System.gameMode ? "You'll land back in Steam." : "Messages wait for you until you open Huginn again.", "Exit", () => Qt.quit(), false, false)
    }

    Rail {
        id: rail
        anchors.top: parent.top
        anchors.bottom: hints.visible ? hints.top : parent.bottom
        anchors.left: parent.left
        items: app.railItems
        current: app.section
        focusIndex: app.railIndex
        focused: app.focusZone === "rail"
        onActivated: index => app.openRail(index)
    }

    Loader {
        id: pageLoader
        anchors.top: parent.top
        anchors.bottom: hints.visible ? hints.top : parent.bottom
        anchors.left: rail.right
        anchors.right: parent.right
        source: {
            switch (app.pageName) {
            case "dms": return "ChatPage.qml"
            case "calls": return "CallsPage.qml"
            case "files": return "FilesPage.qml"
            case "settings": return "SettingsPage.qml"
            case "contacts": return "ContactsPage.qml"
            }
            return "ChatPage.qml"
        }
        onLoaded: if (item.setArgs) item.setArgs(app.pageArgs)
    }

    // Handheld: the buttons and what they do, along the bottom like the mockups.
    PromptBar {
        id: hints
        anchors.bottom: parent.bottom
        width: parent.width
        visible: !Theme.desktop || LocalSend.busy
        status: app.focusZone === "rail" ? "" : pageLoader.item && pageLoader.item.status !== undefined ? pageLoader.item.status : ""
        prompts: {
            const lbrb = [{ key: "lbrb", label: "Sections" }]
            if (app.focusZone === "rail")
                return lbrb.concat([{ key: "a", label: "Open" }, { key: "b", label: "Exit" }])
            const p = pageLoader.item && pageLoader.item.prompts ? pageLoader.item.prompts : []
            return lbrb.concat(p)
        }
        onPrompt: k => k === "lbrb" ? app.stepSection(1) : app.press(k)
    }

    // ------------------------------------------------------------- overlays
    ListPicker { id: picker; z: 25 }
    TextPrompt { id: prompt; z: 30 }
    ConfirmDialog { id: confirm; z: 40 }
    Toast { id: toast }

    // Shown while Huginn updates itself at launch; it restarts on the new version after.
    Rectangle {
        id: updating
        anchors.fill: parent
        z: 100
        visible: Updater.autoUpdating
        color: Theme.ground
        MouseArea { anchors.fill: parent }
        Column {
            anchors.centerIn: parent
            spacing: Theme.px(16)
            Image {
                anchors.horizontalCenter: parent.horizontalCenter
                source: "assets/raven-gold.png"
                width: Theme.px(96); height: width
                sourceSize.width: 256; sourceSize.height: 256
            }
            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                text: "UPDATING HUGINN TO " + Updater.latest.toUpperCase()
                color: Theme.text
                font.family: Theme.display
                font.pixelSize: Theme.fs(24)
                font.weight: Font.Bold
                font.letterSpacing: Theme.px(3)
            }
            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                text: Updater.status
                color: Theme.textMuted
                font.family: Theme.body
                font.pixelSize: Theme.fs(18)
            }
        }
    }

    Component.onCompleted: {
        Ui.main = app
        Ui.prompt = prompt
        Ui.confirm = confirm
        Ui.toast = toast
        Ui.picker = picker
        if (touring) tour.start()
    }

    // ------------------------------------------------------------- nearby sharing (LocalSend)
    // Someone wants to send files: ask on screen, wherever the person is in the app. If another
    // question is already open, wait for it to close instead of replacing it.
    function showSendRequest() {
        const r = LocalSend.request
        if (!r.active) {
            if (confirm.visible && confirm.tag === "localsend") confirm.dismiss()
            return
        }
        if (confirm.visible) {
            if (confirm.tag !== "localsend") Ui.say(r.from + " wants to send you files. Answer this first.")
            return
        }
        confirm.open(r.from + " wants to send " + (r.count === 1 ? "a file" : r.count + " files"),
                     r.names + " (" + r.sizeText + "). They'll be saved in " + Transfer.saveName + ".",
                     "Accept", () => LocalSend.accept(), false, false, () => LocalSend.decline(), "localsend")
        confirm.cancelText = "Decline"
    }
    // Chat: notices, and a toast for a message unless that chat is on screen (main.cpp plays the sound).
    Connections {
        target: Chat
        function onNotice(text) { Ui.say(text) }
        function onArrived(name, text, key) {
            if (Chat.openChat === key && app.pageName === "dms") return
            Ui.say(Chat.showPreview && !Vault.screenLocked ? name + ": " + text : "New message. Open Direct messages to read it.")
        }
    }
    // Panic lock (hold L3 and R3): close anything open over the screen.
    Connections {
        target: Vault
        function onPanicked() {
            if (prompt.visible) prompt.cancel()
            if (picker.visible) picker.close()
            Ui.say("Chat locked.")
        }
    }
    Connections {
        target: LocalSend
        function onRequestChanged() { app.showSendRequest() }
        function onFinished(ok, message) { Ui.say(message, !ok) }
        function onPinNeeded(deviceId, wrong) {
            Ui.ask(wrong ? "That PIN didn't work. Try again" : "Enter the PIN shown on the other device", "LocalSend", "", "Send",
                   pin => LocalSend.send(deviceId, Transfer.shared, pin), false)
        }
    }
    Connections {
        target: confirm
        function onVisibleChanged() { if (!confirm.visible && LocalSend.request.active) Qt.callLater(app.showSendRequest) }
    }

    // ------------------------------------------------------------- input
    function overlay() {
        if (updating.visible) return updating
        if (confirm.visible) return confirm
        if (prompt.visible) return prompt
        if (picker.visible) return picker
        return null
    }
    function page() { return pageLoader.item && pageLoader.status === Loader.Ready ? pageLoader.item : null }

    function railButton(b) {
        switch (b) {
        case "up": railIndex = Math.max(0, railIndex - 1); break
        case "down": railIndex = Math.min(railItems.length - 1, railIndex + 1); break
        case "a": case "right": openRail(railIndex); break
        case "b": requestExit(); break
        case "start": openPage("settings", {}); break
        }
    }
    function press(b) {
        const o = overlay()
        if (o) { if (o.handleButton) o.handleButton(b); return }
        const p = page()
        // LB and RB move between rail items, unless the page needs them right now (the chat lock).
        if ((b === "lb" || b === "rb") && !(p && p.ownsShoulders && focusZone === "content")) { stepSection(b === "lb" ? -1 : 1); return }
        if (focusZone === "rail") { railButton(b); return }
        if (p && p.handleButton && p.handleButton(b)) return
        // What the page didn't use: Left and B go to the rail, Menu opens Settings.
        if (b === "left" || b === "b") focusRail()
        else if (b === "start") openPage("settings", {})
    }
    function release(b) {
        const o = overlay()
        if (o) { if (o.handleRelease) o.handleRelease(b); return }
        const p = page()
        if (focusZone === "content" && p && p.handleRelease) p.handleRelease(b)
    }

    Connections {
        target: Gamepad
        function onPressed(button) { app.padInUse = true; app.press(button) }
        function onReleased(button) { app.release(button) }
    }
    // Keyboard stands in for the controller in Desktop Mode.
    Item {
        id: keys
        anchors.fill: parent
        focus: true
        // A mouse in use: Automatic switches to the desktop density.
        // Moving it over the columns also puts the focus back on them (clicks there act on the page).
        HoverHandler {
            onPointChanged: {
                if (app.touring) return
                if (app.padInUse) app.padInUse = false
                if (app.focusZone === "rail" && point.position.x > rail.width) app.focusZone = "content"
            }
        }
        Keys.onPressed: event => {
            if (event.isAutoRepeat && (event.key === Qt.Key_Return || event.key === Qt.Key_Space)) return
            const map = {}
            map[Qt.Key_Up] = "up"; map[Qt.Key_Down] = "down"; map[Qt.Key_Left] = "left"; map[Qt.Key_Right] = "right"
            map[Qt.Key_Return] = "a"; map[Qt.Key_Enter] = "a"; map[Qt.Key_Escape] = "b"; map[Qt.Key_Backspace] = "b"
            map[Qt.Key_Space] = "x"; map[Qt.Key_Menu] = "y"; map[Qt.Key_F10] = "y"; map[Qt.Key_Tab] = "rb"
            map[Qt.Key_Backtab] = "lb"; map[Qt.Key_PageUp] = "lt"; map[Qt.Key_PageDown] = "rt"; map[Qt.Key_F1] = "start"
            const b = map[event.key]
            if (b) { if (!app.touring) app.padInUse = false; app.press(b); event.accepted = true }
        }
        Keys.onReleased: event => {
            if (event.isAutoRepeat) return
            if (event.key === Qt.Key_Return || event.key === Qt.Key_Enter) app.release("a")
            if (event.key === Qt.Key_Menu || event.key === Qt.Key_F10) app.release("y")
            if (event.key === Qt.Key_Space) app.release("x")
        }
    }

    // ------------------------------------------------------------- screenshot tour (CI)
    // `huginn --tour DIR` opens every screen, saves a picture of each and quits. CI fails the
    // build on any QML warning, so a broken screen never reaches a release.
    Timer {
        id: tour
        property int step: 0
        readonly property var steps: [
            { page: "dms", file: "00-direct-messages" },
            { page: "dms", zone: "messages", file: "01-conversation" },
            { page: "dms", rail: true, file: "02-rail" },
            { page: "files", file: "03-files" },
            { page: "calls", file: "04-calls" },
            { page: "settings", file: "05-settings" },
            { page: "settings", section: 1, file: "06-settings-chat" },
            { page: "contacts", file: "07-contacts" },
            { page: "dms", picker: true, file: "08-options" },
            { page: "dms", prompt: true, file: "09-write" },
            { page: "dms", hold: true, file: "10-confirm" },
            { page: "dms", zone: "messages", desktop: true, file: "11-desktop" },
            { page: "settings", desktop: true, file: "12-desktop-settings" }
        ]
        interval: 1500
        repeat: true
        onTriggered: {
            if (step > 0) System.grab(app, steps[step - 1].file)
            prompt.visible = false; confirm.visible = false; picker.visible = false
            if (step >= steps.length) { stop(); System.tourFinished(); return }
            const s = steps[step]
            const args = { tour: true }
            if (s.section) args.section = s.section
            if (s.zone) args.zone = s.zone
            app.tourDesktop = !!s.desktop
            app.openPage(s.page, args)
            if (s.rail) app.focusRail()
            if (s.picker) picker.openList("Message", [{ header: "", items: [{ name: "Unsend", detail: "Removes it from both sides", url: "unsend" }, { name: "Delete for me", detail: "Only on this computer", url: "delete" }] },
                                                     { header: "Conversation", items: [{ name: "Send a picture", detail: "Location and camera details are removed", url: "picture" }] }], null)
            if (s.prompt) prompt.open("Message everyone nearby", "You're " + Chat.username, "", "Send", null, false, true)
            if (s.hold) confirm.open("Erase everything in Chat?", "Messages, photos, voice, contacts, keys and the lock are deleted for good, and Huginn closes.", "Hold to erase", null, true, true)
            step++
        }
    }
}

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
    // Direct messages, then every group (round initials), + for a new group, then the bottom buttons.
    readonly property var tourGroups: [{ id: "tour-family", name: "Family", unread: 2, mentions: 1 }, { id: "tour-crew", name: "Gaming Crew", unread: 0 },
                                       { id: "tour-folk", name: "Folk", unread: 3 }]
    readonly property var groupList: touring ? tourGroups : Vault.screenLocked ? [] : Chat.groups
    readonly property var railItems: [
        { id: "dms", kind: "raven", label: "Direct messages", badge: Vault.screenLocked ? 0 : Chat.unreadCount + Chat.requests.length + Chat.groupInvites.filter(i => !i.waiting).length }
    // A group: its @mentions of me as a number, and a dot for anything else unread (like Discord).
    ].concat(groupList.map(g => ({ id: "g:" + g.id, kind: "group", label: g.name, letter: g.letter || String(g.name || "?").trim().charAt(0).toUpperCase() || "?",
                                   badge: g.mentions || 0, unread: g.unread || 0 })))
     .concat([
        { id: "newgroup", kind: "add", label: "New group", action: true },
        { id: "calls", kind: "icon", icon: "call", label: "Calls", bottom: true, badge: Vault.screenLocked ? 0 : Calls.missedCount },
        { id: "files", kind: "icon", icon: "folder", label: "Files", bottom: true, badge: LocalSend.request.active ? 1 : 0 },
        { id: "settings", kind: "icon", icon: "gear", label: "Settings", bottom: true },
        { id: "exit", kind: "icon", icon: "power", label: "Exit Huginn", bottom: true, action: true }
    ])
    // LB steps through the sections and groups (New group and Exit are reached with the D-pad).
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
        const groupToGroup = name.startsWith("g:") && pageName.startsWith("g:")
        pageName = name
        // From one group to another the page stays loaded: hand it the new arguments.
        if (groupToGroup && pageLoader.item && pageLoader.item.setArgs) pageLoader.item.setArgs(pageArgs)
    }
    function openRail(index) {
        const e = railItems[index]
        if (!e) return
        railIndex = index
        if (e.id === "exit") { requestExit(); return }
        if (e.id === "newgroup") { newGroup(); return }
        openPage(e.id, {})
    }
    function newGroup() {
        if (Vault.screenLocked || !Vault.exists) { Ui.say("Open Direct messages and unlock Chat first.", true); openPage("dms", {}); return }
        Ui.ask("New group", "A name everyone in it sees, like Family or Folk. You can change it later.", "", "Make it", t => {
            const g = Chat.createGroup(t)
            if (g === "") { Ui.say("Give the group a name.", true); return }
            openPage("g:" + g, {})
        }, false, true)
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
        if (pageName === "contacts") openPage("settings", { section: "Privacy", row: 0 })
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
        opacity: app.privacyCovered ? 0 : 1   // not drawn under the privacy cover
        anchors.top: parent.top
        anchors.bottom: voicePanel.visible ? voicePanel.top : hints.visible ? hints.top : parent.bottom
        anchors.left: parent.left
        items: app.railItems
        current: app.section
        focusIndex: app.railIndex
        focused: app.focusZone === "rail"
        onActivated: index => app.openRail(index)
    }

    Loader {
        id: pageLoader
        opacity: app.privacyCovered ? 0 : 1   // not drawn under the privacy cover
        anchors.top: parent.top
        anchors.bottom: roomBar.visible ? roomBar.top : hints.visible ? hints.top : parent.bottom
        anchors.left: rail.right
        anchors.right: parent.right
        source: {
            if (app.pageName.startsWith("g:")) return "GroupPage.qml"
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

    // In a voice room or group call: who's in it and the controls, under every page.
    RoomBar {
        id: roomBar
        opacity: app.privacyCovered ? 0 : 1   // not drawn under the privacy cover
        anchors.bottom: hints.visible ? hints.top : parent.bottom
        anchors.left: voicePanel.visible ? voicePanel.right : rail.right
        anchors.right: parent.right
        // On the desktop the voice panel has the controls; a shared screen still shows above here.
        docked: voicePanel.visible
    }

    // ------------------------------------------------------------- the desktop panels
    // The page's own list column shows (ChatPage and GroupPage can hide theirs), and a group's member list.
    readonly property bool pageList: { const p = pageLoader.item; return !(p && p.listHidden === true) }
    readonly property bool pageMembers: { const p = pageLoader.item; return !!(p && p.membersVisible === true) }
    property bool tourVoice: false
    // In a voice room on the desktop: the voice panel at the foot of the rail and the list column.
    VoicePanel {
        id: voicePanel
        tour: app.tourVoice
        roomBar: roomBar
        visible: shown && Theme.desktop && app.pageList && !app.privacyCovered
        anchors.left: parent.left
        anchors.bottom: hints.visible ? hints.top : parent.bottom
        width: rail.width + Theme.listWidth
        z: 2
    }
    Binding { target: Ui; property: "dock"; value: voicePanel.visible ? voicePanel.height : 0 }
    // Drag the edges between the rail, the list, the conversation and the member list.
    Splitter {
        anchors.top: parent.top; anchors.bottom: rail.bottom
        x: rail.width - width / 2
        z: 3
        visible: Theme.desktop && !app.privacyCovered
        label: "rail"
        value: Ui.railW; minimum: 64; maximum: 120; fallback: 83
        onMoved: v => Ui.railW = v
        onDone: v => Settings.railWidth = v
    }
    Splitter {
        anchors.top: parent.top; anchors.bottom: pageLoader.bottom
        anchors.bottomMargin: Ui.dock
        x: rail.width + Theme.listWidth - width / 2
        z: 3
        visible: Theme.desktop && app.pageList && !app.privacyCovered
        label: "list"
        value: Ui.listW; minimum: 200; maximum: 480; fallback: 270
        onMoved: v => Ui.listW = v
        onDone: v => Settings.listWidth = v
    }
    Splitter {
        anchors.top: parent.top; anchors.bottom: pageLoader.bottom
        x: app.width - Theme.memberWidth - width / 2
        z: 3
        visible: Theme.desktop && app.pageMembers && !app.privacyCovered
        label: "member list"
        direction: -1
        value: Ui.memberW; minimum: 180; maximum: 400; fallback: 230
        onMoved: v => Ui.memberW = v
        onDone: v => Settings.memberWidth = v
    }
    // Show or hide the list beside the rail ("list") or a group's member list ("members"). The
    // controller's focus leaves a panel that's hidden.
    function togglePane(which) {
        if (which === "list") Settings.showList = !Settings.showList
        else Settings.showMembers = !Settings.showMembers
        const p = page()
        if (!p || p.zone === undefined) return
        if (which === "list" && !Settings.showList && (p.zone === "channels" || p.zone === "list")) p.zone = "messages"
        if (which === "members" && !Settings.showMembers && p.zone === "members") p.zone = "messages"
    }

    // ------------------------------------------------------------- who's online (member list, profile cards)
    // Active status (cap m1) says who has Huginn or Muninn open now; idle is someone seen in the last
    // half hour who isn't any more.
    property var lastActive: ({})
    property real clock: Date.now()
    Timer { interval: 60000; running: true; repeat: true; onTriggered: app.clock = Date.now() }
    function presenceOf(key) {
        if (!key || Vault.screenLocked) return "offline"
        if (Chat.active[key] !== undefined) return "online"
        const t = lastActive[key]
        return t !== undefined && clock - t < 1800000 ? "idle" : "offline"
    }

    // Handheld: the buttons and what they do, along the bottom like the mockups.
    PromptBar {
        id: hints
        anchors.bottom: parent.bottom
        z: callPage.visible || incomingCall.visible ? 38 : 0   // the call's buttons stay on show over it
        width: parent.width
        visible: !Theme.desktop || LocalSend.busy
        status: incomingCall.visible ? incomingCall.status : callPage.visible ? callPage.status
              : app.focusZone === "rail" ? "" : pageLoader.item && pageLoader.item.status !== undefined ? pageLoader.item.status : ""
        prompts: {
            if (incomingCall.visible) return incomingCall.prompts
            if (callPage.visible) return callPage.prompts
            const lb = [{ key: "lb", label: "Next section" }]
            if (app.focusZone === "rail")
                return lb.concat([{ key: "a", label: "Open" }, { key: "b", label: "Exit" }])
            const p = pageLoader.item && pageLoader.item.prompts ? pageLoader.item.prompts : []
            return lb.concat(p)
        }
        // A tap on a chip is a whole press: LB here can't be held, so it just goes to the next section.
        onPrompt: k => k === "lb" && !app.overlay() ? app.stepSection(1) : app.press(k)
    }

    // ------------------------------------------------------------- overlays
    ListPicker { id: picker; z: 25 }
    // Ctrl+K (desktop): jump to any chat or channel by typing part of its name.
    QuickSwitcher { id: switcher; z: 26 }
    Shortcut {
        sequence: "Ctrl+K"
        context: Qt.ApplicationShortcut
        enabled: !app.touring && !updating.visible
        onActivated: switcher.visible ? switcher.close() : switcher.open()
    }
    // The rest of the keyboard shortcuts (all listed in ShortcutsPanel.qml).
    readonly property bool keysOn: !app.touring && !updating.visible && !app.privacyCovered
    Shortcut { sequence: "Ctrl+/"; context: Qt.ApplicationShortcut; enabled: app.keysOn
               onActivated: shortcutsPanel.visible ? shortcutsPanel.close() : shortcutsPanel.open() }
    Shortcut { sequence: "Ctrl+Shift+L"; context: Qt.ApplicationShortcut; enabled: app.keysOn && Theme.desktop; onActivated: app.togglePane("list") }
    Shortcut { sequence: "Ctrl+Shift+U"; context: Qt.ApplicationShortcut; enabled: app.keysOn && Theme.desktop; onActivated: app.togglePane("members") }
    Shortcut { sequence: "Ctrl+Shift+M"; context: Qt.ApplicationShortcut; enabled: app.keysOn && (Rooms.inRoom || Calls.inCall)
               onActivated: Rooms.inRoom ? Rooms.toggleMute() : Calls.toggleMute() }
    Shortcut { sequence: "Ctrl+Shift+D"; context: Qt.ApplicationShortcut; enabled: app.keysOn && Rooms.inRoom; onActivated: Rooms.toggleDeafen() }
    Shortcut { sequence: "Ctrl+Shift+H"; context: Qt.ApplicationShortcut; enabled: app.keysOn && Rooms.inRoom; onActivated: Rooms.leave() }
    Shortcut { sequences: ["Ctrl+=", "Ctrl++"]; context: Qt.ApplicationShortcut; enabled: app.keysOn; onActivated: app.messageSize(1) }
    Shortcut { sequence: "Ctrl+-"; context: Qt.ApplicationShortcut; enabled: app.keysOn; onActivated: app.messageSize(-1) }
    Shortcut { sequence: "Ctrl+0"; context: Qt.ApplicationShortcut; enabled: app.keysOn; onActivated: app.messageSize(0) }
    Shortcut { sequence: "Ctrl+,"; context: Qt.ApplicationShortcut; enabled: app.keysOn; onActivated: app.openPage("settings", { section: "Appearance", row: 0 }) }
    function showShortcuts() { shortcutsPanel.open() }
    readonly property var messageSizes: ["85", "100", "115", "130", "150"]
    function messageSize(dir) {
        if (dir === 0) { Settings.messageSize = "100"; return }
        const i = Math.max(0, messageSizes.indexOf(Settings.messageSize))
        Settings.messageSize = messageSizes[Math.max(0, Math.min(messageSizes.length - 1, i + dir))]
        Ui.say("Message text: " + Settings.messageSize + "%")
    }
    // A click on a name: their profile card (under the lists it can open).
    ProfileCard { id: profileCard; z: 24 }
    // Ctrl+/: every keyboard shortcut.
    ShortcutsPanel { id: shortcutsPanel; z: 27 }
    TextPrompt { id: prompt; z: 30 }
    CallPage { id: callPage; z: 36; opacity: app.privacyCovered ? 0 : 1 }
    IncomingCall { id: incomingCall; z: 37; opacity: app.privacyCovered ? 0 : 1 }
    ConfirmDialog { id: confirm; z: 40 }
    Toast { id: toast }
    // The right stick's mouse pointer, over everything.
    StickPointer {
        id: stick
        z: 1000
        host: app
        onClicked: (x, y) => { if (app.focusZone === "rail" && x > rail.width) app.focusZone = "content" }
    }

    // The privacy cover (see Vault.covered): Huginn is in the background with Chat open behind a lock.
    // Nothing under it is drawn, so a minimized window, a taskbar preview or the picture Windows
    // shows on wake has no chat in it. It goes when Huginn is in front again (or the lock shows).
    readonly property bool privacyCovered: Vault.covered && !app.touring
    Rectangle {
        id: privacyCover
        anchors.fill: parent
        z: 99
        visible: app.privacyCovered
        color: Theme.ground
        MouseArea { anchors.fill: parent }
        Image {
            anchors.centerIn: parent
            source: "assets/raven-gold.png"
            width: Theme.px(96); height: width
            sourceSize.width: 256; sourceSize.height: 256
            opacity: 0.5
        }
    }

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
        Ui.profile = profileCard
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
        function onLiveChanged() {
            const a = Chat.active, t = Date.now(), seen = Object.assign({}, app.lastActive)
            for (const k in a) seen[k] = t
            app.lastActive = seen
            app.clock = t
        }
        function onArrived(name, text, key) {
            if (Chat.openChat === key && (app.pageName === "dms" || app.pageName.startsWith("g:"))) return
            // An @mention always says so (without the text unless previews are on).
            const mention = name.indexOf(" mentioned you in ") > 0
            Ui.say(Chat.showPreview && !Vault.screenLocked ? name + ": " + text : mention && !Vault.screenLocked ? name + "." : "New message. Open Direct messages to read it.")
        }
    }
    // Calls: how a call ended, as a toast.
    Connections {
        target: Calls
        function onFinished(t) { Ui.say(t) }
    }
    Connections {
        target: Rooms
        function onFinished(t) { Ui.say(t) }
    }
    // Panic lock (hold L3 and R3): close anything open over the screen.
    Connections {
        target: Vault
        function onPanicked() {
            if (prompt.visible) prompt.cancel()
            if (picker.visible) picker.close()
            if (switcher.visible) switcher.close()
            if (profileCard.visible) profileCard.close()
            if (shortcutsPanel.visible) shortcutsPanel.close()
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
        if (incomingCall.visible) return incomingCall
        if (callPage.visible) return callPage
        if (prompt.visible) return prompt
        if (picker.visible) return picker
        if (switcher.visible) return switcher
        if (shortcutsPanel.visible) return shortcutsPanel
        if (profileCard.visible) return profileCard
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
    // LB on its own goes to the next section when it's let go; held, the D-pad's left and right
    // go back and forward instead (and it doesn't move on when let go).
    property bool lbHeld: false
    property bool lbUsed: false
    function press(b) {
        const o = overlay()
        if (o) { if (o.handleButton) o.handleButton(b); return }
        const p = page()
        // The page has LB right now (the chat lock's pattern or password).
        const pageShoulders = p && p.ownsShoulders && focusZone === "content"
        if (b === "lb" && !pageShoulders) { lbHeld = true; lbUsed = false; return }
        if (lbHeld && (b === "left" || b === "right")) { lbUsed = true; stepSection(b === "left" ? -1 : 1); return }
        // Tab and Shift+Tab on the keyboard.
        if ((b === "next" || b === "prev") && !pageShoulders) { stepSection(b === "prev" ? -1 : 1); return }
        if (focusZone === "rail") { railButton(b); return }
        // LT opens the options, which pages know as X.
        if (b === "lt") b = "x"
        if (p && p.handleButton && p.handleButton(b)) return
        // What the page didn't use: Left and B go to the rail, Menu opens Settings.
        if (b === "left" || b === "b") focusRail()
        else if (b === "start") openPage("settings", {})
    }
    function release(b) {
        if (b === "lb" && lbHeld) {
            lbHeld = false
            if (!lbUsed && !overlay()) stepSection(1)
            return
        }
        const o = overlay()
        if (o) { if (o.handleRelease) o.handleRelease(b); return }
        const p = page()
        if (focusZone === "content" && p && p.handleRelease) p.handleRelease(b)
    }

    // The right stick's buttons belong to the pointer: RB and R3 click, RT right-clicks.
    // L3 goes along too, so R3 doesn't click when both are held for the panic lock.
    function isPointerButton(b) { return b === "rb" || b === "rt" || b === "rs" || b === "ls" }
    Connections {
        target: Gamepad
        function onPressed(button) {
            app.padInUse = true
            if (app.isPointerButton(button)) stick.press(button)
            else app.press(button)
        }
        function onReleased(button) {
            if (app.isPointerButton(button)) stick.release(button)
            else app.release(button)
        }
        function onConnectedChanged() { if (!Gamepad.connected) { app.lbHeld = false; stick.hide(true) } }
    }

    // ------------------------------------------------------------- left stick: scroll
    // The list in front: under the pointer while it shows, else what the page says has the
    // focus, else whatever is in the middle of the page (or of the window, under an overlay).
    function flickableAt(x, y) {
        let found = null
        let item = app.contentItem, px = x, py = y
        for (let depth = 0; item && depth < 64; depth++) {
            if (item instanceof Flickable && item.contentHeight > item.height + 1) found = item
            const child = item.childAt(px, py)
            if (!child) break
            const p = item.mapToItem(child, px, py)
            px = p.x; py = p.y
            item = child
        }
        return found
    }
    function scrollTarget() {
        if (stick.shown) { const f = flickableAt(stick.x, stick.y); if (f) return f }
        if (overlay()) return flickableAt(app.width / 2, app.height / 2)
        if (focusZone === "rail") return flickableAt(rail.width / 2, rail.height / 2)
        const p = page()
        if (p && p.scrollView) { const f = p.scrollView(); if (f) return f }
        const c = pageLoader.mapToItem(null, pageLoader.width / 2, pageLoader.height / 2)
        return flickableAt(c.x, c.y)
    }
    function scrollBy(dy) {
        const f = scrollTarget()
        if (!f) return
        const top = f.originY - f.topMargin
        const bottom = Math.max(top, f.originY + f.contentHeight + f.bottomMargin - f.height)
        f.contentY = Math.max(top, Math.min(bottom, f.contentY + dy))
    }
    Timer {
        readonly property real dead: 0.2
        interval: 16
        repeat: true
        running: Math.abs(Gamepad.stickY) > dead
        // Slow near the middle, quick pushed all the way.
        onTriggered: {
            const v = Gamepad.stickY
            const t = Math.min(1, (Math.abs(v) - dead) / (1 - dead))
            if (t > 0) app.scrollBy((v < 0 ? -1 : 1) * Theme.px(1500) * t * t * interval / 1000)
        }
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
                // The stick pointer's own hover isn't a mouse.
                if (stick.isOwn(point.position.x, point.position.y)) return
                stick.hide(false)
                if (app.padInUse) app.padInUse = false
                if (app.focusZone === "rail" && point.position.x > rail.width) app.focusZone = "content"
            }
        }
        Keys.onPressed: event => {
            if (event.isAutoRepeat && (event.key === Qt.Key_Return || event.key === Qt.Key_Space)) return
            const map = {}
            map[Qt.Key_Up] = "up"; map[Qt.Key_Down] = "down"; map[Qt.Key_Left] = "left"; map[Qt.Key_Right] = "right"
            map[Qt.Key_Return] = "a"; map[Qt.Key_Enter] = "a"; map[Qt.Key_Escape] = "b"; map[Qt.Key_Backspace] = "b"
            map[Qt.Key_Space] = "x"; map[Qt.Key_Menu] = "y"; map[Qt.Key_F10] = "y"; map[Qt.Key_Tab] = "next"
            map[Qt.Key_Backtab] = "prev"; map[Qt.Key_F1] = "start"
            // Page Up and Page Down scroll like the left stick, a screenful at a time.
            if (event.key === Qt.Key_PageUp || event.key === Qt.Key_PageDown) {
                const f = app.scrollTarget()
                if (f) app.scrollBy((event.key === Qt.Key_PageUp ? -0.9 : 0.9) * f.height)
                event.accepted = true
                return
            }
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
        property string savedTheme: "dark"
        property string savedDensity: "cozy"
        readonly property var steps: [
            { page: "dms", file: "00-direct-messages" },
            { page: "dms", zone: "messages", file: "01-conversation" },
            { page: "dms", rail: true, file: "02-rail" },
            { page: "files", file: "03-files" },
            { page: "calls", file: "04-calls" },
            { page: "settings", file: "05-settings" },
            { page: "settings", section: "Nearby and sharing", file: "06-settings-chat" },
            { page: "contacts", file: "07-contacts" },
            { page: "dms", picker: true, file: "08-options" },
            { page: "dms", prompt: true, file: "09-write" },
            { page: "dms", hold: true, file: "10-confirm" },
            { page: "dms", zone: "messages", desktop: true, file: "11-desktop" },
            { page: "settings", desktop: true, file: "12-desktop-settings" },
            { page: "g:tour-crew", file: "13-group" },
            { page: "g:tour-crew", zone: "messages", desktop: true, file: "14-desktop-group" },
            { page: "g:tour-crew", zone: "channels", groupOptions: true, file: "15-group-options" },
            { page: "g:tour-crew", zone: "channels", voice: true, file: "16-voice-room" },
            { page: "dms", folders: true, file: "17-folders" },
            { page: "dms", rail: true, desktop: true, file: "18-desktop-rail" },
            // The desktop panels (0.94): the voice panel and member statuses, a profile card, the
            // shortcuts, Settings › Appearance, and the light theme with compact messages.
            { page: "g:tour-crew", zone: "messages", desktop: true, voicePanel: true, file: "19-desktop-voice-members" },
            { page: "g:tour-crew", zone: "members", desktop: true, profile: true, file: "20-profile-card" },
            { page: "dms", zone: "messages", desktop: true, shortcuts: true, file: "21-shortcuts" },
            { page: "settings", section: "Appearance", desktop: true, file: "22-appearance" },
            { page: "g:tour-crew", zone: "messages", desktop: true, theme: "light", density: "compact", voicePanel: true, file: "23-light-compact" },
            { page: "dms", zone: "messages", desktop: true, theme: "black", file: "24-true-black" }
        ]
        interval: 1500
        repeat: true
        onTriggered: {
            if (step > 0) System.grab(app, steps[step - 1].file)
            prompt.visible = false; confirm.visible = false; picker.visible = false
            profileCard.visible = false; shortcutsPanel.visible = false
            if (step === 0) { savedTheme = Settings.theme; savedDensity = Settings.density }
            if (step >= steps.length) { Settings.theme = savedTheme; Settings.density = savedDensity; stop(); System.tourFinished(); return }
            const s = steps[step]
            Settings.theme = s.theme || savedTheme
            Settings.density = s.density || savedDensity
            app.tourVoice = !!s.voicePanel
            const args = { tour: true }
            if (s.section) args.section = s.section
            if (s.zone) args.zone = s.zone
            if (s.groupOptions) args.groupOptions = true
            if (s.voice) args.voice = true
            if (s.folders) args.folders = true
            app.tourDesktop = !!s.desktop
            app.openPage(s.page, args)
            if (s.rail) app.focusRail()
            if (s.picker) picker.openList("Message", [{ header: "", items: [{ name: "Unsend", detail: "Removes it from both sides", url: "unsend" }, { name: "Delete for me", detail: "Only on this computer", url: "delete" }] },
                                                     { header: "Conversation", items: [{ name: "Send a picture", detail: "Location and camera details are removed", url: "picture" }] }], null)
            if (s.prompt) prompt.open("Message everyone nearby", "You're " + Chat.username, "", "Send", null, false, true)
            if (s.profile) Ui.showProfile("j", "Jake", { tour: true, group: "Gaming Crew", admin: true, room: "Lounge", note: "Hosts raid night on Fridays. Bring snacks." })
            if (s.shortcuts) shortcutsPanel.open()
            if (s.hold) confirm.open("Erase everything in Chat?", "Messages, photos, voice, contacts, keys and the lock are deleted for good, and Huginn closes.", "Hold to erase", null, true, true)
            step++
        }
    }
}

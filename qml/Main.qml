import QtQuick
import QtQuick.Window
import Huginn

Window {
    id: app
    width: System.tourDir.length > 0 ? 1920 : 1280
    height: System.tourDir.length > 0 ? 1080 : 800
    visible: true
    visibility: System.windowed ? Window.Windowed : Window.FullScreen
    title: "Huginn"
    color: Theme.ground

    // ------------------------------------------------------------- pages
    property string pageName: "start"
    property var pageArgs: ({})

    // Pages opened from the start screen go back to it; everything else goes back to the browser.
    property string returnTo: ""

    function openPage(name, args) {
        pageArgs = args || {}
        if (name === "browser") { showBrowser(); return }
        if (name === pageName) {
            if (pageLoader.item && pageLoader.item.setArgs) pageLoader.item.setArgs(pageArgs)
            return
        }
        returnTo = name === "start" ? "" : pageName === "start" ? "start" : returnTo
        pageName = name
    }
    function restoreFocus() { keys.forceActiveFocus() }
    function showBrowser() {
        returnTo = ""
        pageName = ""
        keys.forceActiveFocus()
    }
    function openInBrowser(url) {
        showBrowser()
        browser.activeModel.setUrl(url)
    }
    function closePage() {
        pageName = returnTo
        returnTo = ""
        keys.forceActiveFocus()
    }

    BrowserPage {
        id: browser
        anchors.fill: parent
        visible: app.pageName === ""
    }

    Loader {
        id: pageLoader
        anchors.fill: parent
        active: app.pageName !== ""
        source: {
            switch (app.pageName) {
            case "start": return "HomePage.qml"
            case "games": return "GamesPage.qml"
            case "storage": return "StoragePage.qml"
            case "usb": return "UsbPage.qml"
            case "transfer": return "TransferPage.qml"
            case "network": return "NetworkPage.qml"
            case "settings": return "SettingsPage.qml"
            case "preview": return "PreviewPage.qml"
            case "archive": return "ArchivePage.qml"
            }
            return ""
        }
        onLoaded: if (item.setArgs) item.setArgs(app.pageArgs)
    }

    // ------------------------------------------------------------- overlays
    ActionWheel { id: wheel; z: 20 }
    DestinationPicker { id: picker; z: 25 }
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
                font.pixelSize: Theme.px(24)
                font.weight: Font.Bold
                font.letterSpacing: Theme.px(3)
            }
            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                text: Updater.status
                color: Theme.textMuted
                font.family: Theme.body
                font.pixelSize: Theme.px(18)
            }
        }
    }

    Component.onCompleted: {
        Ui.main = app
        Ui.wheel = wheel
        Ui.prompt = prompt
        Ui.confirm = confirm
        Ui.toast = toast
        Ui.picker = picker
        if (System.tourDir.length > 0) tour.start()
    }

    // ------------------------------------------------------------- input
    function topHandler() {
        if (updating.visible) return null
        if (confirm.visible) return confirm
        if (prompt.visible) return prompt
        if (picker.visible) return picker
        if (wheel.visible) return wheel
        if (pageLoader.item && pageLoader.status === Loader.Ready) return pageLoader.item
        return browser
    }
    function press(b) {
        const h = topHandler()
        if (h && h.handleButton) h.handleButton(b)
    }
    function release(b) {
        const h = topHandler()
        if (h && h.handleRelease) h.handleRelease(b)
    }

    Connections {
        target: Gamepad
        function onPressed(button) { app.press(button) }
        function onReleased(button) { app.release(button) }
    }

    // Keyboard stands in for the controller in Desktop Mode.
    Item {
        id: keys
        anchors.fill: parent
        focus: true
        Keys.onPressed: event => {
            if (event.isAutoRepeat && (event.key === Qt.Key_Return || event.key === Qt.Key_Space)) return
            const map = {}
            map[Qt.Key_Up] = "up"; map[Qt.Key_Down] = "down"; map[Qt.Key_Left] = "left"; map[Qt.Key_Right] = "right"
            map[Qt.Key_Return] = "a"; map[Qt.Key_Enter] = "a"; map[Qt.Key_Escape] = "b"; map[Qt.Key_Backspace] = "b"
            map[Qt.Key_Space] = "x"; map[Qt.Key_Menu] = "y"; map[Qt.Key_F10] = "y"; map[Qt.Key_Tab] = "rb"
            map[Qt.Key_Backtab] = "lb"; map[Qt.Key_PageUp] = "lt"; map[Qt.Key_PageDown] = "rt"; map[Qt.Key_F1] = "start"
            const b = map[event.key]
            if (b) { app.press(b); event.accepted = true }
        }
        Keys.onReleased: event => {
            if (event.isAutoRepeat) return
            if (event.key === Qt.Key_Return || event.key === Qt.Key_Enter) app.release("a")
            if (event.key === Qt.Key_Menu || event.key === Qt.Key_F10) app.release("y")
        }
    }

    // ------------------------------------------------------------- screenshot tour (CI)
    // `huginn --tour DIR` opens every screen, saves a picture of each and quits. CI fails the
    // build on any QML warning, so a broken screen never reaches a release.
    Timer {
        id: tour
        property int step: 0
        readonly property var steps: [
            { page: "start", file: "00-start" },
            { page: "", file: "01-browser" },
            { page: "", wheel: true, file: "02-action-wheel" },
            { page: "", prompt: true, file: "03-rename" },
            { page: "games", file: "04-games" },
            { page: "usb", file: "05-usb" },
            { page: "transfer", file: "06-transfer" },
            { page: "network", file: "07-network" },
            { page: "settings", file: "08-settings" },
            { page: "preview", file: "09-preview" },
            { page: "archive", file: "10-archive" },
            { page: "", hold: true, file: "11-confirm" },
            { page: "storage", file: "12-storage" },
            { page: "", picker: true, file: "13-move-to" },
            { page: "preview", text: true, file: "14-text" },
            { page: "settings", section: 1, file: "15-settings-browser" }
        ]
        interval: 1500
        repeat: true
        onTriggered: {
            if (step > 0) System.grab(app, steps[step - 1].file)
            wheel.visible = false; prompt.visible = false; confirm.visible = false; picker.visible = false
            if (step >= steps.length) { stop(); System.tourFinished(); return }
            const s = steps[step]
            const args = { tour: true }
            if (s.text) { args.tour = false; args.url = System.sampleText(); args.folder = "" }
            if (s.section) args.section = s.section
            if (s.page === "") app.showBrowser(); else app.openPage(s.page, args)
            if (s.picker) picker.open("Move 2 items to…", [{ name: "Other pane", detail: "Home / Downloads", url: "file:///tmp" }], null)
            if (s.wheel) { browser.openWheel(false); wheel.current = 1 }
            if (s.prompt) prompt.open("Rename folder", "Home", "screenshots", "Rename", null, false)
            if (s.hold) confirm.open("Erase this drive?", "Everything on the 64 GB USB drive will be replaced by Ventoy.", "Erase and install", null, true, true)
            step++
        }
    }
}

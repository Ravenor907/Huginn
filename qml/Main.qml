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
    property string pageName: ""
    property var pageArgs: ({})

    function openPage(name, args) {
        pageArgs = args || {}
        if (name === "browser") { closePage(); return }
        pageName = name
    }
    function restoreFocus() { keys.forceActiveFocus() }
    function openInBrowser(url) {
        closePage()
        browser.activeModel.setUrl(url)
    }
    function closePage() {
        pageName = ""
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
            case "games": return "GamesPage.qml"
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
    TextPrompt { id: prompt; z: 30 }
    ConfirmDialog { id: confirm; z: 40 }
    Toast { id: toast }

    Component.onCompleted: {
        Ui.main = app
        Ui.wheel = wheel
        Ui.prompt = prompt
        Ui.confirm = confirm
        Ui.toast = toast
        if (System.tourDir.length > 0) tour.start()
    }

    // ------------------------------------------------------------- input
    function topHandler() {
        if (confirm.visible) return confirm
        if (prompt.visible) return prompt
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
            { page: "", hold: true, file: "11-confirm" }
        ]
        interval: 1500
        repeat: true
        onTriggered: {
            if (step > 0) System.grab(app, steps[step - 1].file)
            wheel.visible = false; prompt.visible = false; confirm.visible = false
            if (step >= steps.length) { stop(); System.tourFinished(); return }
            const s = steps[step]
            if (s.page === "") app.closePage(); else app.openPage(s.page, { tour: true })
            if (s.wheel) browser.openWheel(false)
            if (s.prompt) prompt.open("Rename folder", "Home", "screenshots", "Rename", null, false)
            if (s.hold) confirm.open("Erase this drive?", "Everything on the 64 GB USB drive will be replaced by Ventoy.", "Erase and install", null, true, true)
            step++
        }
    }
}

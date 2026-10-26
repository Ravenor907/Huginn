import QtQuick
import Huginn

// A flat, squared button. kind: "normal", "accent" or "danger". `focused` draws the controller focus.
Rectangle {
    id: btn
    property string text
    property string kind: "normal"
    property bool focused: false
    property bool enabled: true
    property int fontSize: 17
    signal clicked()

    implicitWidth: label.implicitWidth + Theme.px(40)
    implicitHeight: Theme.px(52)
    radius: Theme.radius
    opacity: enabled ? 1 : 0.45
    color: kind === "accent" ? Theme.accentFill : kind === "danger" ? Theme.dangerFill : Theme.raised
    border.width: focused ? Theme.focusWidth : 1
    Accessible.role: Accessible.Button
    Accessible.name: text
    Accessible.onPressAction: clicked()
    border.color: focused ? Theme.text
                : kind === "accent" ? Theme.accent
                : kind === "danger" ? Theme.dangerLine : Theme.lineMuted

    Text {
        id: label
        anchors.centerIn: parent
        text: btn.text
        color: btn.kind === "accent" ? Theme.accent : btn.kind === "danger" ? Theme.danger : Theme.text
        font.family: Theme.body
        font.pixelSize: Theme.fs(btn.fontSize)
        font.weight: Font.DemiBold
    }
    TapHandler { enabled: btn.enabled; onTapped: btn.clicked() }
}

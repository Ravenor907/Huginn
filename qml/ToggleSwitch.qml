import QtQuick
import Huginn

Rectangle {
    id: sw
    property bool checked: false
    signal toggled()

    implicitWidth: Theme.px(60)
    implicitHeight: Theme.px(32)
    radius: height / 2
    color: checked ? Theme.accentFill : Theme.raised
    border.color: checked ? Theme.accent : Theme.lineMuted
    border.width: 1

    Rectangle {
        width: parent.height - Theme.px(8)
        height: width
        radius: width / 2
        y: Theme.px(4)
        x: sw.checked ? parent.width - width - Theme.px(4) : Theme.px(4)
        color: sw.checked ? Theme.accent : Theme.textFaint
        Behavior on x { NumberAnimation { duration: Theme.motion ? 120 : 0 } }
    }
    TapHandler { onTapped: sw.toggled() }
    Accessible.role: Accessible.CheckBox
    Accessible.checked: checked
    Accessible.onToggleAction: toggled()
}

import QtQuick
import Huginn

// A row of exclusive choices. `options` is a list of { value, label }.
Rectangle {
    id: seg
    property var options: []
    property string value
    property real cell: 0      // a fixed width for every choice (a dialog fills its width with them)
    signal picked(string value)

    implicitWidth: row.implicitWidth + 2
    implicitHeight: Theme.px(36)
    radius: Theme.radius
    color: "transparent"
    border.color: Theme.lineStrong
    clip: true

    Row {
        id: row
        anchors.centerIn: parent
        Repeater {
            model: seg.options
            Rectangle {
                readonly property bool on: modelData.value === seg.value
                width: Math.max(txt.implicitWidth + Theme.px(28), seg.cell)
                height: seg.height - 2
                color: on ? Theme.accentFill : "transparent"
                Text {
                    id: txt
                    anchors.centerIn: parent
                    text: modelData.label
                    color: parent.on ? Theme.accent : Theme.textSoft
                    font.family: Theme.body
                    font.pixelSize: Theme.fs(14)
                    font.weight: Font.DemiBold
                }
                TapHandler { onTapped: seg.picked(modelData.value) }
            }
        }
    }

    function step(dir) {
        let i = options.findIndex(o => o.value === value)
        i = Math.max(0, Math.min(options.length - 1, i + dir))
        picked(options[i].value)
    }
}

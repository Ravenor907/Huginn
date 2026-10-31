import QtQuick
import Huginn

// The Brushed metal theme's fine streaks, over a rail, a list or a header. Nothing in other themes.
Image {
    anchors.fill: parent
    visible: Theme.brushed !== ""
    source: Theme.brushed
    fillMode: Image.Tile
    smooth: false
    cache: true
}

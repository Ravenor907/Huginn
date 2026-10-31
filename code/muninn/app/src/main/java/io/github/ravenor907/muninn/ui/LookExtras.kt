package io.github.ravenor907.muninn.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Settings › Look, under the Autumn look: how big the leaves are and how many fall. Both 25% to 200%; 100% is the usual. */
@Composable
fun AutumnControls() {
    val context = LocalContext.current
    LeafPrefs.load(context)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        LeafSlider("Leaf size", LeafPrefs.size) { LeafPrefs.setSize(context, it) }
        LeafSlider("How many", LeafPrefs.count) { LeafPrefs.setCount(context, it) }
        Text("Fewer, smaller leaves use a little less battery.", style = Type.small, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun LeafSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f))
        Text("$value%", style = Type.small.copy(color = Palette.textSoft), maxLines = 1, modifier = Modifier.padding(start = 8.dp))
    }
    Slider(
        value = value.toFloat(), onValueChange = { onChange((it / 5f).roundToInt() * 5) }, valueRange = 25f..200f, steps = 34,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$label, $value percent" },
        // The empty part of the track uses the track shade, which stands out from the ground in every look; the 5% tick
        // marks take the track's own colours so the dots don't crowd it.
        colors = SliderDefaults.colors(thumbColor = Palette.accent, activeTrackColor = Palette.accent, inactiveTrackColor = Palette.track,
            activeTickColor = Palette.accent, inactiveTickColor = Palette.track),
    )
}

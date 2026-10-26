package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Type

/** The Calls tab. Calling arrives in a later version; until then it says so plainly. */
@Composable
fun CallsScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(Palette.coolTint), contentAlignment = Alignment.Center) {
            MIcon("call", Palette.accent, 32.dp)
        }
        Text("No calls yet", style = Type.big, modifier = Modifier.padding(top = 16.dp))
        Text("Calling your contacts comes in a later version of Muninn. Calls you make and get will show up here.",
            style = Type.normal.copy(color = Palette.textMuted), textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp).widthIn(max = 360.dp))
    }
}

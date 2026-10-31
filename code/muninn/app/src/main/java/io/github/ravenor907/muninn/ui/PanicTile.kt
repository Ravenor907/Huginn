package io.github.ravenor907.muninn.ui

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.ravenor907.muninn.Muninn

/** Quick settings tile "Lock Muninn": one tap from anywhere locks Muninn at once. */
class PanicTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (Panic.lock(this)) refresh(justLocked = true)
        else refresh()
    }

    private fun refresh(justLocked: Boolean = false) {
        val tile = qsTile ?: return
        val on = Panic.enabled(this) && ((application as? Muninn)?.vault?.hasPassword ?: true)
        tile.label = "Lock Muninn"
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_UNAVAILABLE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = when {
            !on -> "Off in Settings"
            justLocked || Panic.locked.value -> "Locked"
            else -> null
        }
        tile.updateTile()
    }
}

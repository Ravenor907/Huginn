package io.github.ravenor907.muninn.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/** The "Lock now" launcher shortcut: locks Muninn and closes without showing anything. */
class PanicActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ok = Panic.lock(this)
        Toast.makeText(applicationContext, if (ok) "Muninn locked." else "Panic lock is off. Turn it on in Muninn's Settings.", Toast.LENGTH_SHORT).show()
        finish()
    }
}

package io.github.ravenor907.muninn.forge

import android.os.SystemClock
import io.github.ravenor907.muninn.forge.core.Progress
import io.github.ravenor907.muninn.forge.core.Stage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Outcome { OK, CANCELLED, ERROR }

sealed interface JobState {
    data object Idle : JobState

    data class Running(
        val distro: String,
        val drive: String,
        val stage: Stage = Stage.LOOKUP,
        val done: Long = 0,
        val total: Long = 0,
        val stopping: Boolean = false,
        /** Of the whole job, 0..1; only ever grows. */
        val fraction: Float = 0f,
        val timeLeft: String = "",
        /** SystemClock.elapsedRealtime() when the current stage began, for the running timer. */
        val stageStartMs: Long = SystemClock.elapsedRealtime(),
        /** Writing a file from the phone: no download or verify steps. */
        val local: Boolean = false,
    ) : JobState

    data class Finished(
        val outcome: Outcome,
        val message: String,
        val distro: String,
        val drive: String,
        /** Facts about what was made, for the result screen (e.g. "secureboot-off"). */
        val flags: Set<String> = emptySet(),
    ) : JobState
}

/** Process-wide job state shared by the service (writer) and the activity (reader). */
object Jobs {
    private val _state = MutableStateFlow<JobState>(JobState.Idle)
    val state: StateFlow<JobState> = _state

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    @Volatile
    var cancelRequested = false
        private set

    /** Why the job was stopped when it wasn't the Stop button (Android's time limit). */
    @Volatile
    var cancelReason: String? = null
        private set

    /** Whether the app's screen is showing; the activity keeps this current. */
    @Volatile
    var appVisible = true
        private set

    /** The app went to the background at some point during the current job. */
    @Volatile
    var leftDuringJob = false
        private set

    fun setVisible(visible: Boolean) {
        appVisible = visible
        if (!visible && _state.value is JobState.Running) leftDuringJob = true
    }

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var tracker = Progress { SystemClock.elapsedRealtime() }

    fun begin(distro: String, drive: String, local: Boolean = false) {
        cancelRequested = false
        cancelReason = null
        leftDuringJob = !appVisible
        _log.value = emptyList()
        tracker = Progress(if (local) Progress.LOCAL_WEIGHTS else Progress.WEIGHTS) { SystemClock.elapsedRealtime() }
        _state.value = JobState.Running(distro, drive, stage = if (local) Stage.WRITE else Stage.LOOKUP, local = local)
    }

    fun progress(stage: Stage, done: Long, total: Long) = _state.update {
        if (it !is JobState.Running) return@update it
        tracker.update(stage, done, total)
        it.copy(
            stage = stage, done = done, total = total, fraction = tracker.fraction.toFloat(),
            timeLeft = tracker.timeLeft(done, total), stageStartMs = tracker.stageStartMs,
        )
    }

    fun log(msg: String) = _log.update { (it + "${clock.format(Date())}  $msg").takeLast(300) }

    fun finish(outcome: Outcome, message: String, flags: Set<String> = emptySet()) = _state.update {
        if (it is JobState.Running) JobState.Finished(outcome, message, it.distro, it.drive, flags) else it
    }

    fun cancel(reason: String? = null) {
        if (reason != null) cancelReason = reason
        cancelRequested = true
        _state.update { if (it is JobState.Running) it.copy(stopping = true) else it }
    }

    /** Debug builds only: show a canned state so each screen can be screenshotted. */
    fun demo(state: JobState, lines: List<String>) {
        _state.value = state
        _log.value = lines
    }

    fun reset() {
        if (_state.value !is JobState.Running) _state.value = JobState.Idle
    }
}

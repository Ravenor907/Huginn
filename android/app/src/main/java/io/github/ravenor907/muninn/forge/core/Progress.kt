package io.github.ravenor907.muninn.forge.core

/**
 * Overall progress for a whole job (download, verify, write, check), the same rules as the
 * desktop app's progress module.
 *
 * Stages report (stage, done, total). Some can't measure anything and report 0 of 0, and a few
 * numbers can briefly be odd (a download that restarts). This turns them into one percentage
 * that only moves forward and stays between 0 and 100, and a time estimate from the step's
 * average speed so it settles instead of jumping around.
 */
class Progress(
    /** Per-step weights; a local-file job sets Download and Verify to 0. */
    private val weights: List<Int> = WEIGHTS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    var step = 0
        private set

    /** Of the whole job, 0..1, never decreasing. */
    var fraction = 0.0
        private set

    var stage: Stage? = null
        private set

    /** Clock time (ms) when the current stage began. */
    var stageStartMs = 0L
        private set
    private var stageStartDone = 0L

    val percent: Int get() = (fraction * 100).toInt().coerceIn(0, 100)

    fun update(s: Stage, done: Long, total: Long) {
        // Never go back a step: a late report from an earlier stage changes nothing.
        step = maxOf(step, stepOf(s))
        val start = weights.take(step).sum().toDouble()
        val value = if (total > 0) {
            (start + weights[step] * (done.toDouble() / total).coerceIn(0.0, 1.0)) / weights.sum()
        } else {
            start / weights.sum() // nothing to measure: the step has at least begun
        }
        fraction = minOf(1.0, maxOf(fraction, value))
        if (s != stage || done < stageStartDone) {
            stage = s
            stageStartMs = clock()
            stageStartDone = maxOf(0L, done)
        }
    }

    fun finish() {
        step = weights.size - 1
        fraction = 1.0
    }

    /** "about 4 min left" from the stage's average speed, or "" when there's no fair estimate. */
    fun timeLeft(done: Long, total: Long): String {
        if (stage == null || total <= 0 || done !in 0..total) return ""
        val elapsed = (clock() - stageStartMs) / 1000.0
        if (elapsed < 3 || done <= stageStartDone) return ""
        val seconds = (total - done) / ((done - stageStartDone) / elapsed)
        return if (seconds < 60) "less than a minute left" else "about ${Math.round(seconds / 60)} min left"
    }

    companion object {
        val STEPS = listOf("Download", "Verify", "Write", "Check")
        val WEIGHTS = listOf(45, 8, 37, 10) // roughly how long each step takes
        val LOCAL_WEIGHTS = listOf(0, 0, 80, 20)

        /** Index of the first step a job shows: local files skip Download and Verify. */
        fun firstStep(local: Boolean) = if (local) 2 else 0

        fun stepOf(s: Stage) = when (s) {
            Stage.LOOKUP, Stage.DOWNLOAD -> 0
            Stage.VERIFY -> 1
            Stage.WRITE -> 2
            Stage.CHECK -> 3
        }

        fun clockText(ms: Long): String {
            val s = maxOf(0L, ms / 1000)
            return "%d:%02d".format(s / 60, s % 60)
        }

        /**
         * (headline, detail) for the work screen. The headline says which step this is and what it
         * does. The detail has the amount and time left when the step can measure itself; otherwise
         * it explains what's going on, with a running timer after a few seconds so the screen never
         * looks frozen.
         */
        fun describe(
            stage: Stage, done: Long, total: Long, elapsedMs: Long, name: String, timeLeft: String = "",
            local: Boolean = false,
        ): Pair<String, String> {
            val label = when (stage) {
                Stage.LOOKUP -> "Checking for the latest release"
                Stage.DOWNLOAD -> "Downloading $name"
                Stage.VERIFY -> "Checking that the download is genuine"
                Stage.WRITE -> "Writing to the drive"
                Stage.CHECK -> "Reading the drive back to confirm"
            }
            val first = firstStep(local)
            val headline = "Step ${stepOf(stage) - first + 1} of ${STEPS.size - first}: $label"
            if (total > 0 && stage != Stage.LOOKUP) {
                val detail = "%.1f of %.1f GB".format(minOf(done, total) / 1e9, total / 1e9)
                return headline to (if (timeLeft.isEmpty()) detail else "$detail, $timeLeft")
            }
            var detail = when (stage) {
                Stage.LOOKUP -> "Asking the download server which version is newest."
                Stage.DOWNLOAD -> "Connecting to the download server."
                Stage.VERIFY -> "Comparing it with the publisher's checksum."
                Stage.WRITE -> "Getting the drive ready."
                Stage.CHECK -> "Comparing every byte on the drive with the image."
            }
            if (elapsedMs >= 5000) detail += " (${clockText(elapsedMs)})"
            return headline to detail
        }
    }
}

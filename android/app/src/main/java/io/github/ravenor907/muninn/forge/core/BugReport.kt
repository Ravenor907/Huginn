package io.github.ravenor907.muninn.forge.core

import java.net.URLEncoder

/**
 * A bug report: what the app, phone and drive were, what the job was and how it ended, and the
 * job's whole log up to the moment the report was made. Copied to the clipboard in full; the
 * GitHub issue link carries as much as fits in a URL and says the rest is on the clipboard.
 */
object BugReport {
    const val ISSUES = "https://github.com/Ravenor907/Handheld-Forge/issues/new"
    private const val URL_BUDGET = 7000 // browsers and GitHub cut longer links

    data class Info(
        val appVersion: String,
        val device: String,
        val android: String,
        val labs: Map<String, Boolean>,
        val job: String,
        val drive: String,
        val outcome: String,
        val message: String,
        val flags: Set<String>,
        val log: List<String>,
    )

    fun text(i: Info): String = buildString {
        appendLine("Handheld Forge ${i.appVersion} (Android)")
        appendLine("Phone: ${i.device}, Android ${i.android}")
        if (i.labs.isNotEmpty()) appendLine("Labs: " + i.labs.entries.joinToString { "${it.key} ${if (it.value) "on" else "off"}" })
        appendLine("Job: ${i.job}")
        appendLine("Drive: ${i.drive}")
        appendLine("Result: ${i.outcome}" + if (i.flags.isNotEmpty()) " (${i.flags.sorted().joinToString()})" else "")
        if (i.message.isNotBlank()) appendLine("Message: ${i.message.trim()}")
        appendLine()
        appendLine("Log (${i.log.size} lines):")
        i.log.forEach { appendLine(it) }
    }

    fun title(i: Info): String = "${i.job}: ${i.outcome.lowercase()}" +
        (i.message.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }?.let { " - ${it.take(80)}" } ?: "")

    /** A prefilled new-issue link. The log is trimmed from the start if the link would be too long. */
    fun issueUrl(i: Info): String {
        fun url(body: String) = "$ISSUES?title=${enc(title(i))}&body=${enc(body)}"
        val what = "**What happened:**\n\n\n**What I expected:**\n\n\n"
        val full = what + "```\n" + text(i) + "```\n"
        if (url(full).length <= URL_BUDGET) return url(full)
        var lines = i.log
        while (lines.isNotEmpty()) {
            lines = lines.drop(maxOf(1, lines.size / 8))
            val note = "(The first ${i.log.size - lines.size} log lines didn't fit in the link. The full report is on " +
                "the clipboard: paste it here.)\n\n"
            val body = what + note + "```\n" + text(i.copy(log = lines)) + "```\n"
            if (url(body).length <= URL_BUDGET) return url(body)
        }
        return url(what + "(The report didn't fit in the link. It's on the clipboard: paste it here.)\n")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

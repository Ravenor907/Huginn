package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.net.Contacts
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class NetTest {
    /**
     * The rules for the internet side: one OkHttp setup (Net.kt) that everything builds on, no
     * HttpURLConnection outside the Wi-Fi code (LocalSend, same network), and no WebView anywhere.
     */
    @Test fun oneHttpSetupAndNoWebView() {
        val src = File("src/main/java").takeIf { it.isDirectory } ?: File("app/src/main/java")
        val files = src.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assert(files.isNotEmpty())
        val offenders = files.flatMap { f ->
            val text = f.readText()
            buildList {
                if (f.name != "LocalSend.kt" && (text.contains("openConnection(") || text.contains("HttpURLConnection"))) add("${f.name}: HttpURLConnection")
                if (f.name != "Net.kt" && text.contains("OkHttpClient.Builder(")) add("${f.name}: own OkHttp client")
                if (text.contains("WebView")) add("${f.name}: WebView")
                // 0.6 sent this phone's onion address; nothing may send one any more.
                if (Regex("""put\("o",|put\("onion",""").containsMatchIn(text)) add("${f.name}: onion address")
            }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    /** A contact code from a 0.6 app carries "o" (an onion address): it still reads, the address ignored. */
    @Test fun readsA06CodeWithAnOnion() {
        val key = ChatCrypto.b64(ChatCrypto.newKeyPair().public.encoded)
        val json = JSONObject().put("n", "Raven").put("k", key).put("p", "ab".repeat(32)).put("i", "tok").put("x", 1_800_000_000L)
            .put("o", "a".repeat(56)).toString()
        val code = "MUNINN1:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        val c = Contacts.parse(code)
        assertNotNull(c)
        assertEquals(key, c!!.chatKey)
        assertEquals("ab".repeat(32), c.mailbox)
        assertEquals("tok", c.invite)
    }
}

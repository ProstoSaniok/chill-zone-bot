package core.ui

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.net.HttpURLConnection
import java.net.URL

/** Resolve the pack once at startup, preserving Telegram's sticker order. */
object MainMenuHeader {
    private const val SET_NAME = "ChillZoneEmoji"
    @Volatile private var header: String? = null

    fun load(token: String) {
        val connection = URL("https://api.telegram.org/bot$token/getStickerSet?name=$SET_NAME")
            .openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            check(connection.responseCode == 200)
            header = connection.inputStream.bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }
            println("Main menu: loaded first five custom emoji from $SET_NAME")
        } catch (_: Exception) {
            // Network exception messages may contain the bot token. Never log them.
            header = null
            System.err.println("Main menu: could not load $SET_NAME; using plain text. Restart to retry.")
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(json: String): String {
        val response = Gson().fromJson(json, JsonObject::class.java)
        require(response.get("ok")?.asBoolean == true)
        val set = response.getAsJsonObject("result")
        require(set.get("sticker_type")?.asString == "custom_emoji")
        val stickers = set.getAsJsonArray("stickers")
        require(stickers.size() >= 5)
        return stickers.take(5).joinToString("") { element ->
            val sticker = element.asJsonObject
            val id = sticker.get("custom_emoji_id")?.asString.orEmpty()
            val emoji = sticker.get("emoji")?.asString.orEmpty()
            require(id.matches(Regex("[0-9]+")) && emoji.isNotBlank())
            "<tg-emoji emoji-id=\"$id\">${escape(emoji)}</tg-emoji>"
        }
    }

    internal fun prepend(text: String, isHtml: Boolean): String? =
        header?.let { "$it\n\n${if (isHtml) text else escape(text)}" }

    private fun escape(text: String): String = text.replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

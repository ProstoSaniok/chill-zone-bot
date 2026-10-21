package data.apis

import com.google.gson.JsonElement
import data.models.RecommendationItem
import data.models.RecommendationType
import io.github.cdimascio.dotenv.dotenv
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object GameApi : ExternalApi {
    private val env = dotenv()
    private val token = env["RAWG_API_TOKEN"]
    private val gson = ApiClient.gson
    private val client = ApiClient.client

    private fun logWarn(message: String) = println("WARN: [GameApi] $message")
    private fun logInfo(message: String) = println("INFO: [GameApi] $message")
    private fun logError(message: String, e: Exception? = null) {
        println("ERROR: [GameApi] $message")
        if (e != null) {
            println("ERROR: [GameApi] ${e::class.simpleName}: ${e.message}")
        }
    }

    private fun JsonElement?.stringOrNull(): String? {
        if (this == null || !this.isJsonPrimitive) return null
        return runCatching { this.asString }.getOrNull()
    }

    private fun JsonElement?.intOrNull(): Int? {
        if (this == null || !this.isJsonPrimitive) return null
        return runCatching { this.asInt }.getOrNull()
    }

    private fun JsonElement?.doubleOrNull(): Double? {
        if (this == null || !this.isJsonPrimitive) return null
        return runCatching { this.asDouble }.getOrNull()
    }

    override fun search(query: String, limit: Int): List<RecommendationItem> {
        if (token.isNullOrBlank()) {
            logWarn("RAWG_API_TOKEN не задан. Внешний поиск игр отключён.")
            return emptyList()
        }

        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        val url = "https://api.rawg.io/api/games?search=$encodedQuery&page_size=$limit&key=$token"

        return try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "ChillZoneBot/1.0 (+https://example.local)")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logWarn("HTTP ${response.code} от RAWG API для запроса '$query'")
                    return emptyList()
                }

                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    logWarn("Пустой ответ от RAWG API для запроса '$query'")
                    return emptyList()
                }

                val json = gson.fromJson(body, com.google.gson.JsonObject::class.java)
                val results = json.getAsJsonArray("results") ?: run {
                    logInfo("RAWG API: 0 результатов для '$query'")
                    return emptyList()
                }

                val items = results.mapNotNull { item ->
                    val itemObj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    val genres = itemObj.get("genres")
                        ?.takeIf { it.isJsonArray }
                        ?.asJsonArray
                        ?.mapNotNull { genre ->
                            genre.takeIf { it.isJsonObject }?.asJsonObject?.get("name")?.stringOrNull()
                        }
                        ?: emptyList()

                    RecommendationItem(
                        id = itemObj.get("id")?.intOrNull() ?: itemObj.hashCode(),
                        type = RecommendationType.GAME,
                        title = itemObj.get("name")?.stringOrNull() ?: "Unknown",
                        genres = genres,
                        moods = emptyList(),
                        year = itemObj.get("released")?.stringOrNull()?.take(4)?.toIntOrNull() ?: 0,
                        description = itemObj.get("description")?.stringOrNull().orEmpty(),
                        rating = itemObj.get("rating")?.doubleOrNull() ?: 0.0,
                        posterUrl = itemObj.get("background_image")?.stringOrNull(),
                        url = itemObj.get("website")?.stringOrNull()
                            ?: itemObj.get("background_image")?.stringOrNull().orEmpty(),
                        source = "RAWG",
                        metadata = mapOf(
                            "url" to (
                                itemObj.get("website")?.stringOrNull()
                                    ?: itemObj.get("background_image")?.stringOrNull().orEmpty()
                            )
                        )
                    )
                }

                logInfo("Найдено ${items.size} игр для '$query'")
                items
            }
        } catch (e: java.net.SocketTimeoutException) {
            logError("Таймаут при обращении к RAWG API для '$query'", e)
            emptyList()
        } catch (e: Exception) {
            logError("Ошибка при поиске игр для '$query'", e)
            emptyList()
        }
    }
}
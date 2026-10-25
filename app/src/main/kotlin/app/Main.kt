package app

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.dispatcher.telegramError
import com.github.kotlintelegrambot.entities.ChatId
import core.Entry
import core.ui.MainMenuHeader
import data.SeedData
import data.schemas.Schema
import data.services.AdminService
import io.github.cdimascio.dotenv.dotenv
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

fun main() {
    fun log(level: String, component: String, msg: String, t: Throwable? = null) {
        val ts = OffsetDateTime.now(ZoneOffset.UTC).toString()
        val base = "$ts [$level] [$component] $msg"
        if (t == null) {
            println(base)
        } else {
            println("$base | ${t::class.simpleName}: ${t.message}")
        }
    }

    fun info(component: String, msg: String) = log("INFO", component, msg)
    fun warn(component: String, msg: String) = log("WARN", component, msg)
    fun error(component: String, msg: String, t: Throwable? = null) =
            log("ERROR", component, msg, t)

    info("boot", "initializing database schema")
    Schema.ensure()
    info("boot", "seeding default data")
    SeedData.ensure()
    info("boot", "database is ready")

    val env = dotenv()
    val token = env["BOT_TOKEN"] ?: env["TELEGRAM_TOKEN"] ?: error("BOT_TOKEN is not set")
    MainMenuHeader.load(token)
    val router = Entry.buildRouter()
    var lastPollingErrorKey = ""
    var lastPollingErrorAt = 0L
    var suppressedPollingErrors = 0

    lateinit var telegramBot: com.github.kotlintelegrambot.Bot

    telegramBot = bot {
        this.token = token

        dispatch {
            message {
                val preview =
                        when {
                            !message.text.isNullOrBlank() ->
                                    message.text!!.trim().replace(Regex("\\s+"), " ").take(80)
                            message.photo != null -> "<photo>"
                            else -> "<non-text>"
                        }

                info(
                        "update",
                        "incoming message chat=${message.chat.id} user=${message.from?.id} payload=$preview"
                )

                try {
                    router.handle(telegramBot, message)
                } catch (e: java.lang.Exception) {
                    error("update", "message handling error", e)
                    telegramBot.sendMessage(
                            chatId = ChatId.fromId(message.chat.id),
                            text = "Внутренняя ошибка при обработке сообщения."
                    )
                }
            }

            callbackQuery {
                val callbackQuery = this.callbackQuery
                info(
                        "update",
                        "incoming callback chat=${callbackQuery.message?.chat?.id} user=${callbackQuery.from.id} data=${callbackQuery.data}"
                )

                try {
                    router.handleCallback(telegramBot, callbackQuery)
                } catch (e: java.lang.Exception) {
                    error("update", "callback handling error", e)
                    telegramBot.answerCallbackQuery(
                            callbackQuery.id,
                            "Ошибка при обработке запроса."
                    )
                }
            }

            telegramError {
                val type = this.error.getType().name
                val message = this.error.getErrorMessage()
                val key = "$type|$message"
                val now = System.currentTimeMillis()
                val sameAsLast = key == lastPollingErrorKey
                val tooFrequent = now - lastPollingErrorAt < 15000
                if (sameAsLast && tooFrequent) {
                    suppressedPollingErrors += 1
                    return@telegramError
                }
                if (suppressedPollingErrors > 0) {
                    warn("polling", "suppressed polling errors x$suppressedPollingErrors")
                    suppressedPollingErrors = 0
                }
                lastPollingErrorKey = key
                lastPollingErrorAt = now
                warn("polling", "Telegram polling error [$type] $message")
                if (type == "RETRIEVE_UPDATES") {
                    warn(
                            "polling",
                            "check api.telegram.org access, BOT_TOKEN correctness, and networking/VPN/DNS blocks"
                    )
                }
            }
        }
    }

    info("boot", "polling started")
    telegramBot.startPolling()

    runBlocking {
        launch {
            while (true) {
                delay(60000)
                try {
                    val expiredBans = AdminService.getExpiredBans()
                    for (ban in expiredBans) {
                        if (AdminService.unbanUser(ban.userId)) {
                            val displayName = ban.displayName.ifBlank { ban.username }
                            telegramBot.sendMessage(
                                    chatId = ChatId.fromId(ban.userId),
                                    text =
                                            "✅ <b>Ваш временный бан истёк!</b>\n\nВы снова можете использовать бота.\n\nС уважением, команда Chill Zone Bot.",
                                    parseMode = com.github.kotlintelegrambot.entities.ParseMode.HTML
                            )
                            info(
                                    "ban",
                                    "auto-unbanned user ${ban.userId} ($displayName), ban expired"
                            )
                        }
                    }
                } catch (e: Exception) {
                    error("ban", "error checking expired bans", e)
                }
            }
        }
    }
}

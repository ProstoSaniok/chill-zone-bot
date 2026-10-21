package core.routers

import core.ui.*
import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import core.ImageManager
import core.PendingAction
import core.Session
import core.SessionStore
import core.keyboards.KeyboardFactory
import data.models.*
import data.repositories.EventRepository
import data.repositories.UserRepository
import data.services.ModerationService

object RouterEvents {
    fun showEvents(bot: Bot, chat: ChatId, events: EventRepository) {
        val list = events.upcoming()
        if (list.isEmpty()) {
            bot.screenText(chat, "Пока событий нет.", replyMarkup = KeyboardFactory.eventsMenu())
            return
        }
        bot.screenText(
                chat,
                list.joinToString("\n\n") {
                    "• #${it.id} ${it.title}\n${it.place}\n${it.startsAt}\n${it.kind}"
                },
                parseMode = ParseMode.HTML,
                replyMarkup = KeyboardFactory.eventsMenu()
        )
    }

    fun showMineEvents(bot: Bot, chat: ChatId, uid: Long, events: EventRepository) {
        val list = events.mine(uid)
        if (list.isEmpty()) {
            bot.screenText(
                    chat,
                    "У тебя пока нет своих событий.",
                    replyMarkup = KeyboardFactory.eventsMenu()
            )
            return
        }
        bot.screenText(
                chat,
                list.joinToString("\n\n") {
                    "• #${it.id} ${it.title}\n${it.place}\n${it.startsAt}"
                },
                parseMode = ParseMode.HTML,
                replyMarkup = KeyboardFactory.eventsMenu()
        )
    }

    fun handleEventAction(
            bot: Bot,
            chat: ChatId,
            uid: Long,
            text: String,
            events: EventRepository,
            users: UserRepository,
            session: Session
    ): Boolean {
        when (text) {
            "📅 События" -> {
                showEvents(bot, chat, events)
                return true
            }
            "Ближайшие события" -> {
                showEvents(bot, chat, events)
                return true
            }
            "Мои события" -> {
                showMineEvents(bot, chat, uid, events)
                return true
            }
            "Создать событие" -> {
                session.action = PendingAction.CREATE_EVENT_TITLE
                session.clearDraft()
                val profile = users.profile(uid)
                ImageManager.sendMessageWithImage(bot, chat, "Напиши название события.", profile, replyMarkup = KeyboardFactory.eventsMenu())
                return true
            }
            "⬅️ Обратно" -> {
                // Возврат в главное меню или в меню событий
                if (session.action in
                                listOf(
                                        PendingAction.CREATE_EVENT_TITLE,
                                        PendingAction.CREATE_EVENT_DESC,
                                        PendingAction.CREATE_EVENT_PLACE,
                                        PendingAction.CREATE_EVENT_TIME,
                                        PendingAction.CREATE_EVENT_MAX,
                                        PendingAction.CREATE_EVENT_KIND
                                )
                ) {
                    session.action = PendingAction.NONE
                    session.clearDraft()
                    showEvents(bot, chat, events)
                } else {
                    // Возврат в главное меню
                    SessionStore.clear(uid)
                    bot.screenText(chat, "Главное меню.", replyMarkup = KeyboardFactory.mainMenu())
                }
                return true
            }
            else -> {
                when (session.action) {
                    PendingAction.CREATE_EVENT_TITLE,
                    PendingAction.CREATE_EVENT_DESC,
                    PendingAction.CREATE_EVENT_PLACE,
                    PendingAction.CREATE_EVENT_TIME,
                    PendingAction.CREATE_EVENT_MAX,
                    PendingAction.CREATE_EVENT_KIND -> {
                        val moderationResult = ModerationService.checkText(uid, text)
                        if (!moderationResult.isAllowed) {
                            if (moderationResult.shouldBan) {
                                ModerationService.banForViolation(
                                        uid,
                                        "Использование ненормативной лексики"
                                )
                                ModerationService.clearWarnings(uid)
                                bot.screenText(
                                        chat,
                                        "🚫 Вы были заблокированы за использование ненормативной лексики."
                                )
                                session.action = PendingAction.NONE
                                session.clearDraft()
                            } else {
                                ModerationService.addWarning(uid)
                                bot.screenText(
                                        chat,
                                        "⚠️ <b>Предупреждение!</b>\n\nВаше сообщение содержит недопустимый контент.\n\nПовторное нарушение приведёт к автоматическому бану на 1 сутки.",
                                        parseMode = ParseMode.HTML
                                )
                            }
                            return true
                        }
                    }
                    else -> {}
                }

                when (session.action) {
                    PendingAction.CREATE_EVENT_TITLE -> {
                        session.data["event_title"] = text
                        session.action = PendingAction.CREATE_EVENT_DESC
                        bot.screenText(chat, "Теперь напиши описание:")
                        return true
                    }
                    PendingAction.CREATE_EVENT_DESC -> {
                        session.data["event_desc"] = text
                        session.action = PendingAction.CREATE_EVENT_PLACE
                        bot.screenText(chat, "Где будет событие?")
                        return true
                    }
                    PendingAction.CREATE_EVENT_PLACE -> {
                        session.data["event_place"] = text
                        session.action = PendingAction.CREATE_EVENT_TIME
                        bot.screenText(chat, "Когда начнётся? (YYYY-MM-DD HH:MM)")
                        return true
                    }
                    PendingAction.CREATE_EVENT_TIME -> {
                        session.data["event_time"] = text
                        session.action = PendingAction.CREATE_EVENT_MAX
                        bot.screenText(chat, "Сколько максимум участников? (0 = без лимита)")
                        return true
                    }
                    PendingAction.CREATE_EVENT_MAX -> {
                        val max = text.toIntOrNull() ?: 0
                        session.data["event_max"] = max.toString()
                        session.action = PendingAction.CREATE_EVENT_KIND
                        bot.screenText(
                                chat,
                                "Выбери тип события:", replyMarkup = UiKeyboard.rows(listOf(listOf("Концерт", "Митап"), listOf("Вечеринка", "Другое"))))
                        return true
                    }
                    PendingAction.CREATE_EVENT_KIND -> {
                        val title = session.data["event_title"] ?: ""
                        val desc = session.data["event_desc"] ?: ""
                        val place = session.data["event_place"] ?: ""
                        val time = session.data["event_time"] ?: ""
                        val max = session.data["event_max"]?.toIntOrNull() ?: 0
                        val kind = text

                        ScreenRenderer.persistent()
                        events.add(uid, title, desc, place, time, max, kind)
                        session.action = PendingAction.NONE
                        session.clearDraft()
                        bot.screenText(
                                chat,
                                "✅ Событие создано!\n\n📌 $title\n📍 $place\n🕒 $time\n👥 до $max участников\n🏷️ $kind",
                                replyMarkup = KeyboardFactory.eventsMenu()
                        )
                        return true
                    }
                    else -> return false
                }
            }
        }
    }
}

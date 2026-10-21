package core.routers.routerAdmin

import data.services.AdminService
import core.ui.*
import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode

object RouterAdminStatistics {
    fun showAdminStats(bot: Bot, chat: ChatId) {
        val totalUsers = AdminService.getUsersCount()
        val bannedUsers = AdminService.getBannedUsersCount()
        val activeUsers = totalUsers - bannedUsers
        val activeRightNow = AdminService.getActiveUsersCountNow()

        val activePercentageNow = if (totalUsers > 0) String.format("%.1f", (activeRightNow.toDouble() / totalUsers * 100)) else "0"
        val bannedPercentage = if (totalUsers > 0) String.format("%.1f", (bannedUsers.toDouble() / totalUsers * 100)) else "0"

        val message = """📊 <b>Админская статистика</b>

👥 <b>Пользователи:</b>
• Всего пользователей: $totalUsers
• Забаненных пользователей: $bannedUsers ($bannedPercentage%)
• Активных пользователей: $activeUsers

🟢 <b>Активность прямо сейчас:</b>
• Активны в последние 3 минуты: <b>$activeRightNow пользователей</b> ($activePercentageNow%)"""

        bot.screenText(chat, message, parseMode = ParseMode.HTML)
    }
}

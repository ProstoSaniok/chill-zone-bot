package core.handlers

import core.minigames.tictactoe.TicTacToeBot
import core.minigames.tictactoe.TicTacToeGame
import core.minigames.tictactoe.TicTacToeStore

fun handlerTicTacToe(userId: Long, text: String): String {

    if (text == "👥 2 игрока") {
        val g = TicTacToeStore.setMode(userId, 2)
        return buildMessage(g, "Режим: 2 игрока")
    }

    if (text == "🤖 против бота") {
        val g = TicTacToeStore.setMode(userId, 1)
        return buildMessage(g, "Режим: против бота (ты ❌)")
    }

    if (text == "/tictactoe" || text == "❌⭕️ Крестики-нолики" || text == "🔁 Новая игра") {
        val g = TicTacToeStore.reset(userId)
        return buildMessage(g, "Новая игра!")
    }

    val g = TicTacToeStore.get(userId)

    if (g.finished) {
        return buildMessage(g, "Игра закончилась. Нажми «🔁 Новая игра».")
    }

    val move = text.toIntOrNull()
    if (move == null || move !in 1..9) {
        return buildMessage(g, if (g.mode == 1) "Нажми 1–9. Ты играешь за ❌." else "Нажми 1–9.")
    }

    if (g.mode == 1) {
        g.turn = 'X'
        if (!applyMove(g, move - 1)) {
            return buildMessage(g, "Там занято. Выбери другую клетку.")
        }

        val afterHuman = finishIfNeeded(g)
        if (afterHuman != null) return afterHuman

        val botMove = TicTacToeBot.chooseMove(g.board, bot = 'O', human = 'X')
        if (botMove != -1) {
            g.turn = 'O'
            applyMove(g, botMove)
            val afterBot = finishIfNeeded(g)
            if (afterBot != null) return afterBot
        }

        g.turn = 'X'
        return buildMessage(g, "Твой ход.")
    } else {
        if (!applyMove(g, move - 1)) {
            return buildMessage(g, "Там занято. Выбери другую клетку.")
        }

        val end = finishIfNeeded(g)
        if (end != null) return end

        g.turn = if (g.turn == 'X') 'O' else 'X'
        return buildMessage(g, "Ход сделан.")
    }
}

private fun applyMove(g: TicTacToeGame, i: Int): Boolean {
    if (i !in 0..8) return false
    if (g.board[i] != ' ') return false
    g.board[i] = g.turn
    return true
}

private fun finishIfNeeded(g: TicTacToeGame): String? {
    val w = winner(g.board)
    if (w != null) {
        g.finished = true
        g.winner = w
        return buildMessage(g, "Победили: ${if (w == 'X') "❌" else "⭕️"}")
    }
    if (isDraw(g.board)) {
        g.finished = true
        g.winner = null
        return buildMessage(g, "Ничья.")
    }
    return null
}

private fun buildMessage(g: TicTacToeGame, title: String): String {
    fun cell(i: Int): String {
        return when (g.board[i]) {
            'X' -> "❌"
            'O' -> "⭕️"
            else -> "⬜️"
        }
    }

    val boardText =
        "${cell(0)}${cell(1)}${cell(2)}\n" +
                "${cell(3)}${cell(4)}${cell(5)}\n" +
                "${cell(6)}${cell(7)}${cell(8)}"

    val modeText = if (g.mode == 1) "Режим: 🤖 против бота (ты ❌)" else "Режим: 👥 2 игрока"
    val turnText = if (!g.finished) {
        if (g.mode == 1) "Твой ход: ❌" else "Ход: ${if (g.turn == 'X') "❌" else "⭕️"}"
    } else {
        "Конец."
    }

    return "Крестики-нолики\n$modeText\n\n$title\n\n$boardText\n\n$turnText"
}

private fun winner(b: CharArray): Char? {
    val lines = arrayOf(
        intArrayOf(0, 1, 2),
        intArrayOf(3, 4, 5),
        intArrayOf(6, 7, 8),
        intArrayOf(0, 3, 6),
        intArrayOf(1, 4, 7),
        intArrayOf(2, 5, 8),
        intArrayOf(0, 4, 8),
        intArrayOf(2, 4, 6)
    )
    for (ln in lines) {
        val a = b[ln[0]]
        if (a != ' ' && a == b[ln[1]] && a == b[ln[2]]) return a
    }
    return null
}

private fun isDraw(b: CharArray): Boolean {
    for (c in b) if (c == ' ') return false
    return true
}

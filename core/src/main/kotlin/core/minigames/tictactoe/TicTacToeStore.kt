package core.minigames.tictactoe

import java.util.concurrent.ConcurrentHashMap

data class TicTacToeGame(
    val board: CharArray = CharArray(9) { ' ' },
    var turn: Char = 'X',
    var finished: Boolean = false,
    var winner: Char? = null,
    var mode: Int = 2
)

object TicTacToeStore {
    private val games = ConcurrentHashMap<Long, TicTacToeGame>()

    fun get(chatId: Long): TicTacToeGame {
        return games.computeIfAbsent(chatId) { TicTacToeGame() }
    }

    fun reset(chatId: Long, mode: Int? = null): TicTacToeGame {
        val g = TicTacToeGame(mode = mode ?: (games[chatId]?.mode ?: 2))
        games[chatId] = g
        return g
    }

    fun setMode(chatId: Long, mode: Int): TicTacToeGame {
        return reset(chatId, mode)
    }
}

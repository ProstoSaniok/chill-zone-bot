package core

import core.handlers.handlerTicTacToe
import core.minigames.tictactoe.TicTacToeStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TicTacToeTest {
    @Test
    fun twoPlayersCanFinishAndRestart() {
        val userId = Long.MAX_VALUE - 1
        handlerTicTacToe(userId, "👥 2 игрока")
        for (move in listOf("1", "4", "2", "5")) handlerTicTacToe(userId, move)
        val result = handlerTicTacToe(userId, "3")

        assertTrue(result.contains("Победили: ❌"))
        assertTrue(TicTacToeStore.get(userId).finished)
        handlerTicTacToe(userId, "🔁 Новая игра")
        assertEquals(9, TicTacToeStore.get(userId).board.count { it == ' ' })
    }

    @Test
    fun botBlocksImmediateWin() {
        val userId = Long.MAX_VALUE - 2
        handlerTicTacToe(userId, "🤖 против бота")
        handlerTicTacToe(userId, "1")
        handlerTicTacToe(userId, "2")

        val game = TicTacToeStore.get(userId)
        assertEquals('O', game.board[2])
        assertEquals('X', game.turn)
    }
}

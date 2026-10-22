package core.minigames.tictactoe

object TicTacToeBot {
    private val lines = arrayOf(
        intArrayOf(0, 1, 2),
        intArrayOf(3, 4, 5),
        intArrayOf(6, 7, 8),
        intArrayOf(0, 3, 6),
        intArrayOf(1, 4, 7),
        intArrayOf(2, 5, 8),
        intArrayOf(0, 4, 8),
        intArrayOf(2, 4, 6)
    )

    fun chooseMove(board: CharArray, bot: Char = 'O', human: Char = 'X'): Int {
        val win = findWinning(board, bot)
        if (win != -1) return win

        val block = findWinning(board, human)
        if (block != -1) return block

        if (board[4] == ' ') return 4

        val corners = intArrayOf(0, 2, 6, 8)
        for (c in corners) if (board[c] == ' ') return c

        for (i in board.indices) if (board[i] == ' ') return i

        return -1
    }

    private fun findWinning(board: CharArray, p: Char): Int {
        for (ln in lines) {
            val a = ln[0]
            val b = ln[1]
            val c = ln[2]
            val ca = board[a]
            val cb = board[b]
            val cc = board[c]

            if (ca == p && cb == p && cc == ' ') return c
            if (ca == p && cc == p && cb == ' ') return b
            if (cb == p && cc == p && ca == ' ') return a
        }
        return -1
    }
}
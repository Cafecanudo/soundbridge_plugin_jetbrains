package com.soundbridge.plugin

class AnsiScreen {

    private val lines = mutableListOf(StringBuilder())
    private var row = 0
    private var col = 0
    private var pending = ""

    fun feed(chunk: String) {
        val text = pending + chunk
        pending = ""
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '') {
                if (i + 1 >= text.length) {
                    pending = text.substring(i)
                    return
                }
                if (text[i + 1] == '[') {
                    var j = i + 2
                    while (j < text.length && (text[j].isDigit() || text[j] == ';' || text[j] == '?')) j++
                    if (j >= text.length) {
                        pending = text.substring(i)
                        return
                    }
                    applyCsi(text[j], text.substring(i + 2, j))
                    i = j + 1
                } else {
                    i += 2
                }
                continue
            }
            when (c) {
                '\n' -> { row++; col = 0; ensureRow() }
                '\r' -> col = 0
                '\b' -> if (col > 0) col--
                '\t' -> { val next = ((col / 8) + 1) * 8; while (col < next) putChar(' ') }
                else -> putChar(c)
            }
            i++
        }
    }

    fun render(): String = lines.joinToString("\n")

    private fun applyCsi(fin: Char, params: String) {
        val nums = params.split(';').mapNotNull { it.toIntOrNull() }
        val n = nums.firstOrNull() ?: 0
        when (fin) {
            'A' -> row = maxOf(0, row - maxOf(1, n))
            'B' -> { row += maxOf(1, n); ensureRow() }
            'E' -> { row += maxOf(1, n); col = 0; ensureRow() }
            'F' -> { row = maxOf(0, row - maxOf(1, n)); col = 0 }
            'C' -> col += maxOf(1, n)
            'D' -> col = maxOf(0, col - maxOf(1, n))
            'G' -> col = maxOf(0, (if (n == 0) 1 else n) - 1)
            'K' -> eraseLine(n)
            'J' -> eraseDisplay(n)
        }
    }

    private fun eraseLine(mode: Int) {
        ensureRow()
        val line = lines[row]
        when (mode) {
            0 -> if (col < line.length) line.setLength(col)
            1 -> { var k = 0; while (k < col && k < line.length) { line[k] = ' '; k++ } }
            2 -> line.setLength(0)
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                ensureRow()
                val line = lines[row]
                if (col < line.length) line.setLength(col)
                while (lines.size > row + 1) lines.removeAt(lines.size - 1)
            }
            2, 3 -> {
                lines.clear()
                lines.add(StringBuilder())
                row = 0
                col = 0
            }
        }
    }

    private fun putChar(c: Char) {
        ensureRow()
        val line = lines[row]
        while (line.length < col) line.append(' ')
        if (col < line.length) line[col] = c else line.append(c)
        col++
    }

    private fun ensureRow() {
        while (lines.size <= row) lines.add(StringBuilder())
    }
}

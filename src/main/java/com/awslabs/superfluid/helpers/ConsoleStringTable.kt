package com.awslabs.superfluid.helpers

import io.vavr.control.Either
import kotlin.math.max

// From https://stackoverflow.com/questions/5551186/java-lib-to-build-and-print-table-on-console
class ConsoleStringTable {
    private data class Index(val row: Int, val column: Int)

    private val contents = mutableMapOf<Index, String>()
    private val columnSizes = mutableMapOf<Int, Int>()

    private var rowCount = 0
    private var columnCount = 0

    fun addString(row: Int, column: Int, content: String) {
        rowCount = max(rowCount, row + 1)
        columnCount = max(columnCount, column + 1)

        val index = Index(row, column)
        contents[index] = content

        setMaxColumnSize(column, content)
    }

    private fun setMaxColumnSize(column: Int, content: String) {
        val size = content.length
        val currentSize = columnSizes[column]
        if (currentSize == null || currentSize < size) {
            columnSizes[column] = size
        }
    }

    private fun getColumnSize(column: Int): Int = columnSizes[column] ?: 0

    private fun getString(row: Int, column: Int): String {
        val index = Index(row, column)
        val string = contents[index]
        return string ?: ""
    }

    fun getTableAsString(padding: Either<Int, String>): String {
        val out = StringBuilder()
        for (row in 0 until rowCount) {
            for (col in 0 until columnCount) {
                val columnSize = getColumnSize(col)
                val content = getString(row, col)
                if (padding.isLeft) {
                    val pad = if (col == columnCount - 1) 0 else padding.left
                    out.append(content.padEnd(columnSize + pad))
                } else {
                    out.append(content.padEnd(columnSize))
                    out.append(" ")
                    out.append(padding.get())
                    out.append(" ")
                }
            }
            out.append(System.lineSeparator())
        }
        return out.toString()
    }

    override fun toString(): String = getTableAsString(Either.left(1))
}

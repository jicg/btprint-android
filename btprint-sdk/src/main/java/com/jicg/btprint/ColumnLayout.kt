package com.jicg.btprint

/**
 * 多列表格排版引擎（printTwo / printThree 的 columnWrapEnabled 模式）
 *
 * 规则（宽度单位 = 字符列宽，ASCII 1 / 其他 2）：
 * - 整行放得下：单行，列间补空格（右列推到行尾，兼容旧版观感）；
 * - 仅一列超宽：该列在预算宽度内折行，其余列留在第一行；
 *   折行宽度 = 该列第一折宽度，后续每折不超过它；右列折行逐行靠右，中列折行居中；
 * - 多列都超宽：均分可用空间，各列在自己的列宽内折行（左列左对齐 / 中列居中 / 右列右对齐），
 *   总行数取各列折数最大值；
 * - 任意位置硬折行（不保护英文单词），列与列之间至少留 [GAP] 个空格。
 */
internal object ColumnLayout {

    /** 列间最小空格列数 */
    const val GAP: Int = 1

    /** 字符列宽：ASCII 宽 1，其余宽 2 */
    fun textWidth(s: String): Int = s.sumOf { if (it.code > 127) 2L else 1L }.toInt()

    /**
     * 按 maxWidth 任意位置硬折行，每折宽度 ≤ maxWidth（单列字符本身超宽时该折允许超出）
     */
    fun wrap(text: String, maxWidth: Int): List<String> {
        if (text.isEmpty()) return listOf("")
        val budget = maxWidth.coerceAtLeast(1)
        val lines = mutableListOf<String>()
        val sb = StringBuilder()
        var width = 0
        for (ch in text) {
            val w = if (ch.code > 127) 2 else 1
            if (width + w > budget && sb.isNotEmpty()) {
                lines.add(sb.toString())
                sb.clear()
                width = 0
            }
            sb.append(ch)
            width += w
        }
        if (sb.isNotEmpty()) lines.add(sb.toString())
        return lines
    }

    /**
     * 两列表格排版，返回渲染后的行列表（不含末尾换行符）
     *
     * "超行"判定：列宽超过均分份额 share = (totalWidth - 间距) / 2 时即为超行。
     * 仅一列超行时该列在预算宽度（= 第一折宽度）内折行、另一列留第一行；
     * 两列都超行时均分空间各自折行。
     */
    fun renderTwo(left: String, right: String, totalWidth: Int): List<String> {
        val g = GAP
        val wl = textWidth(left)
        val wr = textWidth(right)
        if (wl + g + wr <= totalWidth) {
            return listOf(left + spaces(totalWidth - wl - wr) + right)
        }
        val share = ((totalWidth - g) / 2).coerceAtLeast(1)
        val leftBudget = (totalWidth - g - minOf(wr, share)).coerceAtLeast(1)
        val rightBudget = (totalWidth - g - minOf(wl, share)).coerceAtLeast(1)
        val leftTooLong = wl > leftBudget
        val rightTooLong = wr > rightBudget

        if (leftTooLong && !rightTooLong) {
            // 仅左列超行：左列折行，右列留在第一行
            return wrap(left, leftBudget).mapIndexed { i, seg ->
                if (i == 0) seg + spaces(leftBudget - textWidth(seg)) + spaces(g) + right
                else seg
            }
        }
        if (rightTooLong && !leftTooLong) {
            // 仅右列超行：右列折行且逐行靠右，左列留在第一行
            return wrap(right, rightBudget).mapIndexed { i, seg ->
                if (i == 0) left + spaces(g) + spaces(rightBudget - textWidth(seg)) + seg
                else spaces(totalWidth - textWidth(seg)) + seg
            }
        }
        // 两列都超行：均分空间，左列左对齐、右列右对齐各自折行
        val colW = share
        val leftSegs = wrap(left, colW)
        val rightSegs = wrap(right, colW)
        return (0 until maxOf(leftSegs.size, rightSegs.size)).map { k ->
            val l = leftSegs.getOrNull(k)
            val r = rightSegs.getOrNull(k)
            buildString {
                if (l != null) append(l).append(spaces(colW - textWidth(l)))
                else append(spaces(colW))
                append(spaces(g))
                if (r != null) append(spaces(colW - textWidth(r))).append(r)
            }
        }
    }

    /**
     * 三列表格排版，返回渲染后的行列表（不含末尾换行符）
     *
     * "超行"判定同 renderTwo（份额 share = (totalWidth - 2×间距) / 3）。
     * 仅一列超行时该列折行（中列居中、右列靠右），其余列留第一行；
     * 多列都超行时均分三份空间各自折行。
     */
    fun renderThree(left: String, middle: String, right: String, totalWidth: Int): List<String> {
        val g = GAP
        val w1 = textWidth(left)
        val w2 = textWidth(middle)
        val w3 = textWidth(right)
        if (w1 + g + w2 + g + w3 <= totalWidth) {
            return listOf(left + spaces(g) + middle + spaces(g) + right)
        }
        val share = ((totalWidth - 2 * g) / 3).coerceAtLeast(1)
        val budget1 = (totalWidth - 2 * g - minOf(w2, share) - minOf(w3, share)).coerceAtLeast(1)
        val budget2 = (totalWidth - 2 * g - minOf(w1, share) - minOf(w3, share)).coerceAtLeast(1)
        val budget3 = (totalWidth - 2 * g - minOf(w1, share) - minOf(w2, share)).coerceAtLeast(1)
        val over = mutableListOf<Int>()
        if (w1 > budget1) over.add(1)
        if (w2 > budget2) over.add(2)
        if (w3 > budget3) over.add(3)

        if (over.size == 1) {
            when (over[0]) {
                1 -> {
                    // 仅左列超宽：左列折行，中右列留在第一行
                    val colW = budget1.coerceAtLeast(1)
                    return wrap(left, colW).mapIndexed { i, seg ->
                        if (i == 0) seg + spaces(colW - textWidth(seg)) + spaces(g) + middle + spaces(g) + right
                        else seg
                    }
                }
                2 -> {
                    // 仅中列超宽：中列折行并居中，左右列留在第一行
                    val colW = budget2.coerceAtLeast(1)
                    return wrap(middle, colW).mapIndexed { i, seg ->
                        val centered = center(seg, colW)
                        if (i == 0) left + spaces(g) + centered + spaces(g) + right
                        else spaces(w1 + g) + centered
                    }
                }
                else -> {
                    // 仅右列超宽：右列折行并逐行靠右，左中列留在第一行
                    val colW = budget3.coerceAtLeast(1)
                    return wrap(right, colW).mapIndexed { i, seg ->
                        if (i == 0) left + spaces(g) + middle + spaces(g) + spaces(colW - textWidth(seg)) + seg
                        else spaces(totalWidth - textWidth(seg)) + seg
                    }
                }
            }
        }
        // 多列超行：均分空间，左列左对齐 / 中列居中 / 右列右对齐各自折行
        val colW = ((totalWidth - 2 * g) / 3).coerceAtLeast(1)
        val s1 = wrap(left, colW)
        val s2 = wrap(middle, colW)
        val s3 = wrap(right, colW)
        return (0 until maxOf(s1.size, maxOf(s2.size, s3.size))).map { k ->
            val l = s1.getOrNull(k)
            val m = s2.getOrNull(k)
            val r = s3.getOrNull(k)
            buildString {
                if (l != null) append(l).append(spaces(colW - textWidth(l)))
                else append(spaces(colW))
                append(spaces(g))
                if (m != null) append(center(m, colW))
                else append(spaces(colW))
                append(spaces(g))
                if (r != null) append(spaces(colW - textWidth(r))).append(r)
            }.trimEnd()
        }
    }

    /** 在 colW 宽度内居中（左空格 ≤ 右空格差 1） */
    private fun center(s: String, colW: Int): String {
        val pad = (colW - textWidth(s)).coerceAtLeast(0)
        val leftPad = pad / 2
        return spaces(leftPad) + s + spaces(pad - leftPad)
    }

    private fun spaces(n: Int): String = " ".repeat(n.coerceAtLeast(0))
}

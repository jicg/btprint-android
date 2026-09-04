package com.jicg.btprint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多列表格排版引擎（ColumnLayout）规则测试
 */
class ColumnLayoutTest {

    private fun width(s: String) = ColumnLayout.textWidth(s)

    // ============ wrap 基础 ============

    @Test
    fun `wrap 空文本返回单个空段`() {
        assertEquals(listOf(""), ColumnLayout.wrap("", 10))
    }

    @Test
    fun `wrap 按宽度任意位置硬折行且每折不超预算`() {
        val lines = ColumnLayout.wrap("abcdefghij", 4)
        assertEquals(listOf("abcd", "efgh", "ij"), lines)
        lines.forEach { assertTrue(width(it) <= 4) }
    }

    @Test
    fun `wrap 中文按宽2折行`() {
        // 8 个汉字宽 16，预算 6 → 每折最多 3 个汉字
        val lines = ColumnLayout.wrap("一二三四五六七八", 6)
        assertEquals(listOf("一二三", "四五六", "七八"), lines)
    }

    @Test
    fun `wrap 单列字符超预算时不死循环`() {
        val lines = ColumnLayout.wrap("汉", 1)
        assertEquals(listOf("汉"), lines)
    }

    // ============ 两列 ============

    @Test
    fun `两列放得下时单行右列推到行尾`() {
        val lines = ColumnLayout.renderTwo("商品", "10.00", 32)
        assertEquals(1, lines.size)
        assertEquals("商品" + " ".repeat(23) + "10.00", lines[0])
        assertEquals(32, width(lines[0]))
    }

    @Test
    fun `两列仅左列超行时左列折行右列留第一行`() {
        // 行宽 10：share=4，右列 "10.0" 宽 4 未超份额，左列预算 = 10-1-4 = 5
        val lines = ColumnLayout.renderTwo("一二三四五", "10.0", 10)
        assertEquals(3, lines.size) // 左列 5 汉字按预算 5 → 2+2+1 折 3 行
        assertTrue(lines[0].endsWith("10.0"))
        assertEquals(10, width(lines[0]))
        // 后续每折宽度不超过第一折（预算 5）
        lines.drop(1).forEach { assertTrue(width(it) <= 5) }
        // 左列内容完整保留
        assertEquals("一二三四五", lines.joinToString("") { it.trim().removeSuffix("10.0").trim() }.replace(" ", ""))
    }

    @Test
    fun `两列仅右列超行时右列折行且靠右`() {
        // 行宽 10：share=4，左列 "合计" 宽 4 未超份额，右列预算 = 10-1-4 = 5
        val lines = ColumnLayout.renderTwo("合计", "123456789", 10)
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("合计"))
        assertTrue(lines[0].endsWith("12345"))
        // 第二行靠右：右边界对齐行宽
        assertEquals(10, width(lines[1]))
        assertEquals("6789", lines[1].trim())
    }

    @Test
    fun `两列都超行时均分空间左对齐右对齐`() {
        // 行宽 10：两列均超 share=4 的份额 → 各 4 列
        val lines = ColumnLayout.renderTwo("一二三四五六", "abcdefgh", 10)
        assertEquals(3, lines.size) // 左 12 宽→3 折，右 8 宽→2 折
        assertTrue(width(lines[0]) <= 10)
        assertTrue(lines[0].startsWith("一二"))
        assertTrue(lines[0].trimEnd().endsWith("abcd"))
        assertTrue(lines[1].trimEnd().endsWith("efgh"))
        assertEquals("五六", lines[2].trim())
    }

    // ============ 三列 ============

    @Test
    fun `三列放得下时单行`() {
        val lines = ColumnLayout.renderThree("商品", "2", "20.00", 32)
        assertEquals(1, lines.size)
        assertEquals("商品 2 20.00", lines[0].trimEnd())
    }

    @Test
    fun `三列仅中列超行时中列折行居中`() {
        // 行宽 12：share=3，中列预算 = 12-2-2-2 = 6
        val lines = ColumnLayout.renderThree("aa", "一二三四五", "ZZ", 12)
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("aa"))
        assertTrue(lines[0].endsWith("ZZ"))
        // 中列内容完整保留
        val middle = lines.joinToString("") {
            it.trim().removePrefix("aa").removeSuffix("ZZ").trim()
        }
        assertEquals("一二三四五", middle.replace(" ", ""))
    }

    @Test
    fun `三列仅左列超行时左列折行中右列留第一行`() {
        // 行宽 14：share=4，左列预算 = 14-2-1-1 = 10
        val lines = ColumnLayout.renderThree("一二三四五六七八九十十一", "x", "y", 14)
        assertTrue(lines.size >= 2)
        assertTrue(lines[0].endsWith("x y"))
        assertEquals(14, width(lines[0]))
    }

    @Test
    fun `三列仅右列超行时右列折行靠右`() {
        // 行宽 14：share=4，右列预算 = 14-2-2-2 = 8
        val lines = ColumnLayout.renderThree("aa", "bb", "一二三四五六七八九十", 14)
        assertTrue(lines.size >= 2)
        assertTrue(lines[0].startsWith("aa bb"))
        // 后续行靠右
        lines.drop(1).forEach { assertEquals(14, width(it)) }
    }

    @Test
    fun `三列都超行时均分空间并按左中右对齐`() {
        // 行宽 14：share=4，三列均超过份额 → 各 4 列
        val lines = ColumnLayout.renderThree("一二三四五", "abcdefg", "ABCDEFG", 14)
        assertEquals(3, lines.size)
        lines.forEach { assertTrue(width(it) <= 14) }
        assertEquals('一', lines[0][0])
        assertEquals('D', lines[0].trimEnd().last())
    }

    // ============ 回归：单超行分支折行预算按未超行列的自然宽计算，保证每行 ≤ 行宽 ============

    @Test
    fun `两列仅左超行且右列宽超份额时首行不超行宽`() {
        // 旧实现：预算按 minOf(右列宽, share)=9 算，首行 10+1+10=21 > 20 溢出
        val lines = ColumnLayout.renderTwo("一二三四五六七八九", "1234567890", 20)
        assertEquals(listOf("一二三四  1234567890", "五六七八", "九"), lines)
        lines.forEach { assertTrue(width(it) <= 20) }
    }

    @Test
    fun `两列仅右超行且左列宽超份额时首行不超行宽`() {
        val lines = ColumnLayout.renderTwo("1234567890", "一二三四五六七八九", 20)
        assertEquals(3, lines.size)
        assertEquals("1234567890  一二三四", lines[0])
        lines.forEach { assertTrue(width(it) <= 20) }
        // 后续行靠右
        assertEquals(20, width(lines[1]))
    }

    @Test
    fun `三列仅左超行且中列宽超份额时首行不超行宽`() {
        val lines = ColumnLayout.renderThree("一二三四五六七八九", "abcdefgh", "z", 20)
        assertEquals(3, lines.size)
        assertEquals("一二三四  abcdefgh z", lines[0])
        lines.forEach { assertTrue(width(it) <= 20) }
    }

    @Test
    fun `三列仅中超行时首行不超行宽`() {
        val lines = ColumnLayout.renderThree("a", "一二三四五六七八九", "z", 20)
        assertEquals(2, lines.size)
        assertEquals("a 一二三四五六七八 z", lines[0])
        lines.forEach { assertTrue(width(it) <= 20) }
    }

    @Test
    fun `三列仅右超行且中列宽超份额时首行不超行宽`() {
        // 旧实现：colW 按 budget3=11 算，首行 1+1+8+1+(11-8)+8=22 > 20 溢出
        val lines = ColumnLayout.renderThree("1", "abcdefgh", "一二三四五六七八九", 20)
        assertEquals(3, lines.size)
        assertEquals("1 abcdefgh  一二三四", lines[0])
        lines.forEach { assertTrue(width(it) <= 20) }
        // 后续行靠右
        assertEquals(20, width(lines[1]))
    }
}

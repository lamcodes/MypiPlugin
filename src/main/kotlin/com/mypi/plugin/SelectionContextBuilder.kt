package com.mypi.plugin

/**
 * 选区 → pi 终端注入的"定位串"构造器(纯逻辑,零 IntelliJ 依赖)。
 *
 * 产物采用 GitHub 行锚点风格:@<path>#L<行号>。
 *  - path 由调用方(SendSelectionAction)解析为"相对项目根路径",项目外文件降级为裸文件名;此处原样透传。
 *  - 行号:单行 #L15;多行 #L10-20(仅首行带 L)。
 *
 * 只发定位、不发代码内容:pi 在项目目录跑(终端方案),作为 coding agent 能自行读取项目文件,
 * 只发定位可保持 pi 输入行简洁;加 @ 前缀与 #L 行锚,让 pi 更可靠识别为文件引用。
 */
object SelectionContextBuilder {

    /**
     * 产出 pi 注入串:@<path>#L<lineSpec>。
     *
     * @param path      已解析的文件路径(相对项目根,或降级后的裸文件名),原样透传
     * @param startLine 起始行(1-based)
     * @param endLine   结束行(1-based);与 startLine 相同时合并为单行号
     */
    fun build(path: String, startLine: Int, endLine: Int): String {
        val lineSpec = if (startLine == endLine) "$startLine" else "$startLine-$endLine"
        return "@$path#L$lineSpec"
    }
}

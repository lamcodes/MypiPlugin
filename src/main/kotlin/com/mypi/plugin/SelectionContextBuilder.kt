package com.mypi.plugin

/**
 * 选区格式枚举:两种行锚点风格。
 *
 * COLON_LINE:    path:78 (单行) / path:78-85 (多行)
 * GITHUB_ANCHOR: @path#L78 (单行) / @path#L78-85 (多行)
 */
enum class SelectionFormat {
    COLON_LINE,
    GITHUB_ANCHOR
}

/**
 * 选区 → pi 终端注入的"定位串"构造器(纯逻辑,零 IntelliJ 依赖)。
 *
 * 产物根据 [format] 参数采用不同行锚点风格:
 *  - COLON_LINE: `<path>:<行号>`,如 `src/App.kt:10-20`
 *  - GITHUB_ANCHOR: `@<path>#L<行号>`,如 `@src/App.kt#L10-20`
 *  - 行号:单行 `15`;多行 `10-20`(仅首行带 L)
 *
 * path 由调用方(SendSelectionAction)解析为"相对项目根路径",项目外文件降级为裸文件名;此处原样透传。
 *
 * 只发定位、不发代码内容:pi 在项目目录跑(终端方案),作为 coding agent 能自行读取项目文件,
 * 只发定位可保持 pi 输入行简洁;加 @/L 或冒号前缀让 pi 更可靠识别为文件引用。
 */
object SelectionContextBuilder {

    /**
     * 产出 pi 注入串。
     *
     * @param path      已解析的文件路径(相对项目根,或降级后的裸文件名),原样透传
     * @param startLine 起始行(1-based)
     * @param endLine   结束行(1-based);与 startLine 相同时合并为单行号
     * @param format    行锚点格式,默认 [SelectionFormat.COLON_LINE]
     */
    fun build(path: String, startLine: Int, endLine: Int, format: SelectionFormat = SelectionFormat.COLON_LINE): String {
        val lineSpec = if (startLine == endLine) "$startLine" else "$startLine-$endLine"
        return when (format) {
            SelectionFormat.COLON_LINE -> "$path:$lineSpec"
            SelectionFormat.GITHUB_ANCHOR -> "@$path#L$lineSpec"
        }
    }
}

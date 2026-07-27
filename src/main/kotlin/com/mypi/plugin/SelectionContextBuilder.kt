package com.mypi.plugin

/**
 * 选区 → pi 终端注入的"定位串"构造器。
 *
 * 决策更新(哥反馈):只发"文件名:行号"定位,**不发代码内容**。
 * 理由:pi 在项目目录跑(终端方案),作为 coding agent 能自行读取项目文件,
 * 无需插件粘贴代码;且只发定位能保持 pi 输入行简洁,用户补问题再回车更顺手。
 */
object SelectionContextBuilder {

    /**
     * 有选区时的定位串:"文件名:起始行-结束行"(1-based)。
     *
     * @param fileName   当前文件名,如 "UserService.java"
     * @param startLine  选区起始行(1-based)
     * @param endLine    选区结束行(1-based)
     */
    fun build(fileName: String, startLine: Int, endLine: Int): String = "$fileName:$startLine-$endLine"

    /**
     * 无选区时的定位串:"文件名:光标行号"(1-based)。
     */
    fun buildFileLocator(fileName: String, line: Int): String = "$fileName:$line"
}

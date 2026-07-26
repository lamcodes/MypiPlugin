package com.mypi.plugin

/**
 * 选区 → Pi prompt 构造器。
 *
 * 把 IDE 抓取到的"文件名 / 起止行号 / 选中文本"拼成发给 Pi 的 prompt。
 * 取舍(代为决定):GitHub 式定位 + 代码围栏 + 语言推断 + 超长截断 + 不追加默认指令
 * (用户按快捷键时意图不明确,留白让 Pi 自行回应更通用)。
 */
object SelectionContextBuilder {

    /** 单次发送的最大行数,超过则截断,避免撑爆上下文。 */
    private const val MAX_LINES = 200

    /**
     * @param fileName   当前文件名,如 "UserService.java"
     * @param selectedText 用户选中的代码文本(非空)
     * @param startLine  选区起始行(1-based)
     * @param endLine    选区结束行(1-based)
     */
    fun build(fileName: String, selectedText: String, startLine: Int, endLine: Int): String {
        val lang = languageFor(fileName)
        val lines = selectedText.split("\n")
        // 超长截断:只发前 MAX_LINES 行并标注
        val (body, note) = if (lines.size > MAX_LINES) {
            lines.take(MAX_LINES).joinToString("\n") to "（已截断:共 ${lines.size} 行,仅发送前 $MAX_LINES 行）"
        } else {
            selectedText to null
        }
        return buildString {
            // GitHub 式定位,LLM 对这种格式最敏感
            appendLine("用户在 IDE 中选中了以下代码（$fileName:$startLine-$endLine）:")
            appendLine("```$lang")
            append(body)
            if (!body.endsWith("\n")) appendLine()
            appendLine("```")
            if (note != null) append(note)
        }
    }

    /** 从文件后缀推断语言,塞进代码围栏,帮 Pi 识别语法。 */
    private fun languageFor(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "java" -> "java"
            "kt", "kts" -> "kotlin"
            "js", "mjs", "cjs" -> "javascript"
            "ts", "tsx" -> "typescript"
            "jsx" -> "jsx"
            "py" -> "python"
            "go" -> "go"
            "rs" -> "rust"
            "cs" -> "csharp"
            "cpp", "cc", "cxx", "hpp" -> "cpp"
            "c", "h" -> "c"
            "rb" -> "ruby"
            "php" -> "php"
            "swift" -> "swift"
            "scala" -> "scala"
            "groovy" -> "groovy"
            "sql" -> "sql"
            "html", "htm" -> "html"
            "css" -> "css"
            "json", "json5" -> "json"
            "xml" -> "xml"
            "yaml", "yml" -> "yaml"
            "md", "markdown" -> "markdown"
            "sh", "bash", "zsh" -> "bash"
            "vue" -> "vue"
            else -> ""
        }
    }
}

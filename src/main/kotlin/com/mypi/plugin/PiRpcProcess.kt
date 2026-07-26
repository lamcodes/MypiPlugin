package com.mypi.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import com.intellij.openapi.project.Project

/**
 * Pi RPC 子进程管理:拉起 `pi --mode rpc`,按 JSONL 双向通信。
 *
 * 关键约束(与 pi-probe.mjs 一致):
 *  - JSONL 只按 \n 切分,strip 尾部 \r;严禁用 BufferedReader.readLine()
 *    (它会把 U+2028/U+2029 也当换行,破坏 JSON 字符串)。
 *  - 所有进程 IO 必须在后台线程,绝不阻塞 EDT。
 *
 * @param project 用于读取 Project 级 PiSettings(获取用户配置的 pi 路径)。
 */
class PiRpcProcess(private val project: Project) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idSeq = AtomicLong(0)
    private val startLock = Any()

    /** JSON 解析器:忽略未知字段,兼容 pi 协议后续新增字段。 */
    private val json = Json { ignoreUnknownKeys = true }

    /** 进程级错误去重标志:true 表示本进程生命周期内已上报过一次错误,后续抑制。 */
    private val errorReported = AtomicBoolean(false)

    /** 进程序列号:每次 start/stop 自增,供 readLoop 识别"自己是否仍是当前活跃世代",根治 stop→start 竞态。 */
    private val generation = AtomicLong(0)

    private var process: Process? = null
    private var stdin: Writer? = null
    private var readerJob: Job? = null

    @Volatile private var started = false
    /**
     * 主动停止标志:stop() 置 true。readLoop 据此区分"主动关闭"与"异常退出",
     * 主动关闭时不触发 onTerminated/reportError,避免与 stop() 重复上报。
     */
    @Volatile private var stopping = false

    /** 收到 assistant 文本增量时触发(后台线程回调;UI 侧需 invokeLater 切回 EDT)。 */
    @Volatile var onTextDelta: ((String) -> Unit)? = null
    /** 进程异常/应用级错误时触发(后台线程回调)。 */
    @Volatile var onError: ((Throwable) -> Unit)? = null
    /** pi 启动结果回调(true=成功);后台线程触发。 */
    @Volatile var onStarted: ((Boolean) -> Unit)? = null
    /**
     * 进程非主动终止时触发(后台线程回调):EOF 退出传 null,异常退出传该异常。
     * stop() 主动关闭不触发。UI 可据此提示"pi 已断开"或尝试重启。
     */
    @Volatile var onTerminated: ((Throwable?) -> Unit)? = null

    fun isStarted(): Boolean = started
    fun nextId(): String = "req-" + idSeq.incrementAndGet()

    /** 启动 pi 子进程。幂等且线程安全;必须在后台线程调用,严禁在 EDT。 */
    fun start() {
        // 锁内只做状态判定与进程拉起;所有回调一律出锁后再触发,
        // 避免持 startLock 回调造成死锁或重入(#6)。
        var ok = false
        var failure: Throwable? = null
        synchronized(startLock) {
            if (started) return // 已启动,直接返回;无需重复回调
            // 复位停止与去重标志:新一轮生命周期开始,后续崩溃应正常上报
            stopping = false
            errorReported.set(false)
            val isWindows = System.getProperty("os.name", "").lowercase().startsWith("windows")
            // 新世代号:旧 readLoop 退出时据此识别"已被接管",从而跳过 cleanup,避免误清新批状态
            val gen = generation.incrementAndGet()
            // 优先用用户在 Settings 中配置的 pi 路径;空则回退默认(PATH 查找)
            val configured = PiSettings.getInstance(project).piPath.trim()
            val cmd = if (configured.isNotEmpty()) {
                // 用户显式配置了路径。Windows 下 .cmd/.bat 必须经 cmd /c 代理:
                // CreateProcess 不直接支持脚本扩展名,直接执行会 error=193 崩溃;.exe 可直接执行。
                val needCmdProxy = isWindows &&
                    (configured.endsWith(".cmd", ignoreCase = true) || configured.endsWith(".bat", ignoreCase = true))
                if (needCmdProxy) listOf("cmd", "/c", configured, "--mode", "rpc", "--no-session")
                else listOf(configured, "--mode", "rpc", "--no-session")
            } else if (isWindows) {
                listOf("cmd", "/c", "pi", "--mode", "rpc", "--no-session")
            } else {
                listOf("pi", "--mode", "rpc", "--no-session")
            }
            try {
                val p = ProcessBuilder(cmd).redirectErrorStream(false).start()
                process = p
                stdin = BufferedWriter(OutputStreamWriter(p.outputStream, Charsets.UTF_8))
                readerJob = scope.launch { readLoop(p.inputStream, p, gen) }
                started = true
                ok = true
            } catch (e: Throwable) {
                // 启动失败:清理可能残留的半成品引用,保持状态干净(ok 保持初始 false)
                process = null
                stdin = null
                readerJob = null
                started = false
                failure = e
            }
        }
        // 出锁后回调:成功通知 true;失败先走 reportError(去重),再通知 false
        if (ok) {
            onStarted?.invoke(true)
        } else {
            failure?.let { reportError(it) }
            onStarted?.invoke(false)
        }
    }

    /** 发送 prompt。全程异步,可在 EDT 调用;若 pi 未启动则在 IO 协程里自动启动。 */
    fun sendPrompt(message: String) {
        // 用 buildJsonObject 构造请求体,自动处理转义;末尾加 \n 满足 JSONL 帧切分契约
        val payload = buildJsonObject {
            put("id", nextId())
            put("type", "prompt")
            put("message", message)
        }.toString() + "\n"
        scope.launch {
            if (!started) start() // IO 协程里确保启动(幂等),避免在 EDT 调 start
            val w = stdin
            if (w == null) {
                reportError(IllegalStateException("pi 未启动")) // 进程级错误,走去重
                return@launch
            }
            try {
                synchronized(w) { w.write(payload); w.flush() }
            } catch (e: Throwable) {
                reportError(e) // stdin 写失败,进程级错误,走去重
            }
        }
    }

    /**
     * stdout 读取循环:严格按 \n 切 JSONL(严禁 readLine)。
     *
     * @param ownerProcess 本循环所属的进程实例,收尾时用于一致性校验,避免误清新 start 建立的状态。
     * @param gen          启动时的世代号;收尾时若与当前 generation 不符,说明已被新 start/stop 接管。
     */
    private suspend fun readLoop(input: InputStream, ownerProcess: Process, gen: Long) {
        val reader = BufferedInputStream(input)
        val baos = ByteArrayOutputStream()
        var exitCause: Throwable? = null // 非 null 表示因异常退出;null 表示 EOF 正常收尾
        try {
            while (true) {
                val b = reader.read()
                if (b == -1) break // EOF:pi 关闭了 stdout
                if (b == '\n'.code) {
                    var line = baos.toString(Charsets.UTF_8)
                    baos.reset()
                    if (line.endsWith('\r')) line = line.dropLast(1)
                    if (line.isNotEmpty()) handleLine(line)
                } else {
                    baos.write(b)
                }
            }
            if (baos.size() > 0) {
                val line = baos.toString(Charsets.UTF_8)
                if (line.isNotEmpty()) handleLine(line)
            }
        } catch (e: Throwable) {
            exitCause = e
        }
        // 世代号已变:新 start/stop 已接管共享字段,静默退出——不 cleanup、不回调,避免误清与重复上报
        if (generation.get() != gen) return
        // 仍是当前活跃世代:清理属于自己进程的状态(世代号 + ownerProcess 引用判等,双重校验)
        cleanupState(ownerProcess)
        // 仅"非主动停止"才通知上层:EOF 传 null,异常传 exitCause(#2)
        if (!stopping) {
            if (exitCause != null) reportError(exitCause) // 崩溃属进程级错误,走去重(#8)
            onTerminated?.invoke(exitCause)
        }
    }

    /**
     * 复位进程级状态(started/进程/输入流/读循环 job),供 start 失败、readLoop 退出、stop 共用。
     *
     * @param ownerProcess 调用方持有的进程实例;非 null 时仅当共享字段仍指向同一进程才清零,
     *                    防止误清新 start 建立的状态;为 null(如 stop())时无条件清理。
     */
    private fun cleanupState(ownerProcess: Process? = null) {
        synchronized(startLock) {
            if (ownerProcess != null && process !== ownerProcess) return
            started = false
            process = null
            stdin = null
            readerJob = null
        }
    }

    /** 分发单帧 JSON。 */
    private fun handleLine(line: String) {
        val obj = try {
            json.parseToJsonElement(line).jsonObject
        } catch (e: Exception) {
            return // 非 JSON 行或顶层非对象,忽略
        }
        when (obj.str("type")) {
            "message_update" -> {
                // assistantMessageEvent 必须是对象;非对象则视为异常帧,跳过
                val ev = obj["assistantMessageEvent"] as? JsonObject ?: return
                if (ev.str("type") == "text_delta") {
                    val delta = ev.str("delta") ?: ""
                    if (delta.isNotEmpty()) onTextDelta?.invoke(delta)
                }
            }
            "response" -> {
                // pi 对请求的应答状态:仅 success==false 时把 error 文案透出给 UI(#4)。
                // 应用级错误"每次请求独立",不走 reportError 去重(语义不同于进程故障)。
                // bool 对缺失/非布尔均返回 null,默认按成功处理,避免误报。
                val success = obj.bool("success") ?: true
                if (!success) {
                    val msg = obj.str("error") ?: "pi 拒绝了请求"
                    onError?.invoke(RuntimeException(msg))
                }
            }
            "agent_end", "agent_settled" -> onTextDelta?.invoke("\n") // 一轮结束,换行分隔
            else -> Unit // ready/tool_* 等,MVP 暂不处理
        }
    }

    /** 安全取字符串 primitive:仅 JsonPrimitive 返回 content,缺失或非 primitive(对象/数组)返回 null,不抛异常。 */
    private fun JsonObject.str(key: String): String? {
        val e = this[key] ?: return null
        return (e as? JsonPrimitive)?.content
    }

    /** 安全取布尔 primitive:仅布尔字面量 JsonPrimitive 返回值,缺失/非 primitive/非布尔均返回 null。 */
    private fun JsonObject.bool(key: String): Boolean? {
        val e = this[key] ?: return null
        return (e as? JsonPrimitive)?.booleanOrNull
    }

    /**
     * 进程级错误统一入口:本进程生命周期内向 UI 只上报一次
     * (start 失败 / readLoop 崩溃 / stdin 写失败 共享同一去重标志,#8)。
     * 应用级错误(如 #4 的 response failure)语义独立,直接 onError,不走本方法。
     */
    private fun reportError(t: Throwable) {
        if (errorReported.compareAndSet(false, true)) {
            onError?.invoke(t)
        }
    }

    /** 主动停止进程并清理。置 stopping=true 以抑制 readLoop 的 onTerminated/reportError。 */
    fun stop() {
        stopping = true
        // 推进世代号:让旧 readLoop 退出时识别"已被接管"而跳过 cleanup,避免误清(若紧接着有新 start)
        generation.incrementAndGet()
        val job = readerJob
        val w = stdin
        val p = process
        job?.cancel()
        try { w?.close() } catch (_: Exception) {}
        p?.destroy()
        cleanupState()
    }
}

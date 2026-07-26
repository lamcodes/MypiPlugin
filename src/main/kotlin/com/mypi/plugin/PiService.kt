package com.mypi.plugin

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Project 级服务:持有 Pi RPC 子进程,在整个项目生命周期内复用。
 * 用 @Service 注解(2023.1+ 支持),无需在 plugin.xml 注册。
 * 实现 Disposable:项目关闭时回收 pi 子进程,杜绝孤儿进程。
 */
@Service(Service.Level.PROJECT)
class PiService(val project: Project) : Disposable {

    val rpc: PiRpcProcess = PiRpcProcess(project)

    init {
        // 后台线程启动 pi:service 首次获取发生在 ToolWindow 创建(EDT),
        // 不能在 EDT 调 ProcessBuilder.start(),故丢到线程池。
        ApplicationManager.getApplication().executeOnPooledThread { rpc.start() }
    }

    override fun dispose() {
        // 项目/服务释放时主动停止子进程;stop() 已保证不误触发 onTerminated
        rpc.stop()
    }

    companion object {
        fun getInstance(project: Project): PiService = project.service()
    }
}

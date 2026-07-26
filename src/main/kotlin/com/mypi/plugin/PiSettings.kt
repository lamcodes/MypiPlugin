package com.mypi.plugin

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Project 级持久化设置:保存用户配置的 pi 可执行路径。
 *
 * - piPath 非空:PiRpcProcess.start() 以其作为可执行路径,不再依赖 PATH。
 * - piPath 为空:回退到原有默认查找(Windows 走 cmd /c pi,其它平台直接 pi),
 *   保证老用户升级后行为不变。
 */
@Service(Service.Level.PROJECT)
@State(name = "PiSettings", storages = [Storage("pi-settings.xml")])
class PiSettings : PersistentStateComponent<PiSettings> {

    /** 用户配置的 pi 路径;空串表示未配置(走默认 PATH)。@Volatile 保证 EDT 写、IO 线程读的可见性。 */
    @Volatile
    var piPath: String = ""

    override fun getState(): PiSettings = this

    override fun loadState(state: PiSettings) {
        // 平台反序列化出一个新实例后回调,逐字段拷贝,避免直接替换 this 引用
        piPath = state.piPath
    }

    companion object {
        fun getInstance(project: Project): PiSettings = project.service()
    }
}

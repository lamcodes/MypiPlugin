# 0002：反射集成 IDE MCP 与全局写锁原子落盘

> 核对：2026-11-20 0.2.4

- **状态**：已采纳
- **关联文档**：[架构说明.md](../架构说明.md)、[领域词汇表.md](../领域词汇表.md)

## 背景

IntelliJ IDEA 2025.2+ 内置 `com.intellij.mcpServer` 插件，可向外部 Agent 暴露读文件、搜索符号、构建、调试等 IDE 能力。若要求用户手动复制端口与配置 `~/.pi/agent/mcp.json`，门槛高且端口变化后容易失效；但若直接强依赖 `com.intellij.mcpServer` 内部类，在无 MCP 插件或未启用 MCP 的环境中会导致加载失败或编译耦合过重。

此外，IDEA 允许同时打开多个 Project 窗口，而 Pi 的 MCP 配置文件 `~/.pi/agent/mcp.json` 位于用户主目录，属于跨 Project 共享资源。

## 决策

1. **可选依赖 + 反射访问**：在 `plugin.xml` 中声明 `<depends optional="true">com.intellij.mcpServer</depends>`，运行期通过反射调用 `com.intellij.mcpserver.impl.util.network.McpServerConnectionAddressProvider#getServerSseUrl`。失败时静默降级，不影响 Pi 以纯终端模式运行。
2. **去重检测**：开启 `mcpDedupEnabled` 时，先检查 `~/.pi/agent/mcp.json` 的 `imports` 是否包含 `claude-code` 且 `~/.claude.json` 已存在同 `host:port` 的 MCP Server，避免重复注册同一 IDEA MCP。
3. **全局写锁 + 原子替换 + 损坏保护**：
   - 使用 `PiMcpConfigService` companion 级全局 `lock` 保护 `mcp.json` 的读-改-写临界区，反射与去重读取在锁外执行以缩短跨项目阻塞。
   - 写入采用「同目录临时文件 + `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`」，不支持原子移动的文件系统降级为普通写入，且保留其它 server 与顶层字段。
   - 写入前遇到损坏 JSON 或非 Object 根节点时显式抛错返回 `Failed`，绝不静默覆盖用户已有配置；用户关闭 MCP 集成时主动移除 `intellij` 条目。
4. **纯存储层下沉**：将文件读写、去重与 `host:port` 规范化抽离到无平台依赖的 `PiMcpConfigStore`，纳入 Tier1 单元测试。

## 取舍与后果

- **收益**：零手动配置即可获得 IDE 工具能力；多窗口并发安全；文件损坏时数据零丢失；核心文件逻辑可通过 Tier1 自动化验证。
- **代价与风险**：
  - 反射目标为内部实现类，IDEA 大版本升级时可能更名或迁移包路径，Plugin Verifier 无法静态捕获反射签名变更，升级大版本时需在真实 IDE 手测验证。
  - `mcpEnabled` 设置本身是 Project 级，但写入目标是用户级全局 `mcp.json`，多窗口同时打开且开关配置不一致时以后触发者为准。

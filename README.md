# PaiCLI

终端里的 Java 编码 Agent，对标 Claude Code：读代码、改代码、跑命令、联网查资料、驱动浏览器，还支持通过 MCP 协议接入任意外部工具。纯 Java 17 实现，单 jar 交付，零 Python 依赖。

```text
   ████████    PaiCLI π  v16.1.0
     ██  ██    Model glm-5.1 (glm)
     ██  ██    MCP 4/4 · 61 tools · 2/2 skills · ReAct
     ██  ██    ReAct · Plan · MCP · Browser · Image
     ██  ██
```

## 特性

**三种执行方式，共用一套工具、记忆和快照层**

- **ReAct**（默认）：思考-行动-观察循环，适合单步任务和探索式任务；不设固定轮数上限，同类错误连续 3 步注入提醒，判定停滞后关闭工具、让模型整理已完成的部分，不丢弃已有工作
- **Plan-and-Execute**（`/plan`）：把复杂任务拆解为 DAG，严格校验 id 唯一、依赖存在、无环；计划生成后先经人工确认（Enter 执行 / Ctrl+O 展开 / I 补充要求 / ESC 取消），按依赖批次并行执行，任务失败且完成度低于 50% 时自动重新规划（上限 2 次）
- **Multi-Agent**（`/team`）：规划者 / 执行者 / 检查者主从协作，Review 只认 JSON 布尔裁决，未通过带反馈重试（最多 2 次）

**工具系统：18 个内置工具 + MCP 运行时接入**

- 只读工具最多 4 路并行；写文件、执行命令、MCP、浏览器操作严格串行，结果按调用顺序回填
- 工具执行前按 JSON Schema 校验参数，字段级错误回传模型自行修正
- MCP 子系统自研实现（约 3500 行，未依赖第三方 SDK）：stdio 子进程与 Streamable HTTP 双传输、自写 JSON-RPC 2.0 客户端、配置合并与内容指纹信任、崩溃退避自动重启
- server 的工具自动注册为 `mcp__{server}__{tool}`；支持 resources 引用（`@server:protocol://path`）与订阅通知

**多模型接入：8 家供应商统一抽象**

- 内置 DeepSeek（默认）、GLM、混元、StepFun、Kimi、FreeLLMAPI、讯飞 MaaS、Agnes 八个客户端，新增 provider 约 20 行
- `/model` 运行时切换，模型能力目录、失败分类重试、思考内容回传、缓存命中 token 解析统一处理
- 支持图片输入的模型（DeepSeek V4.1 Flash、GLM-5V-Turbo 等）可直接 Ctrl+V 粘贴或 `@image:` 引用截图

**上下文工程与记忆**

- 三级递进压缩：超大工具结果落盘留摘要 → 清理旧工具结果（保留消息配对 id）→ 分栏目摘要；压缩后 token 未减少则回退原文，阈值随模型窗口自适应
- 长期记忆分项目 / 全局作用域：写入前查重与冲突检测（相似度 ≥ 0.8 交用户裁决），文件锁支持多进程并发，超 30 天未核实标注"可能已过时"
- system prompt 按变化频率分层组装（固定内容 → Skills → 运行时 → 项目上下文），保住服务端前缀缓存
- 会话记录只追加不改写，`/resume` 恢复历史会话

**安全与审批（HITL）**

- auto / plan / ask 三档模式，Shift+Tab 一键切换
- auto 模式：每条 Shell 命令先经轻量模型审查，审查出错一律拒绝；连续拦截 3 次才升级人工确认
- PathGuard 路径围栏：文件类工具强制限定项目根内，拦截绝对路径外逃 / `..` 穿越 / 符号链接逃逸
- CommandGuard 命令黑名单 + 可选命令沙箱（macOS Seatbelt / Linux bubblewrap）
- 结构化审计：危险工具调用按天写 JSONL，`/audit` 查看
- 联网来源管控：`web_fetch` 与浏览器只接受用户原文或搜索结果中的 URL，工具返回的正文不产生新访问许可，防提示注入
- 微信无人值守通道默认拒绝：只读工具放行，命令与 MCP 只认白名单

**更多能力**

- **Skill 系统**：可复用的专家手册按需加载，索引常驻 system prompt（20 个 / 4KB），正文 16KB 预算内按需注入；内置 web-access skill 覆盖微信公众号、知乎、X、小红书等站点的访问经验
- **RAG 语义检索**：JavaParser AST 分块 + Ollama Embedding + SQLite 向量存储，SHA-256 增量索引；日常定位优先 glob/grep/read 确定性路径
- **浏览器自动化**：默认接入 chrome-devtools-mcp；`/browser connect` 可复用本机登录态 Chrome（CDP），敏感页面强制单步审批，`close_page` 只允许关闭自己开的标签页
- **Side-Git 快照**：每轮改动前后自动快照（JGit 实现，不写用户项目 `.git`），`/restore` 一键回退
- **LSP 诊断注入**：`write_file` / `edit_file` 成功后自动语法诊断并回传模型（当前 JavaParser MVP）
- **Runtime API + 后台任务**：`serve --http` 启动 localhost API（强制鉴权），SQLite 持久化任务队列
- **微信 iLink 通道**：扫码绑定后在微信里和 Agent 对话，终端同步显示执行过程
- **双形态 TUI**：inline 流式界面（默认，Claude Code 风格状态栏 + 行内折叠工具块）与 lanterna 全屏三栏界面可切换

## 快速开始

环境要求：Java 17+、Maven。`search_code` 语义检索可选配本地 Ollama + nomic-embed-text；`grep_code` 优先使用本机 ripgrep，未安装自动回退。

### 1. 配置 API Key

```bash
cp .env.example .env
# 编辑 .env，八家供应商任配其一（DEEPSEEK_API_KEY / GLM_API_KEY / HUNYUAN_API_KEY /
# STEP_API_KEY / KIMI_API_KEY / FREELLMAPI_API_KEY / XFYUN_MAAS_API_KEY / AGNES_API_KEY）
```

也可以用环境变量：

```bash
export GLM_API_KEY=your_api_key_here
export GLM_MODEL=glm-5.3-flash
```

DeepSeek 是默认供应商（默认模型 DeepSeek V4.1 Flash，ID `deepseek-flash`）；没有 DeepSeek Key 时按 deepseek → glm → hunyuan → step → kimi → freellmapi → xfyun → agnes 顺序回退。会话内 `/model <provider>/<模型ID>` 运行时切换，`/model list`、`/model refresh <provider>`、`/model add` 手动维护模型目录。完整配置项见 [`.env.example`](.env.example)。

### 2. 可选：配置 MCP server

`~/.paicli/mcp.json` 不存在时自动创建默认 chrome-devtools 配置。接入其他 server 直接编辑该文件，或放项目级 `.paicli/mcp.json`（按 server 名覆盖用户级）：

```json
{
  "mcpServers": {
    "fetch": {
      "command": "uvx",
      "args": ["mcp-server-fetch"]
    },
    "remote-demo": {
      "url": "https://mcp.example.com/v1",
      "headers": {"Authorization": "Bearer ${REMOTE_TOKEN}"}
    }
  }
}
```

- `command` 表示 stdio server，`url` 表示 Streamable HTTP server；`${PROJECT_DIR}` / `${HOME}` / `${环境变量}` 自动展开
- stdio 子进程只继承 PATH、HOME、代理等白名单环境变量——本机 API Key 默认不会泄漏给第三方 server，需要传 Key 时在 `env` 里显式写
- 项目级配置随仓库分发，首次启动需 `/mcp trust <name>` 确认，确认绑定内容指纹：仓库更新改了命令、参数、env、url 或 headers 任何一项都要重新确认
- server 崩溃按 1s/2s/4s 退避自动重启，连续 3 次放弃等人工 `/mcp restart`；连续 2 次 ping 不回判定卡死
- 复用本机登录态 Chrome：Chrome 144+ 打开 `chrome://inspect/#remote-debugging` 勾选允许，然后 `/browser connect`

### 3. 编译运行

```bash
mvn clean package
java -jar target/paicli-1.0-SNAPSHOT.jar
```

### 4. 模式切换

- `/plan <任务>` 下一条任务走 Plan-and-Execute；`/team <任务>` 走 Multi-Agent
- `Shift+Tab` 在 auto / plan / ask 三档间轮换（`/mode` 等效）
- `/cancel` 取消运行中的任务

## 使用示例

ReAct 模式：

```text
* 创建一个Java项目叫myapp

🧠 思考过程:
用户要创建一个 Java 项目。我先调用 create_project 工具生成基础结构，再根据工具返回结果确认是否创建成功。

🤖 最终结果:
已成功创建 Java 项目 "myapp"，包含基本的 Maven 结构。
```

Plan 模式（生成 DAG → 人工确认 → 按依赖执行）：

```text
* /plan 创建一个名为 demoapp 的 java 项目，然后读取 pom.xml，最后验证项目结构

╔══════════════════════════════════════════════════════════╗
║  执行计划: 创建一个名为 demoapp 的 java 项目，然后读取...  ║
╠══════════════════════════════════════════════════════════╣
║  1. ⏳ task_1               [COMMAND   ] 依赖: 无         ║
║     创建 demoapp 项目结构                                  ║
║  2. ⏳ task_2               [FILE_READ ] 依赖: task_1     ║
║     读取 demoapp/pom.xml 内容                              ║
║  3. ⏳ task_3               [VERIFICATION] 依赖: task_2   ║
║     验证项目结构与 Maven 配置                               ║
╚══════════════════════════════════════════════════════════╝

📝 计划已生成。
   - 回车：按当前计划执行
   - ESC：取消本次计划
   - I：输入补充要求，在当前计划上修改
```

## 内置工具（18 个）

- `read_file` - 读取文件内容
- `write_file` - 写入文件内容
- `edit_file` - 替换已有文件中的文本片段，模型无需输出整个文件；默认唯一匹配，`replace_all=true` 替换全部；能容忍弯引号、行尾空白和从 `read_file` 带过来的行号前缀，CRLF 文件保留原换行风格
- `list_dir` - 列出目录内容
- `glob_files` - 按文件名 glob 实时查找项目内文件（只读，自动跳过构建/依赖目录）
- `grep_code` - 按关键字或正则实时搜索项目内代码，优先 ripgrep，返回文件、行号、上下文与 suggested_reads
- `execute_command` - 在项目目录执行短时 Shell 命令（默认 60 秒超时，8KB 输出截断，黑名单拦截破坏性命令）
- `create_project` - 创建项目结构（java/python/node）
- `search_code` - RAG 语义检索代码库（自然语言查询，常规搜索无果时的辅助路径）
- `web_search` - 联网搜索（智谱 Web Search / SerpAPI / SearXNG / DeepSeek 原生搜索，按已配 Key 自动选择）
- `web_fetch` - 抓取可信来源 URL 并提取正文 Markdown；屏蔽内网 / `file://`，5MB 上限，30 次/分钟限流
- `browser_connect` / `browser_disconnect` / `browser_status` - 切换 isolated / shared 浏览器模式，复用登录态 Chrome
- `save_memory` - 保存长期记忆（仅在用户明确要求时调用）
- `load_skill` - 加载 Skill 完整指引；带 `file` 时读取 `references/` 附属文档
- `save_skill_reference` - 把新验证的经验写回 Skill 的用户级参考资料
- `revert_turn` - 恢复到最近第 N 个 pre-turn 快照（走 HITL 与审计）
- `mcp__{server}__{tool}` - MCP server 动态注册的外部工具（含 `list_resources` / `read_resource` 虚拟工具）

同一轮多个工具调用时只并行执行只读工具；有副作用的调用按原顺序串行。文件与代码检索类工具路径强制限定在项目根之内。

## 命令

进程级入口：

- `paicli wechat setup|start|status` - 绑定 / 启动微信 iLink 通道
- `paicli wechat daemon start|stop|restart|status|logs` - 管理微信通道后台进程
- `paicli serve --http --port 8080` - 启动 Runtime API（需 `PAICLI_RUNTIME_API_KEY`）

常用交互命令：

| 命令 | 作用 |
|---|---|
| `/plan` `/team` | 切换执行方式；`/cancel` 取消运行中任务 |
| `/mode` / Shift+Tab | auto / plan / ask 三档切换 |
| `/hitl on` / `/hitl default` | 全部确认 / 恢复 auto 审查 |
| `/model [list/info/refresh/add]` | 模型查看与运行时切换 |
| `/mcp [restart/logs/disable/enable/trust/resources/prompts]` | MCP server 管理 |
| `/browser [status/connect/tabs/disconnect]` | 浏览器模式管理 |
| `/skill [list/show/on/off/trust/reload]` | Skill 管理 |
| `/memory [list/search/delete/verify/replace/clear]` `/save` | 长期记忆管理 |
| `/index` `/search` `/graph` | RAG 索引、语义检索、代码关系图谱 |
| `/snapshot` `/restore <N>` | 快照查看与回退 |
| `/context` `/compact` `/export` `/resume` | 上下文状态 / 手动压缩 / 导出 / 恢复会话 |
| `/policy` `/audit [N]` | 安全策略与审计记录 |
| `/init` `/config` `/clear` `/exit` | 生成 PAI.md / 配置 / 清空对话 / 退出 |

微信通道内：`/help`、`/status`、`/pause`、`/resume`、`/stop`。

## 安全设计

- **检查顺序**：本轮工具策略（网址来源）→ HITL 审批 → 参数校验 → PathGuard / CommandGuard；被安全策略拒绝的操作用户也无法批准
- **auto 模式命令审查**：轻量模型只看用户原话 + 命令本身，低风险直接执行；审查超时、格式错误、模型不可用一律不放行
- **工具结果不可信标记**：所有外部数据统一包 `trust="untrusted-data"`，工具返回的内容不能让新网址变成可访问、不能写进长期记忆
- **命令沙箱**：`PAICLI_COMMAND_SANDBOX=auto|required` 时走 Seatbelt / bubblewrap（只读系统目录、工作区可写、无网络）；默认 `off`
- **MCP 子进程环境白名单** 与 **项目级配置信任指纹** 见上文 MCP 配置节

## 技术栈

Java 17 · Maven · OkHttp · Jackson · JLine 4 · SQLite · JavaParser · Ollama Embedding · JGit · JSON Schema · MCP · Chrome DevTools Protocol

## 项目结构

```
src/main/java/com/paicli/
├── agent/    # ReAct Agent、PlanExecuteAgent、AgentOrchestrator（Multi-Agent）
├── cli/      # Main 入口、命令解析
├── plan/     # 任务 DAG、Planner
├── llm/      # 8 家 provider 客户端 + LlmClientFactory
├── tool/     # ToolRegistry 与 18 个内置工具
├── mcp/      # MCP 协议栈：config / transport / jsonrpc / protocol / notifications / resources
├── memory/   # 短期上下文、长期记忆、TokenBudget、三级压缩
├── context/  # 上下文模式与预算
├── history/  # ConversationLedger（只追加会话记录）
├── rag/      # Embedding、VectorStore、CodeChunker、CodeAnalyzer
├── skill/    # Skill 加载、信任、frontmatter 解析
├── web/      # 搜索 provider 与 web_fetch
├── browser/  # 浏览器模式切换、敏感页策略
├── hitl/     # 审批处理
├── policy/   # PathGuard、CommandGuard、AuditLog
├── snapshot/ # Side-Git 快照（JGit）
├── runtime/  # Runtime API 与后台任务队列
├── render/   # Renderer 接口与 inline/lanterna/plain 三实现
├── image/    # 图片输入预处理
├── wechat/   # 微信 iLink 通道
└── lsp/      # 编辑后诊断注入
```

实现细节文档见 [`docs/agents-reference.md`](docs/agents-reference.md)；Runtime API 见 [`docs/phase-20-runtime-api.md`](docs/phase-20-runtime-api.md)。

## 质量与测试

约 8 万行代码、1800+ 单元测试。日常回归 `mvn test -Pquick`，全量 `mvn test -DskipTests=false`（`mvn clean package` 默认跳过测试）。

另搭有 Docker 隔离的 Native AgentBench 评测框架（路径穿越防护、危险指令防护、未批准不可逆操作、工具选择、MCP 多服务联合查询等 28 类题目，当前接入 25 类），运行手册见 [`benchmarks/paicli-native-agentbench-v0.1/RUNBOOK.md`](benchmarks/paicli-native-agentbench-v0.1/RUNBOOK.md)；LLM-as-a-Judge 评分组件说明见 [`docs/llm-as-a-judge.md`](docs/llm-as-a-judge.md)。

## Credits

本项目基于 [itwanger/paicli](https://github.com/itwanger/paicli)（作者 [沉默王二](https://github.com/itwanger)）的教学项目学习与二次开发，感谢原作者的开源分享。本仓库在其基础上做了文档整理、源码注释增强与工程化改进。

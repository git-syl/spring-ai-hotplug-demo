[English](README.md) | **简体中文**

# Spring AI Agent 插件系统示例

技术博客《用 Java + Spring AI 构建 Agent 插件系统》的配套代码：一个最小但完整的、可以在运行时安装、升级和卸载 Java 插件的
Spring AI Agent。

**1. 普通的 Bash 工具，带人工审批。** 没有勾选任何插件：模型决定执行 `uname -a`，这次调用要等到有人批准才会执行。

![普通 Bash 工具：模型请求执行 uname -a，调用在等待人工审批](img/img_1.png)

**2. 自定义插件：网页爬虫工具。** 上传并启用 `webdom` 插件 JAR 后，它的 `webdom_fetch_dom` 工具就和 Bash 一起出现在对话里；Bash 调用仍然需要审批。

![webdom 插件：模型调用 webdom_fetch_dom 抓取 https://example.com/，随后执行经批准的 Bash 命令](img/img.png)

*两张截图都是自带的网页（`http://localhost:8080/`）。页面本身是中文的，截图里是翻译成英文后的样子。*

- **不重启加载插件**：上传 JAR 即安装（安装时不执行插件代码），启用时才加载；升级时先启动新版本再切换，进行中的对话继续用旧版本。
- **流式对话**：`POST /chat/stream` 返回 SSE。每次对话默认带 Bash 工具（Agent Utils 的 `ShellTools`），
  请求里列出的插件，工具也一起交给 Spring AI 官方的工具循环。
- **人工审批**：Bash 每次调用都要批准（Spring AI 官方配置 `spring.ai.tools.confirmation.tools`），插件工具可以由清单或管理员要求审批；
  审批请求经 SSE 推给客户端，`POST /approvals/{id}` 回填决定。
- **插件可回收**：每个插件版本一个类加载器，父优先；停用后旧版本退役，类加载器可以被 GC 回收（有测试证明）。
- **简单的网页**：打开 `http://localhost:8080/`，不用 curl 就能安装、启用、升级、停用和卸载插件，在对话里看到流式回复，并批准或拒绝工具调用。

## 环境要求

- JDK 25、Maven 3.9+
- 一个 DeepSeek 或兼容接口的 API Key。默认使用火山方舟上的 DeepSeek；改用 DeepSeek 官方 API 时，设置
  `DEEPSEEK_BASE_URL=https://api.deepseek.com` 和 `DEEPSEEK_MODEL=deepseek-chat`。

## 目录结构

```text
spring-ai-hotplug-demo/
├── pom.xml                          聚合工程兼父 POM：Spring Boot 4.1.0、Spring AI 2.1.0-RC1、Java 25
├── plugin-api/                      插件契约 agent-plugin-api：AgentPlugin、PluginContext 两个接口，不依赖任何库
├── app/                             宿主 agent-app：Spring Boot + Spring AI + Agent Utils
│   ├── src/main/java/com/example/agent/
│   │   ├── plugin/                  插件运行时：清单、存储、安装、类加载器、运行实例、管理器、租约、工具包装、审批规则、管理接口
│   │   └── chat/                    流式对话：ChatController、事件流 ChatEvents、审批 InteractiveToolCallingManager / ApprovalRegistry
│   └── src/main/resources/static/index.html   管理插件和聊天的网页（一个文件，原生 HTML、CSS、JavaScript，没有构建步骤）
├── plugin-sample/web-dom-crawler/   示例插件 webdom：抓取网页、返回 DOM 大纲；jsoup 经 shade 重定位
└── classloader-lab/                 类加载动手实验（不是 Maven 模块，直接用 javac/java 运行）
```

## 构建与测试

```bash
mvn package
```

这会编译三个模块、运行全部测试，并产出：

- `app/target/agent-app-1.0.0-SNAPSHOT.jar`：宿主（Spring Boot 可执行包）；
- `plugin-sample/web-dom-crawler/target/web-dom-crawler-plugin-1.0.0.jar`：要上传的插件（shade 后的普通 JAR，不是 `original-*.jar`）。

`ChatStreamLiveTest` 用真实模型跑一次完整的流式对话（插件工具、需要审批的 Bash、SSE 事件、租约释放），
只有设置了 `DEEPSEEK_API_KEY` 才运行，否则跳过：

```bash
DEEPSEEK_API_KEY=你的密钥 mvn test -pl app -am -Dtest=ChatStreamLiveTest
```

## 运行

在仓库根目录启动（插件数据默认放在 `./data/plugins`，Bash 的工作目录默认是 `./data/workspace`）：

```bash
DEEPSEEK_API_KEY=你的密钥 java -jar app/target/agent-app-1.0.0-SNAPSHOT.jar
```

> Windows 的 cmd 用 `set DEEPSEEK_API_KEY=你的密钥` 先设置环境变量。没有密钥也能启动并体验插件管理接口，只是对话会失败。

## 体验一遍

### 用网页

打开 `http://localhost:8080/`。左边安装和管理插件，右边是对话。

1. 在“安装插件”里选择 shade 之后的 JAR，点“上传并安装”。插件出现在列表里，状态是 `DISABLED`。
2. 点“启用”，状态变成 `RUNNING`；勾选“在对话中使用”，它的工具就会加入你的对话。输入框上方的一行字会列出下一条消息能用的工具：
   Bash，加上每个勾选的插件。
3. 输入消息，按 Enter 发送（Shift + Enter 换行）。回复会逐字出现；每次工具调用是一张卡片，能展开看参数和结果，并带有执行它的插件版本标签。
4. 需要审批的调用（Bash 每次都需要）会出现黄色卡片和“批准”“拒绝”两个按钮。点“停止”会结束这次对话，并撤回没人回答的审批，工具不会执行。
5. 升级时先上传新版本，在版本下拉框里选它，点“启用”。如果还有对话在用旧版本，“运行实例”那一行会同时列出两个实例
   （`1.0.0 RETIRING · 租约 1`），等那个对话结束旧版本就自动停止。“停用”“卸载”的用法一样。

网页只是一个静态文件，调用的就是下面的接口，所以它能做的事也都能用 curl 完成。

### 用 curl

下面的命令在仓库根目录执行，服务在 `localhost:8080`。

> Windows 的 Git Bash 里，`curl -d` 带中文可能发出非 UTF-8 字节（服务端报 400），可以把请求体存成 UTF-8 文件，用 `--data-binary @文件名` 发送。

**1. 安装插件**（只校验和保存，不执行插件代码；同一个 JAR 再传一次返回 200）：

```bash
curl -X POST http://localhost:8080/admin/plugins \
  -F "file=@plugin-sample/web-dom-crawler/target/web-dom-crawler-plugin-1.0.0.jar"
```

**2. 修改配置并启用**（配置在下一次启动时生效）：

```bash
curl -X PUT http://localhost:8080/admin/plugins/webdom/config \
  -H "Content-Type: application/json" -d '{"values": {"timeoutSeconds": "20"}, "confirmTools": []}'

curl -X POST http://localhost:8080/admin/plugins/webdom/enable \
  -H "Content-Type: application/json" -d '{"version": "1.0.0"}'
```

**3. 在对话中使用插件**：

```bash
curl -N -X POST http://localhost:8080/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "抓取 https://example.com/ ，用一句话说明这个页面的结构。", "plugins": ["webdom"]}'
```

输出节选（省略了事件之间的空行）：

```text
data:{"type":"tool_call","id":"call_xr7v…","tool":"webdom_fetch_dom","plugin":"webdom@1.0.0","arguments":"{\"url\": \"https://example.com/\"}"}
data:{"type":"tool_result","id":"call_xr7v…","tool":"webdom_fetch_dom","plugin":"webdom@1.0.0","text":"\"URL: https://example.com/\\nStatus: 200\\nTitle: Example Domain\\n…"}
data:{"type":"token","text":"这个"}
data:{"type":"token","text":"页面"}
……
data:{"type":"finish"}
```

**4. 让模型执行 Bash，需要你批准**：

```bash
curl -N -X POST http://localhost:8080/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "用 Bash 执行命令 echo hello-from-bash，然后把输出告诉我。"}'
```

流会停在一条审批请求上：

```text
data:{"type":"tool_call","id":"call_ozlq…","tool":"Bash","arguments":"{\"command\": \"echo hello-from-bash\", …}"}
data:{"type":"approval_request","id":"fd75ee8d-311d-426d-86f6-dffe7aa8ce1b","tool":"Bash","arguments":"{\"command\": \"echo hello-from-bash\", …}"}
```

在另一个终端里查看并批准（`"approve": false` 则拒绝，工具不执行，模型会如实告诉你）：

```bash
curl http://localhost:8080/approvals
curl -X POST http://localhost:8080/approvals/fd75ee8d-311d-426d-86f6-dffe7aa8ce1b \
  -H "Content-Type: application/json" -d '{"approve": true}'
```

**5. 不停机升级**：把 `plugin-sample/web-dom-crawler/pom.xml` 中插件自己的 `<version>1.0.0</version>` 改成 `1.1.0`，重新打包、安装、启用：

```bash
mvn -pl plugin-sample/web-dom-crawler -am package -DskipTests
curl -X POST http://localhost:8080/admin/plugins \
  -F "file=@plugin-sample/web-dom-crawler/target/web-dom-crawler-plugin-1.1.0.jar"
curl -X POST http://localhost:8080/admin/plugins/webdom/enable \
  -H "Content-Type: application/json" -d '{"version": "1.1.0"}'
```

如果切换时还有对话在用 1.0.0（例如它正在等待审批），插件详情里会同时看到两个实例，旧版本服务完这个对话后自动停止：

```json
"runtimes": [
  {"version": "1.1.0", "state": "RUNNING", "leases": 0, "inFlight": 0, "startedAt": "…"},
  {"version": "1.0.0", "state": "RETIRING", "leases": 1, "inFlight": 0, "startedAt": "…"}
]
```

**6. 停用与卸载**：

```bash
curl -X POST http://localhost:8080/admin/plugins/webdom/disable
curl -X DELETE "http://localhost:8080/admin/plugins/webdom?purgeData=true"
```

停用后，点名这个插件的对话请求在建立 SSE 之前就返回 400：`{"code":"invalid_request","message":"Plugin webdom is not running (DISABLED)"}`。

## 接口一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/` | 网页 |
| POST | `/chat/stream` | 流式对话（SSE）。请求体 `{"message", "plugins"?, "userId"?, "sessionId"?}` |
| GET | `/approvals` | 等待审批的工具调用 |
| POST | `/approvals/{id}` | 批准或拒绝：`{"approve": true}`；200 已送达，404 已超时、已决定或 ID 错误 |
| POST | `/admin/plugins` | 上传安装（multipart：`file`，可选 `sha256`）；新版本 201，相同版本 200 |
| GET | `/admin/plugins`、`/admin/plugins/{id}` | 插件列表、插件详情（状态、版本、工具、配置、运行实例） |
| POST | `/admin/plugins/{id}/enable` | 启用或切换版本：`{"version": "1.0.0"}`；回滚也用它 |
| POST | `/admin/plugins/{id}/reload` | 用当前配置重启已启用的版本 |
| POST | `/admin/plugins/{id}/disable` | 停用 |
| PUT | `/admin/plugins/{id}/config` | 整体替换配置值和额外审批名单：`{"values": {...}, "confirmTools": [...]}` |
| DELETE | `/admin/plugins/{id}?purgeData=false` | 卸载已停用的插件 |

SSE 事件：`token`（模型文字）、`tool_call`、`approval_request`（`id` 是审批 ID）、`tool_result`、`heartbeat`（忽略即可）、`finish`、`error`。
`plugin` 字段是实际执行这次调用的插件版本，宿主自带的工具没有这个字段。错误响应统一是 `{"code","message"}`。

## 配置一览

| 配置 | 环境变量 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `spring.ai.deepseek.api-key` | `DEEPSEEK_API_KEY` | 占位值 | 模型密钥 |
| `spring.ai.deepseek.base-url` | `DEEPSEEK_BASE_URL` | 火山方舟 | 模型接口地址 |
| `spring.ai.deepseek.chat.model` | `DEEPSEEK_MODEL` | `deepseek-v4-flash-ga-260731` | 模型名 |
| `spring.ai.tools.confirmation.tools` | | `[Bash]` | Spring AI 官方确认名单 |
| `plugins.root` | `PLUGINS_ROOT` | `data/plugins` | 插件存储目录 |
| `plugins.max-jar-size` | | `50MB` | 上传 JAR 上限 |
| `plugins.retire-timeout` | | `5m` | 旧版本退役的最长等待 |
| `chat.workspace` | `CHAT_WORKSPACE` | `data/workspace` | Bash 的工作目录 |
| `chat.approval-timeout` | | `2m` | 审批等待上限，超时按拒绝处理 |
| `chat.heartbeat-interval` | | `15s` | SSE 心跳；客户端断开后靠它及时撤回审批、释放租约 |

## 安全提示

- 插件代码和服务同进程、同权限运行，类加载器**不是**安全沙箱：只安装来源可信、经过审查的插件。
- 示例的管理接口没有鉴权，真实部署时必须由网关或上游服务保护，只对管理员开放。
- Bash 直接在宿主机上执行命令（Windows 上通过 `cmd.exe /c`），默认每次都要人工批准；不要在不可信的环境里去掉这项审批。
- 密钥只通过环境变量提供，不要写进配置文件。

## 类加载实验

`classloader-lab/` 是文章第三章的实验，不依赖 Maven：

```bash
bash classloader-lab/run.sh        # Linux / macOS / Git Bash
classloader-lab\run.cmd            # Windows cmd
```

它依次演示：JDK 的三层类加载器、加载不等于初始化、父优先、类的身份等于“全限定名 + 定义它的加载器”、
契约被插件重复打包时的 `ClassCastException`、线程上下文类加载器与 `ServiceLoader`、类加载器何时能被回收，
以及 `static` 块在哪些情况下执行的十道判断题。

## 代码导读

| 类 | 要点 |
| --- | --- |
| `plugin/PluginManifest` | 严格解析 `META-INF/agent-plugin.json`：未知字段、重复键、类型不符一律拒绝；只读 ZIP 条目，不加载类 |
| `plugin/PluginInstaller`、`PluginStore` | 暂存、限额、SHA-256、原子发布到不可变的版本目录；`registry.json` 先写临时文件再原子替换 |
| `plugin/PluginClassLoader` | 父优先；拒绝宿主内部类（依赖纪律，不是安全边界） |
| `plugin/PluginRuntime` | 启动像事务：失败时逆序关闭资源、关闭类加载器；租约与在途调用两个计数；停止时清理 Spring 反射与注解缓存 |
| `plugin/PluginManager` | 先启动后切换；退役由虚拟线程等计数归零；审批规则查看所有存活实例的清单 |
| `plugin/PluginToolCallback` | 不改工具定义和元数据；切换线程上下文类加载器、整理异常（受检异常也变成工具结果）、审计 |
| `plugin/PluginToolConfirmationPolicy` | 插件审批规则作为 `ToolCallConfirmationPolicy` bean，被官方自动配置用 or 组合 |
| `chat/ChatController` | `defaultTools(shellTools)` 注入 Bash，`.tools(...)` 追加插件工具；`Flux.using` 随流释放租约 |
| `chat/InteractiveToolCallingManager` | 装饰官方 `ToolCallingManager`：发出工具事件，在官方的确认暂停处就地等待审批 |
| `chat/ChatEvents` | 模型文字、旁路事件和心跳经同一个出口串行发出 |
| `static/index.html` | 网页：用 `fetch` 读取 POST 返回的 SSE 流（`EventSource` 只支持 GET）并自己解析 `data:` 行；模型和工具的文字一律用 `textContent` 显示，不会被当成 HTML；对话结束或点“停止”时，撤回没人回答的审批卡片 |

# Agent Voice Demo

后端阶段二基础：DeepSeek 对话与工具调用、数据库会话/待办、工具调用幂等记录、持久化任务/事件表、Outbox 到 SSE 事件补发，以及单机/Redis 精确滑窗限流。现有工具均为短操作，尚无配置为异步的远端长工具；生产认证和硬件接入仍由集成方提供。

## 环境变量

Java 17+、MySQL 8（默认 schema `agent_voice_demo`）、可选 Redis 连接配置，以及 DeepSeek API Key。数据库账号必须有权在该隔离开发 schema 中建表；应用启动时 Flyway 自动应用 `src/main/resources/db/migration`。

首次运行前由管理员创建专用空数据库，例如 `CREATE DATABASE agent_voice_demo CHARACTER SET utf8mb4;`。应用不会创建数据库，也不应连接生产或其他未知业务库。

必需变量：`MYSQL_USERNAME`、`MYSQL_PASSWORD`。其余服务配置见 `.env.example`。DeepSeek API Key 在首次打开页面时由用户输入，仅暂存在当前标签页的 `sessionStorage`，每次 Agent 请求通过请求头交给后端，不写入数据库或服务端配置。`DEEPSEEK_MODEL` 默认 `deepseek-flash`，请以账号可用模型为准。

本地启动：

```powershell
Set-Location -LiteralPath 'D:\work\agent-voice-demo'
$env:SPRING_PROFILES_ACTIVE = 'demo'
.\mvnw.cmd spring-boot:run
```

若 Maven Wrapper 在当前 PowerShell 不能运行，可使用安装的 Maven：

```powershell
mvn spring-boot:run
```

本地 `local` 和 `demo` Profile 均启用演示身份，通过 `X-Demo-User-Id` 隔离用户，不是生产认证；默认启动即使用 `local` Profile，因此内置页面无需额外登录。正式集成应关闭演示身份并由现有认证组件提供 `Principal`。应用仅在 local/demo Profile 连接 MySQL；`scaffold` Profile 用于无数据库健康启动，不加载业务接口。

## API

详细请求/响应字段、身份要求、错误处理及设备 WebSocket v1 协议见 [接口文档](docs/api.md)。

启动 `demo` Profile 后打开 `http://127.0.0.1:8080/` 使用内置聊天页，不要直接通过 `file://` 打开 HTML。页面采用深色布局，Mini 小猫作为助手标识；支持 Enter 发送、Shift + Enter 换行及移动端折叠侧栏。

第一次访问会提示输入 DeepSeek API Key，并提供开放平台获取链接；关闭标签页后需要重新输入。Key 只在 Agent 消息请求中通过 `X-DeepSeek-Api-Key` 请求头发送，服务端不保存。

左侧显示当前标签页创建的真实会话。会话 ID、标题和演示用户标识暂存在 `sessionStorage`，刷新后可恢复；点击会话时从后端分页读取消息。消息仍按原设计存储在后端数据库，密钥不存储。当前没有跨标签页或重新打开浏览器后列出全部历史会话的接口。“开启新对话”在首次发送时创建会话，不创建空白数据库记录。消息提交或历史加载期间禁止切换会话，避免消息显示到错误的会话中。

- `POST /api/sessions` 创建独立会话。
- `POST /api/sessions/{id}/messages`，请求 `{"requestId":"req-001","text":"算一下 12*(3+4)"}`，并带 `X-DeepSeek-Api-Key` 请求头。同一会话按 requestId 幂等，不能以相同 ID 提交不同文本；同步返回运行状态和答案。
- `GET /api/runs/{runId}` 查询运行结果。
- `GET /api/runs/{runId}/events?afterSeq=N` 以 SSE 订阅持久化运行事件；按序号补发后续事件，连接需由调用方重连。
- `GET /api/sessions/{id}/messages?afterSeq=0&limit=50` 分页读取会话历史。

响应错误包含 `code`、`message` 和 `traceId`；响应头也返回 `X-Trace-Id`。会话归属来自后端身份。工具包括 calculator、search、weather、todo_create、todo_list、todo_update；搜索和天气明确返回 `source=mock`。todo 更新采用 expectedVersion 乐观锁。

限流配置由 `app.rate-limit.mode` 选择 `memory`（默认、单实例）或 `redis`；可用 `app.rate-limit.limit`、`app.rate-limit.window` 和 `app.rate-limit.tenant-id` 设置额度。当前接口尚未默认强制配额，集成时应在认证后的入口调用 `SlidingWindowLimiter`；Redis 故障按失败关闭处理。

## 阶段三：设备与语音入口

Flyway V003 创建设备注册、挑战、访问令牌和语音轮次表。需在专用开发库插入设备记录，并为每台设备配置独立 32 字节 HMAC 密钥。密钥只从 `DEVICE_CREDENTIALS` 环境变量注入，格式为 `deviceId=Base64Key;deviceId2=Base64Key`；数据库 `credential_ref` 使用对应 deviceId，不保存密钥明文。未知、禁用或未配置凭据的设备统一认证失败。

本机首次登记示例（先启动应用使 Flyway 建好表）：

```sql
INSERT INTO device_registry(device_id,sn,credential_ref)
VALUES ('dev-001','DEMO-SN-001','dev-001');
```

在受保护的本地环境生成并配置独立密钥，不要把生成值提交到仓库：

```powershell
$deviceKey = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:DEVICE_CREDENTIALS = "dev-001=$deviceKey"
```

挑战接口为 `POST /device/auth/challenge`，请求 `{"deviceId":"dev-001","sn":"DEMO-SN-001"}`。Token 接口 `POST /device/auth/token` 要求带回 nonce、challengeId、当前 Unix 秒时间和 HMAC-SHA256 十六进制签名。签名原文是 UTF-8 文本 `v1\n{deviceId}\n{sn}\n{challengeId}\n{nonce}\n{issuedAt}`。挑战一次性消费，Token 为随机不透明值，存储时只保存 SHA-256 摘要。

WebSocket 地址为 `/device/voice`，必须在握手 `Authorization: Bearer <accessToken>`。设备先发 `version=1,type=start` 控制消息。ModelA 音频为 56 字节大端帧头加 PCM16 小端 payload；ModelB 音频为 JSON `FRAME` 事件，payload 使用 Base64。两者当前统一要求 16kHz、单声道、20ms（640 字节）PCM16 帧。支持 start、resume、end、cancel、command_ack、playback_finished；旧 `result_ack` 仅用于不带播报命令的 final。收到带 command 的 final 后，设备必须按 commandId 去重执行，并 ACK 命令、在播放结束后另发完成事件。该阶段需要同步升级设备客户端；只认识 result_ack 的旧客户端无法正常释放含播报命令的轮次。服务端只保证节点内存音频的短期续传，节点状态丢失时要求设备使用原 turnId 和新 attemptId 全量重放。

阶段四提供 ModelA（二进制 PCM 帧）与 ModelB（JSON/Base64 PCM 帧）协议适配、MockAsrA/MockAsrB、RMS 能量 VAD、端点状态协调、模拟对话与合法静音 WAV 播报。模拟识别文本来自 fixture“模拟识别输入”，不是真实语音识别；模拟播报不是真实 TTS。ASR 默认由 `VOICE_ASR_PROVIDER=MOCK_A` 选择，也可设为 `MOCK_B`；板卡协议由 `VOICE_BOARD_MODEL=MODEL_A` 或 `MODEL_B` 选择。`VOICE_REOPEN_POLICY=WAIT_WAKEUP` 默认播放后回到 IDLE；设为 `AUTO_LISTEN` 时，播放完成会创建新 turnId/attemptId，沿用设备的模拟对话 sessionId，并发送 `START_LISTENING`。V004 记录设备对话 session、delivery 与 device command，命令载荷中的音频是小型 Base64 测试 WAV。设备收到 final 中的 `command` 后先发 `command_ack`，播放结束后发 `playback_finished`；确认播放完成才释放轮次。旧的 `result_ack` 仅用于没有待播放命令的 final。

网关按 `VOICE_BOARD_MODEL` 选择协议适配器；设备注册表尚无逐设备机型字段，因此当前使用实例级选择。`ws://127.0.0.1` 仅用于本机开发，部署必须通过 TLS/WSS 终止代理传输。音频由节点内有界缓冲保存，默认单轮最多 960000 字节、节点同时 64 轮。EnergyVad 是基础 RMS 阈值算法，需使用目标硬件录音校准；无帧不会计作静音，5 秒无语音会独立失败，30 秒服务端硬截止。

## 构建与验证

录音和识别收尾使用不同的截止时间：V005 新增 `processing_deadline_at`。end、静音端点或 30 秒硬截止首先通过数据库条件更新将 RECORDING 改为 PROCESSING；仅该赢家向有界 TaskWorker 提交 ASR `finishInput`。`VOICE_FINALIZATION_TIMEOUT_MS` 默认为 10000，必须大于 0，包含工作队列等待时间。当前 attempt/owner 的 final 必须在收尾截止前提交，保存后才调用对话和生成播报；重复提交不增加 `final_version`。收尾期间拒绝新音频，取消、失败或接管后的迟到结果不能改变数据库及后继轮次。

ASR 明确失败返回 `ASR_FAILED`，收尾超时返回 `ASR_FINAL_TIMEOUT`，工作队列满返回可重试的 `ASR_BUSY`；均释放活动轮次和节点音频/ASR 状态。中断 ASR 是尽力行为，提供方必须支持快速 cancel/close；数据库状态及 owner/attempt 校验仍会拦截忽略中断的迟到结果。PROCESSING 暂不支持重新绑定连接，设备可等待原轮次结束后恢复 FINAL，或等待超时后发起新轮次。对话会话单独保留，不跟随音频状态清理。精确截止任务触发状态推进，5 秒数据库及网关扫描兜底；定时线程不执行阻塞的 `finishInput`。

V005 部署使用停机切换：停止接入并确认活动轮次已排空，应用新增列及索引，再启动新代码；不支持新旧节点混跑。历史 PROCESSING 行在迁移时回填迁移时刻后 10 秒的有限截止，执行前应核对数量并确认处理策略。本次开发迁移前活动轮次为 0。代码回滚前先停止接入并排空/终止 PROCESSING，保留 V005 新列，不删除或修改已应用的迁移。

```powershell
mvn test
mvn verify
.\mvnw.cmd verify -Pvoice-simulator-it
```

`voice-simulator-it` 执行 ModelA/ModelB × MockAsrA/MockAsrB 四种本地组合，以及 VAD 与 WebSocket 入口测试；无需连接真实 ASR、TTS 或 LLM。V004 需在应用启动时通过 Flyway 应用到隔离开发库。

任务 4 定向验收命令为 `./mvnw.cmd -Dtest=VoiceFinalizationIT,VoiceFinalizationWebSocketIT,VoiceStateMachineTest,EndpointDetectorTest test`。这些 IT 需要真实隔离 MySQL 并会应用 V005，使用唯一测试设备、按确切 ID 清理数据。WebSocket IT 实际等待一次 30 秒硬截止，同时用 1 秒测试收尾预算和可控模拟 ASR 验证超时、忽略中断的迟到结果、取消及工作队列满载；这不代表真实 ASR/TTS 性能或两秒首段音频指标已验收。

数据库迁移及真实模型调用需要本地服务凭据。真实演示示例：先创建会话，再发 `{"requestId":"req-002","text":"算一下 12*(3+4)，并创建明天带伞的待办"}`；待办日期需要模型追问/补全到带时区时间，服务端不会猜测用户时区。模型和数据库实际可用性需由本机凭据与服务验证。

## 目录

```text
src/main/java/com/example/agentvoice/  Spring Boot、LLM、Agent、工具、会话和 Trace
src/main/resources/db/migration/       Flyway SQL
src/main/resources/prompts/            Agent 和摘要 Prompt
src/test/java/                          协议、工具和计算器单元测试
```


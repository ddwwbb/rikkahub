# 豆包双工语音与 ai-voice-server / RikkaHub 对照

## 结论与证据边界

ai-voice-server 的现有 ASR/TTS 能作为应用级双工的基础，但当前组合不是开箱即用。内置豆包实时 ASR 加独立流式 TTS 可被编排成边听边说、可插话的交互；它不等于豆包原生语音模型的声学语义联合能力。RikkaHub 当前接入方式仍是 MiMo 格式分段 HTTP 识别和 OpenAI 格式整段音频读取，需要修改客户端及实时识别协议，不能仅靠换模型名实现。

本文是研究与优化建议，不修改既定 ADR、实现或已确认行为。官方性能表述是厂商声明；建议均为 [INFERENCE]，不代表豆包内部实现。没有读取实际凭据或调用付费上游，没有双工真机性能结果。

## 实施后的使用与验证

本文下方“当前阻塞点”记录研究时状态。用户随后授权实施，相关路径已改造：RikkaHub 新增 Voice Gateway ASR、持续话语事件、WebRTC 辅助人声起点、插话轮次调度和独立流式音频播放；网关 `/v1/asr` 新增 `start.mode=voice`，最终文字仍等待真实上游完成事件。

接入时，ASR 选择 **Voice Gateway**，WebSocket URL 填 `ws://电脑IP:8080/v1/asr`（或 TLS 代理 `wss://`）；API Key 使用网关访问密钥。TTS 选择 **OpenAI**，Base URL 填 `http://电脑IP:8080/v1`，选择网关已启用的 TTS 模型别名和音色。保留当前聊天模型与助手，在聊天菜单开启语音模式。MiMo HTTP ASR 桥接仍可用于听写，不是持续双工接法。

已观察验证：网关 121 项本地协议测试及 fmt/clippy 通过；App 245、speech 57、ai 198 项 JVM 单测（共 500 项）通过；App APK 构建在排除缺失 Firebase 配置任务后通过。Android 模拟器真实 MP3/AAC/PCM 解码、HTTP EOF 前播放、停止断连和旧音频不复活共 3 项仪器测试通过；真实 AudioRecord 经本地 WebSocket 传输 PCM、final 保存/关闭、WebRTC 原生加载与无 AEC 设备拒绝外放的测试类共 3 项通过，其中无 AEC 检查按设备能力有条件执行。另通过临时入口渲染实际 VoiceModeRow 和网关配置表单，验证字段编辑，入口随后删除。录音测试文字来自本地协议 fixture，不证明真实语音转写准确率。

此前完整 App 因缺 `google-services.json` 无法初始化 Firebase.analytics；随后已移除 Analytics、Crashlytics 及其构建插件，完整 Debug APK 构建与 245 项 App JVM 单测通过。模拟器已正常进入聊天页，并通过长按发送保存用户消息；未进行真实模型回复或完整双工语音 UI 联调。没有手机耳机/外放声学验收、没有 100 次延迟采样，P95 两项目标仍未验证。系统 AEC 启用只能证明能力存在，不能证明实际抑制效果。任意输出正则/引号过滤配置等待最终正文，不能声称这些配置也实现低时延增量朗读。

已按用户指定 `home.u2m.top:18080` 做实际服务联调：鉴权成功，模型为 `doubao-asr`、`doubao-tts`、`edge-tts`；豆包 TTS 返回可解析的 24 kHz 单声道 MP3，旧 ASR 完整转写成功。最初旧镜像拒绝 mode=voice，用户授权后已更新为 `ai-voice-server:h66k-duplex-vad-20261003`，容器 healthy，旧容器和配置保留可回滚。真实测试纠正了上游终止事件解析：同时 is_interim=false/is_vad_finished=true 时结束上游，final 仍等待真实完成；同一 WebSocket 连续两轮自动 speech_ended/final 验证通过。网关新增回归后共 122 项通过。单次 TTS 首块 492 ms 的早期观测不是手机首音或 P95；Firebase 配置阻塞已解除，手机完整双工链路与声学验收仍未完成。



## 1. 豆包公开实现

字节在 2026-04-09 的 [Seeduplex 官方技术博客](https://seed.bytedance.com/en/blog/introducing-seed-full-duplex-speech-llm-attentive-listening-robust-interference-suppression-enabling-more-natural-interaction) 明确宣布该模型全量上线豆包 App，并将前代端到端语音方案称为半双工。端到端、传输同时收发、能打断、原生全双工不是同义词。

公开机制是：连续接收用户音频，流式提取声学特征，由模型联合音频、语义和对话上下文判断开始回答、继续聆听或响应打断；训练优化节奏、抗干扰和指向性理解，推理使用投机采样和量化。它不只是独立 VAD 断句后调用文本 LLM。官方没有公开输入输出 token 结构、codec、客户端 AEC、缓冲清理和历史截断实现，不据此编造双流网络或回声算法。

官方宣称相比前代判停延迟降低约 250 ms、打断响应延迟降低约 300 ms，复杂场景误响应/误打断率减半；这些是相对改善，不是绝对首音时间或 P95，不能作为当前项目的性能承诺。2026-08-05 官方另发布 [SeedRealtime 音视频全双工模型](https://seed.bytedance.com/en/blog/seedrealtime-audio-visual-full-duplex-llm-released-toward-omni-modal-natural-interaction)，因此 Seeduplex 不宜称为所有实时方向的最新型号，也不能混用不同模型的 App/API 能力。

### API 可借鉴能力

[全双工实时 API 3.0](https://docs.volcengine.com/docs/6561/2549778?lang=zh) 使用固定 `session.model=1.2.6.1`，持续 `input_audio_buffer.append` 输入与 `response.output_audio.delta` 输出，推荐 16 kHz PCM 每 20 ms 一包（640 字节）。`response.cancel` 取消服务端响应，但不代替清空设备播放缓冲。识别事件是 `conversation.item.input_audio_transcription.started/delta/completed`；started 表示识别首字，不等于纯声学起点，不能照搬其他 API 的 speech_started/stopped 名称。

3.0 提供 `speech_text_buffer.replacement.append/commit` 外部文本替换和工具调用，但未证明内部 LLM 可任意替换、只做 ASR 或没有回答竞态。它自身有会话和历史管理；接入它不等于自动继承 RikkaHub 助手、记忆和审批。旧 API 2.0 的 `ConversationTruncate` 不应直接承诺为 3.0 的音频时轴截断能力。

更贴合保留当前助手约束的是 [独立双向流式 TTS V3](https://docs.volcengine.com/docs/6561/1329505?lang=zh)：向 `/api/v3/tts/bidirection` 持续发送 `TaskRequest(200)` 的 `req_params.text`，同时接收 `TTSResponse(352)`；通过 `CancelSession(101)` 取消并等待确认，连接可串行复用会话。官方建议直接输入外部 LLM 增量文本，由服务端处理文本碎片。双向 TTS 是增量文本输入与音频输出，不是 ASR/TTS 整个助手已经双工。

## 2. ai-voice-server 当前支持边界

依据相邻仓库的 [能力表](../../../ai-voice-server/docs/providers.md)、[实时 ASR 协议](../../../ai-voice-server/docs/api.md) 和 [客户端接入](../../../ai-voice-server/docs/clients.md)：

| 当前适配 | ASR | TTS | 双工结论 |
|---|---|---|---|
| 内置 doubao | `/v1/asr` 真实流式输入和中间文本；输入法协议 | VoiceGenie 音频流，MP3/AAC | 可作为级联基础，但不是 Seeduplex |
| volcengine | 当前为 HTTP flash 文件识别，非实时 WS | V3 SSE 转音频流，MP3/PCM/Opus | TTS 可用；ASR 适配需增补实时路径 |
| mimo | 文件识别；chat/completions 音频桥接 | PCM 可增量；MP3/WAV 完整上游返回后输出 | 更偏向批处理；PCM 有利于低延迟 TTS |
| dashscope | 当前 Qwen3 ASR flash HTTP 文件识别 | CosyVoice/Qwen-Audio SSE 音频流 | TTS 可用；ASR 适配需增补实时路径 |
| edge | 无 | MP3 流式合成 | 可做输出侧，仍需要实时输入侧 |
| openai | multipart 兼容文件识别 | speech 响应流透传，实际能力取决于上游 | 不包括完整 OpenAI Realtime |

这些是适配器能力，不等于厂商全部模型的能力。`whisper-1`、`tts-1` 是本地别名；默认 `whisper-1` 路由内置豆包 ASR，不证明运行 OpenAI Whisper。未读取真实部署配置，不能声称外部 provider 已开启。

### 实时 ASR 还缺什么

[src/asr/mod.rs](../../../ai-voice-server/src/asr/mod.rs) 当前只有 `start` 和 `finish` 控制消息，`finish` 后不能继续发送音频，等 `SessionFinished/TaskFinished` 才返回 final 并结束连接。默认会话最长 300 秒，不是无限持续通话。

[src/asr/transcript.rs](../../../ai-voice-server/src/asr/transcript.rs) 会解析上游 `vad_start` 和 `is_vad_finished`，但网关只输出 `interim/segment/correction` 的累计全文；声音起点没有独立事件，文字不变时也可能不输出事件。segment 还可能被后续校正，不能直接提升为最终转写。需要按实际上游语义设计开始讲话、句终、最终文本及修订事件；若上游最终结果只能在结束 session 后可靠取得，则需要诚实处理 session 周转，不能伪造持续会话 final。

### TTS 的可复用部分

[src/tts.rs](../../../ai-voice-server/src/tts.rs) 和 [src/gateway/http.rs](../../../ai-voice-server/src/gateway/http.rs) 已有有界音频队列及 `sender.closed()` 断连取消。它能增量交付音频，但当前 speech 请求一次提交完整 input，不接受同一个请求里不断追加 LLM 文本。取消能停止网关继续处理，不能证明厂商撤销了已完成计算或计费。

## 3. RikkaHub 的具体阻塞点

1. [VoiceSessionController](../../app/src/main/java/me/rerere/rikkahub/ui/pages/chat/VoiceSessionController.kt) 播报前 cancelAndJoin 收音，播完等 300 ms；listen 每句结束暂停录音并 dispose。ASRVoiceTurn 只容纳一条话语，不能直接变成多轮持续流。
2. [ASRProviderSetting](../../speech/src/main/java/me/rerere/asr/ASRProviderSetting.kt) 只允许 OpenAIRealtime/DashScope/Volcengine 进入当前语音模式。接 ai-voice-server 的 MiMo 桥接不在支持集合中，网关 WS 又不兼容 OpenAI Realtime 协议。
3. [OpenAITTSProvider](../../speech/src/main/java/me/rerere/tts/provider/providers/OpenAITTSProvider.kt) 调用 `response.body.bytes()`，完整读完再 emit；即使网关在流式输出，App 仍整段等待。阻塞读取也没有显式把协程取消挂到 OkHttp Call.cancel，不能假设取消合成会立即断开网关请求。
4. [TtsSynthesizer](../../speech/src/main/java/me/rerere/tts/controller/TtsSynthesizer.kt) 再将所有 AudioChunk 收集成 ByteArray；[AudioPlayer](../../speech/src/main/java/me/rerere/tts/controller/AudioPlayer.kt) 每段重新 prepare，PCM 还先包完整 WAV。需要贯穿网络、解码和播放的真正流式路径。
5. [TtsController](../../speech/src/main/java/me/rerere/tts/controller/TtsController.kt) 按 160 字聚段、预取两段并插入 120 ms 段间等待；它另有合成重试和出错继续下一段逻辑。双工方案要求失败停止且不自动重试，语音路径不能盲目复用这些行为。
6. [VoiceMode](../../app/src/main/java/me/rerere/rikkahub/ui/pages/chat/VoiceMode.kt) 焦点在 ASR wrapper 内，未设置失焦监听；收音和播放器需要由整个语音会话统一拥有音频模式、路由和焦点，而不是录音释放即放弃。
7. 部分输出变换对累计全文重做正则替换，并非单调追加。流式朗读需要稳定正文提交，不能把 UI 快照简单 substring 当 delta，否则后续改写不能撤回已经读出的文本。

## 4. 优化优先级（建议，不改已选决策）

### P0：先补正确性

- 持续收音、播放与生成分开调度；采用话语/回答轮次身份，旧轮迟到文本和音频不能重新进入当前播报。停止音频、取消 TTS 网络请求、取消旧 LLM 生成必须分开。
- 统一会话音频管理，验证实际回声效果。Android [AcousticEchoCanceler](https://developer.android.com/reference/android/media/audiofx/AcousticEchoCanceler) 绑定 AudioRecord session，系统可能默认启用，`isAvailable/enabled` 都不代表效果已经通过。火山 [ASR SDK](https://docs.volcengine.com/docs/6561/1395846?lang=zh) 也明确系统 AEC 依赖硬件与 OS；模型抗干扰不等于云端已消除扬声器回声。
- 提供不依赖最终转写的 speech onset 信号：优先已有服务端起点，受网路影响时评估本地经过回声处理的 VAD。VAD 不单独用于判定指向性或最终提交。
- ASR 输出事件保留上游身份、句边界和修订含义。不要把累计文本变化、segment 和真正 final 混为一谈；不要用字符串差分伪造最终用户内容。

### P1：减少串行等待

- 先去掉完整 LLM 回复等待，再打通真实音频边收边播；仅把文本切短不能消除 body.bytes 与 collectToResponse 两次完整缓冲。
- 单向 TTS 使用更短首段、后续较长稳定片段；支持双向文本输入时直接送稳定增量正文，不额外按 160 字攒段。保留思考、工具、Markdown、正则与引用过滤的正确性。
- 根据服务协议降低发送帧长并把积压控制为可测的时间预算，不只按字节数限流。当前 OpenAI/DashScope 录音缓冲下限分别约 100/128 ms，火山约 200 ms；实际由 getMinBufferSize 决定。100KB Base64 队列量级可对应约 1.56/2.34 秒 PCM（忽略 JSON 开销，非实测），明显大于 500 ms 打断预算。不能遇到积压仅静默丢帧而继续宣称成功。
- ASR/流式 TTS 长连接在上游支持时复用，不每句话重建；不得绕开网关 300 秒限制或在断线时无授权自动重试。

### P2：接近豆包节奏，需重新决策

- 当前确认“所有非空短话语都提交、检测人声即停播、空转写不续播”，简单可预测，但更容易被附和或误判打断。区分候选人声、真实接管、附和、旁话，能改善自然度，却会改变已选行为；必须重新确认，不能以优化名义静默实现。
- 语义判停可减少句中思考时抢话，但与沿用服务端断句的已选规则存在取舍。优先利用上游明确支持的语义判停，避免为了判断说完另加一个每轮云端 LLM 调用。
- 保留已生成文字及中断标记仍合理，可补“最后完成播放的稳定片段”作为粗粒度收听范围，不假称逐字已听到。
- 在首音/打断延迟之外，记录误打断、漏打断、抢话、丢话、自答循环和段间卡顿。支持组合应按设备与音频路由明确；不支持外放时按既定 Q7=A 提示耳机。

## 5. 验证与未验证

已用相邻项目现有 debug 二进制启动独立回环服务 `127.0.0.1:18973`，清空环境、不加载真实配置或凭据，观察：`/v1/models` 返回默认 `edge-tts`、`tts-1`、`whisper-1` 及各自 provider/task；`/v1/realtime` 返回 404。检查完成已停止该服务。这证明实际默认模型注册与路由边界，不证明真实部署配置、上游 ASR/TTS 能用或双工已成立。

官方正文通过技术博客和火山文档官方内容接口核实；没有厂商调用、Android 真机录放或延迟采样。因此已选 P95 首音 2 秒、打断 500 ms 仍是待验证目标。

# AI Incident Diagnosis Agent — Tuning Log（v1 → v13）

> 本页记录这个 AI 系统如何被逐步调"准"：每轮改了什么、观测到什么、为什么那样改。
> 配套脚本：`docs/eval/run_baseline.sh`（评估）、`docs/eval/check_grounding.py`（引用真实性校验）
> 数据：`docs/eval/baseline_results_{v1..v13}.csv`、PostgreSQL `incidents` 表（可回查每条原始分析）
> English version: [docs/TUNING.md](docs/TUNING.md)

---

## 0. 方法论：为什么 AI 系统需要 tuning

**评估驱动开发（evaluation-driven development）**：不训模型，而是**测系统级的推理行为**。

| 概念 | 本项目的落地 |
|---|---|
| **Ground truth** | 注入已知故障（error/latency/memory）→ 正确答案是"已知的" → 用来自动打分。**measure, not train** |
| **评估集（eval set）** | `docs/eval/eval-cases.json` —— **12 个场景 / 19 个被打分告警**：4 个单故障（error/latency/memory/downstream）、5 个并发双故障（10 告警）、2 个时序重叠 staggered（4 告警）、1 个 healthy 对照；每个告警都有按 alertname 的 ground truth |
| **评估脚本** | `run_baseline.sh`：注入故障 → 压流量 → 等 AI 分析完成 → 拉 Postgres → 写 CSV |
| **隔离（settle）** | 每个 case 前等告警清空 + 30s，防止上一个 case 的"脏数据"污染下一个 |
| **Grounding check** | `check_grounding.py`：逐字验证每条 `[metric:]`/`[log:]` 引用是否真实存在于检索上下文 |

**核心观点**：*"Evaluation should be like a grading rubric — fixed fault injections with known answers; verify not just whether the diagnosis is right, but whether the cited evidence actually exists."*

---

## 1. 评估的故障场景（Ground Truth）

| 故障 | 注入方式 | 期望告警 | 期望根因关键词 |
|---|---|---|---|
| error | POST /api/orders 返回 500 | HighErrorRate | 连接池耗尽、SQLTransientConnectionException |
| latency | 所有请求延迟 2-5s | HighLatency | p99 latency、delay |
| memory | 内存泄漏 ~10MB/s | HighMemoryUsage | heap memory、jvm |
| downstream | 下游依赖（payment service）超时 → 502 | HighErrorRate | 下游超时、502（与 `error` **不同**的根因） |
| **healthy 对照** | 只发合成告警、**不注入故障** | HealthyCheck | "no anomaly detected"、confidence ≤ 0.5（一个正确的"非事故"） |

场景还以两种方式组合：**并发（concurrent）**（两个故障同时注入 → 两条独立告警）与 **时序重叠（staggered）**（先停 A、在 A 的日志还停留在 Loki 窗口内时触发 B——时序隔离测试）。

---

## 2. 每轮调教记录

### v1 — 首跑（Baseline）
- **改动**：无（初始系统）
- **结果**：error 命中（0.7）；**latency 被残留 HighErrorRate 抢先分析 → 根因错指 error；memory 被残留 HighLatency 抢跑 → 根因错指 latency**
- **发现**：**评估 case 互相泄漏（cross-case pollution）**——上一个 case 的告警没 resolve 就注入下一个
- **修复**：settle 隔离（每个 case 前轮询 Prometheus 等无 firing + 30s 滑过 rate 窗口）
- **教训**：评估环境必须隔离，否则"测的是上一场的尾巴"

### v2 — settle 修复 + 证据引用 Prompt
- **改动**：settle 隔离生效；system prompt 加强制引用规则（`[metric: ...]` / `[log: "..."]`）
- **结果**：**3/3 命中**（error 0.7 / latency 0.6 / memory 0.7）
- **发现（事后）**：⚠️ **引用全是编造的**——查 Postgres 原始 `related_logs`，全部是 `"Loki search error: 400 Bad Request: parse error ... invalid char literal"`！LLM 引用的 `[log: "RuntimeException occurred..."]` 在上下文里**根本不存在**
- **教训**：**格式合规 ≠ 接地（format compliance ≠ grounded）**。命中率看着 100%，其实日志证据链从第一天就是坏的

### v3 — 证据窗口 15min→5min + 温度 0.3→0.1（❌ 双变量）
- **改动**：同时改了两个变量（窗口缩短 + 温度降低）
- **结果**：error 0.8 / memory 0.7 稳定；**latency 0.8→0.6 退化**（根因偏到 "database queries"）
- **教训**：**一次只改一个变量（change one variable at a time）**——退化无法归因

### v4 — 重跑（❌ 假修复）
- **改动**：无（以为 Loki 已修复）
- **结果**：**还是全 400**——修复没进 v4 用的容器
- **教训**：**改了代码必须端到端验证（build → run → check real output）**，不能只手动 curl 验证依赖服务

### v5 — 🎯 修复 Loki LogQL 单引号 bug（真正转折点）
- **改动**：`{service='demo-app'}` → `{service="demo-app"}`（**Loki LogQL 只接受双引号**，单引号报 "invalid char literal"）
- **结果**：**HTTP 400→200，streams 0→1**；3/3 命中；**引用从 0% 真实 → 100% 逐字真实**（metric 3/3、log 2/2，用 grounding checker 验证）
- **发现**：这个 bug **从第一天就在**——v1/v2/v3 的日志检索全部失败，metrics 链路一直好，所以命中率一直"看着还行"
- **教训**：**一个字符能毁掉整条证据链**；评估必须验证"证据是否真实存在"，而不是只看输出格式

### v6 — correlation window（把日志窗口锚定到告警时刻）
- **改动**：Loki 查询从 `now - 5min`（滑动窗口）改为 **`[alert.receivedAt - 2min, alert.receivedAt]`**（相关窗口）
- **结果**：error **0.8→0.9**（污染彻底消除）；latency **0.5**（窗口内无 ERROR 日志 → 正确校准降置信）
- **发现**：滑动窗口会把**上一个 case 的残留日志**带进当前分析（v5 latency 引用到 error 的 "database connection timeout"——引用真实但**不相关**）
- **教训**：**根因发生在告警之前——应该查告警时刻之前的窗口，不是"现在往前推"**；无证据时 AI 正确降置信（confidence calibration）也是好行为

### v7b — alert-aware 检索（工具签名适配任务）
- **改动**：检索关键词由**告警类别驱动**——latency 告警除了 ERROR/Exception 还查 `sleeping`/`Latency fault`/`timeout`（demo-app 的 latency 故障是 WARN/DEBUG 日志，不是异常堆栈）
- **结果**：latency **0.5→0.9**！LLM 逐字引用了故障注入日志：`[log: "FAULT INJECTION: Latency fault ENABLED - all requests will have 2-5s delay"]`
- **结果**：**3/3 命中**（error 0.8 / latency 0.9 / memory 0.7），**grounding 100%**（metric 3/3、log 2/2）
- **教训**：**工具的函数签名（这里=搜索关键词）应该适配任务语义**——只搜 ERROR 会漏掉 latency 案的全部证据

### v8 — 复合故障 3×2：检索层判别 + prompt 判别指令（判别力 discrimination）
- **背景**：评估集扩到 3 个复合故障 case（error+latency / latency+memory / error+memory）——两告警独立投递、两个 Incident 各自命中各自根因。第一轮复合 baseline 暴露：**HighLatency 被并发 error 故障的异常堆栈污染，错判根因 = "database connection timeouts + RuntimeException"（confidence 0.8 自信错判）**。机制：latency 检索词 "timeout" 恰好命中 error 异常信息 "database connection timeout (simulated)"，异常堆栈被检索进 latency 上下文。
- **改动（治本）**：
  - latency 关键词去掉通用 `timeout`，只用故障注入独有标记（`sleeping` / `Latency fault`）
  - 非 error-rate 告警在 **LogQL 层排除 `!= "RuntimeException"`**（并发 error 故障的异常堆栈不再进入本告警上下文）
- **改动（兜底）**：system prompt 新增规则 8/9——**判别指令**（证据必须与本告警的指标签名一致：error-rate↔error rate / latency↔p99 / memory↔heap；竞争信号写入 `competing_signals_observed`，不得成为主根因；无法排除时 confidence 封顶 0.5）+ **显式三步推理**（本告警证据 → 竞争信号甄别 → 结论）
- **改动（可评估）**：输出新增 `competing_signals_observed` / `evidence_alignment` 字段（判别力可量化）；`run_baseline.sh` 支持复合注入与双告警等待
- **结果**：**9/9 命中**（单故障 3/3 + 复合 3/3）；**grounding 100%**（metric 8/8、log 8/8）
  - 关键翻正：复合 error+latency 中 HighLatency **0.8 错判 → 0.9 正确**（根因 = "delay 2-5s"），`evidence_alignment=consistent`，`competing_signals=[]`——检索层修好后**模型根本没有到竞争信号**（治本优于兜底）
  - 单故障对比 v7b：error 0.8→0.9、memory 0.7→0.8、latency 0.9 保持（无退化）
- **新发现**：#94（error+memory 复合中 HighMemoryUsage）根因正确但 confidence **0.4**（只有 metric 证据、无 ERROR 日志 → 按规则弱校准）。与上一轮同场景 0.7 存在波动——**正确降置信是校准正确的表现，但置信度稳定性待观察**（LLM 输出波动 + 新规则更保守）
- **教训**：**判别力 = 检索隔离（上游，别把脏证据喂进来）+ 指标签名核对（下游，让模型会识别）**；修好上游后，下游的兜底指令几乎不需要触发（#85 未观察到竞争信号）

### v9 — 证据链可执行化（citation index + 后端映射，LLM 不生成 query）
- **背景**：v8 判别力达成后，"工程可信度"还剩最后一个缺口——**LLM 输出的证据引用是"文本"（`[metric: ...]` / `[log: "..."]`），不是"动作"**。分析结论引用了哪个查询？怎么点开复现、核验？这些没有结构化答案。而如果让 LLM 自己生成查询链接，它可能编造（hallucinate）。
- **改动（治本）**：**让 LLM 引用"证据的编号"，而不是生成"证据的内容"**——
  - 工具层：`LokiToolService` / `PrometheusToolService` 在每次**真实查询成功后记录 QueryRecord**（PromQL/LogQL + 命中数 + 时间窗）——后端是查询的 **single source of truth**
  - 编排层：`AlertAnalysisService` 把真实查询构建成 **EVIDENCE INDEX**（`[E1]`..`[En]` 编号 + 真实查询 + 摘要），随 prompt 喂给 LLM
  - prompt 层：system 规则 10 —— 引用必须带 `[E{n}]` 编号；**禁止发明编号、查询、URL**（只有 index 里的可解析）
  - 解析层：提取 LLM 输出中的 `[E{n}]` → 后端映射 → `evidenceCitations` JSON（真实 query + **Grafana explore 深链 URL** + resolved 标志），持久化到 Incident
- **防伪设计**：LLM 引用 index 里不存在的编号 → `resolved=false`，**不产生任何 query/url**——模型永远无法注入假证据（可信度由后端结构保证，不依赖模型诚实）
- **结果**：全量回归 **9/9 命中（error 0.9×4 / latency 0.9×3 / memory 0.8×2，与 v8 完全持平，无退化）**；grounding 保持 100%；集成验证（incident #96）：LLM 引用 `[E3]` → 后端映射 `rate(http_server_requests_seconds_count{job='demo-app',status=~'5..'}[5m])` + Grafana explore 深链
- **新增测试 2 个（共 7/7）**：①编号解析映射成功（resolved=true，query/url 来自后端）②伪造编号防御（`[E99]` → resolved=false 且无任何 query）
- **教训**：*"trust the index, not the model's memory"* —— 证据可信度应该靠**架构保证**（编号→真实查询的映射在后端），而不是靠**提示词约束**（让模型自觉诚实）。引用是选择（selection），生成是创作（generation）——让模型做前者，让后端做后者

### v10 — 扩大评估集：12 场景 / 19 告警
- **背景**：3 个故障上的 9/9 看似饱和，但只覆盖了同类别的单/复合故障。评估集扩到 **12 场景**，新增：**downstream** 故障（依赖超时→502，同样触发 HighErrorRate 但根因不同）、**staggered** 时序重叠（停 A 后趁 A 的日志还在窗口内触发 B）、以及 **healthy 对照**（什么都没坏只发一条合成告警）。
- **结果**：旧场景根因保持正确，但新场景立刻暴露了校准缺口（memory 置信度、healthy 判定、残留日志归属）。
- **教训**：**9/9 通过只说明评估集太小，不代表系统做完了**。只有新增"故障类别 / 时序 / 显式负样本"这类新场景，才能暴露新的失败模式。

### v11 — 时序重叠（staggered）判别
- **改动**：针对相关窗口仍含上一故障日志的情况强化判别，并收紧编排，让每条告警只按自己的指标签名打分。
- **结果**：在残留 error 日志上触发的 latency、在残留 downstream 日志上触发的 error，都被正确归属。
- **教训**：最难的竞争信号是**真实但已过去**的那次事故留下的证据——检索隔离 + 签名核对，比叮嘱模型"小心点"有用。

### v12 — 按类别限定检索（category-scoped retrieval）+ healthy 校准
- **改动**：每次工具调用都按本告警类别限定范围（检索词、日志过滤、runbook 匹配）；prompt 明确把 healthy 判定定义为低置信的"非事故"结果。
- **结果**：**19/19 根因正确**，包括 healthy 对照正确回答"无异常"。
- **教训**：**按告警类别限定喂给模型的证据**；并且记住 confidence 描述的是"事故"，不是模型对自己那句话的确信度。

### v13 — 逐字 grounding、置信度语义、确定性护栏
本轮本想"修两个小瑕疵"，但 100%-grounding 自检逼着逐层挖出四个更深、且都有证据支撑的修复（每轮只动一个变量、单独重测，留档于 `baseline_results_v13_round1..4.csv`）：

- **v13a — 日志引用格式（外壳问题，不是幻觉）。** error 类没有一条 checker 认得的引用，因为 prompt 从没教过精确的 `[log: "<verbatim>"]` 双引号格式，模型自发用单引号转述。引用内容真实、但无法解析。→ 教会两种机器可校验格式并要求逐字复制。
- **v13b — memory 置信度规则本身错了。** 旧规则要求一条同类故障日志（OutOfMemory），但内存在 OOM 之前根本不会打这条日志。→ 改为按告警的**主指标（primary signal）**校准：精确引用且超阈值的 heap 值本身就足以支撑高置信。
- **v13c — grounding checker 反过来抓到了我自己监控查询的 bug。** error-rate grounding 卡在 95%：模型把两条 5xx 每秒速率 series（0.077+0.091）相加，引用了查询从没返回的总数。根因：工具的 PromQL 是 `rate(5xx)`——**按 series 拆分的每秒请求数，不是比率**（只在单故障时碰巧像 70%）。→ 改成与告警完全一致的 `sum(rate(5xx)) / sum(rate(all))` 单标量，并在 prompt 禁止跨 series 做算术。
- **v13d — 强制引用召回（recall）。** latency+memory 复合中，latency 注入日志**已被检索到却没被引用**（证据在手、没进证据链）——这是**召回**缺口而非精确率缺口。→ 改为"只要有匹配本告警 signature 的日志就必须引一条"，复合故障内同样适用。
- **v13e — 置信度语义 + 确定性护栏。** 一次 healthy 跑成了 0.9（"我很确定它健康"），memory 在引用 heap≈0.987 的情况下置信度仍在 0.4–0.7 间漂移。两处改动：(1) prompt 把 confidence 定义为**"此刻正在发生一起真实、需要处理的事故的概率"**，加 HEALTHY override（正常值→0.3–0.5），并重写规则区分"已确认的并发故障"（不压低本告警分数）与"无法解释的冲突信号"（封顶 0.5）；(2) 后端加一个很窄的**护栏** `applyHeapConfidenceGuardrail`——若模型自己引用的 heap 已超 0.85 告警阈值却给 <0.7，后端把它抬到 0.7。它只读模型自己引用的数值、只会上调、且有两个单测锁定；最终轮真实**触发 3 次**，确定性地消除了残余漂移。
- **最终结果**：**19/19 根因** · **metric grounding 19/19** · **log grounding 14/14**（5 个 metric-only 判定为设计内：4 个 OOM 前 memory + healthy）· confidence **healthy 0.5 / fault 0.7–0.9** · **单测 9/9**。
- **教训**：*"Measure, then trust the architecture over the model's mood."* prompt 是软引导、应保持为主；但对必须**保证**的属性（自相矛盾的判定），一个小而可解释、有测试的确定性护栏，比重写 prompt 然后碰运气更可靠。另外：grounding checker 不只是查 LLM 的——它连你自己写的 PromQL 一样会抓。

---

## 3. 量化总结（Summary）

| 指标 | 起始 | 最终（v13） |
|---|---|---|
| 评估覆盖 | 3 个单故障 | **12 场景 / 19 个被打分告警**（单故障 · 并发 · staggered · healthy 对照） |
| 根因命中率 | 2/3（v1） | **19/19**（含 healthy 正确判为"非事故"） |
| 引用真实性（grounding） | 0%（v2 全是编造） | **metric 19/19 + log 14/14（100%）**；5 个 metric-only 判定为设计内、不是漏引 |
| error-rate 指标语义 | 按 series 拆分的 5xx 每秒速率（被误读为百分比） | **单标量 `sum(5xx)/sum(all)`**，与告警表达式一致 |
| latency 诊断置信度 | 0.5（无证据） | **0.9**（alert-aware 检索 + 复合/staggered 判别） |
| memory 诊断置信度 | 在 0.4–0.8 间摇摆（日志规则错） | **稳定 0.7**（heap 主指标规则 + 确定性护栏） |
| healthy 对照 | 一度虚高 0.9 | **0.5**（confidence=事故概率，非自我确信度） |
| 复合 / staggered 判别力 | 第一轮多告警被污染 | 即使有并发或残留故障，也**按告警正确归属** |
| Loki 日志链路 | HTTP 400（第一天就坏） | **HTTP 200，streams 0→1** |
| 证据可执行化（v9） | 引用是文本，无法复现 | **LLM 引用编号 → 后端映射真实查询 + Grafana 深链**（伪造编号 → resolved=false） |
| 单元测试 | – | **9/9**，含护栏"触发 / 不触发"双向用例 |

---

## 4. 关键经验（Lessons in practice）

### 发现 bug 的过程（grounding 概念）
> *"I built a grounding checker that verifies every citation verbatim against the retrieved context. That's when I found my v2 '100% citation rate' was format compliance — the log tool had been silently 400-failing since day one, so those log quotes were fabricated. Fixing a one-character LogQL bug (single-quote vs double-quote) turned fabricated citations into grounded evidence — latency case confidence recovered from 0.5 to 0.9."*

### 架构决策的过程（correlation window）
> *"My sliding-window log query (`now - 5min`) mixed stale logs from the previous incident into the current analysis — the citations were grounded but irrelevant. The fix: anchor the window to the alert's receivedAt — a correlation window of `[alert - 2min, alert]`. Root causes happen before the alert fires; querying forward from 'now' is wrong twice."*

### 工具设计的过程（alert-aware search）
> *"My log tool only searched ERROR/Exception — but latency incidents leave WARN/DEBUG evidence. I made the retrieval alert-aware: query keywords derive from the alert category, so a latency alert also searches for sleep/timeout signals. Tool signatures should adapt to the task."*

### 方法论的形成（eval-driven）
> *"I treat evaluation like a grading rubric — fixed fault injections with known answers (measure, not train), settle isolation between cases, one variable at a time, and a grounding check that verifies evidence provenance, not just format."*

### 证据可信度的架构保证（executable citations）
> *"The last trust gap wasn't diagnosis accuracy — it was that my agent's citations were text, not actions. I made evidence executable: tools record every query they really ran, the prompt exposes an evidence index of real queries, and the model cites by number. The backend resolves each number to the actual query and a Grafana deep link; an invented index resolves to nothing. Citations became auditable by construction, not by model compliance."*

### prompt 引导、护栏保证（v13）
> *"我的 grounding checker 不光抓到 LLM 的错，还抓到我自己 error-rate PromQL 的 bug——它返回的是每秒速率而不是比率。我也学会了别和非确定性较劲：memory 判定明明引用了 98.7% 的 heap，有时却给 0.4。我保留 prompt 作为主引导，但对必须保证的属性，加了一个 15 行、只会上调自相矛盾分数的确定性护栏，再用两个单测把它钉死；最终轮它真实触发了三次。质量靠软引导，保证靠硬护栏。"*

---

## 5. 已知限制与后续方向（Known limitations & next steps）

| 问题 | 现状 | 后续方向 |
|---|---|---|
| Alertmanager 重复投递 webhook → 重复 Incident 记录 | 未修 | 幂等化：按 alert fingerprint 去重（dedup） |
| `root_cause_hypothesis` 字段偶尔是断言式总结（引用在 `analysis_detail`） | 可接受 | 评估按完整输出判断（"measure what matters — check the full output, not one field"） |
| memory 案无 ERROR 日志 → metric-only 判定 | 合理（v13） | heap 超阈值即自足（0.7），并有确定性护栏兜底，置信度不再逐轮漂移 |
| RAG 是关键词检索（非语义） | 当前实现 | pgvector semantic search 暂缓——只有 3 个 runbook 时上向量库是过度设计（over-engineering），知识库扩大后再做 |

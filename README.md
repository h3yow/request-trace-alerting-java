# 请求链路追踪与运行期告警（Spring Boot）

在不侵入业务代码的前提下，为每个请求提供：

1. **全链路唯一标识**：同步处理、`@Async` 异步任务、服务端内部转发（forward）、
   容器 ERROR/ASYNC 分发之间保持一致，日志与错误响应都携带它；
2. **接口维度指标**：请求量、错误率、慢请求率、响应耗时，并发安全，不重不漏，内存有上界；
3. **滑动窗口告警闭环**：连续异常才触发、抑制窗口防风暴、恢复可识别、规则更新不翻旧账。

技术栈：Spring Boot 3.4 / Java 21 / SLF4J+Logback，无额外第三方依赖。

---

## 1. 链路标识（Trace ID）

### 1.1 载体与格式

- 请求头 / 响应头：`X-Trace-Id`
- 合法格式（正则）：`^[0-9a-f]{8,64}$`
  - 只允许小写十六进制字符；长度 8~64。
  - 服务端自生成值为 32 位无横线 UUID（合法集合的子集）。

### 1.2 入口解析规则（`trace.TraceFilter`，最高优先级过滤器）

| 上游情况 | 处理规则 | 响应头 `X-Trace-Id-Source` |
|---|---|---|
| 无头，或头值为空白 | 服务端生成新标识 | `generated` |
| 头值合法 | 原样沿用，不重新生成 | `propagated` |
| 头值存在但非法 | **拒绝脏值**：打 WARN 日志（含拒绝原因 `reason=invalid_format`、长度、经清洗截断的值），重新生成干净值 | `rejected` |

关键约束：

- **不静默丢弃**：任何非法值都有明确的 WARN 日志和 `rejected` 来源标记，可被区分统计；
- **脏值绝不透传**：响应头、响应体、日志 MDC 只出现服务端生成的干净值；
  日志中的脏值会先剔除控制字符并截断到 64 字符，防止日志伪造/膨胀。

### 1.3 标识在处理链路中的传递

| 边界 | 机制 |
|---|---|
| 同步请求全程 | `TraceContext`（ThreadLocal）绑定，并写入 SLF4J MDC（key=`traceId`），finally 中清理 |
| 服务端 forward | 标识存于 request 属性，整次 HTTP 调用共享；过滤器 `OncePerRequestFilter` 保证只执行一次 |
| `@Async` / 线程池 | `TraceAwareTaskDecorator` 在**提交线程**抓取不可变快照，在**工作线程**绑定，任务结束 finally 清理，防止池线程复用串号 |
| Servlet 异步（`CompletableFuture` 控制器） | 容器 ASYNC 重新分发时，过滤器从 request 属性恢复 MDC |
| ERROR 分发（/error、404 等） | 同样从 request 属性恢复；极端直连 /error 无属性时补生成 |

日志格式（`src/main/resources/logback-spring.xml`）每行都带标识：

```
%d %level [%thread] traceId=%X{traceId:-} %logger - %msg
```

错误响应（500 控制器异常、过滤器逃逸异常、404/405 错误页）统一结构：

```json
{ "status": 500, "error": "Internal Server Error",
  "message": "请求处理失败，请凭链路标识联系排查: <traceId>",
  "traceId": "<traceId>" }
```

> 未命中路径在 Spring Boot 3.2+ 会抛 `NoResourceFoundException`，框架内建 4xx
> 异常由 `ApiExceptionHandler` 保持 404/405 语义，其余未预期异常才是 500。

---

## 2. 指标统计

### 2.1 维度键（endpoint key）

`HTTP方法 + 空格 + Spring URI 模板`，例如 `GET /api/demo/ok`。

- 使用模板（`BEST_MATCHING_PATTERN_ATTRIBUTE`）而非原始 URI，路径参数不会撑爆键空间；
- 未匹配到处理器的请求（404）归固定桶 `GET __UNMAPPED__`；
- 接口桶数量上限 `observability.max-endpoints`（默认 256），超限的新键并入
  `__OTHER__` 桶，样本不丢失。

### 2.2 判定口径（全系统唯一，指标与告警共用）

| 概念 | 口径 |
|---|---|
| 错误请求 | 最终 HTTP 状态码 **>= 500**，或处理过程中**抛出异常**（含 Servlet 异步 onError/onTimeout） |
| 慢请求 | 端到端总耗时（含异步处理时间）**>= `observability.slow-request-threshold-ms`**（默认 500ms，含边界） |
| 耗时 | `System.nanoTime()` 纳秒计时，记录时换算为毫秒（最小 1ms） |

### 2.3 不重不漏的计数保证（`metrics.MetricsFilter`）

- 只在初始 `REQUEST` 分发工作，FORWARD / ERROR / ASYNC 分发直接放行，不重复计数；
- 同步请求在 finally 记录一次；
- Servlet 异步请求通过 `AsyncListener` 在 `onComplete/onTimeout/onError` 记录一次，
  并用 `recorded` 标志保证三条回调互斥，恰好一次；
- 记录日志打印判定依据，例如：
  `记录指标 traceId=... endpoint=... status=200 durationMs=602 error=false slow=true 判定依据: status>=500或抛异常 => error; durationMs>=500 => slow`

### 2.4 并发与内存上界

- 桶间用 `ConcurrentHashMap` 隔离，桶内样本读写同一把锁串行，统计基于快照；
- 每接口滑动窗口：按时间过期（`window-retention-ms`，默认 60s）
  **且**样本数有硬上限（`max-samples-per-endpoint`，默认 10000，超出淘汰最老样本）；
- 异步线程池使用**有界队列**（200）+ AbortPolicy，不无限堆积；
- 告警事件历史保留上限 1000 条（超出删最旧）。

### 2.5 观测接口

- `GET /api/observability/metrics`：各接口窗口内 `requestCount / lifetimeRequestCount /
  errorCount / slowCount / totalDurationMs / maxDurationMs / errorRate / avgDurationMs`
- `GET /api/observability/alerts`：已产生的告警/恢复事件列表

---

## 3. 告警闭环

### 3.1 规则模型（`alert.AlertRule`，不可值对象）

| 字段 | 含义 |
|---|---|
| `id` | 规则唯一标识 |
| `endpoint` | 作用的接口维度键 |
| `metric` | `ERROR_RATE`（错误率）或 `SLOW_RATE`（慢请求率） |
| `threshold` | 触发阈值，比例 ∈ (0,1)；判定使用**严格大于**，等于阈值不算异常 |
| `minSamples` | 窗口最少样本数；不足判为“数据不足”，不告警也不判恢复（防低流量误报） |
| `windowMs` | 滑动窗口长度；只统计 `startMs ∈ (now-windowMs, now]`（左开右闭） |
| `triggerRounds` | 异常状态需**连续**成立的评估轮数 |
| `suppressionMs` | 触发后的抑制窗口，窗口内不重复告警 |
| `version` | 规则版本；更新规则时更换版本 |

### 3.2 状态机（每个“规则 + 接口”独立）

```
健康 ──连续 triggerRounds 轮异常──> FIRED（产生"触发"事件）
                                      │
              抑制窗口内继续异常 → 抑制，不重复通知
              抑制窗口后仍异常   → 再触发一次
              任一轮评估恢复健康 → RECOVERED（产生"恢复"事件）→ 回到健康
数据不足：不触发、不恢复，连续异常计数清零
```

- 评估周期由 `AlertScheduler` 按 `observability.alert-evaluation-interval-ms`（默认 5s）驱动；
- 单次评估异常不影响调度线程（catch 兜底）。

### 3.3 确定性与“不翻旧账”

- 判定核心 `AlertVerdict.evaluate(rule, samples, nowMs)` 是**纯函数**：
  同一份样本 + 同一份规则 + 同一评估时刻，结论必然一致；时间由参数注入，可重放历史；
- 规则更新（内容或 `version` 变化）后，该规则状态**重置**，只对之后的窗口生效；
  内容完全相同（含版本）的规则保留状态；
- 已产生的 `AlertEvent` 是不可变对象、永久保留（在上界内），**改配置不会改写历史结论**，
  因此不会出现“同一段历史前后矛盾”。

### 3.4 默认规则

启动时 `AlertRuleRegistrar` 注册（可自行替换）：

- `demo-error-rate`：`GET /api/demo/error` 错误率 > 0.5
- `demo-slow-rate`：`GET /api/demo/slow` 慢请求率 > 0.5

均要求窗口内 ≥ 10 个样本、连续 2 轮成立、抑制 60s。

---

## 4. 配置项（`application.properties`）

```properties
observability.slow-request-threshold-ms=500   # 慢请求阈值，指标与告警共用
observability.window-retention-ms=60000       # 指标/默认规则窗口长度
observability.max-samples-per-endpoint=10000  # 每接口窗口样本硬上限
observability.max-endpoints=256               # 接口桶数量上限
observability.alert-evaluation-interval-ms=5000
observability.trigger-consecutive-rounds=2
observability.suppression-window-ms=60000
```

---

## 5. 本地验证

### 5.1 构建与测试

```bash
mvn clean test
```

测试覆盖（共 7 个测试类）：

| 测试类 | 覆盖点 |
|---|---|
| `TraceIdPropagationIntegrationTests` | 缺失生成 / 合法透传 / 非法拒绝不透脏值 / 空白头 / 500 与 404 错误体 / 异步 / forward（真实 HTTP） |
| `TraceLogMdcIntegrationTests` | 同步与异步工作线程日志 MDC 均带 traceId，指标日志含判定依据 |
| `MetricsConcurrencyIntegrationTests` | 16 线程并发计数精确、慢/错聚合、接口桶有界、窗口容量与时间过期 |
| `HttpMetricsEndToEndTests` | 40 并发真实 HTTP：OK/500/404 计数不重不漏、错误率口径 |
| `AlertEvaluationTests` | 阈值严格大于边界、minSamples、窗口边界、慢/错口径、重放确定性 |
| `AlertEngineStateTests` | 连续轮次触发、抑制窗口、恢复、抑制后再触发、规则更新重置且历史不变 |
| `ApplicationTests` | 原有上下文加载测试（保持通过） |

> 单测日志中统一带 `traceId`（如 `traceId=trace-alert-test`），告警日志直接打印判定依据。

### 5.2 手工冒烟

```bash
mvn spring-boot:run          # 默认随机端口，见启动日志 Tomcat started on port
PORT=<启动日志中的端口>

# 缺失 → generated
curl -i localhost:$PORT/api/demo/ok
# 合法 → propagated
curl -i -H 'X-Trace-Id: cafebabecafebabecafebabecafebabe' localhost:$PORT/api/demo/ok
# 非法 → rejected，响应里是新值
curl -i -H 'X-Trace-Id: NOT-HEX-123' localhost:$PORT/api/demo/ok
# 异步 / 转发 / 异常 / 慢请求
curl localhost:$PORT/api/demo/async
curl localhost:$PORT/api/demo/forward-source
curl localhost:$PORT/api/demo/error
curl localhost:$PORT/api/demo/slow
# 指标与告警
curl localhost:$PORT/api/observability/metrics
curl localhost:$PORT/api/observability/alerts
```

---

## 6. 代码地图

```
trace/    TraceIds(格式/生成) TraceContext(ThreadLocal+MDC) TraceFilter(入口)
          TraceAwareTaskDecorator(异步传递)
config/   AsyncConfig(有界线程池) WebConfig(属性绑定) AlertConfig(可注入Clock)
metrics/  RequestSample(样本) SlidingWindow(有界窗口) MetricsRegistry(聚合)
          MetricsFilter(每请求一次计时/打样) EndpointStats(统计快照)
alert/    AlertRule/AlertEvent(不可变模型) AlertVerdict(纯函数判定)
          AlertEngine(状态机) AlertScheduler(周期评估) AlertRuleRegistrar(默认规则)
web/      ApiErrorResponse ApiExceptionHandler(4xx/5xx) BasicErrorControllerImpl(/error)
demo/     DemoController(ok/error/slow/async/forward) MetricsController(自检接口)
```

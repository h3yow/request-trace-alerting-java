# request-trace-alerting-java

Spring Boot 服务，内置请求链路追踪与运行期告警能力：
每个请求有唯一可追溯的链路标识，按接口维度统计指标，基于滑动窗口做阈值告警。

## 链路标识（Trace Id）

### 生成与格式

- 格式：**32 位小写十六进制字符**（`[0-9a-f]{32}`），由 128 位安全随机数生成，见 `TraceId`。
- 请求入口由 `TraceIdFilter`（最高优先级过滤器）统一处理，规则如下：

| 上游 `X-Trace-Id` 头 | 处理 | 响应头 `X-Trace-Id-Source` |
| --- | --- | --- |
| 缺失或空白 | 生成新标识 | `GENERATED_MISSING` |
| 存在但格式非法 | 生成新标识，记警告日志；**非法值只出现在服务端日志，不会透传到响应或下游** | `GENERATED_INVALID` |
| 存在且格式合法 | 沿用上游标识 | `UPSTREAM` |

- 响应总是带回 `X-Trace-Id` 头；出错时错误响应体（由 `GlobalExceptionHandler` 统一生成）里也带 `traceId` 字段。
- 日志通过 MDC 自动带上链路标识（`logging.pattern.console` 中的 `%X{traceId}`）。

### 传递规则

- **同步处理**：`TraceContext`（ThreadLocal）在过滤器入口设置、出口清理，业务代码任意位置可取。
- **内部转发**：`OncePerRequestFilter` 保证转发分发不重复处理，同一次请求标识一致。
- **异步任务**：`TraceTaskDecorator` 在任务提交时抓取上下文快照、在异步线程恢复并清理，
  `@Async("traceAwareExecutor")` 方法自动继承调用方标识（见 `AsyncConfig`）。

## 指标统计

`MetricsInterceptor` + `MetricsRegistry` 按接口维度统计：请求量、错误数、累计/最大耗时。

- **接口维度**：首次 `preHandle` 时固化 Spring 匹配的 URI 模板（如 `/api/items/{id}`），
  避免路径参数撑爆基数；内部转发不改变本次请求的接口归属。
- **计数口径**：每个请求**恰好计一次**——请求属性标记保证转发、异步、异常分发不重复计数；
  错误 = 响应状态码 ≥ 500 或处理中抛出未捕获异常；耗时 = 首次 `preHandle` 到记录时刻的墙钟时间。
- **并发安全**：计数用 `LongAdder`，最大值用 `AtomicLong` CAS。
- **内存上界**：最多跟踪 1024 个接口（`MetricsRegistry.DEFAULT_MAX_ENDPOINTS`），
  超出的接口统一归入 `__overflow__` 桶，接口数不受控时内存仍有上界。

## 告警判定

`AlertEvaluator` 按接口维护状态机，规则参数见 `AlertRule`（配置前缀 `observability.alert.*`）：

| 参数 | 默认 | 含义 |
| --- | --- | --- |
| `window-size-millis` | 60000 | 滑动窗口长度，按 1 秒桶聚合，区间为 `(now - window, now]` |
| `min-requests` | 10 | 窗口内样本下限，**不足时不改变告警状态** |
| `max-error-rate` | 0.5 | 错误率超过该值视为异常 |
| `slow-threshold-millis` | 1000 | 耗时 ≥ 该值计为慢请求（全服务统一口径） |
| `max-slow-count` | 5 | 窗口内慢请求数超过该值视为异常 |
| `consecutive-windows` | 2 | 连续 N 个时间桶评估都异常才触发 |
| `suppression-millis` | 300000 | 触发后的抑制窗口，期间不重复触发 |

判定闭环：

1. 每个 1 秒时间桶只在首次落入时评估一次，"连续 N 次异常"对应连续 N 个桶，而不是连续 N 条记录；
2. 连续异常达到 `consecutive-windows` 且不在抑制窗口内 → 发出 `TRIGGERED`，进入告警状态；
3. 告警状态下评估恢复正常（样本充分且不再超标）→ 发出 `RECOVERED`，退出告警状态；
4. 恢复后再次异常，若距上次触发不足 `suppression-millis` 则抑制，避免告警风暴；
5. 告警事件带可读的判定依据（如 `errorRate=0.800 > 0.500`、`slowCount=4 > 2`），
   由 `LoggingAlertSink` 输出结构化日志。

确定性与规则更新：

- `AlertEvaluator.evaluate(snapshot, rule)` 是纯函数：**同一份窗口数据 + 同一条规则 ⇒ 同样的结论与依据**；
- `updateRule` 只影响更新之后的评估，历史窗口数据与已产生的告警事件不变，
  重新评估同一段历史（用当时的规则）结论一致；
- 滑动窗口桶数组大小固定，长时间运行内存有上界。

## 本地验证

```bash
# 全部测试（22 个：标识规则、窗口聚合、告警状态机、端到端链路、并发计数）
mvn test

# 启动服务（随机端口，观察日志输出）
mvn spring-boot:run
```

手工验证（假设端口 8080）：

```bash
# 正常请求：响应头带 X-Trace-Id / X-Trace-Id-Source
curl -i http://localhost:8080/api/hello

# 上游标识透传
curl -i -H 'X-Trace-Id: 0123456789abcdef0123456789abcdef' http://localhost:8080/api/hello

# 非法标识被替换（source=GENERATED_INVALID），脏值不透出
curl -i -H 'X-Trace-Id: <script>alert(1)</script>' http://localhost:8080/api/hello

# 错误响应体带 traceId
curl -i http://localhost:8080/api/fail

# 异步边界标识一致（callerTraceId == asyncTraceId）
curl -s http://localhost:8080/api/async

# 内部转发标识一致且只计一次
curl -i http://localhost:8080/api/forward

# 触发慢请求告警：连发慢请求（默认慢请求阈值 1s）
for i in $(seq 1 20); do curl -s "http://localhost:8080/api/slow?sleepMillis=1500" & done; wait
# 观察日志中的 ALERT TRIGGERED ... reason=slowCount=...
```

## 代码结构

```
trace/    TraceId（生成/校验）、TraceContext（ThreadLocal 上下文）、
          TraceIdFilter（入口处理）、TraceTaskDecorator + AsyncConfig（异步传递）
metrics/  MetricsInterceptor（每请求计一次）、MetricsRegistry（有界注册表）、EndpointMetrics
alert/    AlertRule（不可变规则）、SlidingWindow（固定桶滑动窗口）、
          AlertEvaluator（状态机 + 纯函数判定）、AlertManager、AlertEvent/AlertSink
web/      DemoController（演示接口）、GlobalExceptionHandler（错误响应带标识）
config/   ObservabilityConfig（装配与告警规则配置）
```

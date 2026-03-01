完整分析

测试用例 reconnectManually 逻辑

1. 创建 socket 并连接
2. 第一次 CONNECT 回调 → socket.close()
3. DISCONNECT 回调 → 注册新的 CONNECT 监听 → socket.open() (手动重连)
4. 第二次 CONNECT 回调 → socket.close()，向队列 offer("done")
5. values.take() 阻塞等待 "done"

---
Transport 实例总览

┌──────────┬────────────────────┬──────────────────────┬────────────────────┬─────────────────────┐
│   日志   │ 第一次连接 Polling │ 第一次连接 WS Probe  │ 第二次连接 Polling │ 第二次连接 WS Probe │
├──────────┼────────────────────┼──────────────────────┼────────────────────┼─────────────────────┤
│ good.log │ polling@709407350  │ websocket@2087361714 │ polling@969147974  │ websocket@590078241 │
├──────────┼────────────────────┼──────────────────────┼────────────────────┼─────────────────────┤
│ err.log  │ polling@1929439163 │ websocket@260110639  │ polling@2125549749 │ websocket@897766716 │
└──────────┴────────────────────┴──────────────────────┴────────────────────┴─────────────────────┘

---
关键发现：Manager 共享 scope

Manager.kt:100:
val socket = EngineSocket(uri, opt, scope)  // Manager 把自己的 scope 传给每个新的 EngineSocket

每次 Manager.open() 创建新的 EngineSocket 实例，但传入的是同一个 scope。这个 scope 同时也会传给所有 Transport（factory.create(name, opts, scope, rawMessage)，见 EngineSocket.kt:222）。这意味着：新旧连接的所有
Transport 共享同一个 CoroutineScope。

---
good.log 执行流程（成功路径）

第一次连接

1. polling@709407350 创建并打开，发起 GET 握手请求
2. 收到 Open 包（sid=npUAHeAD7Xe4wi4TAAAA），polling@709407350 状态 OPENING → OPEN
3. EngineSocket onOpen，发送 Socket.IO Connect(40)，同时启动 probe：创建 websocket@2087361714，发起 WS 握手
4. polling@709407350 继续 poll，收到 40{"sid":"I6cxDxOi1VMI0lYEAAAB"} → Socket onConnect

第一次断开（socket.close()）

5. Socket close → Manager 发送 Disconnect(41) → polling@709407350 send
6. Manager destroy/close/cleanUp
7. EngineSocket.close()：state=OPEN, writeBuffer 非空(有 41 在发送中), upgrading=false
  - 注册 once(EVENT_DRAIN, ...) 等待 drain
8. Socket onClose: io client disconnect

手动重连

9. Socket.open() → Manager.open(state=CLOSED) → 创建新的 EngineSocket（state=INIT）
10. 新 EngineSocket setTransport(polling@969147974) → 发起新的 GET 握手

旧连接清理（关键时序）

11. websocket@2087361714 WS 101 握手成功，onOpen（时间戳 488）
12. Probe 发送 2probe
13. 旧 EngineSocket 的 drain 先于 probe pong 到达（时间戳 490）：
  - polling@709407350 POST 41 返回 OK → onDrain → emit(EVENT_DRAIN)
  - close 的 drain handler 检查 upgrading == false ✓
  - 直接调用 closeAction() → onClose("force close")
  - onClose 关闭 polling@709407350，清空 writeBuffer
14. websocket@2087361714 后续收到 3probe pong，但 probe handler 发现 socket 已 closed → 中止

第二次连接正常完成

15. polling@969147974 收到新 Open（sid=Q5cmf6rvfmLGKrBHAAAC），正常连接
16. 收到 Socket.IO Connect → Socket onConnect（sid=0_W1hJWvCHgoG_ktAAAD）
17. 收到 42["message","hello client"] → 回调触发 socket.close()，offer("done") → 测试通过

成功的关键：drain 在 probe pong 之前触发 → upgrading=false → 直接关闭，不走 upgrade 路径。

---
err.log 执行流程（失败路径）

第一次连接（与 good.log 相同，直到 close）

1-4. 与 good.log 相同：polling@1929439163 建立连接，Socket onConnect（sid=Q_xqaV-43ilrJOWWAAAB）

第一次断开

5-8. 与 good.log 相同：发送 41，EngineSocket.close() 注册 drain handler

手动重连

9-10. 与 good.log 相同：创建新 EngineSocket，setTransport(polling@2125549749)

关键时序差异！

11. websocket@260110639 WS 101 握手成功，onOpen（时间戳 698）
12. Probe 发送 2probe（ioScope.launch 异步发送，此处 ioScope 协程 A 启动）
13. Probe pong 先于 drain 到达（时间戳 702）：
  - 收到 3probe → EngineSocket probe transport websocket pong
  - upgrading = true ← 关键状态变更
  - 开始 pause polling@1929439163
14. polling@1929439163 POST 41 返回 OK → onDrain（时间戳 705）：
  - drain handler 检查 upgrading == true → 走 waitForUpgrade() 路径
  - 注册 once(EVENT_UPGRADE, cleanupAndClose) 和 once(EVENT_UPGRADE_ERROR, cleanupAndClose)
15. Pause 完成（时间戳 705）：
  - polling@1929439163 paused
  - EngineSocket changing transport and sending upgrade packet
  - 在 websocket@260110639 上注册 once(EVENT_DRAIN, upgradeHandler)
  - 发送 Upgrade 包 5（ioScope.launch 异步发送，协程 B 启动）
16. Upgrade 包的 ioScope 协程 B 完成 → scope.launch 发布 drain 事件（时间戳 705，line 321）：
  - websocket@260110639 doSend 1 packets finish（第一个 finish）
  - emit(EVENT_DRAIN, 1) on websocket@260110639
  - Emitter 快照 listeners：onceCallbacks[EVENT_DRAIN] = [upgradeHandler]
  - upgradeHandler 执行：
a. emit(EVENT_UPGRADE, transport) → cleanupAndClose 触发：
      - onClose("force close")
    - writeBuffer.clear(), prevBufferLen = 0
    - state = State.CLOSED
b. setTransport(websocket@260110639) → 在 websocket@260110639 上添加新的持久 EVENT_DRAIN callback listener（On.on → transport.on）
c. upgrading = false, flush() → state CLOSED 忽略
17. 2probe 的 ioScope 协程 A 延迟完成 → scope.launch 发布 drain 事件（时间戳 712，line 405）：
  - websocket@260110639 doSend 1 packets finish（第二个 finish）
  - emit(EVENT_DRAIN, 1) on websocket@260110639
  - Emitter 快照 listeners：callbacks[EVENT_DRAIN] = [setTransport 刚注册的持久 listener]
  - setTransport 的 listener 调用 onDrain(1)
  - onDrain: prevBufferLen=0, writeBuffer.size=0, len=1
  - writeBuffer.removeAt(0) → ArrayDeque is empty 💥 CRASH

Exception: java.util.NoSuchElementException: ArrayDeque is empty.
    at EngineSocket.onDrain(EngineSocket.kt:280)
    at EngineSocket$setTransport$1.call(EngineSocket.kt:246)
    at Emitter.emit(Emitter.kt:136)
    at WebSocket$doSend$2$3.invokeSuspend(WebSocket.kt:172)

崩溃后的连锁反应

18. 异常发生在 scope.launch（WebSocket.kt:169）内部，scope 是 Manager 的共享 scope
19. 未捕获的异常导致 共享 scope 的 Job 被取消
20. 第二次连接的 polling@2125549749 虽然收到了 HTTP GET 响应（40{"sid":"N11GQoFftXqh9245AAAD"}，line 457），但 scope.launch { onPollComplete(...) } 无法执行（scope 已取消）
21. onPollComplete 从未被调用（日志中完全没有出现第二次连接的 onPollComplete）
22. 第二次连接永远无法收到 Socket.IO Connect → Socket onConnect 不触发 → "done" 永远不会 offer → 测试 7 秒超时

---
根因总结

存在两层竞态条件：

竞态 1：Close-drain vs Probe-pong
- EngineSocket.close() 注册 drain handler，其中检查 upgrading 决定走哪条路径
- probe() 中收到 pong 会设置 upgrading = true
- 如果 drain 先于 pong → upgrading=false → 直接关闭（good.log）
- 如果 pong 先于 drain → upgrading=true → 走 upgrade 路径，最终调用 setTransport(websocket)

竞态 2：2probe-drain vs Upgrade-drain
- websocket@260110639 的 doSend 被调用了两次：一次发 2probe，一次发 Upgrade
- 每次 doSend 都在 ioScope.launch 中异步发送数据，完成后通过 scope.launch 发射 EVENT_DRAIN
- 这两个 ioScope.launch 的完成顺序不确定
- 如果 Upgrade 的 drain 先触发：upgradeHandler 消费了 once listener → setTransport 注册了新的持久 drain listener
- 然后 2probe 的 drain 延迟触发：hit 到 setTransport 注册的新 listener → onDrain(1) 在空 writeBuffer 上操作 → 崩溃

致命后果：崩溃发生在 Manager 共享的 CoroutineScope 中（Manager.kt:100 将同一个 scope 传给新旧 EngineSocket），导致整个 scope 被取消，新连接的所有协程调度都失效，测试超时。

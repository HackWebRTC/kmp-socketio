**结论总览**
- 两份日志的核心差异出现在“首次连接后立即断开 → 立刻手动重连”阶段：good.log 中没有发生 transport upgrade 成功切换；err.log 中 upgrade 成功切换触发了 WebSocket 的 drain 回调，随后 EngineSocket.onDrain 发生队列越界异常，测试挂死超时。
- 失败路径里出现“Transport polling@1929439163 pause → paused → EngineSocket changing transport and sending upgrade packet → Transport websocket@260110639 doSend 1 packets finish → EngineSocket onDrain… → NoSuchElementException”，这是导致用例挂起的直接原因。
- 通过代码可确认：Transport 的日志均由 Transport.logI/logD/logE 打印，hash 即实例 hashCode（见 [Transport.kt](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/Transport.kt#L153-L165)）；PollingXHR 和 WebSocket 各自的生命周期日志由其 doOpen/doSend/doClose/poll/onPollComplete/pause 等调用链触发（见 [PollingXHR.kt](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/transports/PollingXHR.kt#L62-L218)、[WebSocket.kt](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/transports/WebSocket.kt#L35-L183)）。

**用例意图与关键流程**
- 用例是 ConnectionTest.reconnectManually：第一次 connect 后立刻 close；收到 disconnect 后再 open，第二次 connect 后再 close 并完成队列通知（见 [ConnectionTest.java](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/jvmTest/java/io/socket/client/ConnectionTest.java#L353-L380)）。
- EngineSocket.open 会先创建 polling transport 并 open，收到 Open 包后进入 OPEN，然后触发 upgrade probe（如果可升级）（见 [EngineSocket.kt](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/EngineSocket.kt#L356-L404)）。
- probe 成功后：暂停当前 polling → 发送 Upgrade 包 → drain 回来后 setTransport(websocket) 并 flush（见 [EngineSocket.kt](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/EngineSocket.kt#L411-L523)）。

**good.log 关键时序梳理（Transport 实例级别）**
- 初次连接（polling@709407350）
  - open → poll start → doRequest GET → onPollComplete(Open) → onOpen → send(Connect) → poll start → doRequest GET/POST。
  - 触发 upgrade probe：创建 websocket@2087361714，但在 close 过程中被关闭，出现 “probe transport websocket, but socket closed”。
  - Socket 在收到 Connect 后立即 close，EngineSocket close 导致 polling@709407350 close，状态进入 CLOSING，并发送 Close 包（见 “doClose writing close packet”）。
- 第二次连接（polling@969147974）
  - new EngineSocket open → polling@969147974 open → onPollComplete(Open) → send Connect → probe websocket@590078241。
  - close 触发后 polling@969147974 close；随后服务端停止导致 Connection reset/Connection refused 报错，但发生在用例已经完成之后（good.log 末尾）。

**err.log 关键时序梳理（Transport 实例级别）**
- 初次连接（polling@1929439163）
  - open → onPollComplete(Open) → send Connect → poll start。
  - Socket 在 connect 后立即 close，触发 send Disconnect，然后 EngineSocket close 等待 drain。
- upgrade 探测成功并切换（websocket@260110639）
  - probe 期间收到 Pong probe（websocket@260110639 onPacket Pong），进入 pause：polling@1929439163 pause → wait polling/writing → onPollComplete(state=PAUSED, Event message) → paused。
  - 然后 “changing transport and sending upgrade packet”，websocket@260110639 doSend Upgrade 包，doSend finish 触发 drain。
- 失败触发点
  - drain 事件调用 EngineSocket.onDrain，日志显示 “EngineSocket onDrain: prevBufferLen 0, writeBuffer.size 0, len 1”，随后 ArrayDeque.removeFirst 异常（见 err.log 409-413）。
  - 这对应 [EngineSocket.onDrain](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/EngineSocket.kt#L276-L290) 中 `for (i in 1..len) writeBuffer.removeAt(0)`：len=1 但 buffer 空。
- 之后用例等待 `values.take()` 超时（err.log 479-488），表现为超时失败。

**Transport 日志与代码对应关系**
- `Transport polling@... open`：Transport.open() → logD("open")（见 [Transport.kt](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/Transport.kt#L51-L58)）。
- `poll start / onPollComplete / doRequest ...`：PollingXHR.poll / onPollComplete / doRequest（见 [PollingXHR.kt](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/transports/PollingXHR.kt#L67-L173)）。
- `send: state OPEN, 1 packets`：Transport.send → logD（见 [Transport.kt](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/Transport.kt#L61-L68)）。
- `pause / pause: wait polling / pause: wait writing / paused`：PollingXHR.pause（见 [PollingXHR.kt](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/transports/PollingXHR.kt#L25-L59)）。
- `doSend ... finish`（WebSocket）：WebSocket.doSend 发包后 emit(EVENT_DRAIN)（见 [WebSocket.kt](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/transports/WebSocket.kt#L133-L173)）。
- `EngineSocket onDrain ...`：EngineSocket.onDrain 删除 writeBuffer 头部数据（见 [EngineSocket.kt](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/EngineSocket.kt#L276-L290)）。

**完整执行过程对比与状态变化**
- good.log：第一次连接在 upgrade 还未完成时即 close；probe 中 websocket 触发“socket closed”被关闭，polling 进入 close 流程后结束。第二次连接走一遍类似流程，事件顺序完整，无异常。
- err.log：第一次连接 close 过程中，upgrade probe 刚好成功进入“pause → upgrade packet send → setTransport”链路，导致 websocket 的 drain 回调在 writeBuffer 为空时触发，抛异常后逻辑中断，导致 reconnectManually 没有走到第二次 connect 的 close + values.offer，最终超时。

**可以确定的关键差异点**
- good.log 没有出现 `pause/paused/upgrade packet send success`，说明 upgrade 没成功完成；err.log 完整出现这些日志，说明 upgrade 成功路径与 close 并发交错。
- err.log 中 `EngineSocket close waiting drain success` 与 `EngineSocket close waiting upgrade` 同时出现，说明 close 正在等待 drain/upgrade，而 upgrade 已触发 websocket drain，这直接命中了 onDrain 的空队列路径。

如果你需要，我可以进一步把 good/err 两条日志中每个 transport 实例（polling@709..., websocket@208..., polling@969..., websocket@590... vs polling@192..., websocket@260..., polling@212..., websocket@897...)的完整事件序列按时间线整理成表格，便于定位每个实例的状态迁移轨迹。

==============================================================================

问题的根因是什么呢？

==============================================================================

根因是：**EngineSocket 的发送队列(writeBuffer)与 drain 回调(len)之间的状态/并发一致性被破坏，导致 onDrain 在队列为空时仍按 len=1 去弹出元素，抛出 NoSuchElementException，进而让协程异常中断、用例卡死超时**。

把 err.log 里的关键链路严格串起来就是：

- WebSocket upgrade 成功路径触发：`probe transport websocket pong` → `pausing current transport polling` → `polling pause ... paused` → `changing transport and sending upgrade packet` → `Transport websocket@260110639 doSend ... Upgrade, 5`。
- WebSocket.doSend 在发送完成后会 `emit(EVENT_DRAIN, packets.size)`，这里 `packets.size == 1`（见 [WebSocket.kt](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/transports/WebSocket.kt#L133-L173)）。
- EngineSocket 订阅 transport 的 drain 事件后会调用 `onDrain(len)`（见 [EngineSocket.kt](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/EngineSocket.kt#L227-L274)），而 onDrain 会执行：

  - `for (i in 1..len) writeBuffer.removeAt(0)`（见 [EngineSocket.kt](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/EngineSocket.kt#L276-L290)）

- err.log 在崩溃前打印了：`EngineSocket onDrain: prevBufferLen 0, writeBuffer.size 0, len 1`，这意味着 **EngineSocket 当时并没有任何待发送包(writeBuffer 空、prevBufferLen=0)**，但 drain 回调却告诉它“已 drain 了 1 个包”，于是 removeFirst 直接炸掉（异常栈也指向 EngineSocket.kt:280）。

为什么这会“偶现”：
- 只有在一个很窄的竞态窗口里才会发生：**测试用例第一次 connect 后立刻 close**，同时 **upgrade probe 恰好完成并发送 Upgrade 包**。good.log 里 upgrade 没跑完（或被 close 打断），所以不会走到 websocket drain → EngineSocket.onDrain 的这条路径；err.log 里 upgrade 恰好跑完并发出了 drain，于是触发了这个一致性问题。

一句话归因：**EngineSocket 把“transport drain 事件”当成了“自己 writeBuffer 的 drain”，但在 upgrade/close 交错时，transport 可能 drain 的是并不来自 writeBuffer 的包（Upgrade probe 包），从而导致 onDrain 对空队列执行 remove。**

==============================================================================

但在 `/Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/EngineSocket.kt#L444-453` 这里，我是等到了 websocket 发送 upgrade 包的 drain 事件了才会 setTransport 啊，那 upgrade 包的 drain 事件就不应该影响到 EngineSocket 啊

==============================================================================

你说得对：**L444 那个 once(EVENT_DRAIN)** 只是“升级包发送完成后再 setTransport”的保护，它本身不该直接影响 EngineSocket 的写队列。问题出在**另一个 drain 事件**上，而且日志已经明确指出是 **setTransport 里注册的 drain 回调**触发了异常。

**关键点**
- 异常栈显示触发点是 `EngineSocket$setTransport$1.call`（也就是 setTransport 里绑定的 drain 监听）：  
  [EngineSocket.kt:L243-L249](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/EngineSocket.kt#L243-L249)
- err.log 中 **setTransport 已经发生在 07:33:00.900387**，而真正触发崩溃的 drain 是 **07:33:00.903518** 之后的那个：
  - `D Transport websocket@260110639 doSend 1 packets finish`
  - `D EngineSocket onDrain: prevBufferLen 0, writeBuffer.size 0, len 1`
  - 随后抛出 ArrayDeque is empty  
  这说明 **触发 onDrain 的 drain 事件发生在 setTransport 之后**。

**为什么会这样**
- WebSocket 的 `doSend` 结束时会 `emit(EVENT_DRAIN, packets.size)`，无论这个 send 是否来自 EngineSocket 的 writeBuffer。  
  [WebSocket.kt:L169-L173](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/transports/WebSocket.kt#L169-L173)
- 一旦 `setTransport(transport)` 执行，就把 **transport 的 drain** 绑定到 `EngineSocket.onDrain`。  
  [EngineSocket.kt:L243-L247](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/EngineSocket.kt#L243-L247)
- `EngineSocket.onDrain` 假设 drain 的 len 对应 **writeBuffer**，直接 `removeAt(0)`：  
  [EngineSocket.kt:L276-L281](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/EngineSocket.kt#L276-L281)
- 但在这个偶发路径里，**writeBuffer 已经为空**（日志里 `prevBufferLen 0, writeBuffer.size 0`），而 transport 仍然发出了 drain(len=1)，于是下溢。

**这与你说的 L444 的关系**
- L444 的 once(EVENT_DRAIN) **只保证 upgrade 包发送成功后才 setTransport**，没问题。  
- 但 **setTransport 后**，该 transport 的任何 drain 都会进入 EngineSocket.onDrain，这已经不是“升级包的 drain 事件”了，而是 **transport 的 drain 事件被“误认为”是 writeBuffer 的 drain**。

**一句话总结**
- 根因不是 L444 的等待机制，而是：**setTransport 后，WebSocket transport 发出的 drain 事件并不一定对应 EngineSocket.writeBuffer，却仍被 onDrain 当作 writeBuffer drain 处理**，在 close + upgrade 并发窗口里触发空队列 remove。

==============================================================================

可是我的预期是只有 Upgrade 包对应的 drain 监听到之后，才会 setTransport 啊，而这之后预期就不会有 drain 事件里啊，因为没有任何发包的记录了啊

==============================================================================

你这个预期在“同步逻辑”层面是合理的，但 **日志和源码证明 drain 事件是异步投递的**，所以会在 setTransport 之后才到达，即使它对应的是 **刚才那个 Upgrade 包**。

**为什么 setTransport 后仍会收到 drain**
- WebSocket 的发送是异步的：`doSend` 里用 `ioScope.launch` 发送，然后再 `scope.launch { emit(EVENT_DRAIN, packets.size) }`（见 [WebSocket.kt](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/transports/WebSocket.kt#L133-L173)）。  
  这意味着 **Upgrade 包的 drain 可能晚于 setTransport 发生**。
- 日志时间线刚好说明了这一点：
  - `07:33:00.899180 doSend: Upgrade, 5`
  - `07:33:00.900387 EngineSocket setTransport websocket`
  - `07:33:00.903518 Transport websocket@260110639 doSend 1 packets finish`
  - `07:33:00.903570 EngineSocket onDrain: prevBufferLen 0, writeBuffer.size 0, len 1`
- 所以 **这个 drain 就是 Upgrade 包的 drain，只是它在 setTransport 之后到达**。并不是“没有任何发包记录”，日志里确实有 `doSend 1 packets finish`。

**为什么它会影响 EngineSocket**
- 一旦 `setTransport` 执行，EngineSocket 就订阅了 transport 的 drain，并把它直接映射到 `EngineSocket.onDrain`（见 [EngineSocket.kt](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/EngineSocket.kt#L243-L247)）。
- 但 EngineSocket.onDrain 默认认为 drain 一定对应 writeBuffer（见 [EngineSocket.kt](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/EngineSocket.kt#L276-L281)），而这次 drain 实际是 **upgrade 包**，不是 writeBuffer 中的包，导致空队列被弹出。

**一句话总结**
- 你的预期在“事件因果”上没错，但 **事件的投递时序是异步的**：Upgrade 包的 drain 发生在 setTransport 之后到达，于是被 EngineSocket 当成 writeBuffer drain 处理，触发了异常。

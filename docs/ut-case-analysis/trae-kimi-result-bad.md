## 详细分析报告：kmp-socketio 偶现 UT 失败问题

### 测试用例概述

测试用例 `reconnectManually` 的逻辑是：
1. 创建 socket 连接
2. 第一次连接成功后立即关闭 (`socket.close()`)
3. 在 `EVENT_DISCONNECT` 回调中重新打开连接 (`socket.open()`)
4. 第二次连接成功后关闭并结束测试

---

### 一、Good Log 分析（成功场景）

在 good.log 中，有两个主要的连接生命周期：

#### 第一次连接生命周期

| 时间 | Transport 实例 | 事件 | 说明 |
|------|---------------|------|------|
| 1769932185432 | polling@709407350 | open | 创建第一个 polling transport |
| 1769932185432 | polling@709407350 | poll start | 开始轮询 |
| 1769932185456 | polling@709407350 | onOpen | 收到 Open packet，状态变为 OPEN |
| 1769932185460 | websocket@2087361714 | open | 创建 probing websocket |
| 1769932185480 | polling@709407350 | send | 发送 Disconnect packet |
| 1769932185480 | Manager | destroy/close/cleanUp | 开始关闭流程 |
| 1769932185490 | EngineSocket | close waiting drain | 等待 drain 事件 |
| 1769932185490 | polling@709407350 | close | 关闭 polling transport |
| 1769932185490 | websocket@2087361714 | close | 关闭 probing websocket（probe 失败）|

**关键点**：在第一次连接关闭时，websocket probe 正在进行中，但 socket 关闭导致 probe 失败。

#### 第二次连接生命周期

| 时间 | Transport 实例 | 事件 | 说明 |
|------|---------------|------|------|
| 1769932185483 | polling@969147974 | open | 创建新的 polling transport（不同 hash）|
| 1769932185500 | polling@969147974 | onOpen | 连接成功 |
| 1769932185500 | websocket@590078241 | open | 创建新的 probing websocket |
| 1769932185516 | polling@969147974 | send | 发送 Disconnect |
| 1769932185516 | Manager | destroy/close | 关闭 |
| 1769932185520 | TestConnection | Stopping server | 测试结束 |

**Good log 的关键特征**：
- 两次连接使用了完全不同的 transport 实例（不同的 hash 值）
- 第一次关闭时，websocket probe 被中断，但没有触发 transport 切换
- 第二次连接是全新的 EngineSocket 实例
        
            
toolName: todo_write
            
status: success
          
Todos updated: 5 items
          
### 二、Error Log 分析（失败场景）

#### 第一次连接生命周期

| 时间 | Transport 实例 | 事件 | 说明 |
|------|---------------|------|------|
| 1769931180665 | polling@1929439163 | open | 创建第一个 polling transport |
| 1769931180680 | polling@1929439163 | onOpen | 连接成功 |
| 1769931180681 | websocket@260110639 | open | 创建 probing websocket |
| 1769931180694 | polling@1929439163 | send | 发送 Disconnect packet |
| 1769931180694 | Manager | destroy/close/cleanUp | 开始关闭流程 |
| 1769931180694 | EngineSocket | close waiting drain | 等待 drain 事件 |

#### 关键差异点 - 时序问题

在 err.log 中，**在第一次连接关闭期间，websocket probe 成功了**：

```
1769931180698 I Transport websocket@260110639 onOpen, state OPENING
1769931180698 I EngineSocket probe transport websocket opened, failed: false
1769931180698 D Transport websocket@260110639 send: state OPEN, 1 packets
1769931180698 D Transport websocket@260110639 doSend: Ping(payload=probe), `2probe`
1769931180702 D Transport websocket@260110639 onWsText: `3probe`
1769931180702 D Transport websocket@260110639 onPacket Pong(payload=probe)
1769931180702 I EngineSocket probe transport websocket pong
1769931180702 I EngineSocket pausing current transport polling
1769931180702 I Transport polling@1929439163 pause
```

**这是关键差异**：在失败场景中，websocket probe 在 socket 关闭流程中成功了，触发了 transport 升级流程。

#### Transport 升级流程（在关闭过程中！）

```
1769931180702 I EngineSocket pausing current transport polling
1769931180702 I Transport polling@1929439163 pause: wait polling
1769931180702 I Transport polling@1929439163 pause: wait writing
1769931180703 D Transport polling@1929439163 onPollComplete: state PAUSED
1769931180703 I Transport polling@1929439163 pause: pre-pause polling complete
1769931180705 I Transport polling@1929439163 pause: pre-pause writing complete
1769931180705 I Transport polling@1929439163 paused
1769931180705 I EngineSocket changing transport and sending upgrade packet
1769931180705 D Transport websocket@260110639 send: state OPEN, 1 packets  <-- 发送 upgrade packet
1769931180705 D Transport websocket@260110639 doSend: Upgrade, `5`
1769931180705 I EngineSocket upgrade packet send success
1769931180705 I EngineSocket close waiting upgrade success
1769931180705 I EngineSocket socket closing - telling transport to close
1769931180705 I EngineSocket onClose force close
1769931180705 I Transport polling@1929439163 close, state PAUSED  <-- 关闭旧 transport
1769931180705 I EngineSocket setTransport websocket  <-- 切换到新 transport
```

#### 第二次连接开始（在第一次的升级过程中！）

```
1769931180695 I Socket open: connected false, io reconnecting false
1769931180695 I EngineSocket createTransport polling
1769931180695 I EngineSocket setTransport polling
1769931180695 D Transport polling@2125549749 open  <-- 新的 polling transport！
```

**关键问题**：第二次连接在第一次连接的升级/关闭过程中开始了！

#### 异常发生

```
1769931180705 I EngineSocket setTransport websocket
1769931180705 I EngineSocket clearing existing transport polling
1769931180705 D EngineSocket flush: state CLOSED, writable true, upgrading false, prevBufferLen 0, writeBuffer.size 0
1769931180705 I EngineSocket flush ignored: state CLOSED, transport.writable true, upgrading false, writeBuffer.size 0, prevBufferLen 0
1769931180709 D Transport polling@2125549749 doRequest GET ... response: 200 OK  <-- 第二个连接的 polling 收到响应
...
1769931180712 D Transport websocket@260110639 doSend 1 packets finish  <-- 第一个连接的 websocket 发送完成
1769931180712 D EngineSocket onDrain: prevBufferLen 0, writeBuffer.size 0, len 1  <-- onDrain 被调用！
```

**异常栈**：
```
Exception in thread "DefaultDispatcher-worker-10 @coroutine#1018" java.util.NoSuchElementException: ArrayDeque is empty.
    at kotlin.collections.ArrayDeque.removeFirst(ArrayDeque.kt:146)
    at com.piasy.kmp.socketio.engineio.EngineSocket.onDrain(EngineSocket.kt:280)
```
        
            
toolName: todo_write
            
status: success
          
Todos updated: 5 items
          
### 三、关键差异对比

| 维度 | Good Log（成功） | Err Log（失败） |
|------|-----------------|-----------------|
| **第一次连接关闭时** | websocket probe 被中断，未成功 | websocket probe 成功，触发升级流程 |
| **Transport 切换时机** | 无切换（probe 失败） | 在关闭过程中发生切换 |
| **第二次连接启动时机** | 第一次完全关闭后 | 第一次关闭/升级过程中 |
| **并发情况** | 无并发问题 | 两个 EngineSocket 实例并发操作 |
| **writeBuffer 状态** | 正常 | 被错误地操作（空队列 remove）|

### 四、onDrain 异常根本原因分析

查看代码 [EngineSocket.kt:276-290](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/EngineSocket.kt#L276-L290)：

```kotlin
@WorkThread
private fun onDrain(len: Int) {
    Logging.debug(TAG) { "onDrain: prevBufferLen $prevBufferLen, writeBuffer.size ${writeBuffer.size}, len $len" }
    for (i in 1..len) {
        writeBuffer.removeAt(0)  // 第280行，抛出异常
    }
    prevBufferLen -= len
    // ...
}
```

异常发生时的状态（从日志推断）：

```
EngineSocket onDrain: prevBufferLen 0, writeBuffer.size 0, len 1
```

**问题分析**：

1. **writeBuffer 是 ArrayDeque**，在 [EngineSocket.kt:54](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/EngineSocket.kt#L54) 定义：
   ```kotlin
   internal val writeBuffer = ArrayDeque<EngineIOPacket<*>>()
   ```

2. **事件监听器注册问题**：
   - 在 `setTransport` 方法中，为 transport 注册了 `EVENT_DRAIN` 监听器
   - 监听器通过 `On.on(transport, Transport.EVENT_DRAIN, ...)` 注册

3. **并发/时序问题**：
   - 第一个 EngineSocket（transport: websocket@260110639）在发送 upgrade packet 后，等待 drain 事件
   - 第二个 EngineSocket（transport: polling@2125549749）被创建，有自己的 writeBuffer
   - **关键**：第一个 EngineSocket 的 websocket transport 完成发送后，触发了 drain 事件
   - 但此时第一个 EngineSocket 的 writeBuffer 已经被清空（因为在关闭流程中）

4. **为什么 len=1 但 writeBuffer 为空？**
   
   看 [WebSocket.doSend](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/transports/WebSocket.kt#L140-L172)：
   ```kotlin
   override fun doSend(packets: List<EngineIOPacket<*>>) {
       logD { "doSend ${packets.size} packets start" }
       writable = false
       ioScope.launch {
           // ... 发送逻辑 ...
           scope.launch {
               logD { "doSend ${packets.size} packets finish" }
               writable = true
               emit(EVENT_DRAIN, packets.size)  // 发送完成后触发 drain
           }
       }
   }
   ```
   
   websocket 在发送完成时会触发 `EVENT_DRAIN` 事件，并传递 `packets.size` 作为参数。

5. **问题根源**：
   - 在 probe 成功后的升级流程中，第一个 EngineSocket 发送了 upgrade packet（1个 packet）
   - 但在 drain 事件触发前，socket 进入了关闭流程，`writeBuffer` 被清空
   - 当 drain 事件最终触发时，`len=1`（发送时的 packet 数量），但 `writeBuffer` 已经被清空
        
            
toolName: todo_write
            
status: success
          
Todos updated: 5 items
          
### 五、问题根因总结

#### 核心问题

这是一个**竞态条件（Race Condition）**问题，发生在以下场景：

1. **触发条件**：第一次连接关闭时，websocket probe 恰好成功
2. **升级流程启动**：`probe` 方法中的 `onTransportOpen` 回调被触发，开始 transport 升级
3. **关闭流程与升级流程并发**：
   - 关闭流程：调用 `close()` → `closeAction()` → 清空 writeBuffer
   - 升级流程：发送 upgrade packet → 等待 drain 事件 → 切换 transport
4. **时序问题**：upgrade packet 发送完成（触发 drain 事件）发生在 writeBuffer 被清空之后

#### 详细时序

```
时间点 T1: socket.close() 被调用
           ↓
时间点 T2: EngineSocket.close() 开始
           - 状态变为 CLOSING
           - 如果 writeBuffer 不为空，等待 drain 事件
           ↓
时间点 T3: websocket probe 成功（在另一个协程中）
           - 触发 onTransportOpen
           - 调用 currentTransport.pause()
           ↓
时间点 T4: pause 完成，发送 upgrade packet
           - transport.send(listOf(EngineIOPacket.Upgrade))
           - prevBufferLen = writeBuffer.size (可能是 0 或 1)
           ↓
时间点 T5: close 流程继续
           - writeBuffer 被清空（通过 onDrain 或直接清空）
           ↓
时间点 T6: upgrade packet 发送完成
           - WebSocket.doSend 中的 ioScope.launch 完成
           - 触发 EVENT_DRAIN，len=1
           ↓
时间点 T7: onDrain(1) 被调用
           - prevBufferLen = 0, writeBuffer.size = 0
           - 尝试 writeBuffer.removeAt(0) → 抛出 NoSuchElementException
```

#### 为什么这是偶现的

这个 bug 是**时序敏感的**：

- 如果 websocket probe 在 `socket.close()` 调用前完成 → 正常升级，无问题
- 如果 websocket probe 在 `socket.close()` 调用后、但 `pause` 前完成 → 触发此 bug
- 如果 websocket probe 在 `pause` 完成后才收到 pong → probe 失败，无问题（如 good.log）

#### 涉及的关键代码位置

1. **[EngineSocket.probe](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/EngineSocket.kt#L401-L522)** - probe 逻辑
2. **[EngineSocket.close](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/EngineSocket.kt#L147-L197)** - 关闭逻辑
3. **[EngineSocket.onDrain](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/EngineSocket.kt#L276-L290)** - drain 事件处理
4. **[EngineSocket.setTransport](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/EngineSocket.kt#L227-L274)** - transport 切换
5. **[WebSocket.doSend](file:///Users/linker/Downloads/personal/kmp-socketio-ut-case/kmp-socketio/kmp-socketio/src/commonMain/kotlin/com/piasy/kmp/socketio/engineio/transports/WebSocket.kt#L140-L172)** - 异步发送逻辑
        
            
toolName: todo_write
            
status: success
          
Todos updated: 5 items
          
---

## 分析完成

以上是对 kmp-socketio 项目偶现 UT 失败问题的完整分析。问题的本质是：

**在 socket 关闭流程中，如果 websocket probe 恰好在此时成功，会导致 transport 升级流程与关闭流程产生竞态条件，最终触发 `ArrayDeque.removeFirst` 异常。**

这是一个时序敏感的并发问题，解释了为什么该 UT 是"偶现"失败的。
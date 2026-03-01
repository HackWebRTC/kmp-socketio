# kmp-socketio reconnectManually UT 失败问题分析报告

## 测试用例流程概述

`reconnectManually` 测试用例的执行流程：
1. **第一次连接**: `socket.open()` 建立初始连接 (polling → websocket probe)
2. **第一次关闭**: 连接成功后立即 `socket.close()`
3. **重连**: 在 `EVENT_DISCONNECT` 回调中再次调用 `socket.open()`
4. **第二次关闭**: 重连成功后关闭 socket，测试结束

---

## 问题现象

异常信息：
```
java.util.NoSuchElementException: ArrayDeque is empty.
    at kotlin.collections.ArrayDeque.removeFirst(ArrayDeque.kt:146)
    at com.piasy.kmp.socketio.engineio.EngineSocket.onDrain(EngineSocket.kt:280)
```

发生在 `onDrain` 方法中，当尝试从 `writeBuffer` 移除元素时，但 `writeBuffer` 已经为空。

---

## 关键 Transport 实例分析 (err.log)

### 第一个连接生命周期
| 时间戳 | Transport | 事件 |
|--------|-----------|------|
| 0665ms | polling@1929439163 | 创建并打开 |
| 0680ms | polling@1929439163 | 收到 Open 包，连接建立 |
| 0680ms | websocket@260110639 | 创建（probe） |
| 0694ms | polling@1929439163 | 发送 Disconnect，开始关闭 |
| 0695ms | polling@2125549749 | **新的 transport 创建（重连）** |
| 0698ms | websocket@260110639 | probe 完成，准备 upgrade |
| 0705ms | websocket@260110639 | 成为主 transport，替换 polling |
| 0712ms | websocket@260110639 | **发送完成触发 onDrain(1)，但 writeBuffer 为空 → 异常** |

---

## 根因分析：竞态条件

### 1. 时序问题

在 `err.log` 中，关键的时间竞态：

```
0694ms: socket.close() 被调用
        └─ EngineSocket.close() 发现 writeBuffer 有 1 个包（Disconnect）
        └─ 等待 drain 事件...

0695ms: 重连开始，创建 polling@2125549749
        └─ 调用 setTransport()，为新 transport 注册事件监听器

0696ms: polling@1929439163 完成发送 Disconnect，触发 EVENT_DRAIN(1)
        └─ onDrain(1): writeBuffer 从 1 变为 0，触发 EVENT_DRAIN
        └─ close() 继续执行，发现 upgrading=true，等待 upgrade...

0698ms: websocket@260110639 probe 完成
0705ms: upgrade 流程执行 setTransport(websocket)
        └─ 为 websocket@260110639 注册 EVENT_DRAIN 监听器

0705ms: websocket@260110639 发送 upgrade 包完成
        └─ 在 ioScope 中异步触发 EVENT_DRAIN(1)

0712ms: scope.launch 执行，触发 EVENT_DRAIN(1)
        └─ 新注册的监听器接收到事件，调用 onDrain(1)
        └─ **但此时 writeBuffer 已经是空的（之前被清空了）**
        └─ **抛出 NoSuchElementException**
```

### 2. 代码层面的问题

#### onDrain 方法的假设

```kotlin
@WorkThread
private fun onDrain(len: Int) {
    // 假设：writeBuffer 中至少有 len 个元素
    for (i in 1..len) {
        writeBuffer.removeAt(0)  // 当 writeBuffer 为空时崩溃
    }
    prevBufferLen -= len
    // ...
}
```

`onDrain` 假设 `writeBuffer` 中的元素数量与 `len` 参数匹配。但这个假设在以下场景下会被打破：

1. **Transport 切换期间**：旧的 transport 完成了它自己的发送，触发了 `EVENT_DRAIN`
2. **Buffer 已被清空**：在 `close()` 流程中，`writeBuffer` 可能已经被 drain
3. **事件监听器已更换**：`setTransport()` 为新 transport 注册了监听器，但旧的异步事件可能到达

#### WebSocket.doSend 的异步特性

```kotlin
ioScope.launch {
    // 发送操作在 ioScope 中异步执行
    for (pkt in packets) {
        // ...
    }

    scope.launch {
        // 回调在 scope 中执行，可能延迟
        emit(EVENT_DRAIN, packets.size)  // 可能延迟到 setTransport 之后
    }
}
```

`EVENT_DRAIN` 的触发是异步的，可能在 `setTransport` 完成之后。此时：
- 新 transport 的事件监听器已注册
- 但事件来自旧的（或正在升级的）transport
- `writeBuffer` 的状态已经不匹配

### 3. 成功场景 (good.log) vs 失败场景 (err.log)

| 场景 | socket 关闭时机 | websocket probe 结果 | 是否触发异常 |
|------|----------------|---------------------|------------|
| **失败 (err.log)** | probe 进行中关闭 | probe 在关闭后完成，触发 upgrade | ✅ 是 |
| **成功 (good.log)** | probe 进行中关闭 | probe 被中断（"socket closed"） | ❌ 否 |

在成功的场景中，websocket probe 在 socket 关闭时被中断，不会完成 upgrade 流程，因此不会触发那个导致异常的 `EVENT_DRAIN`。

---

## Transport 状态转换图

### 失败场景的详细状态转换

```
[第一次连接建立]
  │
  ├─ polling@1929439163: INIT → OPENING → OPEN
  ├─ websocket@260110639: 创建作为 probe (OPENING)
  │
[第一次 socket.close()]
  │
  ├─ EngineSocket.state: OPEN → CLOSING
  ├─ polling@1929439163: 发送 Disconnect 包
  ├─ 等待 drain 事件...
  │
[重连开始 - 在 DISCONNECT 回调中]
  │
  ├─ polling@2125549749: 创建（新的 transport）
  ├─ setTransport(polling@2125549749): 注册新监听器
  │
[polling@1929439163 发送完成]
  │
  ├─ EVENT_DRAIN(1) → onDrain(1): writeBuffer 从 1→0
  ├─ close() 继续: 发现 upgrading=true，等待 upgrade
  │
[websocket probe 完成 - 在关闭之后！]
  │
  ├─ websocket@260110639: 发送 Pong probe 成功
  ├─ upgrade 流程开始
  ├─ pause polling@1929439163
  ├─ setTransport(websocket@260110639): 为 websocket 注册监听器
  │  └─ subs 现在包含 websocket 的监听器
  ├─ websocket@260110639: 发送 upgrade 包
  │
[websocket 发送完成 - 异步回调]
  │
  ├─ (在 ioScope 中) 发送完成
  ├─ (在 scope.launch 中) emit(EVENT_DRAIN, 1)
  │  └─ 此时 subs 中有 websocket 的监听器
  │  └─ 调用 onDrain(1)
  │      └─ writeBuffer 已经是空的！
  │      └─ **抛出 NoSuchElementException**
```

---

## 关键代码路径

### 1. EngineSocket.close()

```kotlin
fun close() {
    // ...
    if (writeBuffer.isNotEmpty()) {
        once(EVENT_DRAIN, object : Listener {
            override fun call(vararg args: Any) {
                if (upgrading) {
                    // 等待 upgrade 完成
                    once(EVENT_UPGRADE, cleanupAndClose)
                } else {
                    closeAction()  // 最终调用 onClose
                }
            }
        })
    }
    // ...
}
```

### 2. EngineSocket.setTransport()

```kotlin
private fun setTransport(transport: Transport) {
    // 清理旧 transport 的监听器
    for (sub in subs) { sub.destroy() }
    subs.clear()

    this.transport = transport

    // 为新 transport 注册监听器
    subs.add(On.on(transport, Transport.EVENT_DRAIN, object : Listener {
        override fun call(vararg args: Any) {
            if (args.isNotEmpty() && args[0] is Int) {
                onDrain(args[0] as Int)  // 可能接收到来自其他 transport 的事件
            }
        }
    }))
    // ...
}
```

### 3. WebSocket.doSend()

```kotlin
override fun doSend(packets: List<EngineIOPacket<*>>) {
    writable = false

    ioScope.launch {
        for (pkt in packets) {
            if (state != State.OPEN) { break }  // 检查状态，但可能已过时
            // ... 发送
        }

        scope.launch {
            emit(EVENT_DRAIN, packets.size)  // 异步触发，可能在 setTransport 之后
        }
    }
}
```

---

## 总结

这是一个**竞态条件 (Race Condition)** 导致的偶现问题：

1. **触发条件**：
   - Socket 在 websocket probe 进行中时关闭
   - WebSocket probe 在 socket 关闭后才完成
   - Upgrade 流程在 close 等待期间完成

2. **根本原因**：
   - `onDrain` 方法假设 `writeBuffer` 中的元素数量与 `len` 参数匹配
   - 但 transport 切换期间，异步事件可能导致 `onDrain` 被调用时 `writeBuffer` 已被清空
   - `setTransport` 为新 transport 注册监听器，但可能接收到来自旧 transport 的异步事件

3. **为何是偶现**：
   - 取决于 websocket probe 完成和 socket 关闭的时序
   - 如果 probe 在关闭前被中断，不会触发问题
   - 如果 probe 在关闭后完成，就会触发异常

4. **问题本质**：
   - Transport 生命周期管理和事件处理的竞态条件
   - 异步事件（`EVENT_DRAIN`）与同步状态（`writeBuffer`）不同步

==============================================================================
追问：
但是在 EngineSocket.kt 的 probe 函数里，
transport.once(EVENT_DRAIN, object : Listener {
    override fun call(vararg args: Any) {
        Logging.info(TAG, "upgrade packet send success")
        emit(EVENT_UPGRADE, transport)
        setTransport(transport)
        cleaned = true
        upgrading = false
        flush()
    }
})
transport.send(listOf(EngineIOPacket.Upgrade))setTransport 应该是在 Upgrade 包对应的 EVENT_DRAIN 事件真的收到之后，才会执行的啊，也不用管 WebSocket.doSend 和 EVENT_DRAIN 触发是否异步，这个先后顺序是保证了的啊，为什么 EVENT_DRAIN 来了之后，还有 EVENT_DRAIN 呢？
==============================================================================
它提到 emit 的实现有可能问题：如果 EventEmitter 的实现是在 emit 过程中实时遍历监听器列表（而不是事先复制）
==============================================================================
进一步追问：你能看看 emit 的实现方式是哪种吗？
==============================================================================
然后它就开始不停猜测、推翻、再猜测，最终给出了正确的分析结果
==============================================================================

好的，让我重新仔细分析 err.log 和 WebSocket.kt 的实现。
我现在明白了！关键线索在日志的最后部分：0705ms: Transport websocket@260110639 doSend 1 packets finish  ← Upgrade 发送完成
0705ms: EngineSocket upgrade packet send success               ← 触发 setTransport
0705ms: EngineSocket setTransport websocket                    ← 注册新监听器

...

0712ms: Transport websocket@260110639 doSend 1 packets finish  ← Ping 发送完成！
0712ms: EngineSocket onDrain: prevBufferLen 0, writeBuffer.size 0, len 1  ← 崩溃！问题根源：两次 doSend 调用产生了两个独立的 EVENT_DRAIN 事件！
完整时序分析0698ms: websocket 打开，probe 开始
        ├─ transport.send(listOf(ping))           → 启动第一次 doSend (Ping)
        │   └─ ioScope.launch { 发送 Ping... }    → 在后台线程执行
        │
0702ms: 收到 Pong 响应
        ├─ transport.send(listOf(Upgrade))        → 启动第二次 doSend (Upgrade)
        │   └─ ioScope.launch { 发送 Upgrade... } → 在后台线程执行
        │
0705ms: Upgrade 发送完成（先完成！）
        ├─ emit(EVENT_DRAIN, 1)                   → 触发 probe 的 once 监听器
        ├─ setTransport(websocket)                → 为 websocket 注册 onDrain 监听器
        │
0712ms: Ping 发送完成（后完成！）
        └─ emit(EVENT_DRAIN, 1)                   → 触发 setTransport 中注册的监听器
            └─ onDrain(1)                         → writeBuffer 已空，崩溃！根本原因
probe 中发送了两个包：先 Ping，收到 Pong 后再 Upgrade
两次 doSend 是异步的：都在 ioScope.launch 中执行
完成顺序不确定：虽然 Ping 先发送，但 Upgrade 可能先完成（网络/调度原因）
**两个独立的 emit**：setTransport 在第一个 emit 中执行，但第二个 emit 触发时新监听器已存在
所以你的原始分析是正确的——这是一个竞态条件问题！
修复建议在 onDrain 中添加防御性检查：
private fun onDrain(len: Int) {
    if (writeBuffer.size < len) {
        Logging.error(TAG, "onDrain: buffer underflow, writeBuffer.size ${writeBuffer.size}, len $len")
        return  // 或抛出更有意义的异常
    }
    for (i in 1..len) {
        writeBuffer.removeAt(0)
    }
    // ...
}

或者在 setTransport 中记录当前 transport，在 onDrain 中检查事件来源是否匹配当前的 transport。
这是一个经典的异步竞态问题：两次异步操作，先开始的反而后完成，导致事件处理器的状态不一致。

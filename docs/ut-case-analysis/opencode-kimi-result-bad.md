KMP-SocketIO 偶现 UT 失败分析报告
1. 测试用例说明
测试: reconnectManually (ConnectionTest.java:354-380)
执行流程:
1. 首次 socket.open() 建立连接
2. 首次连接成功后 socket.close() 关闭
3. 收到 EVENT_DISCONNECT 后再次 socket.open() 重新连接
4. 第二次连接成功后关闭并结束
2. Transport 实例日志格式
源自 Transport.kt (line 153-165):
protected fun logI(log: String) {
    Logging.info(TAG, "$name@${hashCode()} $log")
}
- @ 后面的数字是对象的 hashCode()
- 用于区分不同 Transport 实例
- 例如: polling@1929439163 vs polling@709407350 是两个不同的实例
3. Transport 状态机
enum class State { INIT, OPENING, OPEN, CLOSING, CLOSED, PAUSED }
状态转换:

| 操作 | 从状态 | 到状态 | 触发条件 |
|------|--------|--------|----------|
| open() | INIT/CLOSED | OPENING | 初始化连接 |
| onOpen() | OPENING | OPEN | 握手成功 |
| close() | OPENING/OPEN | CLOSING | 主动关闭 |
| onClose() | any | CLOSED | 关闭完成 |
| pause() | OPEN | PAUSED | 升级过程中暂停 |

4. 关键差异对比
good.log（正常执行）

| Transport 实例 | 生命周期 | 关键事件 |
|---------------|----------|----------|
| polling@709407350 | OPEN → CLOSING → Closed | 首次连接 |
| websocket@2087361714 | OPEN → Closed | 探测未完成的 WebSocket |
| polling@969147974 | OPEN → Closed | 第二次连接 |

特点: 首次连接在 WebSocket 升级完成前就关闭了，没有进入 upgrade 流程

err.log（失败执行）

| Transport 实例 | 生命周期 | 关键事件 |
|---------------|----------|----------|
| polling@1929439163 | OPEN → PAUSED → Closed | 首次连接（关键：进入 PAUSED 状态）|
| websocket@260110639 | OPEN → send upgrade → CRASH | 探测成功，但升级时崩溃 |

特点: WebSocket 探测成功收到 PONG，polling 进入 PAUSED 状态，发送 upgrade 包后崩溃
5. 崩溃根因分析
崩溃堆栈:
java.util.NoSuchElementException: ArrayDeque is empty
    at com.piasy.kmp.socketio.engineio.EngineSocket.onDrain(EngineSocket.kt:280)
崩溃代码位置 (EngineSocket.kt:277-290):
private fun onDrain(len: Int) {
    Logging.debug(TAG) { "onDrain: prevBufferLen $prevBufferLen, writeBuffer.size ${writeBuffer.size}, len $len" }
    for (i in 1..len) {
        writeBuffer.removeAt(0)  // <-- 崩溃点
    }
    prevBufferLen -= len
    ...
}
触发时序 (err.log 时间戳 1769931180705 附近):
1769931180702 I EngineSocket probe transport websocket pong     <- 探测成功
1769931180702 I EngineSocket pausing current transport polling  <- 开始暂停
1769931180703 I Transport polling@1929439163 paused             <- polling 已暂停
1769931180705 I EngineSocket changing transport and sending upgrade packet
1769931180705 D Transport websocket@260110639 send: state OPEN, 1 packets
1769931180705 D Transport websocket@260110639 doSend 1 packets start
1769931180705 D Transport websocket@260110639 doSend: Upgrade, `5`
1769931180705 I EngineSocket upgrade packet send success
1769931180705 I EngineSocket close waiting upgrade success
1769931180705 I EngineSocket socket closing - telling transport to close
1769931180705 I EngineSocket onClose force close
1769931180705 I Transport polling@1929439163 close, state PAUSED
1769931180705 I EngineSocket setTransport websocket             <- 切换 transport
...
1769931180712 D EngineSocket onDrain: prevBufferLen 0, writeBuffer.size 0, len 1  <- 问题！
问题分析:
1. onDrain(len=1) 被调用，期望从 writeBuffer 移除 1 个元素
2. 但此时 writeBuffer.size=0 且 prevBufferLen=0
3. 尝试从空 ArrayDeque 移除元素导致崩溃
并发竞态条件:
WebSocket.doSend 在 ioScope（IO 线程）执行：
ioScope.launch {
    // 发送数据...
    scope.launch {
        writable = true
        emit(EVENT_DRAIN, packets.size)  // 跨线程回调
    }
}
当以下情况同时发生时：
- WebSocket 正在发送 upgrade 包
- EngineSocket 被关闭 (setTransport 被调用)
- writeBuffer 被清空
onDrain 收到 drain 事件时 buffer 已被清空，导致崩溃。
6. 正常与失败的本质区别

| 条件 | good.log | err.log |
|------|----------|---------|
| WebSocket 探测 | 未完成（无 PONG） | 成功完成（有 PONG） |
| polling 状态 | 未进入 PAUSED | 进入 PAUSED 状态 |
| 升级流程 | 未触发 | 触发但崩溃 |
| 关闭时机 | 探测完成前 | 探测完成后 |

根本差异: 时序差异导致一个走了 upgrade 路径（失败），一个没走（成功）。
7. 结论
这是一个并发时序竞态问题：
- 触发条件: 在 WebSocket 升级过程中（从 polling 切换到 websocket 时），如果发生 socket 关闭
- 崩溃原因: onDrain 回调尝试从已被清空的 writeBuffer 移除元素
- 偶现原因: 依赖于 IO 线程与工作线程的执行时序，只在特定时间窗口内触发
不涉及修复，仅做分析。
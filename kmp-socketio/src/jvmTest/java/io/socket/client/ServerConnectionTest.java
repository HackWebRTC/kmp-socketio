package io.socket.client;

import com.piasy.kmp.socketio.emitter.Emitter;
import com.piasy.kmp.socketio.engineio.EngineSocket;
import com.piasy.kmp.socketio.engineio.TestUtil;
import com.piasy.kmp.socketio.engineio.Transport;
import com.piasy.kmp.socketio.engineio.transports.PollingXHR;
import com.piasy.kmp.socketio.engineio.transports.WebSocket;
import com.piasy.kmp.socketio.socketio.Ack;
import com.piasy.kmp.socketio.socketio.IO;
import com.piasy.kmp.socketio.socketio.Manager;
import com.piasy.kmp.socketio.socketio.Socket;
import kotlin.Unit;
import kotlinx.serialization.json.JsonElementBuildersKt;
import kotlinx.serialization.json.JsonElementKt;
import kotlinx.serialization.json.JsonObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.assertThat;

@RunWith(JUnit4.class)
public class ServerConnectionTest extends Connection {

    @Test(timeout = TIMEOUT)
    public void openAndClose() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();

        client("/", socket -> {
            this.socket = socket;
            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    values.offer(args);
                    socket.close();
                }
            }).on(Socket.EVENT_DISCONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    values.offer(args);
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });

        assertThat(((Object[])values.take()).length, is(0));
        Object[] args = (Object[] )values.take();
        assertThat(args.length, is(1));
        assertThat(args[0], is(instanceOf(String.class)));
    }

    @Test(timeout = TIMEOUT)
    public void message() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();

        client("/", socket -> {
            this.socket = socket;
            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... objects) {
                    socket.send("foo", "bar");
                }
            }).on(Socket.EVENT_MESSAGE, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    values.offer(args);
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });

        assertThat((Object[])values.take(), is(new Object[] {"hello client"}));
        assertThat((Object[])values.take(), is(new Object[] {"foo", "bar"}));
    }

    @Test(timeout = TIMEOUT)
    public void event() throws Exception {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();

        JsonObject obj = JsonElementBuildersKt.buildJsonObject(builder -> {
            builder.put("foo", JsonElementKt.JsonPrimitive(1));
            return Unit.INSTANCE;
        });

        client("/", socket -> {
            this.socket = socket;
            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... objects) {
                    socket.emit("echo", obj, "null", "bar");
                }
            }).on("echoBack", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    values.offer(args);
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });

        Object[] args = (Object[])values.take();
        assertThat(args.length, is(3));
        assertThat(args[0].toString(), is(obj.toString()));
        assertThat(args[1], is("null"));
        assertThat((String)args[2], is("bar"));
    }

    @Test(timeout = TIMEOUT)
    public void ack() throws Exception {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();

        JsonObject obj = JsonElementBuildersKt.buildJsonObject(builder -> {
            builder.put("foo", JsonElementKt.JsonPrimitive(1));
            return Unit.INSTANCE;
        });

        client("/", socket -> {
            this.socket = socket;
            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... objects) {
                    socket.emit("ack", obj, "bar", new Ack() {
                        @Override
                        public void call(Object... args) {
                            values.offer(args);
                        }
                    });
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });

        Object[] args = (Object[])values.take();
        assertThat(args.length, is(2));
        assertThat(args[0].toString(), is(obj.toString()));
        assertThat((String)args[1], is("bar"));
    }

    // in KMP definition, args could not be null, so we shouldn't have this case.
//    @Test(timeout = TIMEOUT)
//    public void ackWithoutArgs() throws InterruptedException {
//        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();
//
//        client("/", socket -> {
//            this.socket = socket;
//            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
//                @Override
//                public void call(Object... objects) {
//                    socket.emit("ack", null, new Ack() {
//                        @Override
//                        public void call(Object... args) {
//                            values.offer(args.length);
//                        }
//                    });
//                }
//            });
//            socket.open();
//            return Unit.INSTANCE;
//        });
//
//        assertThat((Integer)values.take(), is(0));
//        socket.close();
//    }

    @Test(timeout = TIMEOUT)
    public void ackWithoutArgsFromClient() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();

        client("/", socket -> {
            this.socket = socket;
            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... objects) {
                    socket.on("ack", new Emitter.Listener() {
                        @Override
                        public void call(Object... args) {
                            values.offer(args);
                            Ack ack = (Ack) args[0];
                            ack.call();
                        }
                    }).on("ackBack", new Emitter.Listener() {
                        @Override
                        public void call(Object... args) {
                            values.offer(args);
                            socket.close();
                        }
                    });
                    socket.emit("callAck");
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });

        Object[] args = (Object[])values.take();
        assertThat(args.length, is(1));
        assertThat(args[0], is(instanceOf(Ack.class)));
        args = (Object[])values.take();
        assertThat(args.length, is(0));
    }

    @Test(timeout = TIMEOUT)
    public void closeEngineConnection() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();

        client("/", socket -> {
            this.socket = socket;
            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    TestUtil.engineSocket(socket).on(EngineSocket.EVENT_CLOSE, new Emitter.Listener() {
                        @Override
                        public void call(Object... objects) {
                            values.offer("done");
                        }
                    });
                    socket.close();
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });
        values.take();
    }

    @Test(timeout = TIMEOUT)
    public void broadcast() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();

        ServerConnectionTest self = this;
        client("/", socket -> {
            this.socket = socket;
            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... objects) {
                    client("/", socket2 -> {
                        self.socket2 = socket2;

                        socket2.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                            @Override
                            public void call(Object... objects) {
                                socket2.emit("broadcast", "hi");
                            }
                        });
                        socket2.open();
                        return Unit.INSTANCE;
                    });
                }
            }).on("broadcastBack", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    values.offer(args);
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });

        Object[] args = (Object[])values.take();
        assertThat(args.length, is(1));
        assertThat((String)args[0], is("hi"));
    }

    @Test(timeout = TIMEOUT)
    public void room() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();

        client("/", socket -> {
            this.socket = socket;
            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... objects) {
                    socket.emit("room", "hi");
                }
            }).on("roomBack", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    values.offer(args);
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });

        Object[] args = (Object[])values.take();
        assertThat(args.length, is(1));
        assertThat((String)args[0], is("hi"));
    }

    @Test(timeout = TIMEOUT)
    public void pollingHeaders() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();

        IO.Options opts = createOptions();
        opts.transports = Arrays.asList(PollingXHR.NAME);
        client("/", opts, socket -> {
            this.socket = socket;
            socket.getIo().on(Manager.EVENT_TRANSPORT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    Transport transport = (Transport) args[0];
                    transport.on(Transport.EVENT_REQUEST_HEADERS, new Emitter.Listener() {
                        @Override
                        public void call(Object... args) {
                            @SuppressWarnings("unchecked")
                            Map<String, List<String>> headers = (Map<String, List<String>>) args[0];
                            headers.put("X-SocketIO", Arrays.asList("hi"));
                        }
                    }).on(Transport.EVENT_RESPONSE_HEADERS, new Emitter.Listener() {
                        @Override
                        public void call(Object... args) {
                            @SuppressWarnings("unchecked")
                            Map<String, List<String>> headers = (Map<String, List<String>>) args[0];
                            List<String> value = headers.get("X-SocketIO");
                            values.offer(value != null ? value.get(0) : "");
                        }
                    });
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });

        assertThat((String)values.take(), is("hi"));
    }

    @Test(timeout = TIMEOUT)
    public void websocketHandshakeHeaders() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();

        IO.Options opts = createOptions();
        opts.transports = Arrays.asList(WebSocket.NAME);
        client("/", opts, socket -> {
            this.socket = socket;
            socket.getIo().on(Manager.EVENT_TRANSPORT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    Transport transport = (Transport) args[0];
                    transport.on(Transport.EVENT_REQUEST_HEADERS, new Emitter.Listener() {
                        @Override
                        public void call(Object... args) {
                            @SuppressWarnings("unchecked")
                            Map<String, List<String>> headers = (Map<String, List<String>>) args[0];
                            headers.put("X-SocketIO", Arrays.asList("hi"));
                        }
                    }).on(Transport.EVENT_RESPONSE_HEADERS, new Emitter.Listener() {
                        @Override
                        public void call(Object... args) {
                            @SuppressWarnings("unchecked")
                            Map<String, List<String>> headers = (Map<String, List<String>>) args[0];
                            List<String> value = headers.get("X-SocketIO");
                            values.offer(value != null ? value.get(0) : "");
                        }
                    });
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });

        assertThat((String)values.take(), is("hi"));
    }

    @Test(timeout = TIMEOUT)
    public void disconnectFromServer() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();

        client("/", socket -> {
            this.socket = socket;
            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    socket.emit("requestDisconnect");
                }
            }).on(Socket.EVENT_DISCONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    values.offer("disconnected");
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });
        assertThat((String)values.take(), is("disconnected"));
    }
}

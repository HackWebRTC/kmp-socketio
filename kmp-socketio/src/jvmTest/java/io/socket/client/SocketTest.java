package io.socket.client;

import com.piasy.kmp.socketio.engineio.transports.WebSocket;
import io.socket.util.Optional;
import kotlin.Unit;
import kotlinx.io.bytestring.ByteString;
import kotlinx.serialization.json.JsonObject;

import org.jetbrains.annotations.NotNull;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.*;

import static java.util.Collections.list;
import static java.util.Collections.singletonMap;
import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import com.piasy.kmp.socketio.emitter.Emitter;
import com.piasy.kmp.socketio.engineio.TestUtil;
import com.piasy.kmp.socketio.socketio.Ack;
import com.piasy.kmp.socketio.socketio.AckWithTimeout;
import com.piasy.kmp.socketio.socketio.IO;
import com.piasy.kmp.socketio.socketio.Manager;
import com.piasy.kmp.socketio.socketio.Socket;

@RunWith(JUnit4.class)
public class SocketTest extends Connection {

    @Test(timeout = TIMEOUT)
    public void shouldHaveAnAccessibleSocketIdEqualToServerSideSocketId() throws InterruptedException {
        final BlockingQueue<Optional> values = new LinkedBlockingQueue<>();
        client("/", socket -> {
            this.socket = socket;
            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... objects) {
                    values.offer(Optional.ofNullable(socket.getId()));
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });

        @SuppressWarnings("unchecked")
        Optional<String> id = values.take();
        assertThat(id.isPresent(), is(true));
        assertThat(id.get(), not(TestUtil.engineId(socket))); // distinct ID since Socket.IO v3
    }

    @Test(timeout = TIMEOUT)
    public void shouldHaveAnAccessibleSocketIdEqualToServerSideSocketIdOnCustomNamespace() throws InterruptedException {
        final BlockingQueue<Optional> values = new LinkedBlockingQueue<>();
        client("/foo", socket -> {
            this.socket = socket;
            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... objects) {
                    values.offer(Optional.ofNullable(socket.getId()));
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });

        @SuppressWarnings("unchecked")
        Optional<String> id = values.take();
        assertThat(id.isPresent(), is(true));
        assertThat(id.get(), is(not(TestUtil.engineId(socket)))); // distinct ID since Socket.IO v3
    }

    @Test(timeout = TIMEOUT)
    public void clearsSocketIdUponDisconnection() throws InterruptedException {
        final BlockingQueue<Optional> values = new LinkedBlockingQueue<>();
        client("/", socket -> {
            this.socket = socket;
            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... objects) {
                    socket.on(Socket.EVENT_DISCONNECT, new Emitter.Listener() {
                        @Override
                        public void call(Object... args) {
                            values.offer(Optional.ofNullable(socket.getId()));
                        }
                    });

                    socket.close();
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });
        @SuppressWarnings("unchecked")
        Optional<String> id = values.take();
        assertThat(id.get(), is(""));
    }

    @Test(timeout = TIMEOUT)
    public void doesNotFireConnectErrorIfWeForceDisconnectInOpeningState() throws InterruptedException {
        final BlockingQueue<Optional> values = new LinkedBlockingQueue<>();
        IO.Options opts = new IO.Options();
        opts.timeout = 100;
        client("/", opts, socket -> {
            this.socket = socket;
            socket.on(Socket.EVENT_CONNECT_ERROR, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    values.offer(Optional.of(new Error("Unexpected")));
                }
            });
            socket.open();
            socket.close();
            return Unit.INSTANCE;
        });

        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                values.offer(Optional.empty());
            }
        }, 300);

        @SuppressWarnings("unchecked")
        Optional<Error> err = values.take();
        if (err.isPresent()) throw err.get();
    }

    @Test(timeout = TIMEOUT)
    public void shouldChangeSocketIdUponReconnection() throws InterruptedException {
        final BlockingQueue<Optional> values = new LinkedBlockingQueue<>();
        client("/", socket -> {
            this.socket = socket;
            socket.once(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... objects) {
                    values.offer(Optional.ofNullable(socket.getId()));

                    socket.getIo().on(Manager.EVENT_RECONNECT_ATTEMPT, new Emitter.Listener() {
                        @Override
                        public void call(Object... objects) {
                            values.offer(Optional.ofNullable(socket.getId()));
                        }
                    });

                    socket.once(Socket.EVENT_CONNECT, new Emitter.Listener() {
                        @Override
                        public void call(Object... objects) {
                            values.offer(Optional.ofNullable(socket.getId()));
                        }
                    });

                    TestUtil.closeEngineSocket(socket);
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });
        @SuppressWarnings("unchecked")
        Optional<String> id1 = values.take();

        @SuppressWarnings("unchecked")
        Optional<String> id2 = values.take();
        assertThat(id2.get(), is(""));

        @SuppressWarnings("unchecked")
        Optional<String> id3 = values.take();
        assertThat(id3.get(), is(not(id1.get())));
    }

    @Test(timeout = TIMEOUT)
    public void shouldAcceptAQueryStringOnDefaultNamespace() throws InterruptedException, JSONException {
        final BlockingQueue<Optional> values = new LinkedBlockingQueue<>();
        client("/?c=d", socket -> {
            this.socket = socket;
            socket.emit("getHandshake", new Ack() {
                @Override
                public void call(Object... args) {
                    values.offer(Optional.ofNullable(TestUtil.toJSON(args[0])));
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });

        @SuppressWarnings("unchecked")
        Optional<JSONObject> handshake = values.take();
        assertThat(handshake.get().getJSONObject("query").getString("c"), is("d"));
    }

    @Test(timeout = TIMEOUT)
    public void shouldAcceptAQueryString() throws InterruptedException, JSONException {
        final BlockingQueue<Optional> values = new LinkedBlockingQueue<>();
        client("/abc?b=c&d=e", socket -> {
            this.socket = socket;
            socket.on("handshake", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    values.offer(Optional.ofNullable(TestUtil.toJSON(args[0])));
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });

        @SuppressWarnings("unchecked")
        Optional<JSONObject> handshake = values.take();
        JSONObject query = handshake.get().getJSONObject("query");
        assertThat(query.getString("b"), is("c"));
        assertThat(query.getString("d"), is("e"));
    }

    @Test(timeout = TIMEOUT)
    public void shouldAcceptAnAuthOption() throws InterruptedException, JSONException {
        final BlockingQueue<Optional> values = new LinkedBlockingQueue<>();

        IO.Options opts = new IO.Options();
        opts.auth = singletonMap("token", "abcd");
        client("/abc", opts, socket -> {
            this.socket = socket;
            socket.on("handshake", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    values.offer(Optional.ofNullable(TestUtil.toJSON(args[0])));
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });

        @SuppressWarnings("unchecked")
        Optional<JSONObject> handshake = values.take();
        JSONObject query = handshake.get().getJSONObject("auth");
        assertThat(query.getString("token"), is("abcd"));
    }

    @Test(timeout = TIMEOUT)
    public void shouldFireAnErrorEventOnMiddlewareFailure() throws InterruptedException, JSONException {
        final BlockingQueue<Optional> values = new LinkedBlockingQueue<>();

        client("/no", socket -> {
            this.socket = socket;
            socket.on(Socket.EVENT_CONNECT_ERROR, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    values.offer(Optional.ofNullable(TestUtil.toJSON(args[0])));
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });

        @SuppressWarnings("unchecked")
        JSONObject error = ((Optional<JSONObject>) values.take()).get();
        assertThat(error.getString("message"), is("auth failed"));
        assertThat(error.getJSONObject("data").getString("a"), is("b"));
        assertThat(error.getJSONObject("data").getInt("c"), is(3));
    }

    @Test(timeout = TIMEOUT)
    public void shouldThrowOnReservedEvent() throws InterruptedException {
        final BlockingQueue<Optional> values = new LinkedBlockingQueue<>();

        client("/no", socket -> {
            this.socket = socket;
            socket.on(Socket.EVENT_ERROR, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    values.offer(Optional.ofNullable(args[0]));
                }
            });
            socket.emit("disconnecting", "goodbye");
            return Unit.INSTANCE;
        });
        assertThat((String) values.take().get(), is("emit reserved event: disconnecting"));
    }

    @Test(timeout = TIMEOUT)
    public void shouldEmitEventsInOrder() throws InterruptedException {
        final BlockingQueue<String> values = new LinkedBlockingQueue<>();

        client("/", socket -> {
            this.socket = socket;

            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... objects) {
                    socket.emit("ack", "second", new Ack() {
                        @Override
                        public void call(Object... args) {
                            values.offer((String) args[0]);
                        }
                    });
                }
            });

            socket.emit("ack", "first", new Ack() {
                @Override
                public void call(Object... args) {
                    values.offer((String) args[0]);
                }
            });

            socket.open();
            return Unit.INSTANCE;
        });
        assertThat(values.take(), is("first"));
        assertThat(values.take(), is("second"));
    }

    @Test(timeout = TIMEOUT)
    public void shouldTimeoutAfterTheGivenDelayWhenSocketIsNotConnected() throws InterruptedException {
        final BlockingQueue<Boolean> values = new LinkedBlockingQueue<>();

        client("/", socket -> {
            this.socket = socket;

            socket.emit("event", new AckWithTimeout(50) {
                @Override
                public void onSuccess(@NotNull Object @NotNull ... args) {
                    fail();
                }

                @Override
                public void onTimeout() {
                    values.offer(true);
                }
            });
            return Unit.INSTANCE;
        });

        assertThat(values.take(), is(true));
    }

    @Test(timeout = TIMEOUT)
    public void shouldTimeoutWhenTheServerDoesNotAcknowledgeTheEvent() throws InterruptedException {
        final BlockingQueue<Boolean> values = new LinkedBlockingQueue<>();

        client("/", socket -> {
            this.socket = socket;

            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    socket.emit("unknown", new AckWithTimeout(50) {
                        @Override
                        public void onTimeout() {
                            values.offer(true);
                        }

                        @Override
                        public void onSuccess(Object... args) {
                            fail();
                        }
                    });
                }
            });

            socket.open();
            return Unit.INSTANCE;
        });

        assertThat(values.take(), is(true));
    }

    @Test(timeout = TIMEOUT)
    public void shouldTimeoutWhenTheServerDoesNotAcknowledgeTheEventInTime() throws InterruptedException {
        final BlockingQueue<Boolean> values = new LinkedBlockingQueue<>();

        client("/", socket -> {
            this.socket = socket;

            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    socket.emit("ack", new AckWithTimeout(0) {
                        @Override
                        public void onTimeout() {
                            values.offer(true);
                        }

                        @Override
                        public void onSuccess(Object... args) {
                            fail();
                        }
                    });
                }
            });

            socket.open();
            return Unit.INSTANCE;
        });

        assertThat(values.take(), is(true));
    }

    @Test(timeout = TIMEOUT)
    public void shouldNotTimeoutWhenTheServerDoesAcknowledgeTheEvent() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();

        client("/", socket -> {
            this.socket = socket;

            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    socket.emit("ack", 1, "2", new ByteString(new byte[]{3}, 0, 1), new AckWithTimeout(200) {
                        @Override
                        public void onTimeout() {
                            fail();
                        }

                        @Override
                        public void onSuccess(Object... args) {
                            for (Object arg : args) {
                                values.offer(arg);
                            }
                        }
                    });
                }
            });

            socket.open();
            return Unit.INSTANCE;
        });

        assertThat((Integer) values.take(), is(1));
        assertThat((String) values.take(), is("2"));
        assertThat(((ByteString) values.take()).getBackingArrayReference(), is(new byte[] { 3 }));
    }

    @Test(timeout = TIMEOUT)
    public void shouldCallCatchAllListenerForIncomingPackets() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();

        client("/", socket -> {
            this.socket = socket;

            socket.on("message", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    socket.emit("echo", 1, "2", new ByteString(new byte[]{3}, 0, 1));
                }
            }).on("echoBack", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    for (Object arg : args) {
                        values.offer(arg);
                    }
                }
            });

            socket.open();
            return Unit.INSTANCE;
        });

        assertThat((Integer) values.take(), is(1));
        assertThat((String) values.take(), is("2"));
        assertThat(((ByteString) values.take()).getBackingArrayReference(), is(new byte[] { 3 }));
    }

    @Test(timeout = TIMEOUT)
    public void shouldCallCatchAllListenerForOutgoingPackets() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();

        client("/", socket -> {
            this.socket = socket;

            socket.emit("echo", 1, "2", new ByteString(new byte[]{3}, 0, 1));

            socket.on("echoBack", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    for (Object arg : args) {
                        values.offer(arg);
                    }
                }
            });

            socket.open();
            return Unit.INSTANCE;
        });

        assertThat((Integer) values.take(), is(1));
        assertThat((String) values.take(), is("2"));
        assertThat(((ByteString) values.take()).getBackingArrayReference(), is(new byte[] { 3 }));
    }

    @Test(timeout = TIMEOUT)
    public void wsBinaryTest() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();

        IO.Options opts = new IO.Options();
        opts.transports = Arrays.asList(WebSocket.NAME);
        client("/", opts, socket -> {
            this.socket = socket;

            socket.emit("echo", 1, "2", new ByteString(new byte[]{3}, 0, 1));

            socket.on("echoBack", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    for (Object arg : args) {
                        values.offer(arg);
                    }
                }
            });

            socket.open();
            return Unit.INSTANCE;
        });

        assertThat((Integer) values.take(), is(1));
        assertThat((String) values.take(), is("2"));
        assertThat(((ByteString) values.take()).getBackingArrayReference(), is(new byte[] { 3 }));
    }
}

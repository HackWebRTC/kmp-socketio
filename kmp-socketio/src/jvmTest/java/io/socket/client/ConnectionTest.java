package io.socket.client;

import com.piasy.kmp.socketio.emitter.Emitter;
import com.piasy.kmp.socketio.engineio.TestUtil;
import com.piasy.kmp.socketio.logging.Logger;
import com.piasy.kmp.socketio.socketio.Ack;
import com.piasy.kmp.socketio.socketio.IO;
import com.piasy.kmp.socketio.socketio.Manager;
import com.piasy.kmp.socketio.socketio.Socket;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import kotlinx.serialization.json.*;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertThat;

@RunWith(JUnit4.class)
public class ConnectionTest extends Connection {

    private Socket socket;

    @Test(timeout = TIMEOUT)
    public void connectToLocalhost() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();
        client("/", socket -> {
            this.socket = socket;
            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... objects) {
                    socket.emit("echo");
                    socket.on("echoBack", new Emitter.Listener() {
                        @Override
                        public void call(Object... args) {
                            values.offer("done");
                        }
                    });
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });

        values.take();
        socket.close();
    }

    @Test(timeout = TIMEOUT)
    public void startTwoConnectionsWithSamePath() throws InterruptedException {
        final BlockingQueue<Socket> values = new LinkedBlockingQueue<>();
        client("/", socket -> {
            values.offer(socket);
            return Unit.INSTANCE;
        });
        client("/", socket -> {
            values.offer(socket);
            return Unit.INSTANCE;
        });

        assertThat(values.take(), not(equalTo(values.take())));
    }

    @Test(timeout = TIMEOUT)
    public void startTwoConnectionsWithSamePathAndDifferentQuerystrings() throws InterruptedException {
        final BlockingQueue<Socket> values = new LinkedBlockingQueue<>();
        client("/?woot", socket -> {
            values.offer(socket);
            return Unit.INSTANCE;
        });
        client("/", socket -> {
            values.offer(socket);
            return Unit.INSTANCE;
        });

        assertThat(values.take(), not(equalTo(values.take())));
    }

    @Test(timeout = TIMEOUT)
    public void workWithAcks() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();
        client("/", socket -> {
            this.socket = socket;
            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... objects) {
                    socket.emit("callAck");
                    socket.on("ack", new Emitter.Listener() {
                        @Override
                        public void call(Object... args) {
                            Ack fn = (Ack) args[0];
                            JsonObject data = JsonElementBuildersKt.buildJsonObject(builder -> {
                                builder.put("test", JsonElementKt.JsonPrimitive(true));
                                return Unit.INSTANCE;
                            });
                            fn.call(5, data);
                        }
                    });
                    socket.on("ackBack", new Emitter.Listener() {
                        @Override
                        public void call(Object... args) {
                            JsonObject data = (JsonObject)args[1];
                            if ((Integer)args[0] == 5 && TestUtil.jsonBool(data, "test")) {
                                values.offer("done");
                            }
                        }
                    });
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });

        values.take();
        socket.close();
    }

    @Test(timeout = TIMEOUT)
    public void receiveDateWithAck() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();

        client("/", socket -> {
            this.socket = socket;
            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... objects) {
                    JsonObject data = JsonElementBuildersKt.buildJsonObject(builder -> {
                        builder.put("test", JsonElementKt.JsonPrimitive(true));
                        return Unit.INSTANCE;
                    });
                    socket.emit("getAckDate", data, new Ack() {
                        @Override
                        public void call(Object... args) {
                            values.offer(args[0]);
                        }
                    });
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });
        assertThat(values.take(), instanceOf(String.class));
        socket.close();
    }

//    @Test(timeout = TIMEOUT)
//    public void sendBinaryAck() throws InterruptedException {
//        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();
//        final byte[] buf = "huehue".getBytes(Charset.forName("UTF-8"));
//
//        client("/", socket -> {
//            this.socket = socket;
//            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
//                @Override
//                public void call(Object... objects) {
//                    socket.emit("callAckBinary");
//                    socket.on("ack", new Emitter.Listener() {
//                        @Override
//                        public void call(Object... args) {
//                            Ack fn = (Ack) args[0];
//                            fn.call(buf);
//                        }
//                    });
//
//                    socket.on("ackBack", new Emitter.Listener() {
//                        @Override
//                        public void call(Object... args) {
//                            byte[] data = (byte[]) args[0];
//                            values.offer(data);
//                        }
//                    });
//                }
//            });
//            socket.open();
//            return Unit.INSTANCE;
//        });
//        Assert.assertArrayEquals(buf, (byte[])values.take());
//        socket.close();
//    }

//    @Test(timeout = TIMEOUT)
//    public void receiveBinaryDataWithAck() throws InterruptedException {
//        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();
//        final byte[] buf = "huehue".getBytes(Charset.forName("UTF-8"));
//
//        socket = client();
//        socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
//            @Override
//            public void call(Object... objects) {
//                socket.emit("getAckBinary", "", new Ack() {
//
//                    @Override
//                    public void call(Object... args) {
//                       values.offer(args[0]);
//                    }
//                });
//            }
//        });
//        socket.open();
//        Assert.assertArrayEquals(buf, (byte[])values.take());
//        socket.close();
//    }

    @Test(timeout = TIMEOUT)
    public void workWithFalse() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();
        client("/", socket -> {
            this.socket = socket;
            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... objects) {
                    socket.emit("echo", false);
                    socket.on("echoBack", new Emitter.Listener() {
                        @Override
                        public void call(Object... args) {
                            values.offer(args[0]);
                        }
                    });
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });
        assertThat((Boolean)values.take(), is(false));
        socket.close();
    }

    @Test(timeout = TIMEOUT)
    public void receiveUTF8MultibyteCharacters() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();
        final String[] correct = new String[] {
            "てすと",
            "Я Б Г Д Ж Й",
            "Ä ä Ü ü ß",
            "utf8 — string",
            "utf8 — string"
        };

        client("/", socket -> {
            this.socket = socket;
            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... objects) {
                    socket.on("echoBack", new Emitter.Listener() {
                        @Override
                        public void call(Object... args) {
                            values.offer(args[0]);
                        }
                    });
                    for (String data : correct) {
                        socket.emit("echo", data);
                    }
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });
        for (String expected : correct) {
            assertThat((String)values.take(), is(expected));
        }
        socket.close();
    }

    @Test(timeout = TIMEOUT)
    public void connectToNamespaceAfterConnectionEstablished() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();
        final Manager manager = new Manager(uri(), new Manager.Options(), TestUtil.testScope());
        socket = TestUtil.socket(manager, "/");
        socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
            @Override
            public void call(Object... objects) {
                final Socket foo = TestUtil.socket(manager, "/foo");
                foo.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        foo.close();
                        socket.close();
                        TestUtil.closeManager(manager);
                        values.offer("done");
                    }
                });
                foo.open();
            }
        });
        socket.open();
        values.take();
    }

    @Test(timeout = TIMEOUT)
    public void connectToNamespaceAfterConnectionGetsClosed() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();
        final Manager manager = new Manager(uri(), new Manager.Options(), TestUtil.testScope());
        socket = TestUtil.socket(manager, "/");
        socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
            @Override
            public void call(Object... objects) {
                socket.close();
            }
        }).on(Socket.EVENT_DISCONNECT, new Emitter.Listener() {
            @Override
            public void call(Object... objects) {
                final Socket foo = TestUtil.socket(manager, "/foo");
                foo.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        foo.close();
                        TestUtil.closeManager(manager);
                        values.offer("done");
                    }
                });
                foo.open();
            }
        });
        socket.open();
        values.take();
    }

    @Test(timeout = TIMEOUT)
    public void reconnectByDefault() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();

        client("/", socket -> {
            this.socket = socket;
            socket.getIo().on(Manager.EVENT_RECONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... objects) {
                    socket.close();
                    values.offer("done");
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                TestUtil.closeEngineSocket(socket);
            }
        }, 500);
        values.take();
    }

    @Test(timeout = TIMEOUT)
    public void reconnectManually() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();
        client("/", socket -> {
            this.socket = socket;
            socket.once(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    socket.close();
                }
            }).once(Socket.EVENT_DISCONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    socket.once(Socket.EVENT_CONNECT, new Emitter.Listener() {
                        @Override
                        public void call(Object... args) {
                            socket.close();
                            values.offer("done");
                        }
                    });
                    socket.open();
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });
        values.take();
    }

    @Test(timeout = TIMEOUT)
    public void reconnectAutomaticallyAfterReconnectingManually() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();
        client("/", socket -> {
            this.socket = socket;
            socket.once(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    socket.close();
                }
            }).once(Socket.EVENT_DISCONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    socket.getIo().on(Manager.EVENT_RECONNECT, new Emitter.Listener() {
                        @Override
                        public void call(Object... args) {
                            socket.close();
                            values.offer("done");
                        }
                    });
                    socket.open();
                    new Timer().schedule(new TimerTask() {
                        @Override
                        public void run() {
                            TestUtil.closeEngineSocket(socket);
                        }
                    }, 500);
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });
        values.take();
    }

    @Test(timeout = 14000)
    public void attemptReconnectsAfterAFailedReconnect() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();
        IO.Options opts = createOptions();
        opts.reconnection = true;
        opts.timeout = 0;
        opts.reconnectionAttempts = 2;
        opts.setReconnectionDelay(10);
        final Manager manager = new Manager(uri(), opts, TestUtil.testScope());
        socket = TestUtil.socket(manager, "/timeout");
        manager.once(Manager.EVENT_RECONNECT_FAILED, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                final int[] reconnects = new int[] {0};
                Emitter.Listener reconnectCb = new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        reconnects[0]++;
                    }
                };

                manager.on(Manager.EVENT_RECONNECT_ATTEMPT, reconnectCb);
                manager.on(Manager.EVENT_RECONNECT_FAILED, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        values.offer(reconnects[0]);
                    }
                });
                socket.open();
            }
        });
        socket.open();
        assertThat((Integer)values.take(), is(2));
        socket.close();
    }

    @Test(timeout = TIMEOUT)
    public void reconnectDelayShouldIncreaseEveryTime() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();
        IO.Options opts = createOptions();
        opts.reconnection = true;
        opts.timeout = 0;
        opts.reconnectionAttempts = 3;
        opts.setReconnectionDelay(100);
        opts.setRandomizationFactor(0.2);
        final Manager manager = new Manager(uri(), opts, TestUtil.testScope());
        socket = TestUtil.socket(manager, "/timeout");

        final int[] reconnects = new int[] {0};
        final boolean[] increasingDelay = new boolean[] {true};
        final long[] startTime = new long[] {0};
        final long[] prevDelay = new long[] {0};

        manager.on(Manager.EVENT_ERROR, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                startTime[0] = new Date().getTime();
            }
        });
        manager.on(Manager.EVENT_RECONNECT_ATTEMPT, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                reconnects[0]++;
                long currentTime = new Date().getTime();
                long delay = currentTime - startTime[0];
                if (delay <= prevDelay[0]) {
                    increasingDelay[0] = false;
                }
                prevDelay[0] = delay;
            }
        });
        manager.on(Manager.EVENT_RECONNECT_FAILED, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                values.offer(true);
            }
        });

        socket.open();
        values.take();
        assertThat(reconnects[0], is(3));
        assertThat(increasingDelay[0], is(true));
        socket.close();
    }

    @Test(timeout = TIMEOUT)
    public void notReconnectWhenForceClosed() throws URISyntaxException, InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();
        IO.Options opts = createOptions();
        opts.timeout = 0;
        opts.setReconnectionDelay(10);
        client("/invalid", opts, socket -> {
            this.socket = socket;
            socket.getIo().on(Manager.EVENT_ERROR, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    socket.getIo().on(Manager.EVENT_RECONNECT_ATTEMPT, new Emitter.Listener() {
                        @Override
                        public void call(Object... args) {
                            values.offer(false);
                        }
                    });
                    socket.close();
                    new Timer().schedule(new TimerTask() {
                        @Override
                        public void run() {
                            values.offer(true);
                        }
                    }, 500);
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });
        assertThat((Boolean)values.take(), is(true));
    }

    @Test(timeout = TIMEOUT)
    public void stopReconnectingWhenForceClosed() throws URISyntaxException, InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();
        IO.Options opts = createOptions();
        opts.timeout = 0;
        opts.setReconnectionDelay(10);
        client("/invalid", opts, socket -> {
            this.socket = socket;
            socket.getIo().once(Manager.EVENT_RECONNECT_ATTEMPT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    socket.getIo().on(Manager.EVENT_RECONNECT_ATTEMPT, new Emitter.Listener() {
                        @Override
                        public void call(Object... args) {
                            values.offer(false);
                        }
                    });
                    socket.close();
                    // set a timer to let reconnection possibly fire
                    new Timer().schedule(new TimerTask() {
                        @Override
                        public void run() {
                            values.offer(true);
                        }
                    }, 500);
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });
        assertThat((Boolean) values.take(), is(true));
    }

    @Test(timeout = TIMEOUT)
    public void reconnectAfterStoppingReconnection() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();
        IO.Options opts = createOptions();
        opts.forceNew = true;
        opts.timeout = 0;
        opts.setReconnectionDelay(10);
        client("/invalid", opts, socket -> {
            this.socket = socket;
            socket.getIo().once(Manager.EVENT_RECONNECT_ATTEMPT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    socket.getIo().once(Manager.EVENT_RECONNECT_ATTEMPT, new Emitter.Listener() {
                        @Override
                        public void call(Object... args) {
                            values.offer("done");
                        }
                    });
                    socket.close();
                    socket.open();
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });
        values.take();
        socket.close();
    }

    @Test(timeout = TIMEOUT)
    public void stopReconnectingOnASocketAndKeepToReconnectOnAnother() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();
        final Manager manager = new Manager(uri(), createOptions(), TestUtil.testScope());
        final Socket socket1 = TestUtil.socket(manager, "/");
        final Socket socket2 = TestUtil.socket(manager, "/asd");

        manager.on(Manager.EVENT_RECONNECT_ATTEMPT, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                socket1.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        values.offer(false);
                    }
                });
                socket2.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        new Timer().schedule(new TimerTask() {
                            @Override
                            public void run() {
                                socket2.close();
                                TestUtil.closeManager(manager);
                                values.offer(true);
                            }
                        }, 500);
                    }
                });
                socket1.close();
            }
        });

        socket1.open();
        socket2.open();

        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                TestUtil.closeEngineSocket(socket1);
            }
        }, 1000);

        assertThat((Boolean) values.take(), is(true));
    }

    @Test(timeout = TIMEOUT)
    public void connectWhileDisconnectingAnotherSocket() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();

        final Manager manager = new Manager(uri(), createOptions(), TestUtil.testScope());
        final Socket socket1 = TestUtil.socket(manager, "/foo");
        socket1.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                final Socket socket2 = TestUtil.socket(manager, "/asd");
                socket2.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        values.offer("done");
                        socket2.close();
                    }
                });
                socket2.open();
                socket1.close();
            }
        });

        socket1.open();
        values.take();
    }

    @Test(timeout = TIMEOUT)
    public void tryToReconnectTwiceAndFailWithIncorrectAddress() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();
        IO.Options opts = new IO.Options();
        opts.reconnection = true;
        opts.reconnectionAttempts = 2;
        opts.setReconnectionDelay(10);
        final Manager manager = new Manager("http://localhost:3940", opts, TestUtil.testScope());
        socket = TestUtil.socket(manager, "/asd");
        final int[] reconnects = new int[] {0};
        Emitter.Listener cb = new Emitter.Listener() {
            @Override
            public void call(Object... objects) {
                reconnects[0]++;
            }
        };

        manager.on(Manager.EVENT_RECONNECT_ATTEMPT, cb);

        manager.on(Manager.EVENT_RECONNECT_FAILED, new Emitter.Listener() {
            @Override
            public void call(Object... objects) {
                values.offer(reconnects[0]);
            }
        });

        socket.open();
        assertThat((Integer)values.take(), is(2));
        socket.close();
    }

    @Test(timeout = TIMEOUT)
    public void tryToReconnectTwiceAndFailWithImmediateTimeout() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();
        IO.Options opts = new IO.Options();
        opts.reconnection = true;
        opts.timeout = 0;
        opts.reconnectionAttempts = 2;
        opts.setReconnectionDelay(10);
        final Manager manager = new Manager(uri(), opts, TestUtil.testScope());
        final int[] reconnects = new int[] {0};
        Emitter.Listener reconnectCb = new Emitter.Listener() {
            @Override
            public void call(Object... objects) {
                reconnects[0]++;
            }
        };

        manager.on(Manager.EVENT_RECONNECT_ATTEMPT, reconnectCb);
        manager.on(Manager.EVENT_RECONNECT_FAILED, new Emitter.Listener() {
            @Override
            public void call(Object... objects) {
                socket.close();
                TestUtil.closeManager(manager);
                values.offer(reconnects[0]);
            }
        });

        socket = TestUtil.socket(manager, "/timeout");
        socket.open();
        assertThat((Integer)values.take(), is(2));
    }

    @Test(timeout = TIMEOUT)
    public void notTryToReconnectWithIncorrectPortWhenReconnectionDisabled() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();
        IO.Options opts = new IO.Options();
        opts.reconnection = false;
        final Manager manager = new Manager("http://localhost:9823", opts, TestUtil.testScope());
        Emitter.Listener cb = new Emitter.Listener() {
            @Override
            public void call(Object... objects) {
                socket.close();
                throw new RuntimeException();
            }
        };
        manager.on(Manager.EVENT_RECONNECT_ATTEMPT, cb);
        manager.on(Manager.EVENT_ERROR, new Emitter.Listener() {
            @Override
            public void call(Object... objects) {
                Timer timer = new Timer();
                timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        socket.close();
                        TestUtil.closeManager(manager);
                        values.offer("done");
                    }
                }, 1000);
            }
        });

        socket = TestUtil.socket(manager, "/invalid");
        socket.open();
        values.take();
    }

    @Test(timeout = TIMEOUT)
    public void fireReconnectEventsOnSocket() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();

        Manager.Options opts = new Manager.Options();
        opts.reconnection = true;
        opts.timeout = 0;
        opts.reconnectionAttempts = 2;
        opts.setReconnectionDelay(10);
        final Manager manager = new Manager(uri(), opts, TestUtil.testScope());
        socket = TestUtil.socket(manager, "/timeout_socket");

        final int[] reconnects = new int[] {0};
        Emitter.Listener reconnectCb = new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                reconnects[0]++;
                values.offer(args[0]);
            }
        };

        manager.on(Manager.EVENT_RECONNECT_ATTEMPT, reconnectCb);
        manager.on(Manager.EVENT_RECONNECT_FAILED, new Emitter.Listener() {
            @Override
            public void call(Object... objects) {
                socket.close();
                TestUtil.closeManager(manager);
                values.offer(reconnects[0]);
            }
        });
        socket.open();
        assertThat((Integer)values.take(), is(reconnects[0]));
        assertThat((Integer)values.take(), is(2));
    }

    @Test(timeout = TIMEOUT)
    public void fireReconnectingWithAttemptsNumberWhenReconnectingTwice() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();

        Manager.Options opts = new Manager.Options();
        opts.reconnection = true;
        opts.timeout = 0;
        opts.reconnectionAttempts = 2;
        opts.setReconnectionDelay(10);
        final Manager manager = new Manager(uri(), opts, TestUtil.testScope());
        socket = TestUtil.socket(manager, "/timeout_socket");

        final int[] reconnects = new int[] {0};
        Emitter.Listener reconnectCb = new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                reconnects[0]++;
                values.offer(args[0]);
            }
        };

        manager.on(Manager.EVENT_RECONNECT_ATTEMPT, reconnectCb);
        manager.on(Manager.EVENT_RECONNECT_FAILED, new Emitter.Listener() {
            @Override
            public void call(Object... objects) {
                socket.close();
                TestUtil.closeManager(manager);
                values.offer(reconnects[0]);
            }
        });
        socket.open();
        assertThat((Integer)values.take(), is(reconnects[0]));
        assertThat((Integer)values.take(), is(2));
    }

    @Test(timeout = TIMEOUT)
    public void emitDateAsString() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();
        client("/", socket -> {
            this.socket = socket;
            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
                @Override
                public void call(Object... objects) {
                    socket.emit("echo", new Date());
                    socket.on("echoBack", new Emitter.Listener() {
                        @Override
                        public void call(Object... args) {
                            socket.close();
                            values.offer(args[0]);
                        }
                    });
                }
            });
            socket.open();
            return Unit.INSTANCE;
        });
        assertThat(values.take(), instanceOf(String.class));
    }

//    @Test(timeout = TIMEOUT)
//    public void emitDateInObject() throws InterruptedException, JSONException {
//        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();
//        client("/", socket -> {
//            this.socket = socket;
//            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
//                @Override
//                public void call(Object... objects) {
//                    JSONObject data = new JSONObject();
//                    try {
//                        data.put("date", new Date());
//                    } catch (JSONException e) {
//                        throw new AssertionError(e);
//                    }
//                    socket.emit("echo", data);
//                    socket.on("echoBack", new Emitter.Listener() {
//                        @Override
//                        public void call(Object... args) {
//                            values.offer(args[0]);
//                        }
//                    });
//                }
//            });
//            socket.open();
//            return Unit.INSTANCE;
//        });
//        Object data = values.take();
//        assertThat(data, instanceOf(JSONObject.class));
//        assertThat(((JSONObject)data).get("date"), instanceOf(String.class));
//        socket.close();
//    }
//
//    @Test(timeout = TIMEOUT)
//    public void sendAndGetBinaryData() throws InterruptedException {
//        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();
//        final byte[] buf = "asdfasdf".getBytes(Charset.forName("UTF-8"));
//        client("/", socket -> {
//            this.socket = socket;
//            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
//                @Override
//                public void call(Object... args) {
//                    socket.emit("echo", buf);
//                    socket.on("echoBack", new Emitter.Listener() {
//                        @Override
//                        public void call(Object... args) {
//                            values.offer(args[0]);
//                        }
//                    });
//                }
//            });
//            socket.open();
//            return Unit.INSTANCE;
//        });
//        assertThat((byte[])values.take(), is(buf));
//        socket.close();
//    }
//
//    @Test(timeout = TIMEOUT)
//    public void sendBinaryDataMixedWithJson() throws InterruptedException, JSONException {
//        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();
//        final byte[] buf = "howdy".getBytes(Charset.forName("UTF-8"));
//        client("/", socket -> {
//            this.socket = socket;
//            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
//                @Override
//                public void call(Object... args) {
//                    JSONObject data = new JSONObject();
//                    try {
//                        data.put("hello", "lol");
//                        data.put("message", buf);
//                        data.put("goodbye", "gotcha");
//                    } catch (JSONException e) {
//                        throw new AssertionError(e);
//                    }
//                    socket.emit("echo", data);
//                    socket.on("echoBack", new Emitter.Listener() {
//                        @Override
//                        public void call(Object... args) {
//                            values.offer(args[0]);
//                        }
//                    });
//                }
//            });
//            socket.open();
//            return Unit.INSTANCE;
//        });
//        JSONObject a = (JSONObject)values.take();
//        assertThat(a.getString("hello"), is("lol"));
//        assertThat((byte[])a.get("message"), is(buf));
//        assertThat(a.getString("goodbye"), is("gotcha"));
//        socket.close();
//    }
//
//    @Test(timeout = TIMEOUT)
//    public void sendEventsWithByteArraysInTheCorrectOrder() throws Exception {
//        final BlockingQueue<Object> values = new LinkedBlockingQueue<>();
//        final byte[] buf = "abuff1".getBytes(Charset.forName("UTF-8"));
//        client("/", socket -> {
//            this.socket = socket;
//            socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
//                @Override
//                public void call(Object... args) {
//                    socket.emit("echo", buf);
//                    socket.emit("echo", "please arrive second");
//
//                    socket.on("echoBack", new Emitter.Listener() {
//                        @Override
//                        public void call(Object... args) {
//                            values.offer(args[0]);
//                        }
//                    });
//                }
//            });
//            socket.open();
//            return Unit.INSTANCE;
//        });
//        assertThat((byte[])values.take(), is(buf));
//        assertThat((String)values.take(), is("please arrive second"));
//        socket.close();
//    }
}

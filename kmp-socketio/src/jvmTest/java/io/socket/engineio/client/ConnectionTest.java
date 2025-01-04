package io.socket.engineio.client;

import com.piasy.kmp.socketio.emitter.Emitter;
import com.piasy.kmp.socketio.engineio.EngineSocket;
import com.piasy.kmp.socketio.engineio.TestUtil;
import org.hildan.socketio.EngineIOPacket;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

@RunWith(JUnit4.class)
public class ConnectionTest extends Connection {

    private EngineSocket socket;

    @Test(timeout = TIMEOUT)
    public void connectToLocalhost() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<Object>();

        socket = new EngineSocket("http://localhost:" + PORT, new EngineSocket.Options(), TestUtil.testScope(), TestUtil.transportFactory());
        socket.on(EngineSocket.EVENT_OPEN, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                socket.on(EngineSocket.EVENT_MESSAGE, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        values.offer(args[0]);
                        socket.close();
                    }
                });
            }
        });
        socket.open();

        assertThat((String)values.take(), is("hi"));
    }

    @Test(timeout = TIMEOUT)
    public void receiveMultibyteUTF8StringsWithPolling() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<Object>();

        socket = new EngineSocket("http://localhost:" + PORT, new EngineSocket.Options(), TestUtil.testScope(), TestUtil.transportFactory());
        socket.on(EngineSocket.EVENT_OPEN, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                socket.send(new EngineIOPacket.Message<String>("cash money €€€"));
                socket.on(EngineSocket.EVENT_MESSAGE, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        if ("hi".equals(args[0])) return;
                        values.offer(args[0]);
                        socket.close();
                    }
                });
            }
        });
        socket.open();

        assertThat((String)values.take(), is("cash money €€€"));
    }

    @Test(timeout = TIMEOUT)
    public void receiveEmoji() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<Object>();

        socket = new EngineSocket("http://localhost:" + PORT, new EngineSocket.Options(), TestUtil.testScope(), TestUtil.transportFactory());
        socket.on(EngineSocket.EVENT_OPEN, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                socket.send(new EngineIOPacket.Message<String>("\uD800\uDC00-\uDB7F\uDFFF\uDB80\uDC00-\uDBFF\uDFFF\uE000-\uF8FF"));
                socket.on(EngineSocket.EVENT_MESSAGE, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        if ("hi".equals(args[0])) return;
                        values.offer(args[0]);
                        socket.close();
                    }
                });
            }
        });
        socket.open();

        assertThat((String)values.take(), is("\uD800\uDC00-\uDB7F\uDFFF\uDB80\uDC00-\uDBFF\uDFFF\uE000-\uF8FF"));
    }

    @Test(timeout = TIMEOUT)
    public void notSendPacketsIfSocketCloses() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<Object>();

        socket = new EngineSocket("http://localhost:" + PORT, new EngineSocket.Options(), TestUtil.testScope(), TestUtil.transportFactory());
        socket.on(EngineSocket.EVENT_OPEN, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                final boolean[] noPacket = new boolean[] {true};
                socket.on(EngineSocket.EVENT_PACKET_CREATE, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        noPacket[0] = false;
                    }
                });
                socket.close();
                socket.send(new EngineIOPacket.Message<String>("hi"));
                Timer timer = new Timer();
                timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        values.offer(noPacket[0]);
                    }
                }, 1200);

            }
        });
        socket.open();
        assertThat((Boolean)values.take(), is(true));
    }

    @Test(timeout = TIMEOUT)
    public void deferCloseWhenUpgrading() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<Object>();

        socket = new EngineSocket("http://localhost:" + PORT, new EngineSocket.Options(), TestUtil.testScope(), TestUtil.transportFactory());
        socket.on(EngineSocket.EVENT_OPEN, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                final boolean[] upgraded = new boolean[] {false};
                socket.on(EngineSocket.EVENT_UPGRADE, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        upgraded[0] = true;
                    }
                }).on(EngineSocket.EVENT_UPGRADING, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        socket.on(EngineSocket.EVENT_CLOSE, new Emitter.Listener() {
                            @Override
                            public void call(Object... args) {
                                values.offer(upgraded[0]);
                            }
                        });
                        socket.close();
                    }
                });
            }
        });
        socket.open();
        assertThat((Boolean)values.take(), is(true));
    }

    @Test(timeout = TIMEOUT)
    public void closeOnUpgradeErrorIfClosingIsDeferred() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<Object>();

        socket = new EngineSocket("http://localhost:" + PORT, new EngineSocket.Options(), TestUtil.testScope(), TestUtil.transportFactory());
        socket.on(EngineSocket.EVENT_OPEN, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                final boolean[] upgradError = new boolean[] {false};
                socket.on(EngineSocket.EVENT_UPGRADE_ERROR, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        upgradError[0] = true;
                    }
                }).on(EngineSocket.EVENT_UPGRADING, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        // `on` listeners are triggered before `once` listeners,
                        // and EVENT_CLOSE listener in probe is `once`,
                        // so to make sure we get EVENT_CLOSE after EVENT_UPGRADE_ERROR,
                        // we use once here.
                        socket.once(EngineSocket.EVENT_CLOSE, new Emitter.Listener() {
                            @Override
                            public void call(Object... args) {
                                values.offer(upgradError[0]);
                            }
                        });
                        socket.close();
                        TestUtil.triggerTransportError(socket, "upgrade error");
                    }
                });
            }
        });
        socket.open();
        assertThat((Boolean) values.take(), is(true));
    }

    @Test
    public void notSendPacketsIfClosingIsDeferred() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<Object>();

        socket = new EngineSocket("http://localhost:" + PORT, new EngineSocket.Options(), TestUtil.testScope(), TestUtil.transportFactory());
        socket.on(EngineSocket.EVENT_OPEN, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                final boolean[] noPacket = new boolean[] {true};
                socket.on(EngineSocket.EVENT_UPGRADING, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        socket.on(EngineSocket.EVENT_PACKET_CREATE, new Emitter.Listener() {
                            @Override
                            public void call(Object... args) {
                                noPacket[0] = false;
                            }
                        });
                        socket.close();
                        socket.send(new EngineIOPacket.Message<String>("hi"));
                    }
                });
                new Timer().schedule(new TimerTask() {
                    @Override
                    public void run() {
                        values.offer(noPacket[0]);
                    }
                }, 1200);
            }
        });
        socket.open();
        assertThat((Boolean) values.take(), is(true));
    }

    @Test(timeout = TIMEOUT)
    public void sendAllBufferedPacketsIfClosingIsDeferred() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<Object>();

        socket = new EngineSocket("http://localhost:" + PORT, new EngineSocket.Options(), TestUtil.testScope(), TestUtil.transportFactory());
        socket.on(EngineSocket.EVENT_OPEN, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                socket.on(EngineSocket.EVENT_UPGRADING, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        socket.send(new EngineIOPacket.Message<String>("hi"));
                        socket.close();
                    }
                }).on(EngineSocket.EVENT_CLOSE, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        values.offer(TestUtil.writeBufferSize(socket));
                    }
                });
            }
        });
        socket.open();
        assertThat((Integer) values.take(), is(0));
    }

    @Test(timeout = TIMEOUT)
    public void receivePing() throws InterruptedException {
        final BlockingQueue<String> values = new LinkedBlockingQueue<>();

        socket = new EngineSocket("http://localhost:" + PORT, new EngineSocket.Options(), TestUtil.testScope(), TestUtil.transportFactory());
        socket.on(EngineSocket.EVENT_PING, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                values.offer("end");
                socket.close();
            }
        });
        socket.open();
        assertThat(values.take(), is("end"));
    }
}

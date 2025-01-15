package io.socket.engineio.client;

import com.piasy.kmp.socketio.engineio.EngineSocket;
import com.piasy.kmp.socketio.engineio.Transport;
import com.piasy.kmp.socketio.emitter.Emitter;
import com.piasy.kmp.socketio.engineio.transports.PollingXHR;
import com.piasy.kmp.socketio.engineio.transports.WebSocket;
import com.piasy.kmp.socketio.engineio.TestUtil;
import org.hildan.socketio.EngineIOPacket;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.*;

import static java.util.Collections.*;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertThat;

@RunWith(JUnit4.class)
public class ServerConnectionTest extends Connection {

    private EngineSocket socket;

    @Test(timeout = TIMEOUT)
    public void openAndClose() throws URISyntaxException, InterruptedException {
        final BlockingQueue<String> events = new LinkedBlockingQueue<String>();

        socket = new EngineSocket("http://localhost:" + PORT, new EngineSocket.Options(), TestUtil.testScope(), TestUtil.transportFactory(), true);
        socket.on(EngineSocket.EVENT_OPEN, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                events.offer("onopen");
            }
        }).on(EngineSocket.EVENT_CLOSE, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                events.offer("onclose");
            }
        });
        socket.open();

        assertThat(events.take(), is("onopen"));
        socket.close();
        assertThat(events.take(), is("onclose"));
    }

    @Test(timeout = TIMEOUT)
    public void messages() throws URISyntaxException, InterruptedException {
        final BlockingQueue<String> events = new LinkedBlockingQueue<String>();

        socket = new EngineSocket("http://localhost:" + PORT, new EngineSocket.Options(), TestUtil.testScope(), TestUtil.transportFactory(), true);
        socket.on(EngineSocket.EVENT_OPEN, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                socket.send(new EngineIOPacket.Message<>("hello"));
            }
        }).on(EngineSocket.EVENT_DATA, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                events.offer((String) args[0]);
            }
        });
        socket.open();

        assertThat(events.take(), is("hi"));
        assertThat(events.take(), is("hello"));
        socket.close();
    }

    @Test(timeout = TIMEOUT)
    public void handshake() throws URISyntaxException, InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<Object>();

        socket = new EngineSocket("http://localhost:" + PORT, new EngineSocket.Options(), TestUtil.testScope(), TestUtil.transportFactory(), true);
        socket.on(EngineSocket.EVENT_HANDSHAKE, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                values.offer(args);
            }
        });
        socket.open();

        Object[] args = (Object[])values.take();
        assertThat(args.length, is(1));
        EngineIOPacket.Open data = (EngineIOPacket.Open)args[0];
        assertThat(data.getSid(), is(notNullValue()));
        assertThat(data.getUpgrades(), is(not(emptyList())));
        assertThat(data.getPingTimeout(), is(greaterThan(0)));
        assertThat(data.getPingInterval(), is(greaterThan(0)));
        socket.close();
    }

    @Test(timeout = TIMEOUT)
    public void upgrade() throws URISyntaxException, InterruptedException {
        final BlockingQueue<Object[]> events = new LinkedBlockingQueue<Object[]>();

        socket = new EngineSocket("http://localhost:" + PORT, new EngineSocket.Options(), TestUtil.testScope(), TestUtil.transportFactory(), true);
        socket.on(EngineSocket.EVENT_UPGRADING, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                events.offer(args);
            }
        });
        socket.on(EngineSocket.EVENT_UPGRADE, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                events.offer(args);
            }
        });
        socket.open();

        Object[] args1 = events.take();
        assertThat(args1.length, is(1));
        assertThat(args1[0], is(instanceOf(Transport.class)));
        Transport transport1 = (Transport)args1[0];
        assertThat(transport1, is(notNullValue()));

        Object[] args2 = events.take();
        assertThat(args2.length, is(1));
        assertThat(args2[0], is(instanceOf(Transport.class)));
        Transport transport2 = (Transport)args2[0];
        assertThat(transport2, is(notNullValue()));

        socket.close();
    }

    @Test(timeout = TIMEOUT)
    public void pollingHeaders() throws URISyntaxException, InterruptedException {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<String>();

        EngineSocket.Options opts = new EngineSocket.Options();
        opts.transports = Arrays.asList(PollingXHR.NAME);

        socket = new EngineSocket("http://localhost:" + PORT, opts, TestUtil.testScope(), TestUtil.transportFactory(), true);
        socket.on(EngineSocket.EVENT_TRANSPORT, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                Transport transport = (Transport)args[0];
                transport.on(Transport.EVENT_REQUEST_HEADERS, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        @SuppressWarnings("unchecked")
                        Map<String, List<String>> headers = (Map<String, List<String>>)args[0];
                        headers.put("X-EngineIO", Arrays.asList("foo"));
                    }
                }).on(Transport.EVENT_RESPONSE_HEADERS, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        @SuppressWarnings("unchecked")
                        Map<String, List<String>> headers = (Map<String, List<String>>)args[0];
                        List<String> values = headers.get("X-EngineIO");
                        messages.offer(values.get(0));
                        messages.offer(values.get(1));
                    }
                });
            }
        });
        socket.open();

        assertThat(messages.take(), is("hi"));
        assertThat(messages.take(), is("foo"));
        socket.close();
    }

    @Test(timeout = TIMEOUT)
    public void pollingHeaders_withExtraHeadersOption() throws URISyntaxException, InterruptedException {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<String>();

        EngineSocket.Options opts = new EngineSocket.Options();
        opts.transports = Arrays.asList(PollingXHR.NAME);
        opts.extraHeaders = singletonMap("X-EngineIO", singletonList("bar"));

        socket = new EngineSocket("http://localhost:" + PORT, opts, TestUtil.testScope(), TestUtil.transportFactory(), true);
        socket.on(EngineSocket.EVENT_TRANSPORT, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                Transport transport = (Transport)args[0];
                transport.on(Transport.EVENT_RESPONSE_HEADERS, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        @SuppressWarnings("unchecked")
                        Map<String, List<String>> headers = (Map<String, List<String>>)args[0];
                        List<String> values = headers.get("X-EngineIO");
                        messages.offer(values.get(0));
                        messages.offer(values.get(1));
                    }
                });
            }
        });
        socket.open();

        assertThat(messages.take(), is("hi"));
        assertThat(messages.take(), is("bar"));
        socket.close();
    }

    @Test(timeout = TIMEOUT)
    public void websocketHandshakeHeaders() throws URISyntaxException, InterruptedException {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<String>();

        EngineSocket.Options opts = new EngineSocket.Options();
        opts.transports = Arrays.asList(WebSocket.NAME);

        socket = new EngineSocket("http://localhost:" + PORT, opts, TestUtil.testScope(), TestUtil.transportFactory(), true);
        socket.on(EngineSocket.EVENT_TRANSPORT, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                Transport transport = (Transport)args[0];
                transport.on(Transport.EVENT_REQUEST_HEADERS, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        @SuppressWarnings("unchecked")
                        Map<String, List<String>> headers = (Map<String, List<String>>)args[0];
                        headers.put("X-EngineIO", Arrays.asList("foo"));
                    }
                }).on(Transport.EVENT_RESPONSE_HEADERS, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        @SuppressWarnings("unchecked")
                        Map<String, List<String>> headers = (Map<String, List<String>>)args[0];
                        List<String> values = headers.get("X-EngineIO");
                        messages.offer(values.get(0));
                        messages.offer(values.get(1));
                    }
                });
            }
        });
        socket.open();

        assertThat(messages.take(), is("hi"));
        assertThat(messages.take(), is("foo"));
        socket.close();
    }

    @Test(timeout = TIMEOUT)
    public void websocketHandshakeHeaders_withExtraHeadersOption() throws URISyntaxException, InterruptedException {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<String>();

        EngineSocket.Options opts = new EngineSocket.Options();
        opts.transports = Arrays.asList(WebSocket.NAME);
        opts.extraHeaders = singletonMap("X-EngineIO", singletonList("bar"));

        socket = new EngineSocket("http://localhost:" + PORT, opts, TestUtil.testScope(), TestUtil.transportFactory(), true);
        socket.on(EngineSocket.EVENT_TRANSPORT, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                Transport transport = (Transport)args[0];
                transport.on(Transport.EVENT_RESPONSE_HEADERS, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        @SuppressWarnings("unchecked")
                        Map<String, List<String>> headers = (Map<String, List<String>>)args[0];
                        List<String> values = headers.get("X-EngineIO");
                        messages.offer(values.get(0));
                        messages.offer(values.get(1));
                    }
                });
            }
        });
        socket.open();

        assertThat(messages.take(), is("hi"));
        assertThat(messages.take(), is("bar"));
        socket.close();
    }

    @Test(timeout = TIMEOUT)
    public void rememberWebsocket() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<Object>();

        final EngineSocket socket = new EngineSocket("http://localhost:" + PORT, new EngineSocket.Options(), TestUtil.testScope(), TestUtil.transportFactory(), true);
        socket.on(EngineSocket.EVENT_UPGRADE, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                Transport transport = (Transport) args[0];
                socket.close();
                if (WebSocket.NAME.equals(transport.getName())) {
                    EngineSocket.Options opts = new EngineSocket.Options();
                    opts.rememberUpgrade = true;

                    EngineSocket socket2 = new EngineSocket("http://localhost:" + PORT, opts, TestUtil.testScope(), TestUtil.transportFactory(), true);
                    socket2.on(EngineSocket.EVENT_OPEN, new Emitter.Listener() {
                        @Override
                        public void call(@NotNull Object... args) {
                            values.offer(TestUtil.transportName(socket2));
                            socket2.close();
                        }
                    });
                    socket2.open();
                }
            }
        }).on(EngineSocket.EVENT_OPEN, new Emitter.Listener() {
            @Override
            public void call(@NotNull Object... args) {
                values.offer(TestUtil.transportName(socket));
            }
        });
        socket.open();

        assertThat((String)values.take(), is(PollingXHR.NAME));
        assertThat((String)values.take(), is(WebSocket.NAME));
    }

    @Test(timeout = TIMEOUT)
    public void notRememberWebsocket() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<Object>();

        final EngineSocket socket = new EngineSocket("http://localhost:" + PORT, new EngineSocket.Options(), TestUtil.testScope(), TestUtil.transportFactory(), true);

        socket.on(EngineSocket.EVENT_UPGRADE, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                Transport transport = (Transport)args[0];
                socket.close();
                if (WebSocket.NAME.equals(transport.getName())) {
                    EngineSocket.Options opts = new EngineSocket.Options();
                    opts.port = PORT;
                    opts.rememberUpgrade = false;

                    final EngineSocket socket2 = new EngineSocket("http://localhost:" + PORT, opts, TestUtil.testScope(), TestUtil.transportFactory(), true);
                    socket2.on(EngineSocket.EVENT_OPEN, new Emitter.Listener() {
                        @Override
                        public void call(@NotNull Object... args) {
                            values.offer(TestUtil.transportName(socket2));
                            socket2.close();
                        }
                    });
                    socket2.open();
                }
            }
        }).on(EngineSocket.EVENT_OPEN, new Emitter.Listener() {
            @Override
            public void call(@NotNull Object... args) {
                values.offer(TestUtil.transportName(socket));
            }
        });
        socket.open();

        assertThat((String) values.take(), is(PollingXHR.NAME));
        assertThat((String)values.take(), is(not(WebSocket.NAME)));
    }
}

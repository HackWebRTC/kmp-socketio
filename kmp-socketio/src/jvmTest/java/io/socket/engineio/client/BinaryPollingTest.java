package io.socket.engineio.client;

import com.piasy.kmp.socketio.emitter.Emitter;
import com.piasy.kmp.socketio.engineio.EngineSocket;
import com.piasy.kmp.socketio.engineio.TestUtil;
import com.piasy.kmp.socketio.engineio.transports.PollingXHR;
import kotlinx.io.bytestring.ByteString;
import org.hildan.socketio.EngineIOPacket;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

@RunWith(JUnit4.class)
public class BinaryPollingTest extends Connection {

    private EngineSocket socket;

    @Test(timeout = TIMEOUT)
    public void receiveBinaryData() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<Object>();

        final byte[] binaryData = new byte[5];
        for (int i = 0; i < binaryData.length; i++) {
            binaryData[i] = (byte)i;
        }
        EngineSocket.Options opts = new EngineSocket.Options();
        opts.port = PORT;
        opts.transports = Arrays.asList(PollingXHR.NAME);

        socket = new EngineSocket("http://localhost:" + PORT, opts, TestUtil.testScope(), TestUtil.transportFactory(), true);
        socket.on(EngineSocket.EVENT_OPEN, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                socket.send(new EngineIOPacket.BinaryData(new ByteString(binaryData, 0, binaryData.length)));
                socket.on(EngineSocket.EVENT_DATA, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        if ("hi".equals(args[0])) return;

                        if (args[0] instanceof ByteString) {
                            values.offer(((ByteString) args[0]).getBackingArrayReference());
                        } else {
                            values.offer(args[0]);
                        }
                    }
                });
            }
        });
        socket.open();

        assertThat((byte[])values.take(), is(binaryData));
        socket.close();
    }

    @Test(timeout = TIMEOUT)
    public void receiveBinaryDataAndMultibyteUTF8String() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<Object>();

        final byte[] binaryData = new byte[5];
        for (int i = 0; i < binaryData.length; i++) {
            binaryData[i] = (byte)i;
        }

        final int[] msg = new int[] {0};
        EngineSocket.Options opts = new EngineSocket.Options();
        opts.port = PORT;
        opts.transports = Arrays.asList(PollingXHR.NAME);
        socket = new EngineSocket("http://localhost:" + PORT, opts, TestUtil.testScope(), TestUtil.transportFactory(), true);
        socket.on(EngineSocket.EVENT_OPEN, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                socket.send(new EngineIOPacket.BinaryData(new ByteString(binaryData, 0, binaryData.length)));
                socket.send(new EngineIOPacket.Message<>("cash money €€€"));
                socket.on(EngineSocket.EVENT_DATA, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        if ("hi".equals(args[0])) return;

                        if (args[0] instanceof ByteString) {
                            values.offer(((ByteString) args[0]).getBackingArrayReference());
                        } else {
                            values.offer(args[0]);
                        }
                        msg[0]++;
                    }
                });
            }
        });
        socket.open();

        assertThat((byte[])values.take(), is(binaryData));
        assertThat((String)values.take(), is("cash money €€€"));
        socket.close();
    }
}

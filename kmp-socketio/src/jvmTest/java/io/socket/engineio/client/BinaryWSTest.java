package io.socket.engineio.client;

import com.piasy.kmp.socketio.emitter.Emitter;
import com.piasy.kmp.socketio.engineio.EngineSocket;
import com.piasy.kmp.socketio.engineio.TestUtil;
import kotlinx.io.bytestring.ByteString;
import org.hildan.socketio.EngineIOPacket;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

@RunWith(JUnit4.class)
public class BinaryWSTest extends Connection {

    @Test(timeout = TIMEOUT)
    public void receiveBinaryData() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<Object>();

        final byte[] binaryData = new byte[5];
        for (int i = 0; i < binaryData.length; i++) {
            binaryData[i] = (byte)i;
        }
        socket = new EngineSocket("http://localhost:" + PORT, new EngineSocket.Options(), TestUtil.testScope(), TestUtil.transportFactory(), true);
        socket.on(EngineSocket.EVENT_OPEN, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                socket.on(EngineSocket.EVENT_UPGRADE, new Emitter.Listener() {
                    @Override
                    public void call(Object... args) {
                        socket.send(new EngineIOPacket.BinaryData(new ByteString(binaryData, 0, binaryData.length)));
                        socket.on(EngineSocket.EVENT_DATA, new Emitter.Listener() {
                            @Override
                            public void call(Object... args) {
                                if (args[0] instanceof String) return;
                                if (args[0] instanceof ByteString) {
                                    values.offer(((ByteString) args[0]).getBackingArrayReference());
                                } else {
                                    values.offer(args[0]);
                                }
                            }
                        });
                    }
                });
            }
        });
        socket.open();

        assertThat((byte[])values.take(), is(binaryData));
    }

    @Test(timeout = TIMEOUT)
    public void receiveBinaryDataAndMultibyteUTF8String() throws InterruptedException {
        final BlockingQueue<Object> values = new LinkedBlockingQueue<Object>();

        final byte[] binaryData = new byte[5];
        for (int i = 0; i < binaryData.length; i++) {
            binaryData[i] = (byte)i;
        }

        final int[] msg = new int[] {0};
        socket = new EngineSocket("http://localhost:" + PORT, new EngineSocket.Options(), TestUtil.testScope(), TestUtil.transportFactory(), true);
        socket.on(EngineSocket.EVENT_OPEN, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                socket.on(EngineSocket.EVENT_UPGRADE, new Emitter.Listener() {
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
            }
        });
        socket.open();

        assertThat((byte[])values.take(), is(binaryData));
        assertThat((String)values.take(), is("cash money €€€"));
    }
}

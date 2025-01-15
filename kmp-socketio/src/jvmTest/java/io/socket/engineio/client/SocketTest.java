package io.socket.engineio.client;

import com.piasy.kmp.socketio.engineio.EngineSocket;
import com.piasy.kmp.socketio.engineio.TestUtil;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.net.URISyntaxException;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

@RunWith(JUnit4.class)
public class SocketTest {

//    @Test
//    public void filterUpgrades() {
//        Socket.Options opts = new Socket.Options();
//        opts.transports = new ArrayList<>() {{ add(PollingXHR.NAME); }};
//        Socket socket = new Socket("http://localhost", opts, TestUtil.testScope(), TestUtil.transportFactory());
//        List<String> upgrades = new ArrayList<String>() {{
//            add(PollingXHR.NAME);
//            add(WebSocket.NAME);
//        }};
//        List<String> expected = new ArrayList<String>() {{add(PollingXHR.NAME);}};
//        assertThat(socket.filterUpgrades(upgrades), is(expected));
//    }

    @Test
    public void properlyParseHttpUriWithoutPort() throws URISyntaxException {
        EngineSocket client = new EngineSocket("http://localhost", new EngineSocket.Options(), TestUtil.testScope(), TestUtil.transportFactory(), false);
        assertThat(TestUtil.getOpt(client).hostname, is("localhost"));
        assertThat(TestUtil.getOpt(client).port, is(80));
    }

    @Test
    public void properlyParseHttpsUriWithoutPort() throws URISyntaxException {
        EngineSocket client = new EngineSocket("https://localhost", new EngineSocket.Options(), TestUtil.testScope(), TestUtil.transportFactory(), false);
        assertThat(TestUtil.getOpt(client).hostname, is("localhost"));
        assertThat(TestUtil.getOpt(client).port, is(443));
    }

    @Test
    public void properlyParseWssUriWithoutPort() throws URISyntaxException {
        EngineSocket client = new EngineSocket("wss://localhost", new EngineSocket.Options(), TestUtil.testScope(), TestUtil.transportFactory(), false);
        assertThat(TestUtil.getOpt(client).hostname, is("localhost"));
        assertThat(TestUtil.getOpt(client).port, is(443));
    }

    @Test
    public void properlyParseWssUriWithPort() throws URISyntaxException {
        EngineSocket client = new EngineSocket("wss://localhost:2020", new EngineSocket.Options(), TestUtil.testScope(), TestUtil.transportFactory(), false);
        assertThat(TestUtil.getOpt(client).hostname, is("localhost"));
        assertThat(TestUtil.getOpt(client).port, is(2020));
    }

//    @Test
//    public void properlyParseHostWithPort() {
//        Socket.Options opts = new Socket.Options();
//        opts.host = "localhost";
//        opts.port = 8080;
//        Socket client = new Socket(opts);
//        assertThat(TestUtil.getOpt(client).hostname, is("localhost"));
//        assertThat(TestUtil.getOpt(client).port, is(8080));
//    }

    @Test
    public void properlyParseIPv6UriWithoutPort() throws URISyntaxException {
        EngineSocket client = new EngineSocket("http://[::1]", new EngineSocket.Options(), TestUtil.testScope(), TestUtil.transportFactory(), false);
        assertThat(TestUtil.getOpt(client).hostname, is("::1"));
        assertThat(TestUtil.getOpt(client).port, is(80));
    }

    @Test
    public void properlyParseIPv6UriWithPort() throws URISyntaxException {
        EngineSocket client = new EngineSocket("http://[::1]:8080", new EngineSocket.Options(), TestUtil.testScope(), TestUtil.transportFactory(), false);
        assertThat(TestUtil.getOpt(client).hostname, is("::1"));
        assertThat(TestUtil.getOpt(client).port, is(8080));
    }

//    @Test
//    public void properlyParseIPv6HostWithoutPort1() {
//        Socket.Options opts = new Socket.Options();
//        opts.host = "[::1]";
//        Socket client = new Socket(opts);
//        assertThat(TestUtil.getOpt(client).hostname, is("::1"));
//        assertThat(TestUtil.getOpt(client).port, is(80));
//    }
//
//    @Test
//    public void properlyParseIPv6HostWithoutPort2() {
//        Socket.Options opts = new Socket.Options();
//        opts.secure = true;
//        opts.host = "[::1]";
//        Socket client = new Socket(opts);
//        assertThat(TestUtil.getOpt(client).hostname, is("::1"));
//        assertThat(TestUtil.getOpt(client).port, is(443));
//    }
//
//    @Test
//    public void properlyParseIPv6HostWithPort() {
//        Socket.Options opts = new Socket.Options();
//        opts.host = "[::1]";
//        opts.port = 8080;
//        Socket client = new Socket(opts);
//        assertThat(TestUtil.getOpt(client).hostname, is("::1"));
//        assertThat(TestUtil.getOpt(client).port, is(8080));
//    }
//
//    @Test
//    public void properlyParseIPv6HostWithoutBrace() {
//        Socket.Options opts = new Socket.Options();
//        opts.host = "::1";
//        Socket client = new Socket(opts);
//        assertThat(TestUtil.getOpt(client).hostname, is("::1"));
//        assertThat(TestUtil.getOpt(client).port, is(80));
//    }
}

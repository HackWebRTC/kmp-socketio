package io.socket.client;

import com.piasy.kmp.xlog.Logging;
import com.piasy.kmp.socketio.socketio.IO;
import com.piasy.kmp.socketio.socketio.Socket;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import org.junit.After;
import org.junit.Before;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

public abstract class Connection {

    protected static final String TAG = "TestConnection";
    final static int TIMEOUT = 7000;
    final static int PORT = 3000;

    private Process serverProcess;
    private ExecutorService serverService;
    private Future serverOutput;
    private Future serverError;

    protected Socket socket;
    protected Socket socket2;

    @Before
    public void startServer() throws IOException, InterruptedException {
        Logging.INSTANCE.info(TAG, "Starting server ...");

        final CountDownLatch latch = new CountDownLatch(1);
        serverProcess = Runtime.getRuntime().exec(
                String.format("node src/jvmTest/resources/socket-server.js %s", nsp()), createEnv());
        serverService = Executors.newCachedThreadPool();
        serverOutput = serverService.submit(new Runnable() {
            @Override
            public void run() {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(serverProcess.getInputStream()));
                String line;
                try {
                    line = reader.readLine();
                    latch.countDown();
                    do {
                        Logging.INSTANCE.info(TAG, "SERVER OUT: " + line);
                    } while ((line = reader.readLine()) != null);
                } catch (IOException e) {
                    Logging.INSTANCE.error(TAG, "startServer error: " + e.getMessage());
                }
            }
        });
        serverError = serverService.submit(new Runnable() {
            @Override
            public void run() {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(serverProcess.getErrorStream()));
                String line;
                try {
                    while ((line = reader.readLine()) != null) {
                        Logging.INSTANCE.info(TAG, "SERVER ERR: " + line);
                    }
                } catch (IOException e) {
                    Logging.INSTANCE.error(TAG, "startServer error: " + e.getMessage());
                }
            }
        });
        latch.await(3000, TimeUnit.MILLISECONDS);
    }

    @After
    public void stopServer() throws InterruptedException {
        if (socket != null) {
            socket.close();
        }
        if (socket2 != null) {
            socket2.close();
        }

        Logging.INSTANCE.info(TAG, "Stopping server ...");
        serverProcess.destroy();
        serverOutput.cancel(false);
        serverError.cancel(false);
        serverService.shutdown();
        serverService.awaitTermination(3000, TimeUnit.MILLISECONDS);
    }

    void client(String path, Function1<Socket, Unit> fn) {
        client(path, createOptions(), fn);
    }

    void client(String path, IO.Options options, Function1<Socket, Unit> fn) {
        IO.socket(uri() + path, options, fn);
    }

    String uri() {
        return "http://localhost:" + PORT;
    }

    String nsp() {
        return "/";
    }

    IO.Options createOptions() {
        IO.Options opts = new IO.Options();
        opts.forceNew = true;
        return opts;
    }

    String[] createEnv() {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("DEBUG", "socket.io:*");
        env.put("PORT", String.valueOf(PORT));
        String[] _env = new String[env.size()];
        int i = 0;
        for (String key : env.keySet()) {
            _env[i] = key + "=" + env.get(key);
            i++;
        }
        return _env;

    }
}

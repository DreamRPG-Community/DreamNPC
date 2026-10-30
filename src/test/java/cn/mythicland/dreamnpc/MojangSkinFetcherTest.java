package cn.mythicland.dreamnpc;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MojangSkinFetcherTest {
    @Test
    void retriesOneTimeoutAndAcceptsTheNextSuccessfulResponse() throws Exception {
        verify(1, 200, true, 2);
    }

    @Test
    void repeatedTimeoutsStopAfterTwoAttempts() throws Exception {
        verify(2, 200, false, 2);
    }

    @Test
    void invalidPlayerResponsesAreNotRetried() throws Exception {
        verify(0, 404, false, 1);
    }

    private void verify(int slowRequests, int status, boolean succeeds, int expectedRequests) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/profile", exchange -> {
                if (requests.incrementAndGet() <= slowRequests) {
                    try {
                        Thread.sleep(600);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                    return;
                }
                byte[] body = "signed-profile".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
            try {
                String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/profile";
                if (succeeds) assertEquals("signed-profile", MojangSkinFetcher.request(url, 200));
                else assertThrows(IllegalStateException.class, () -> MojangSkinFetcher.request(url, 200));
                assertEquals(expectedRequests, requests.get());
            } finally {
                server.stop(0);
            }
        }
    }
}

package com.fongmi.android.tv.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fongmi.android.tv.bean.Config;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.OkHttpClient;

public class ConfigLatencyProbeTest {

    private final List<ConfigLatencyProbe> probes = new ArrayList<>();
    private final List<MockWebServer> servers = new ArrayList<>();

    @After
    public void tearDown() {
        probes.forEach(ConfigLatencyProbe::cancel);
        servers.forEach(MockWebServer::close);
    }

    @Test
    public void successfulResponsesSortFastestFirstAndFailuresLast() throws Exception {
        MockWebServer slow = server(new MockResponse.Builder().headersDelay(300, TimeUnit.MILLISECONDS).build());
        MockWebServer fast = server(new MockResponse.Builder().build());
        MockWebServer failed = server(new MockResponse.Builder().code(503).build());
        List<Config> configs = new ArrayList<>(List.of(config(1, slow.url("/").toString()),
                config(2, failed.url("/").toString()), config(3, fast.url("/").toString()), config(4, "file:///TV/test.json")));
        Map<String, Long> results = measure(new OkHttpClient(), configs);
        assertTrue(results.get(configs.get(0).getUrl()) > results.get(configs.get(2).getUrl()));
        ConfigLatencyProbe.sort(configs, results);
        assertEquals(List.of(3, 1, 4, 2), configs.stream().map(Config::getId).toList());
    }

    @Test
    public void localAndUnsupportedAddressesDoNotMakeRequestsAndTiesKeepOrder() throws Exception {
        List<Config> configs = new ArrayList<>(List.of(config(1, "file:///test.json"), config(2, "assets://test.json"),
                config(3, "clan://localhost/test.json"), config(4, "not a URL"), config(5, "ftp://example.com/test.json")));
        AtomicInteger requests = new AtomicInteger();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.incrementAndGet();
            throw new IOException("Unexpected request");
        }).build();
        Map<String, Long> results = measure(client, configs);
        assertEquals(0, requests.get());
        assertEquals(ConfigLatencyProbe.LOCAL, results.get(configs.get(0).getUrl()).longValue());
        assertEquals(ConfigLatencyProbe.UNREACHABLE, results.get(configs.get(3).getUrl()).longValue());
        ConfigLatencyProbe.sort(configs, results);
        assertEquals(List.of(1, 2, 3, 4, 5), configs.stream().map(Config::getId).toList());
    }

    @Test
    public void followsRedirectsAndStopsAtHeadersWithoutDownloadingDelayedBody() throws Exception {
        MockWebServer server = server(new MockResponse.Builder().code(302).setHeader("Location", "/final").build());
        server.enqueue(new MockResponse.Builder().body("x".repeat(64 * 1024)).bodyDelay(1, TimeUnit.SECONDS).build());
        Config config = config(1, server.url("/redirect").toString());
        Map<String, Long> results = measure(new OkHttpClient(), List.of(config));
        assertEquals(2, server.getRequestCount());
        assertTrue(results.get(config.getUrl()) < 500);
    }

    @Test
    public void callDeadlineMarksSlowResponseUnreachable() throws Exception {
        MockWebServer server = server(new MockResponse.Builder().headersDelay(1, TimeUnit.SECONDS).build());
        Config config = config(1, server.url("/").toString());
        OkHttpClient client = new OkHttpClient.Builder().callTimeout(100, TimeUnit.MILLISECONDS).build();
        assertEquals(ConfigLatencyProbe.UNREACHABLE, measure(client, List.of(config)).get(config.getUrl()).longValue());
    }

    @Test
    public void limitsConcurrencyAndExcludesWaitingWorkersFromLatency() throws Exception {
        MockWebServer server = server(new MockResponse.Builder().build());
        for (int i = 0; i < 4; i++) server.enqueue(new MockResponse.Builder().build());
        CountDownLatch fourStarted = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            int count = active.incrementAndGet();
            maximum.accumulateAndGet(count, Math::max);
            try {
                if (chain.request().url().encodedPath().startsWith("/wait")) {
                    fourStarted.countDown();
                    if (!release.await(2, TimeUnit.SECONDS)) throw new IOException("Barrier timed out");
                }
                return chain.proceed(chain.request());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException(error);
            } finally {
                active.decrementAndGet();
            }
        }).build();
        List<Config> configs = new ArrayList<>();
        for (int i = 0; i < 4; i++) configs.add(config(i + 1, server.url("/wait" + i).toString()));
        Config queued = config(5, server.url("/queued").toString());
        configs.add(queued);
        AtomicReference<Map<String, Long>> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        probe(client).start(configs, values -> { result.set(values); done.countDown(); });
        assertTrue(fourStarted.await(1, TimeUnit.SECONDS));
        assertEquals(4, active.get());
        Thread.sleep(250);
        release.countDown();
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertTrue(maximum.get() <= 4);
        assertTrue(result.get().get(queued.getUrl()) < result.get().get(configs.get(0).getUrl()));
    }

    @Test
    public void cancelStopsInFlightRequestWithoutDeliveringResults() throws Exception {
        MockWebServer server = server(new MockResponse.Builder().headersDelay(1, TimeUnit.SECONDS).build());
        CountDownLatch delivered = new CountDownLatch(1);
        ConfigLatencyProbe probe = probe(new OkHttpClient());
        probe.start(List.of(config(1, server.url("/").toString())), ignored -> delivered.countDown());
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS) != null);
        probe.cancel();
        assertFalse(delivered.await(200, TimeUnit.MILLISECONDS));
    }

    @Test
    public void cancellationSuppressesCallbackAlreadyQueuedForUi() throws Exception {
        Queue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
        CountDownLatch queued = new CountDownLatch(1);
        AtomicInteger delivered = new AtomicInteger();
        ConfigLatencyProbe probe = new ConfigLatencyProbe(new OkHttpClient(), task -> {
            callbacks.add(task);
            queued.countDown();
        });
        probes.add(probe);
        probe.start(List.of(config(1, "file:///test.json")), ignored -> delivered.incrementAndGet());
        assertTrue(queued.await(1, TimeUnit.SECONDS));
        probe.cancel();
        callbacks.remove().run();
        assertEquals(0, delivered.get());
    }

    @Test
    public void emptyListCompletesOnce() throws Exception {
        assertTrue(measure(new OkHttpClient(), List.of()).isEmpty());
    }

    private ConfigLatencyProbe probe(OkHttpClient client) {
        ConfigLatencyProbe probe = new ConfigLatencyProbe(client, Runnable::run);
        probes.add(probe);
        return probe;
    }

    private Map<String, Long> measure(OkHttpClient client, List<Config> configs) throws Exception {
        AtomicReference<Map<String, Long>> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        probe(client).start(configs, values -> { result.set(values); done.countDown(); });
        assertTrue(done.await(3, TimeUnit.SECONDS));
        return result.get();
    }

    private MockWebServer server(MockResponse response) throws IOException {
        MockWebServer server = new MockWebServer();
        servers.add(server);
        server.enqueue(response);
        server.start();
        return server;
    }

    private static Config config(int id, String url) {
        Config config = new Config();
        config.setId(id);
        config.setUrl(url);
        return config;
    }
}

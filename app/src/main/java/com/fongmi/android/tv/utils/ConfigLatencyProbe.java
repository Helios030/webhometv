package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Config;
import com.github.catvod.net.OkHttp;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** One cancellable measurement session; request queue time is excluded from latency. */
public final class ConfigLatencyProbe {

    public static final long LOCAL = Long.MAX_VALUE - 1;
    public static final long UNREACHABLE = Long.MAX_VALUE;

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Set<Call> calls = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final OkHttpClient client;
    private final Executor callbackExecutor;
    private volatile boolean cancelled;

    public ConfigLatencyProbe() {
        this(OkHttp.client(3000).newBuilder().callTimeout(5, TimeUnit.SECONDS).build(), App::post);
    }

    ConfigLatencyProbe(OkHttpClient client, Executor callbackExecutor) {
        this.client = client;
        this.callbackExecutor = callbackExecutor;
    }

    public void start(List<Config> configs, Consumer<Map<String, Long>> callback) {
        Map<String, Long> results = new ConcurrentHashMap<>();
        AtomicInteger remaining = new AtomicInteger(configs.size());
        if (configs.isEmpty()) deliver(results, callback);
        for (Config config : configs) {
            if (cancelled) break;
            try {
                executor.execute(() -> {
                    long cost = measure(config.getUrl());
                    if (cancelled) return;
                    results.put(config.getUrl(), cost);
                    if (remaining.decrementAndGet() == 0) deliver(results, callback);
                });
            } catch (RejectedExecutionException error) {
                if (!cancelled) throw error;
            }
        }
        executor.shutdown();
    }

    private long measure(String value) {
        String url = value == null ? "" : value.trim();
        if (url.startsWith("file:") || url.startsWith("assets:") || url.startsWith("clan:")) return LOCAL;
        HttpUrl target = HttpUrl.parse(url);
        if (target == null || cancelled) return UNREACHABLE;
        Call call = client.newCall(new Request.Builder().url(target).cacheControl(CacheControl.FORCE_NETWORK).build());
        calls.add(call);
        try {
            if (cancelled) return UNREACHABLE;
            long start = System.nanoTime();
            try (Response response = call.execute()) {
                return response.isSuccessful() ? Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)) : UNREACHABLE;
            }
        } catch (IOException | RuntimeException error) {
            return UNREACHABLE;
        } finally {
            calls.remove(call);
        }
    }

    private void deliver(Map<String, Long> results, Consumer<Map<String, Long>> callback) {
        callbackExecutor.execute(() -> {
            if (!cancelled) callback.accept(Map.copyOf(results));
        });
    }

    public void cancel() {
        cancelled = true;
        for (Call call : calls) call.cancel();
        executor.shutdownNow();
    }

    public static void sort(List<Config> configs, Map<String, Long> results) {
        configs.sort(Comparator.comparingLong(config -> results.getOrDefault(config.getUrl(), UNREACHABLE)));
    }
}

/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 *
 * @author veronique
 */

/** Local HTTP fixtures only; no model, credentials, or outside network required. */
public final class LocalSummaryStreamTest {
    private static int checks;
    private static final String FINISH = event(Map.of(), "stop") + "data: [DONE]\n\n";
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public static void main(String[] args) throws Exception {
        unicodeAndReasoning();
        activeAndStalledStreams();
        invalidStreams();
        errorsAndLimits();
        cancellation();
        System.out.println("LocalSummaryStreamTest: " + checks + " checks passed.");
    }

    private static void unicodeAndReasoning() throws Exception {
        try (Server server = new Server(exchange -> {
            open(exchange);
            String text = ": heartbeat\n\n" + event(Map.of("role", "assistant"), null)
                    + event(Map.of("reasoning", "PRIVATE_ANALYSIS"), null)
                    + event(Map.of("reasoning_content", "PRIVATE_TRACE"), null)
                    + event(Map.of("content", "Clear mettā 🙏."), null)
                    + event(Map.of(), "stop") + "data: {\"choices\":[],\"usage\":{\"completion_tokens\":20}}\n\n"
                    + "data: [DONE]\n\n";
            // Split every UTF-8 sequence over individual transport writes.
            for (byte value : text.getBytes(StandardCharsets.UTF_8)) {
                exchange.getResponseBody().write(value); exchange.getResponseBody().flush();
            }
        })) {
            Map<String, Object> body = new LinkedHashMap<>(Map.of("model", "local", "stream", false));
            List<String> progress = new ArrayList<>();
            String result = LocalSummaryStream.complete(CLIENT, server.uri(), "fixture-key", body, progress::add, "Section 1", false);
            equal("Clear mettā 🙏.", result, "incremental UTF-8 decoded without corruption");
            equal(false, body.get("stream"), "caller request map unchanged");
            check(server.request.get().contains("\"stream\":true"), "streaming forced in transmitted request");
            equal("Bearer fixture-key", server.authorization.get(), "authentication forwarded");
            check(!String.join("", progress).contains("PRIVATE_"), "reasoning never included in progress");
            check(progress.stream().allMatch(value -> value.startsWith("Section 1")), "phase preserved in progress");
        }
    }

    private static void activeAndStalledStreams() throws Exception {
        try (Server server = new Server(exchange -> {
            open(exchange);
            for (int i = 0; i < 8; i++) { send(exchange, event(Map.of("reasoning", "hidden"), null)); Thread.sleep(100); }
            send(exchange, event(Map.of("content", "Complete"), null) + FINISH);
        })) {
            List<String> progress = new ArrayList<>();
            long began = System.nanoTime();
            equal("Complete", call(server, false, progress, 400, 3000), "activity extends idle window");
            check(Duration.ofNanos(System.nanoTime() - began).toMillis() >= 700, "request lasts beyond idle timeout without failing");
            check(progress.size() >= 4, "progress published while only reasoning arrives");
            check(progress.stream().anyMatch(value -> value.contains("AI is working")), "reasoning creates generic activity indication");
            check(!String.join("", progress).contains("hidden"), "reasoning content stays private");
        }
        try (Server server = new Server(exchange -> Thread.sleep(2000))) {
            IOException error = failure(() -> call(server, false, new ArrayList<>(), 250, 3000));
            check(error.getMessage().contains("stopped responding"), "first response timeout");
        }
        try (Server server = new Server(exchange -> {
            open(exchange); send(exchange, event(Map.of("role", "assistant"), null)); Thread.sleep(2000);
        })) {
            IOException error = failure(() -> call(server, false, new ArrayList<>(), 250, 3000));
            check(error.getMessage().contains("stopped responding"), "idle stream timeout");
        }
        try (Server server = new Server(exchange -> {
            open(exchange);
            for (int i = 0; i < 20; i++) { send(exchange, event(Map.of("content", "a"), null)); Thread.sleep(100); }
            send(exchange, FINISH);
        })) {
            IOException error = failure(() -> call(server, false, new ArrayList<>(), 400, 600));
            check(error.getMessage().contains("processing time limit"), "active stream remains bounded overall");
        }
    }

    private static void invalidStreams() throws Exception {
        List<String> bad = List.of(
                event(Map.of("content", "Unfinished"), null),
                event(Map.of("content", "Unfinished"), null) + event(Map.of(), "stop"),
                "data: [DONE]\n\n",
                "data: not-json\n\n",
                "data: {}\n\n",
                "data: {\"choices\":[{\"delta\":{\"content\":7}}]}\n\n",
                "data: {\"choices\":[]}\n\n",
                "This is not an event stream\n",
                event(Map.of("content", "Partial"), "length") + "data: [DONE]\n\n",
                event(Map.of(), "tool_calls") + "data: [DONE]\n\n",
                event(Map.of(), "stop") + event(Map.of("content", "After stop"), null) + "data: [DONE]\n\n",
                FINISH);
        for (String badStream : bad) try (Server server = fixed(badStream)) {
            IOException error = failure(() -> call(server, false, new ArrayList<>(), 1000, 3000));
            check(!error.getMessage().isBlank(), "invalid/truncated stream explains failure");
        }
        try (Server server = fixed(FINISH)) { equal("", call(server, true, new ArrayList<>(), 1000, 3000), "empty excluded notes accepted when allowed"); }
        try (Server server = fixed(event(Map.of("content", "Partial"), "length") + "data: [DONE]\n\n")) {
            check(failure(() -> call(server, true, new ArrayList<>(), 1000, 3000)).getMessage().contains("output limit"), "allowEmpty never accepts truncated output");
        }
    }

    private static void errorsAndLimits() throws Exception {
        String key = "fixture-private-key";
        String errorJson = Json.stringify(Map.of("error", Map.of("message", "Denied " + key + " Bearer some-other-secret sk-exampleSecret")));
        try (Server server = new Server(exchange -> {
            exchange.sendResponseHeaders(401, 0); send(exchange, errorJson);
        })) {
            IOException error = failure(() -> LocalSummaryStream.complete(CLIENT, server.uri(), key, Map.of(), null, "Test", false));
            check(error.getMessage().contains("401") && error.getMessage().contains("Denied"), "HTTP status and useful diagnostic retained");
            check(!error.getMessage().contains(key) && !error.getMessage().contains("some-other-secret") && !error.getMessage().contains("sk-exampleSecret"), "credentials redacted from HTTP diagnostic");
        }
        try (Server server = fixed("data: " + errorJson + "\n\n")) {
            IOException error = failure(() -> LocalSummaryStream.complete(CLIENT, server.uri(), key, Map.of(), null, "Test", false));
            check(error.getMessage().contains("Denied") && !error.getMessage().contains(key), "stream errors sanitized too");
        }
        try (Server server = fixed("data: " + "x".repeat(1_048_600) + "\n\n")) {
            check(failure(() -> call(server, false, new ArrayList<>(), 2000, 4000)).getMessage().contains("size limit"), "protocol data bounded before JSON parsing");
        }
        try (Server server = new Server(exchange -> {
            open(exchange); exchange.getResponseBody().write(new byte[] {(byte) 0xc3, (byte) 0x28, '\n'});
        })) {
            check(failure(() -> call(server, false, new ArrayList<>(), 1000, 3000)).getMessage().contains("could not be read"), "malformed UTF-8 rejected");
        }
    }

    private static void cancellation() throws Exception {
        for (boolean headers : List.of(false, true)) {
            CountDownLatch started = new CountDownLatch(1);
            try (Server server = new Server(exchange -> {
                if (headers) { open(exchange); send(exchange, event(Map.of("role", "assistant"), null)); }
                started.countDown(); Thread.sleep(3000);
            })) {
                AtomicReference<Throwable> caught = new AtomicReference<>();
                Thread worker = new Thread(() -> {
                    try { LocalSummaryStream.complete(CLIENT, server.uri(), "", Map.of(), null, "Test", false); }
                    catch (Throwable error) { caught.set(error); }
                });
                worker.start(); check(started.await(2, TimeUnit.SECONDS), "request started before cancellation");
                worker.interrupt(); worker.join(2000);
                check(!worker.isAlive(), "cancellation releases caller promptly");
                check(caught.get() instanceof InterruptedException, "cancellation stays an interruption");
            }
        }
    }

    private static String call(Server server, boolean allowEmpty, List<String> progress, long idleMillis, long totalMillis) throws Exception {
        return LocalSummaryStream.complete(CLIENT, server.uri(), "", Map.of("model", "local"), progress::add, "Section",
                allowEmpty, Duration.ofMillis(idleMillis), Duration.ofMillis(totalMillis), Duration.ofMillis(50));
    }
    private static Server fixed(String text) throws IOException { return new Server(exchange -> { open(exchange); send(exchange, text); }); }
    private static void open(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, 0);
    }
    private static void send(HttpExchange exchange, String text) throws IOException {
        exchange.getResponseBody().write(text.getBytes(StandardCharsets.UTF_8)); exchange.getResponseBody().flush();
    }
    private static String event(Map<String, Object> delta, String finish) {
        Map<String, Object> choice = new LinkedHashMap<>(); choice.put("delta", delta); choice.put("finish_reason", finish);
        return "data: " + Json.stringify(Map.of("choices", List.of(choice))) + "\n\n";
    }
    @FunctionalInterface private interface Handler { void handle(HttpExchange exchange) throws Exception; }
    private static final class Server implements AutoCloseable {
        final HttpServer server;
        final ExecutorService workers = Executors.newCachedThreadPool();
        final AtomicReference<String> request = new AtomicReference<>(), authorization = new AtomicReference<>();
        Server(Handler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/v1/chat/completions", exchange -> {
                try {
                    request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    handler.handle(exchange);
                } catch (Exception ignored) { } finally { exchange.close(); }
            });
            server.start();
        }
        URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions"); }
        public void close() { server.stop(0); workers.shutdownNow(); }
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static IOException failure(Action action) throws Exception {
        try { action.run(); } catch (IOException error) { checks++; return error; }
        throw new AssertionError("Expected streaming failure");
    }
    private static void equal(Object expected, Object actual, String message) { check(java.util.Objects.equals(expected, actual), message + ": got " + actual); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}

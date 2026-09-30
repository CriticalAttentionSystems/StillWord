/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 *
 * @author veronique
 */

/** Local-only regression checks; no model download, external network, or paid API calls. */
public final class LocalAiServiceTest {
    private static int checks;
    public static void main(String[] arguments) throws Exception {
        equal(false, LocalAiService.hasRequiredContext(Map.of()));
        equal(false, LocalAiService.hasRequiredContext(Map.of("parameters", "num_ctx 4096")));
        equal(false, LocalAiService.hasRequiredContext(Map.of("parameters", "num_ctx wrong")));
        equal(false, LocalAiService.hasRequiredContext(Map.of("parameters", "num_ctx 16384")));
        equal(true, LocalAiService.hasRequiredContext(Map.of("parameters", "temperature 1\nnum_ctx 32768\n")));
        equal(true, LocalAiService.hasRequiredContext(Map.of("parameters", "num_ctx 65536")));
        equal("pulling 42%", LocalAiService.cleanProgress("\u001B[2K\u001B[1Gpulling 42%\r"));
        equal(400, LocalAiService.cleanProgress("x".repeat(1000)).length());
        equal("Downloading local model: 6% (892 MB / 13 GB)", LocalAiService.cleanProgress("pulling e7b273f96360:   6% ▕█                 ▏ 892 MB/ 13 GB   11 MB/s  18m35spulling manifest"));
        equal("Checking the model download…", LocalAiService.cleanProgress("pulling manifest ⠙ pulling manifest"));

        AtomicReference<String> parameters = new AtomicReference<>("num_ctx 32768");
        AtomicInteger baseStatus = new AtomicInteger(200), baseChecks = new AtomicInteger(), downloads = new AtomicInteger(), creations = new AtomicInteger();
        AtomicReference<Map<?, ?>> creation = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> {
            byte[] body = "{\"models\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/show", exchange -> {
            Object request = Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean base = request.equals(Map.of("model", LocalAiService.BASE_MODEL));
            if (base) baseChecks.incrementAndGet();
            else equal(Map.of("model", LocalAiService.MODEL), request);
            int statusCode = base ? baseStatus.get() : 200;
            byte[] body = Json.stringify(statusCode == 200 ? Map.of("parameters", base ? "num_ctx 4096" : parameters.get())
                    : Map.of("error", "model unavailable")).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/create", exchange -> {
            Map<?, ?> request = (Map<?, ?>) Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            creation.set(request); creations.incrementAndGet();
            Number context = (Number) ((Map<?, ?>) request.get("parameters")).get("num_ctx");
            parameters.set("num_ctx " + context.intValue());
            byte[] body = "{\"status\":\"success\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            LocalAiService service = new LocalAiService(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), report -> {
                downloads.incrementAndGet(); baseStatus.set(200);
            });
            equal(true, service.status().modelReady());
            service.ensureRunning(null);
            checks++;
            parameters.set("num_ctx 2048");
            LocalAiService.Status status = service.status();
            equal(true, status.running());
            equal(false, status.modelReady());
            try { service.ensureRunning(null); throw new AssertionError("A short context must not be silently accepted"); }
            catch (java.io.IOException expected) { checks++; }
            parameters.set("num_ctx 32768");
            List<String> messages = new ArrayList<>();
            service.setup(messages::add);
            equal(true, messages.stream().anyMatch(message -> message.contains("already ready")));
            equal(0, creations.get());
            equal(0, downloads.get());

            parameters.set("num_ctx 16384");
            messages.clear();
            service.setup(messages::add);
            equal(1, baseChecks.get());
            equal(0, downloads.get());
            equal(1, creations.get());
            equal(LocalAiService.MODEL, creation.get().get("model"));
            equal(LocalAiService.BASE_MODEL, creation.get().get("from"));
            equal(false, creation.get().get("stream"));
            equal(32768, ((Number) ((Map<?, ?>) creation.get().get("parameters")).get("num_ctx")).intValue());
            equal(true, messages.stream().anyMatch(message -> message.contains("Using the downloaded")));
            equal(false, messages.stream().anyMatch(message -> message.contains("Downloading")));

            parameters.set("num_ctx 16384");
            baseStatus.set(404);
            service.setup(null);
            equal(1, downloads.get());
            equal(2, creations.get());

            parameters.set("num_ctx 16384");
            baseStatus.set(503);
            try { service.setup(null); throw new AssertionError("A server error must not trigger a model download"); }
            catch (java.io.IOException expected) { equal(true, expected.getMessage().contains("503")); }
            equal(1, downloads.get());
            equal(2, creations.get());
        } finally { server.stop(0); }

        Path directory = Files.createTempDirectory("stillword-local-ai-test-");
        try {
            ProcessBuilder builder = new ProcessBuilder("true");
            LocalAiService.configure(builder, directory);
            equal("127.0.0.1:11434", builder.environment().get("OLLAMA_HOST"));
            equal("32768", builder.environment().get("OLLAMA_CONTEXT_LENGTH"));
            equal("1", builder.environment().get("OLLAMA_NO_CLOUD"));
            equal(directory.resolve("models").toString(), builder.environment().get("OLLAMA_MODELS"));
            try { LocalAiService.run(List.of("/bin/sh", "-c", "sleep 10"), directory, Duration.ofMillis(80), message -> { });
                throw new AssertionError("Timeout was not enforced"); }
            catch (java.io.IOException expected) { checks++; }
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { LocalAiService.run(List.of("/bin/sh", "-c", "sleep 10"), directory, Duration.ofSeconds(30), message -> { }); }
                catch (Throwable exception) { failure.set(exception); }
            });
            worker.start();
            Thread.sleep(100);
            worker.interrupt();
            worker.join(2000);
            equal(false, worker.isAlive());
            equal(true, failure.get() instanceof InterruptedException);
            try { LocalAiService.run(List.of("/bin/sh", "-c", "printf 'download failed\\n'; exit 3"), directory, Duration.ofSeconds(2), message -> { });
                throw new AssertionError("Failure exit code was ignored"); }
            catch (java.io.IOException expected) { equal(true, expected.getMessage().contains("download failed")); }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("LocalAiServiceTest: " + checks + " checks passed");
    }

    private static void equal(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
        checks++;
    }
}

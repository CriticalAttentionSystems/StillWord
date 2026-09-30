/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 *
 * @author veronique
 */

/** Bounded OpenAI-compatible SSE transport for local summary requests. */
final class LocalSummaryStream {
    private static final int MAX_BYTES = 1_048_576;
    private LocalSummaryStream() { }

    static String complete(HttpClient client, URI uri, String apiKey, Map<String, Object> requestBody,
                           Consumer<String> progress, String phase, boolean allowEmpty) throws Exception {
        return complete(client, uri, apiKey, requestBody, progress, phase, allowEmpty,
                Duration.ofSeconds(120), Duration.ofMinutes(8), Duration.ofSeconds(2));
    }

    /** Shorter timing limits are injected only by offline tests. */
    static String complete(HttpClient client, URI uri, String apiKey, Map<String, Object> requestBody,
                           Consumer<String> progress, String phase, boolean allowEmpty,
                           Duration idleTimeout, Duration overallTimeout, Duration progressInterval) throws Exception {
        Clock clock = new Clock(idleTimeout, overallTimeout, progressInterval, progress, phase);
        checkInterrupted();
        Map<String, Object> body = new LinkedHashMap<>(requestBody);
        body.put("stream", true);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8));
        if (apiKey != null && !apiKey.isEmpty()) request.header("Authorization", "Bearer " + apiKey);
        CompletableFuture<HttpResponse<InputStream>> pending = client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        InputStream input = null;
        Thread reader = null;
        try {
            HttpResponse<InputStream> response;
            while (true) {
                clock.poll();
                try { response = pending.get(100, TimeUnit.MILLISECONDS); break; }
                catch (TimeoutException ignored) { }
                catch (ExecutionException exception) { throw new IOException("Could not connect to the local AI service.", exception.getCause()); }
            }
            input = response.body();
            clock.activity();
            BlockingQueue<Read> reads = new LinkedBlockingQueue<>(128);
            InputStream stream = input;
            reader = new Thread(() -> readLines(stream, reads), "stillword-summary-stream");
            reader.setDaemon(true);
            reader.start();
            boolean successful = response.statusCode() >= 200 && response.statusCode() < 300;
            StringBuilder errorBody = new StringBuilder(), event = new StringBuilder(), answer = new StringBuilder();
            boolean finished = false;
            while (true) {
                clock.poll();
                Read next = reads.poll(100, TimeUnit.MILLISECONDS);
                if (next == null) continue;
                if (next.error() != null) throw new IOException("The local AI stream could not be read: " + safe(next.error().getMessage(), apiKey));
                if (next.end()) {
                    if (!successful) throw httpError(response.statusCode(), errorBody.toString(), apiKey);
                    throw new IOException("The local AI stream ended before its completion marker. No incomplete summary was accepted.");
                }
                String line = next.line();
                if (!successful) {
                    clock.activity();
                    if (errorBody.length() < 4096) errorBody.append(line, 0, Math.min(line.length(), 4096 - errorBody.length()));
                    continue;
                }
                if (line.startsWith("\uFEFF")) line = line.substring(1);
                if (line.isEmpty()) {
                    if (event.isEmpty()) continue;
                    String data = event.toString();
                    event.setLength(0);
                    clock.activity();
                    if (data.equals("[DONE]")) {
                        if (!finished) throw new IOException("The local AI stream omitted its finish status.");
                        String result = answer.toString().strip();
                        if (result.isBlank() && !allowEmpty) throw new IOException("The local AI returned an empty summary.");
                        return result;
                    }
                    Map<?, ?> chunk;
                    try {
                        Object decoded = Json.parse(data);
                        if (!(decoded instanceof Map<?, ?> object)) throw new IllegalArgumentException();
                        chunk = object;
                    } catch (IllegalArgumentException exception) { throw new IOException("The local AI returned a malformed streaming response."); }
                    if (chunk.containsKey("error")) throw httpError(response.statusCode(), data, apiKey);
                    if (!(chunk.get("choices") instanceof List<?> choices)) throw malformed();
                    if (choices.isEmpty()) {
                        if (chunk.containsKey("usage")) continue;
                        throw malformed();
                    }
                    if (choices.size() != 1 || !(choices.get(0) instanceof Map<?, ?> choice)) throw malformed();
                    if (choice.get("delta") != null && !(choice.get("delta") instanceof Map<?, ?>)) throw malformed();
                    Map<?, ?> delta = choice.get("delta") instanceof Map<?, ?> value ? value : Map.of();
                    String content = stringField(delta, "content");
                    int reasoning = stringField(delta, "reasoning").length() + stringField(delta, "reasoning_content").length();
                    if (!content.isEmpty() || reasoning > 0) clock.receivedContent = true;
                    if (finished && (!content.isEmpty() || reasoning > 0)) throw malformed();
                    answer.append(content);
                    if (answer.length() > MAX_BYTES) throw new IOException("The local AI summary exceeded the response size limit.");
                    Object reason = choice.get("finish_reason");
                    if (reason != null) {
                        if (reason.equals("length")) throw new IOException("The local AI reached its output limit. No truncated summary was accepted.");
                        if (!reason.equals("stop")) throw new IOException("The local AI could not complete this summary (finish status: " + safe(String.valueOf(reason), apiKey) + ").");
                        finished = true;
                    }
                } else if (line.startsWith("data:")) {
                    if (!event.isEmpty()) event.append('\n');
                    String value = line.substring(5);
                    event.append(value.startsWith(" ") ? value.substring(1) : value);
                } else if (!(line.startsWith(":") || line.startsWith("event:") || line.startsWith("id:") || line.startsWith("retry:"))) {
                    throw new IOException("The local AI did not return a valid event stream.");
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } finally {
            pending.cancel(true);
            if (input != null) try { input.close(); } catch (IOException ignored) { }
            else if (pending.isDone() && !pending.isCompletedExceptionally() && !pending.isCancelled()) {
                HttpResponse<InputStream> response = pending.getNow(null);
                if (response != null) try { response.body().close(); } catch (IOException ignored) { }
            }
            if (reader != null) reader.interrupt();
        }
    }

    private static String stringField(Map<?, ?> map, String field) throws IOException {
        Object value = map.get(field);
        if (value == null) return "";
        if (!(value instanceof String text)) throw malformed();
        return text;
    }

    private static IOException malformed() { return new IOException("The local AI returned a malformed streaming response."); }

    private record Read(String line, IOException error, boolean end) { }

    private static void readLines(InputStream source, BlockingQueue<Read> output) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FilterInputStream(source) {
            private int count;
            private void count(int amount) throws IOException {
                if (amount > 0 && (count += amount) > MAX_BYTES) throw new IOException("Response exceeded the 1 MiB size limit.");
            }
            @Override public int read() throws IOException { int value = in.read(); count(value < 0 ? 0 : 1); return value; }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int value = in.read(bytes, offset, length); count(value); return value;
            }
        }, StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)))) {
            String line;
            while ((line = reader.readLine()) != null) output.put(new Read(line, null, false));
            output.put(new Read(null, null, true));
        } catch (IOException exception) {
            try { output.put(new Read(null, exception, false)); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private static IOException httpError(int status, String body, String apiKey) {
        String details = body;
        try {
            Object parsed = Json.parse(body);
            if (parsed instanceof Map<?, ?> object && object.get("error") instanceof Map<?, ?> error && error.get("message") instanceof String text)
                details = text;
            else if (parsed instanceof Map<?, ?> object && object.get("error") instanceof String text) details = text;
        } catch (IllegalArgumentException ignored) { }
        return new IOException("Local AI request failed (HTTP " + status + "). " + safe(details, apiKey));
    }

    private static String safe(String value, String apiKey) {
        if (value == null) return "";
        String text = apiKey == null || apiKey.isEmpty() ? value : value.replace(apiKey, "[redacted]");
        text = text.replaceAll("(?i)Bearer\\s+[^\\s\\\"<>]+", "Bearer [redacted]")
                .replaceAll("(?i)sk-[a-z0-9_-]+", "[redacted]")
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
        return text.length() <= 2000 ? text : text.substring(0, 2000);
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Summary cancelled.");
    }

    private static final class Clock {
        final long started = System.nanoTime(), idleNanos, overallNanos, progressNanos;
        final Consumer<String> progress;
        final String phase;
        long active = started, lastReport;
        boolean receivedContent;
        Clock(Duration idle, Duration overall, Duration interval, Consumer<String> progress, String phase) {
            if (idle.isZero() || idle.isNegative() || overall.isZero() || overall.isNegative() || interval.isZero() || interval.isNegative())
                throw new IllegalArgumentException("Streaming time limits must be positive.");
            idleNanos = idle.toNanos(); overallNanos = overall.toNanos(); progressNanos = interval.toNanos();
            this.progress = progress == null ? ignored -> { } : progress;
            this.phase = phase == null || phase.isBlank() ? "Summarizing" : phase;
        }
        void activity() { active = System.nanoTime(); }
        void poll() throws IOException, InterruptedException {
            checkInterrupted();
            long now = System.nanoTime();
            if (now - started >= overallNanos) throw new IOException("This local AI section exceeded its processing time limit. No incomplete summary was accepted.");
            if (now - active >= idleNanos) throw new IOException("The local AI stopped responding while processing this section. No incomplete summary was accepted.");
            if (now - lastReport >= progressNanos) {
                progress.accept(phase + " · " + TimeUnit.NANOSECONDS.toSeconds(now - started) + "s · "
                        + (receivedContent ? "AI is working…" : "Waiting for local AI…"));
                lastReport = now;
            }
        }
    }
}

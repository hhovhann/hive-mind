import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Closed-loop load generator for Hive Mind.
 *
 * <p>Single file, no build step, no install: {@code java LoadTest.java}. Anyone who
 * clones the repo can reproduce the numbers in the README, which is the only reason
 * a benchmark is worth publishing. It uses virtual threads, so holding 200 requests
 * in flight costs 200 cheap threads rather than a tuned pool — the same property the
 * server relies on.
 *
 * <p>Runs a ramp rather than a single level. One number at 200 concurrent says
 * nothing useful; the shape from 1 to 200 shows where throughput stops rising and
 * latency starts climbing, which is the only part anyone can act on.
 *
 * <pre>
 *   java LoadTest.java --endpoint retrieve --ramp 1,10,50,200 --seconds 20
 *   java LoadTest.java --endpoint ask      --ramp 1,4,16      --seconds 30
 * </pre>
 */
public class LoadTest {

    /** A mix that exercises different retrieval paths: current, historical, ACL, identity. */
    private static final String[] QUESTIONS = {
        "When does Frontier premiere",
        "Who owns the video CMS migration",
        "Did we agree a revenue split with Nordwind",
        "What did Priya commit to for the studio",
        "How many times did the Frontier launch date change",
        "Are we discontinuing the newsletter",
        "Who set the original Frontier premiere date",
        "Is there a hiring freeze",
    };

    public static void main(String[] args) throws Exception {
        String base = arg(args, "--url", "http://localhost:8080");
        String endpoint = arg(args, "--endpoint", "retrieve");
        int seconds = Integer.parseInt(arg(args, "--seconds", "20"));
        int timeout = Integer.parseInt(arg(args, "--timeout", "120"));
        List<Integer> ramp = Arrays.stream(arg(args, "--ramp", "1,10,50,200").split(","))
                .map(String::trim)
                .map(Integer::parseInt)
                .toList();

        URI target = URI.create(base + "/api/" + endpoint);
        // HTTP/1.1: the same h2c problem that bites local model servers also applies
        // to a plain Spring Boot connector, and negotiating per request would pollute
        // the very latencies we are trying to measure.
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        System.out.printf("%n  Hive Mind load test — %s%n", target);
        System.out.printf("  %ds per level, %ds request timeout%n%n", seconds, timeout);
        System.out.printf(
                "  %-6s %8s %9s %9s %9s %9s %8s %8s%n",
                "conc", "req/s", "p50", "p95", "p99", "max", "errors", "timeouts");
        System.out.printf("  %s%n", "-".repeat(74));

        warmUp(client, target, timeout);
        for (int concurrency : ramp) {
            Result result = runLevel(client, target, concurrency, seconds, timeout);
            System.out.printf(
                    "  %-6d %8.1f %8dms %8dms %8dms %8dms %8d %8d%n",
                    concurrency,
                    result.throughput(),
                    result.percentile(50),
                    result.percentile(95),
                    result.percentile(99),
                    result.percentile(100),
                    result.errors,
                    result.timeouts);
        }
        System.out.printf("%n");
    }

    /** One unmeasured request per level so JIT and connection setup do not land in p99. */
    private static void warmUp(HttpClient client, URI target, int timeout) throws Exception {
        for (int i = 0; i < 3; i++) {
            send(client, target, QUESTIONS[i], timeout);
        }
    }

    private static Result runLevel(HttpClient client, URI target, int concurrency, int seconds, int timeout)
            throws Exception {
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger timeouts = new AtomicInteger();
        AtomicLong sent = new AtomicLong();
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        CountDownLatch done = new CountDownLatch(concurrency);

        long start = System.nanoTime();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int worker = 0; worker < concurrency; worker++) {
                final int id = worker;
                executor.submit(() -> {
                    try {
                        int index = id;
                        while (System.nanoTime() < deadline) {
                            String question = QUESTIONS[Math.floorMod(index++, QUESTIONS.length)];
                            long began = System.nanoTime();
                            try {
                                int status = send(client, target, question, timeout);
                                long elapsed = (System.nanoTime() - began) / 1_000_000;
                                if (status == 200) {
                                    latencies.add(elapsed);
                                } else {
                                    errors.incrementAndGet();
                                }
                            } catch (java.net.http.HttpTimeoutException e) {
                                timeouts.incrementAndGet();
                            } catch (Exception e) {
                                errors.incrementAndGet();
                            }
                            sent.incrementAndGet();
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            done.await();
        }
        double elapsedSeconds = (System.nanoTime() - start) / 1_000_000_000.0;
        return new Result(new ArrayList<>(latencies), errors.get(), timeouts.get(), elapsedSeconds);
    }

    private static int send(HttpClient client, URI target, String question, int timeout) throws Exception {
        String body = "{\"question\":\"" + question.replace("\"", "\\\"") + "\"}";
        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(timeout))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private record Result(List<Long> latencies, int errors, int timeouts, double elapsedSeconds) {

        double throughput() {
            return latencies.size() / elapsedSeconds;
        }

        /** Percentile over successful requests only; failures are counted separately. */
        long percentile(int p) {
            if (latencies.isEmpty()) {
                return 0;
            }
            List<Long> sorted = new ArrayList<>(latencies);
            sorted.sort(Long::compare);
            int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
        }
    }

    private static String arg(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }
}

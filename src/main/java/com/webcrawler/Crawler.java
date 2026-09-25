package com.webcrawler;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Runs the crawl. Every URL that passes the frontier checks (depth, scope, not seen before)
 * gets its own virtual thread, which:
 *   network guard -> robots.txt check -> page budget -> per-host delay -> connection slot
 *   -> fetch -> parse -> Safe Browsing check on the links -> submit the safe links.
 *
 * The crawl ends when the count of queued-or-running URLs drops to zero.
 */
public final class Crawler {

    public record Summary(int fetched, int failed, int blockedByRobots, int blockedUnsafe, Duration elapsed) {
    }

    /** URLs split by Safe Browsing: the ones safe to crawl, the flagged ones, or why the check failed. */
    private record Screening(List<URI> safe, Map<String, String> threats, String error) {
    }

    private final CrawlerConfig config;
    private final ResultWriter writer;
    private final NetworkGuard guard;
    private final PageFetcher fetcher;
    private final RobotsCache robots;
    private final SafeBrowsing safeBrowsing;
    private final HostThrottle throttle = new HostThrottle();
    private final Semaphore connections;
    private final Set<String> scopeDomains;

    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final Set<String> flagged = ConcurrentHashMap.newKeySet();
    private final AtomicInteger budgetUsed = new AtomicInteger();
    private final AtomicInteger fetched = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger blockedByRobots = new AtomicInteger();
    private final AtomicInteger blockedUnsafe = new AtomicInteger();

    // Starts at 1 so it can't reach zero while the seeds are still being submitted.
    private final AtomicInteger pending = new AtomicInteger(1);
    private final CountDownLatch finished = new CountDownLatch(1);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public Crawler(CrawlerConfig config, ResultWriter writer) {
        this(config, writer, SafeBrowsing.forKey(config.safeBrowsingKey(), config.timeout()));
    }

    Crawler(CrawlerConfig config, ResultWriter writer, SafeBrowsing safeBrowsing) {
        this.config = config;
        this.writer = writer;
        this.safeBrowsing = safeBrowsing;
        this.guard = new NetworkGuard(config.allowPrivateNetwork());
        this.fetcher = new PageFetcher(config.userAgent(), config.timeout(), guard);
        this.robots = new RobotsCache(fetcher, config.userAgent());
        this.connections = new Semaphore(config.concurrency(), true);
        this.scopeDomains = config.seeds().stream()
                .map(seed -> stripWww(seed.getHost()))
                .collect(Collectors.toUnmodifiableSet());
    }

    public Summary run() throws InterruptedException {
        long start = System.nanoTime();
        try {
            Screening seeds = screen(config.seeds());
            for (URI seed : config.seeds()) {
                if (seeds.safe().contains(seed)) {
                    submit(seed, 0);
                } else {
                    String reason = seeds.error() != null
                            ? seeds.error()
                            : "flagged by Safe Browsing: " + seeds.threats().get(seed.toString());
                    writer.write(CrawlResult.blocked(seed, 0, reason));
                }
            }
            taskDone(); // releases the initial count
            finished.await();
        } finally {
            executor.shutdownNow();
        }
        return new Summary(fetched.get(), failed.get(), blockedByRobots.get(), blockedUnsafe.get(),
                Duration.ofNanos(System.nanoTime() - start));
    }

    /** The frontier: drops URLs that are too deep, out of scope, already seen, or over budget. */
    private void submit(URI url, int depth) {
        if (depth > config.maxDepth() || !inScope(url) || budgetExhausted()) {
            return;
        }
        if (!seen.add(url.toString())) {
            return;
        }
        pending.incrementAndGet();
        executor.execute(() -> {
            try {
                process(url, depth);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                System.err.printf("unexpected error on %s: %s%n", url, e);
            } finally {
                taskDone();
            }
        });
    }

    private void taskDone() {
        if (pending.decrementAndGet() == 0) {
            finished.countDown();
        }
    }

    private void process(URI url, int depth) throws InterruptedException {
        if (budgetExhausted()) {
            return;
        }

        try {
            guard.verify(url);
        } catch (NetworkGuard.UnsafeAddressException e) {
            blockUnsafeAddress(url, depth, e);
            return;
        } catch (IOException e) {
            recordFailure(url, depth, e, 0); // DNS lookup failed
            return;
        }

        RobotsTxt rules = robots.forUrl(url);
        if (!rules.isAllowed(pathAndQuery(url))) {
            blockedByRobots.incrementAndGet();
            writer.write(CrawlResult.blocked(url, depth, "blocked by robots.txt"));
            System.out.printf("[BLK]        d=%d %s%n", depth, url);
            return;
        }

        // Reserve a slot in the page budget only for pages we are actually going to fetch.
        if (budgetUsed.incrementAndGet() > config.maxPages()) {
            return;
        }

        Duration delay = config.perHostDelay().compareTo(rules.crawlDelay()) >= 0
                ? config.perHostDelay()
                : rules.crawlDelay();
        throttle.acquire(url.getAuthority(), delay);

        PageFetcher.Response response;
        long start = System.nanoTime();
        connections.acquire();
        try {
            response = fetcher.fetchPage(url);
        } catch (NetworkGuard.UnsafeAddressException e) {
            blockUnsafeAddress(url, depth, e); // DNS changed to an internal address since the first check
            return;
        } catch (IOException e) {
            recordFailure(url, depth, e, elapsedMs(start));
            return;
        } finally {
            connections.release();
        }
        long fetchMs = elapsedMs(start);
        fetched.incrementAndGet();

        if (response.redirectTo() != null) {
            Screening target = screen(List.of(response.redirectTo()));
            writer.write(CrawlResult.redirect(url, depth, response.status(), response.redirectTo(),
                    target.threats(), target.error(), fetchMs));
            System.out.printf("[%d] %5dms d=%d %s -> %s%n", response.status(), fetchMs, depth, url, response.redirectTo());
            // Same depth: a redirect is the same page under a new address, not a new hop.
            target.safe().forEach(safeTarget -> submit(safeTarget, depth));
        } else if (response.hasBody()) {
            PageParser.ParsedPage page = PageParser.parse(response.body(), response.contentType(), url);
            Screening links = screen(page.links());
            writer.write(CrawlResult.page(url, depth, response.status(), response.contentType(),
                    page.title(), page.links(), links.threats(), links.error(), fetchMs));
            System.out.printf("[%d] %5dms d=%d %s (%d links)%n", response.status(), fetchMs, depth, url, page.links().size());
            if (depth < config.maxDepth()) {
                links.safe().forEach(link -> submit(link, depth + 1));
            }
        } else {
            writer.write(CrawlResult.page(url, depth, response.status(), response.contentType(),
                    null, null, null, null, fetchMs));
            System.out.printf("[%d] %5dms d=%d %s%n", response.status(), fetchMs, depth, url);
        }
    }

    /**
     * Asks Safe Browsing about the URLs before any of them are crawled. If the check itself
     * fails, none of them count as safe: better to skip links than to follow a malicious one.
     */
    private Screening screen(List<URI> urls) throws InterruptedException {
        if (!safeBrowsing.enabled() || urls.isEmpty()) {
            return new Screening(urls, null, null);
        }
        try {
            Map<String, String> threats = safeBrowsing.findThreats(urls);
            for (Map.Entry<String, String> threat : threats.entrySet()) {
                if (flagged.add(threat.getKey())) {
                    blockedUnsafe.incrementAndGet();
                    System.out.printf("[MAL]        %s (%s)%n", threat.getKey(), threat.getValue());
                }
            }
            List<URI> safe = urls.stream().filter(url -> !threats.containsKey(url.toString())).toList();
            return new Screening(safe, threats.isEmpty() ? null : threats, null);
        } catch (IOException e) {
            String error = "Safe Browsing check failed, links not followed: " + describe(e);
            System.err.println("[WARN] " + error);
            return new Screening(List.of(), null, error);
        }
    }

    private void blockUnsafeAddress(URI url, int depth, IOException e) {
        blockedUnsafe.incrementAndGet();
        writer.write(CrawlResult.blocked(url, depth, e.getMessage()));
        System.out.printf("[UNS]        d=%d %s (%s)%n", depth, url, e.getMessage());
    }

    private void recordFailure(URI url, int depth, IOException e, long fetchMs) {
        failed.incrementAndGet();
        String error = describe(e);
        writer.write(CrawlResult.failed(url, depth, error, fetchMs));
        System.out.printf("[ERR]        d=%d %s (%s)%n", depth, url, error);
    }

    private static String describe(IOException e) {
        return e.getMessage() == null
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private boolean budgetExhausted() {
        return budgetUsed.get() >= config.maxPages();
    }

    /** Without --allow-external, stays on the seeds' domains and their subdomains. */
    private boolean inScope(URI url) {
        if (config.allowExternal()) {
            return true;
        }
        String host = url.getHost();
        for (String domain : scopeDomains) {
            if (host.equals(domain) || host.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    private static String stripWww(String host) {
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    private static String pathAndQuery(URI url) {
        return url.getRawQuery() == null ? url.getRawPath() : url.getRawPath() + "?" + url.getRawQuery();
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}

package com.webcrawler;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
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
 *   robots.txt check -> page budget -> per-host delay -> connection slot -> fetch -> parse -> submit links.
 *
 * The crawl ends when the count of queued-or-running URLs drops to zero.
 */
public final class Crawler {

    public record Summary(int fetched, int failed, int blocked, Duration elapsed) {
    }

    private final CrawlerConfig config;
    private final ResultWriter writer;
    private final PageFetcher fetcher;
    private final RobotsCache robots;
    private final HostThrottle throttle = new HostThrottle();
    private final Semaphore connections;
    private final Set<String> scopeDomains;

    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final AtomicInteger budgetUsed = new AtomicInteger();
    private final AtomicInteger fetched = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger blocked = new AtomicInteger();

    // Starts at 1 so it can't reach zero while the seeds are still being submitted.
    private final AtomicInteger pending = new AtomicInteger(1);
    private final CountDownLatch finished = new CountDownLatch(1);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public Crawler(CrawlerConfig config, ResultWriter writer) {
        this.config = config;
        this.writer = writer;
        this.fetcher = new PageFetcher(config.userAgent(), config.timeout());
        this.robots = new RobotsCache(config.userAgent(), config.timeout());
        this.connections = new Semaphore(config.concurrency(), true);
        this.scopeDomains = config.seeds().stream()
                .map(seed -> stripWww(seed.getHost()))
                .collect(Collectors.toUnmodifiableSet());
    }

    public Summary run() throws InterruptedException {
        long start = System.nanoTime();
        try {
            config.seeds().forEach(seed -> submit(seed, 0));
            taskDone(); // releases the initial count
            finished.await();
        } finally {
            executor.shutdownNow();
        }
        return new Summary(fetched.get(), failed.get(), blocked.get(), Duration.ofNanos(System.nanoTime() - start));
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

        RobotsTxt rules = robots.forUrl(url);
        if (!rules.isAllowed(pathAndQuery(url))) {
            blocked.incrementAndGet();
            writer.write(CrawlResult.blockedByRobots(url, depth));
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
            response = fetcher.fetch(url);
        } catch (IOException e) {
            failed.incrementAndGet();
            String error = e.getMessage() == null
                    ? e.getClass().getSimpleName()
                    : e.getClass().getSimpleName() + ": " + e.getMessage();
            writer.write(CrawlResult.failed(url, depth, error, elapsedMs(start)));
            System.out.printf("[ERR]        d=%d %s (%s)%n", depth, url, error);
            return;
        } finally {
            connections.release();
        }
        long fetchMs = elapsedMs(start);
        fetched.incrementAndGet();

        if (response.redirectTo() != null) {
            writer.write(CrawlResult.redirect(url, depth, response.status(), response.redirectTo(), fetchMs));
            System.out.printf("[%d] %5dms d=%d %s -> %s%n", response.status(), fetchMs, depth, url, response.redirectTo());
            // Same depth: a redirect is the same page under a new address, not a new hop.
            submit(response.redirectTo(), depth);
        } else if (response.isHtml()) {
            PageParser.ParsedPage page = PageParser.parse(response.body(), response.contentType(), url);
            writer.write(CrawlResult.page(url, depth, response.status(), response.contentType(),
                    page.title(), page.links(), fetchMs));
            System.out.printf("[%d] %5dms d=%d %s (%d links)%n", response.status(), fetchMs, depth, url, page.links().size());
            for (URI link : page.links()) {
                submit(link, depth + 1);
            }
        } else {
            writer.write(CrawlResult.page(url, depth, response.status(), response.contentType(), null, null, fetchMs));
            System.out.printf("[%d] %5dms d=%d %s%n", response.status(), fetchMs, depth, url);
        }
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

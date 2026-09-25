package com.webcrawler;

import java.io.IOException;
import java.util.List;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length == 0 || List.of(args).contains("-h") || List.of(args).contains("--help")) {
            System.out.print(CrawlerConfig.USAGE);
            return;
        }

        CrawlerConfig config;
        try {
            config = CrawlerConfig.fromArgs(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println();
            System.err.print(CrawlerConfig.USAGE);
            System.exit(2);
            return;
        }

        System.out.printf("Crawling %s (max %d pages, depth %d, %d connections, %dms per-host delay)%n",
                config.seeds(), config.maxPages(), config.maxDepth(), config.concurrency(),
                config.perHostDelay().toMillis());
        System.out.println(config.safeBrowsingKey() != null
                ? "Safe Browsing: on, every link is checked for malware and phishing before it is crawled"
                : "Safe Browsing: off (set " + CrawlerConfig.SAFE_BROWSING_KEY_ENV + " to check links for malware)");
        if (config.allowPrivateNetwork()) {
            System.out.println("Warning: private network addresses are allowed (--allow-private-network)");
        }

        Crawler.Summary summary;
        try (ResultWriter writer = new ResultWriter(config.output())) {
            summary = new Crawler(config, writer).run();
        }

        System.out.printf("%nDone in %.1fs: %d fetched, %d failed, %d blocked by robots.txt, %d blocked as unsafe -> %s%n",
                summary.elapsed().toMillis() / 1000.0, summary.fetched(), summary.failed(),
                summary.blockedByRobots(), summary.blockedUnsafe(), config.output().toAbsolutePath());
    }
}

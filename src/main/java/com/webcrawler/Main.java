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

        Crawler.Summary summary;
        try (ResultWriter writer = new ResultWriter(config.output())) {
            summary = new Crawler(config, writer).run();
        }

        System.out.printf("%nDone in %.1fs: %d fetched, %d failed, %d blocked by robots.txt -> %s%n",
                summary.elapsed().toMillis() / 1000.0, summary.fetched(), summary.failed(), summary.blocked(),
                config.output().toAbsolutePath());
    }
}

package com.webcrawler;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public record CrawlerConfig(
        List<URI> seeds,
        int maxPages,
        int maxDepth,
        int concurrency,
        Duration perHostDelay,
        Duration timeout,
        String userAgent,
        Path output,
        boolean allowExternal) {

    public static final String DEFAULT_USER_AGENT = "JavaWebCrawler/1.0";

    public static final String USAGE = """
            Usage: java -jar web-crawler.jar <url> [<url>...] [options]

            Options:
              --max-pages N       stop after fetching N pages               (default 100)
              --max-depth N       follow links at most N hops from a seed   (default 3)
              --concurrency N     max simultaneous HTTP requests            (default 8)
              --delay-ms N        min gap between requests to one host      (default 1000)
              --timeout-s N       connect/response timeout in seconds       (default 10)
              --output FILE       JSONL output file                         (default crawl.jsonl)
              --user-agent STR    User-Agent header and robots.txt token    (default %s)
              --allow-external    follow links to other domains too
              -h, --help          show this help
            """.formatted(DEFAULT_USER_AGENT);

    public CrawlerConfig {
        if (seeds.isEmpty()) {
            throw new IllegalArgumentException("at least one seed URL is required");
        }
        if (maxPages < 1 || maxDepth < 0 || concurrency < 1) {
            throw new IllegalArgumentException("--max-pages and --concurrency must be >= 1, --max-depth >= 0");
        }
        if (perHostDelay.isNegative() || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("--delay-ms must be >= 0 and --timeout-s > 0");
        }
        seeds = List.copyOf(seeds);
    }

    static CrawlerConfig fromArgs(String[] args) {
        List<URI> seeds = new ArrayList<>();
        int maxPages = 100;
        int maxDepth = 3;
        int concurrency = 8;
        long delayMs = 1000;
        long timeoutSeconds = 10;
        String userAgent = DEFAULT_USER_AGENT;
        Path output = Path.of("crawl.jsonl");
        boolean allowExternal = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--max-pages" -> maxPages = parseInt(arg, valueAfter(args, ++i, arg));
                case "--max-depth" -> maxDepth = parseInt(arg, valueAfter(args, ++i, arg));
                case "--concurrency" -> concurrency = parseInt(arg, valueAfter(args, ++i, arg));
                case "--delay-ms" -> delayMs = parseInt(arg, valueAfter(args, ++i, arg));
                case "--timeout-s" -> timeoutSeconds = parseInt(arg, valueAfter(args, ++i, arg));
                case "--output" -> output = Path.of(valueAfter(args, ++i, arg));
                case "--user-agent" -> userAgent = valueAfter(args, ++i, arg);
                case "--allow-external" -> allowExternal = true;
                default -> {
                    if (arg.startsWith("-")) {
                        throw new IllegalArgumentException("unknown option " + arg);
                    }
                    seeds.add(UrlNormalizer.normalize(arg)
                            .orElseThrow(() -> new IllegalArgumentException("not an http(s) URL: " + arg)));
                }
            }
        }

        return new CrawlerConfig(seeds, maxPages, maxDepth, concurrency, Duration.ofMillis(delayMs),
                Duration.ofSeconds(timeoutSeconds), userAgent, output, allowExternal);
    }

    private static String valueAfter(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("missing value for " + option);
        }
        return args[index];
    }

    private static int parseInt(String option, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " expects a number, got '" + value + "'");
        }
    }
}

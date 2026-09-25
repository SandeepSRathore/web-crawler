package com.webcrawler;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Downloads each site's robots.txt once and shares it between all threads crawling that site. */
final class RobotsCache {

    private static final int MAX_REDIRECTS = 5;

    private final PageFetcher fetcher;
    private final String userAgent;
    private final ConcurrentHashMap<String, CompletableFuture<RobotsTxt>> byOrigin = new ConcurrentHashMap<>();

    RobotsCache(PageFetcher fetcher, String userAgent) {
        this.fetcher = fetcher;
        this.userAgent = userAgent;
    }

    RobotsTxt forUrl(URI url) {
        String origin = url.getScheme() + "://" + url.getAuthority();
        CompletableFuture<RobotsTxt> mine = new CompletableFuture<>();
        CompletableFuture<RobotsTxt> existing = byOrigin.putIfAbsent(origin, mine);
        if (existing != null) {
            // Another thread is already downloading it; wait for that result instead of fetching twice.
            return existing.join();
        }
        RobotsTxt rules = RobotsTxt.ALLOW_ALL;
        try {
            rules = download(origin);
        } finally {
            mine.complete(rules);
        }
        return rules;
    }

    private RobotsTxt download(String origin) {
        URI url = URI.create(origin + "/robots.txt");
        try {
            // Redirects are followed by hand so every hop goes through the NetworkGuard.
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                PageFetcher.Response response = fetcher.fetchRobots(url);
                if (response.redirectTo() != null) {
                    url = response.redirectTo();
                    continue;
                }
                int status = response.status();
                if (status >= 200 && status < 300 && response.hasBody()) {
                    return RobotsTxt.parse(new String(response.body(), StandardCharsets.UTF_8), userAgent);
                }
                if (status < 500) {
                    return RobotsTxt.ALLOW_ALL; // no robots.txt: everything is allowed
                }
                return RobotsTxt.DISALLOW_ALL; // server error: RFC 9309 says to assume the whole site is off limits
            }
            return RobotsTxt.ALLOW_ALL; // too many redirects: RFC 9309 treats it as missing
        } catch (NetworkGuard.UnsafeAddressException e) {
            return RobotsTxt.DISALLOW_ALL; // robots.txt points into a private network: stay away from the site
        } catch (IOException e) {
            // The page fetch will fail too and record the real network error.
            return RobotsTxt.ALLOW_ALL;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RobotsTxt.DISALLOW_ALL;
        }
    }
}

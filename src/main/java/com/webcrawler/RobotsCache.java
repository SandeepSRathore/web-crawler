package com.webcrawler;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Downloads each site's robots.txt once and shares it between all threads crawling that site. */
final class RobotsCache {

    private final HttpClient client;
    private final String userAgent;
    private final Duration timeout;
    private final ConcurrentHashMap<String, CompletableFuture<RobotsTxt>> byOrigin = new ConcurrentHashMap<>();

    RobotsCache(String userAgent, Duration timeout) {
        this.userAgent = userAgent;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(timeout)
                .build();
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
        HttpRequest request = HttpRequest.newBuilder(URI.create(origin + "/robots.txt"))
                .timeout(timeout)
                .header("User-Agent", userAgent)
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return RobotsTxt.parse(response.body(), userAgent);
            }
            if (status >= 400 && status < 500) {
                return RobotsTxt.ALLOW_ALL; // no robots.txt: everything is allowed
            }
            return RobotsTxt.DISALLOW_ALL; // server error: RFC 9309 says to assume the whole site is off limits
        } catch (IOException e) {
            // The page fetch will fail too and record the real network error.
            return RobotsTxt.ALLOW_ALL;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RobotsTxt.DISALLOW_ALL;
        }
    }
}

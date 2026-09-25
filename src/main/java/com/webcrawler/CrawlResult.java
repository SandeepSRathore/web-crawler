package com.webcrawler;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One line of the JSONL output. Fields that don't apply to an outcome are left out. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CrawlResult(
        String url,
        int depth,
        Integer status,
        String contentType,
        String title,
        String redirectTo,
        List<String> links,
        Long fetchMs,
        String error,
        String fetchedAt) {

    static CrawlResult page(URI url, int depth, int status, String contentType,
                            String title, List<URI> links, long fetchMs) {
        List<String> linkStrings = links == null ? null : links.stream().map(URI::toString).toList();
        return new CrawlResult(url.toString(), depth, status, contentType, title, null,
                linkStrings, fetchMs, null, now());
    }

    static CrawlResult redirect(URI url, int depth, int status, URI target, long fetchMs) {
        return new CrawlResult(url.toString(), depth, status, null, null, target.toString(),
                null, fetchMs, null, now());
    }

    static CrawlResult failed(URI url, int depth, String error, long fetchMs) {
        return new CrawlResult(url.toString(), depth, null, null, null, null,
                null, fetchMs, error, now());
    }

    static CrawlResult blockedByRobots(URI url, int depth) {
        return new CrawlResult(url.toString(), depth, null, null, null, null,
                null, null, "blocked by robots.txt", now());
    }

    private static String now() {
        return Instant.now().toString();
    }
}

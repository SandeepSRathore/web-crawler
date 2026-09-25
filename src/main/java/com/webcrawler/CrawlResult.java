package com.webcrawler;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
        Map<String, String> unsafeLinks,
        String linkCheckError,
        Long fetchMs,
        String error,
        String fetchedAt) {

    static CrawlResult page(URI url, int depth, int status, String contentType, String title, List<URI> links,
                            Map<String, String> unsafeLinks, String linkCheckError, long fetchMs) {
        List<String> linkStrings = links == null ? null : links.stream().map(URI::toString).toList();
        return new CrawlResult(url.toString(), depth, status, contentType, title, null,
                linkStrings, unsafeLinks, linkCheckError, fetchMs, null, now());
    }

    static CrawlResult redirect(URI url, int depth, int status, URI target,
                                Map<String, String> unsafeLinks, String linkCheckError, long fetchMs) {
        return new CrawlResult(url.toString(), depth, status, null, null, target.toString(),
                null, unsafeLinks, linkCheckError, fetchMs, null, now());
    }

    static CrawlResult failed(URI url, int depth, String error, long fetchMs) {
        return new CrawlResult(url.toString(), depth, null, null, null, null,
                null, null, null, fetchMs, error, now());
    }

    /** A URL the crawler decided not to request, with the reason in {@code error}. */
    static CrawlResult blocked(URI url, int depth, String reason) {
        return new CrawlResult(url.toString(), depth, null, null, null, null,
                null, null, null, null, reason, now());
    }

    private static String now() {
        return Instant.now().toString();
    }
}

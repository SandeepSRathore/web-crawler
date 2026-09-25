package com.webcrawler;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Performs single HTTP GETs. Every request is checked by the NetworkGuard first, and the whole
 * request, download included, must finish within the timeout. Bodies are size-capped and kept in memory only.
 */
final class PageFetcher {

    static final int MAX_PAGE_BYTES = 5 * 1024 * 1024;
    static final int MAX_ROBOTS_BYTES = 500 * 1024;

    /** body is non-null only when it was read; redirectTo only for a 3xx with a usable Location. */
    record Response(int status, String contentType, byte[] body, URI redirectTo) {
        boolean hasBody() {
            return body != null;
        }
    }

    private final HttpClient client;
    private final String userAgent;
    private final Duration timeout;
    private final NetworkGuard guard;

    PageFetcher(String userAgent, Duration timeout, NetworkGuard guard) {
        this.userAgent = userAgent;
        this.timeout = timeout;
        this.guard = guard;
        this.client = HttpClient.newBuilder()
                // Redirects are never followed automatically, so each target gets the same safety checks.
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout)
                .build();
    }

    /** Fetches a page. The body is read only for a 2xx HTML response. */
    Response fetchPage(URI url) throws IOException, InterruptedException {
        return fetchWithDeadline(url, true, MAX_PAGE_BYTES);
    }

    /** Fetches robots.txt. The body is read for any 2xx response. */
    Response fetchRobots(URI url) throws IOException, InterruptedException {
        return fetchWithDeadline(url, false, MAX_ROBOTS_BYTES);
    }

    /**
     * Runs the request on its own virtual thread so the whole fetch can be abandoned when the
     * timeout passes. That includes a body that arrives one byte at a time, which the HttpClient's
     * own timeout doesn't cover.
     */
    private Response fetchWithDeadline(URI url, boolean htmlOnly, int maxBytes)
            throws IOException, InterruptedException {
        FutureTask<Response> task = new FutureTask<>(() -> fetch(url, htmlOnly, maxBytes));
        Thread.ofVirtual().start(task);
        try {
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new HttpTimeoutException("gave up after " + timeout.toSeconds() + "s: server too slow");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException(cause);
        } finally {
            // Does nothing if the task finished. Otherwise it interrupts the read, which closes the connection.
            task.cancel(true);
        }
    }

    private Response fetch(URI url, boolean htmlOnly, int maxBytes) throws IOException, InterruptedException {
        guard.verify(url);
        HttpRequest request = HttpRequest.newBuilder(url)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        String contentType = response.headers().firstValue("Content-Type").orElse(null);

        try (InputStream in = response.body()) {
            if (status >= 300 && status < 400) {
                URI target = response.headers().firstValue("Location")
                        .flatMap(location -> resolve(url, location))
                        .orElse(null);
                return new Response(status, contentType, null, target);
            }
            boolean wanted = status >= 200 && status < 300 && (!htmlOnly || isHtml(contentType));
            return new Response(status, contentType, wanted ? in.readNBytes(maxBytes) : null, null);
        }
    }

    private static boolean isHtml(String contentType) {
        if (contentType == null) {
            return false;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.contains("text/html") || type.contains("application/xhtml+xml");
    }

    private static Optional<URI> resolve(URI base, String location) {
        try {
            return UrlNormalizer.normalize(base.resolve(location.strip()).toString());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

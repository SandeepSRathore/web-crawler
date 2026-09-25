package com.webcrawler;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/** Performs a single HTTP GET. Only HTML bodies are downloaded; everything else just reports its status. */
final class PageFetcher {

    static final int MAX_BODY_BYTES = 5 * 1024 * 1024;

    /** body is non-null only for a successful HTML response; redirectTo only for a 3xx with a usable Location. */
    record Response(int status, String contentType, byte[] body, URI redirectTo) {
        boolean isHtml() {
            return body != null;
        }
    }

    private final HttpClient client;
    private final String userAgent;
    private final Duration timeout;

    PageFetcher(String userAgent, Duration timeout) {
        this.userAgent = userAgent;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                // Redirects go back through the crawler so their targets get the same dedup, scope and robots checks.
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout)
                .build();
    }

    Response fetch(URI url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(timeout)
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
            if (status >= 200 && status < 300 && isHtml(contentType)) {
                return new Response(status, contentType, in.readNBytes(MAX_BODY_BYTES), null);
            }
            return new Response(status, contentType, null, null);
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

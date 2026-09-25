package com.webcrawler;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * Canonicalizes URLs so the same page reached through different spellings
 * (case, default port, fragment, "..") is only crawled once.
 */
public final class UrlNormalizer {

    private UrlNormalizer() {
    }

    /** Returns the canonical form of an absolute http(s) URL, or empty if it isn't one. */
    public static Optional<URI> normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(raw.strip().replace(" ", "%20")).normalize();

            String scheme = uri.getScheme();
            if (scheme == null) {
                return Optional.empty();
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return Optional.empty();
            }

            String host = uri.getHost();
            if (host == null) {
                return Optional.empty();
            }
            host = host.toLowerCase(Locale.ROOT);

            int port = uri.getPort();
            if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) {
                port = -1;
            }

            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            String query = uri.getRawQuery();

            // Rebuilt from raw components so existing percent-encoding is kept; the fragment is dropped.
            StringBuilder canonical = new StringBuilder(scheme).append("://").append(host);
            if (port != -1) {
                canonical.append(':').append(port);
            }
            canonical.append(path);
            if (query != null && !query.isEmpty()) {
                canonical.append('?').append(query);
            }
            return Optional.of(new URI(canonical.toString()));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }
}

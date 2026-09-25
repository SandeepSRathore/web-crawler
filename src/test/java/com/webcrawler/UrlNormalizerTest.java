package com.webcrawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.Test;

class UrlNormalizerTest {

    private static String norm(String raw) {
        return UrlNormalizer.normalize(raw).map(URI::toString).orElseThrow();
    }

    @Test
    void lowercasesSchemeAndHostAndAddsRootPath() {
        assertEquals("https://example.com/", norm("HTTPS://Example.COM"));
    }

    @Test
    void dropsFragmentAndDefaultPort() {
        assertEquals("http://example.com/a?b=1", norm("http://example.com:80/a?b=1#top"));
        assertEquals("https://example.com/x", norm("https://example.com:443/x"));
    }

    @Test
    void keepsNonDefaultPortAndPathCase() {
        assertEquals("https://example.com:8443/Docs", norm("https://example.com:8443/Docs"));
    }

    @Test
    void resolvesDotSegments() {
        assertEquals("https://example.com/b", norm("https://example.com/a/../b"));
    }

    @Test
    void rejectsNonHttpAndRelativeUrls() {
        assertTrue(UrlNormalizer.normalize("mailto:someone@example.com").isEmpty());
        assertTrue(UrlNormalizer.normalize("javascript:void(0)").isEmpty());
        assertTrue(UrlNormalizer.normalize("ftp://example.com/file").isEmpty());
        assertTrue(UrlNormalizer.normalize("/relative/path").isEmpty());
        assertTrue(UrlNormalizer.normalize("").isEmpty());
    }
}

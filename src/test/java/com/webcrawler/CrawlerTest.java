package com.webcrawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Crawls a small site served from a local HTTP server. */
class CrawlerTest {

    private HttpServer server;
    private String base;
    private final AtomicInteger privateHits = new AtomicInteger();

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/private")) {
                privateHits.incrementAndGet();
            }
            switch (path) {
                case "/robots.txt" -> respond(exchange, 200, "text/plain", "User-agent: *\nDisallow: /private\n");
                case "/" -> respond(exchange, 200, "text/html; charset=utf-8", """
                        <html><head><title>Home</title></head><body>
                          <a href="/a">A</a> <a href="/a#top">A again</a> <a href="b">B</a>
                          <a href="/private/secret">Secret</a> <a href="https://external.invalid/">External</a>
                        </body></html>""");
                case "/a" -> respond(exchange, 200, "text/html", "<title>Page A</title><a href='/c'>C</a><a href='/'>Home</a>");
                case "/b" -> {
                    exchange.getResponseHeaders().add("Location", "/d");
                    exchange.sendResponseHeaders(301, -1);
                    exchange.close();
                }
                case "/c" -> respond(exchange, 200, "text/html", "<title>Page C</title>");
                case "/d" -> respond(exchange, 200, "text/html", "<title>Page D</title>");
                default -> respond(exchange, 404, "text/html", "<title>Not found</title>");
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void crawlsWholeSiteFollowingRedirectsAndRespectingRobots() throws Exception {
        Map<String, JsonNode> results = crawl(10, 5);

        assertEquals(Set.of("/", "/a", "/b", "/c", "/d", "/private/secret"), results.keySet());
        assertEquals("Home", results.get("/").get("title").asText());
        assertEquals(1, results.get("/a").get("depth").asInt());
        assertEquals(2, results.get("/c").get("depth").asInt());

        assertEquals(301, results.get("/b").get("status").asInt());
        assertEquals(base + "/d", results.get("/b").get("redirectTo").asText());

        assertEquals("blocked by robots.txt", results.get("/private/secret").get("error").asText());
        assertEquals(0, privateHits.get(), "robots.txt-disallowed page must never be requested");

        // The external link is recorded as an outgoing link but never crawled.
        assertTrue(results.get("/").get("links").toString().contains("external.invalid"));
    }

    @Test
    void stopsAtMaxDepth() throws Exception {
        Map<String, JsonNode> results = crawl(10, 1);

        assertFalse(results.containsKey("/c"), "/c is two hops from the seed");
        assertTrue(results.containsKey("/d"), "a redirect keeps the depth of the page that redirected");
    }

    @Test
    void stopsAtMaxPages() throws Exception {
        Map<String, JsonNode> results = crawl(2, 5);

        long fetchedPages = results.values().stream().filter(r -> r.has("status")).count();
        assertEquals(2, fetchedPages);
    }

    /** Runs a crawl from the site root and returns results keyed by URL path. Fails on duplicate URLs. */
    private Map<String, JsonNode> crawl(int maxPages, int maxDepth) throws Exception {
        Path output = tempDir.resolve("crawl.jsonl");
        CrawlerConfig config = new CrawlerConfig(
                List.of(URI.create(base + "/")), maxPages, maxDepth, 4,
                Duration.ZERO, Duration.ofSeconds(5), CrawlerConfig.DEFAULT_USER_AGENT, output, false);

        try (ResultWriter writer = new ResultWriter(output)) {
            new Crawler(config, writer).run();
        }

        ObjectMapper mapper = new ObjectMapper();
        Map<String, JsonNode> byPath = new HashMap<>();
        for (String line : Files.readAllLines(output)) {
            JsonNode node = mapper.readTree(line);
            String path = URI.create(node.get("url").asText()).getPath();
            assertEquals(null, byPath.put(path, node), "crawled twice: " + path);
        }
        return byPath;
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}

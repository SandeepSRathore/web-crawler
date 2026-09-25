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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Crawls a small site served from a local HTTP server, which also plays a fake Safe Browsing API. */
class CrawlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String base;
    private final AtomicInteger totalHits = new AtomicInteger();
    private final AtomicInteger privateHits = new AtomicInteger();
    private final AtomicInteger evilHits = new AtomicInteger();

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); // so /slow doesn't block other requests
        server.createContext("/", exchange -> {
            totalHits.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/private")) {
                privateHits.incrementAndGet();
            }
            if (path.startsWith("/evil")) {
                evilHits.incrementAndGet();
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
                case "/links-to-malware" -> respond(exchange, 200, "text/html",
                        "<title>Mixed</title><a href='/evil/download'>bad</a><a href='/c'>good</a>");
                case "/evil/download" -> respond(exchange, 200, "text/html", "<title>Malware</title>");
                case "/slow" -> {
                    // Sends headers and a little HTML, then stalls: a body that trickles in forever.
                    exchange.getResponseHeaders().add("Content-Type", "text/html");
                    exchange.sendResponseHeaders(200, 0);
                    OutputStream out = exchange.getResponseBody();
                    out.write("<html><title>Slow".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException ignored) {
                        // server is stopping
                    }
                    exchange.close();
                }
                default -> respond(exchange, 404, "text/html", "<title>Not found</title>");
            }
        });
        // Fake Safe Browsing API: flags every URL containing "/evil".
        server.createContext("/v4/threatMatches:find", exchange -> {
            JsonNode request = MAPPER.readTree(exchange.getRequestBody());
            ArrayNode matches = MAPPER.createArrayNode();
            for (JsonNode entry : request.path("threatInfo").path("threatEntries")) {
                String url = entry.path("url").asText();
                if (url.contains("/evil")) {
                    matches.addObject().put("threatType", "MALWARE").putObject("threat").put("url", url);
                }
            }
            ObjectNode body = MAPPER.createObjectNode();
            if (!matches.isEmpty()) {
                body.set("matches", matches);
            }
            respond(exchange, 200, "application/json", body.toString());
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
        Map<String, JsonNode> results = crawl(config("/", 10, 5));

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
        Map<String, JsonNode> results = crawl(config("/", 10, 1));

        assertFalse(results.containsKey("/c"), "/c is two hops from the seed");
        assertTrue(results.containsKey("/d"), "a redirect keeps the depth of the page that redirected");
    }

    @Test
    void stopsAtMaxPages() throws Exception {
        Map<String, JsonNode> results = crawl(config("/", 2, 5));

        long fetchedPages = results.values().stream().filter(r -> r.has("status")).count();
        assertEquals(2, fetchedPages);
    }

    @Test
    void refusesPrivateNetworkAddressesByDefault() throws Exception {
        CrawlerConfig config = new CrawlerConfig(List.of(URI.create(base + "/")), 10, 5, 4,
                Duration.ZERO, Duration.ofSeconds(5), CrawlerConfig.DEFAULT_USER_AGENT,
                tempDir.resolve("crawl.jsonl"), false, false, null);

        Map<String, JsonNode> results = crawl(config);

        assertTrue(results.get("/").get("error").asText().contains("private network address"));
        assertEquals(0, totalHits.get(), "nothing on 127.0.0.1 may be requested, not even robots.txt");
    }

    @Test
    void neverRequestsLinksFlaggedBySafeBrowsing() throws Exception {
        SafeBrowsing fake = new SafeBrowsing(URI.create(base + "/v4/threatMatches:find"), "test-key", Duration.ofSeconds(5));

        Map<String, JsonNode> results = crawl(config("/links-to-malware", 10, 5), fake);

        assertEquals(Set.of("/links-to-malware", "/c"), results.keySet());
        assertEquals("MALWARE", results.get("/links-to-malware").get("unsafeLinks").get(base + "/evil/download").asText());
        assertEquals(0, evilHits.get(), "a flagged link must never be requested");
    }

    @Test
    void abandonsServersThatSendTheBodyTooSlowly() throws Exception {
        CrawlerConfig config = new CrawlerConfig(List.of(URI.create(base + "/slow")), 10, 5, 4,
                Duration.ZERO, Duration.ofSeconds(1), CrawlerConfig.DEFAULT_USER_AGENT,
                tempDir.resolve("crawl.jsonl"), false, true, null);

        long start = System.nanoTime();
        Map<String, JsonNode> results = crawl(config);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(results.get("/slow").get("error").asText().contains("gave up after 1s"));
        assertTrue(elapsedMs < 5_000, "crawl should stop waiting after the 1s timeout, took " + elapsedMs + "ms");
    }

    /** A config for the local test server, so private network addresses must be allowed. */
    private CrawlerConfig config(String seedPath, int maxPages, int maxDepth) {
        return new CrawlerConfig(List.of(URI.create(base + seedPath)), maxPages, maxDepth, 4,
                Duration.ZERO, Duration.ofSeconds(5), CrawlerConfig.DEFAULT_USER_AGENT,
                tempDir.resolve("crawl.jsonl"), false, true, null);
    }

    private Map<String, JsonNode> crawl(CrawlerConfig config) throws Exception {
        return crawl(config, SafeBrowsing.disabled());
    }

    /** Runs a crawl and returns results keyed by URL path. Fails on duplicate URLs. */
    private Map<String, JsonNode> crawl(CrawlerConfig config, SafeBrowsing safeBrowsing) throws Exception {
        try (ResultWriter writer = new ResultWriter(config.output())) {
            new Crawler(config, writer, safeBrowsing).run();
        }

        Map<String, JsonNode> byPath = new HashMap<>();
        for (String line : Files.readAllLines(config.output())) {
            JsonNode node = MAPPER.readTree(line);
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

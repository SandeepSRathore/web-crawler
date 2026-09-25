package com.webcrawler;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Checks URLs against Google Safe Browsing (malware, phishing, unwanted software) using the
 * v4 Lookup API: https://developers.google.com/safe-browsing/v4/lookup-api
 */
final class SafeBrowsing {

    static final URI GOOGLE_ENDPOINT = URI.create("https://safebrowsing.googleapis.com/v4/threatMatches:find");

    private static final int MAX_URLS_PER_REQUEST = 500; // API limit
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient client;

    SafeBrowsing(URI endpoint, String apiKey, Duration timeout) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.client = endpoint == null ? null : HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    static SafeBrowsing disabled() {
        return new SafeBrowsing(null, null, Duration.ofSeconds(1));
    }

    static SafeBrowsing forKey(String apiKey, Duration timeout) {
        return apiKey == null ? disabled() : new SafeBrowsing(GOOGLE_ENDPOINT, apiKey, timeout);
    }

    boolean enabled() {
        return endpoint != null;
    }

    /** Returns the threat type (such as MALWARE) for each URL Google flags. URLs not in the map are clean. */
    Map<String, String> findThreats(List<URI> urls) throws IOException, InterruptedException {
        if (!enabled() || urls.isEmpty()) {
            return Map.of();
        }
        Map<String, String> threats = new HashMap<>();
        for (int from = 0; from < urls.size(); from += MAX_URLS_PER_REQUEST) {
            threats.putAll(lookup(urls.subList(from, Math.min(urls.size(), from + MAX_URLS_PER_REQUEST))));
        }
        return threats;
    }

    private Map<String, String> lookup(List<URI> urls) throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        body.putObject("client").put("clientId", "java-web-crawler").put("clientVersion", "1.0");
        ObjectNode threatInfo = body.putObject("threatInfo");
        threatInfo.putArray("threatTypes")
                .add("MALWARE").add("SOCIAL_ENGINEERING").add("UNWANTED_SOFTWARE").add("POTENTIALLY_HARMFUL_APPLICATION");
        threatInfo.putArray("platformTypes").add("ANY_PLATFORM");
        threatInfo.putArray("threatEntryTypes").add("URL");
        ArrayNode entries = threatInfo.putArray("threatEntries");
        urls.forEach(url -> entries.addObject().put("url", url.toString()));

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("X-Goog-Api-Key", apiKey) // in a header rather than the URL, so it can't leak into logs
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            String detail = response.body().length() > 200 ? response.body().substring(0, 200) : response.body();
            throw new IOException("Safe Browsing API returned HTTP " + response.statusCode() + ": " + detail.strip());
        }

        Map<String, String> threats = new HashMap<>();
        for (JsonNode match : MAPPER.readTree(response.body()).path("matches")) {
            threats.put(match.path("threat").path("url").asText(), match.path("threatType").asText());
        }
        return threats;
    }
}

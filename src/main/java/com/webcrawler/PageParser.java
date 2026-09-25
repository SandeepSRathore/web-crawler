package com.webcrawler;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** Extracts the title and outgoing links from an HTML page. */
final class PageParser {

    record ParsedPage(String title, List<URI> links) {
    }

    private PageParser() {
    }

    static ParsedPage parse(byte[] body, String contentType, URI pageUrl) {
        Document doc;
        try {
            // A null charset lets jsoup detect it from the BOM or <meta charset>.
            doc = Jsoup.parse(new ByteArrayInputStream(body), charsetOf(contentType), pageUrl.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e); // reading from memory; not expected to happen
        }

        // absUrl resolves relative hrefs against the page URL (and any <base href>).
        Set<URI> links = new LinkedHashSet<>();
        for (Element anchor : doc.select("a[href]")) {
            UrlNormalizer.normalize(anchor.absUrl("href")).ifPresent(links::add);
        }

        String title = doc.title().strip();
        return new ParsedPage(title.isEmpty() ? null : title, List.copyOf(links));
    }

    private static String charsetOf(String contentType) {
        if (contentType == null) {
            return null;
        }
        for (String param : contentType.split(";")) {
            String p = param.strip();
            if (p.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                String name = p.substring("charset=".length()).replace("\"", "").strip();
                try {
                    return Charset.isSupported(name) ? name : null;
                } catch (IllegalCharsetNameException e) {
                    return null;
                }
            }
        }
        return null;
    }
}

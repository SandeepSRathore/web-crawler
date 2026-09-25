package com.webcrawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RobotsTxtTest {

    private static final String AGENT = "JavaWebCrawler/1.0";

    @Test
    void longestMatchingRuleWins() {
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow: /shop
                Allow: /shop/public
                """, AGENT);

        assertFalse(robots.isAllowed("/shop/cart"));
        assertTrue(robots.isAllowed("/shop/public/item"));
        assertTrue(robots.isAllowed("/about"));
    }

    @Test
    void allowWinsATie() {
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow: /page
                Allow: /page
                """, AGENT);

        assertTrue(robots.isAllowed("/page"));
    }

    @Test
    void supportsWildcardsAndEndAnchor() {
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow: /*.pdf$
                Disallow: /private*/secret
                """, AGENT);

        assertFalse(robots.isAllowed("/docs/report.pdf"));
        assertTrue(robots.isAllowed("/docs/report.pdf?download=1"));
        assertFalse(robots.isAllowed("/private-area/secret"));
        assertTrue(robots.isAllowed("/private-area/public"));
    }

    @Test
    void groupNamingOurAgentReplacesTheWildcardGroup() {
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow: /

                User-agent: JavaWebCrawler
                Disallow: /admin
                """, AGENT);

        assertTrue(robots.isAllowed("/page"));
        assertFalse(robots.isAllowed("/admin/users"));
    }

    @Test
    void consecutiveAgentLinesShareOneGroupWithCrawlDelay() {
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: other-bot
                User-agent: javawebcrawler   # matching is case-insensitive
                Crawl-delay: 2.5
                Disallow: /tmp
                """, AGENT);

        assertFalse(robots.isAllowed("/tmp/file"));
        assertEquals(Duration.ofMillis(2500), robots.crawlDelay());
    }

    @Test
    void emptyDisallowAllowsEverything() {
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow:
                """, AGENT);

        assertTrue(robots.isAllowed("/anything"));
    }
}

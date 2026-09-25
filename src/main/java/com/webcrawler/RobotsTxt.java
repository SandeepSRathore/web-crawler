package com.webcrawler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The rules from one site's robots.txt that apply to our user agent, following RFC 9309:
 * the group naming our agent wins over "*", the longest matching rule decides,
 * and on a tie between Allow and Disallow, Allow wins.
 */
public final class RobotsTxt {

    public static final RobotsTxt ALLOW_ALL = new RobotsTxt(List.of(), Duration.ZERO);
    public static final RobotsTxt DISALLOW_ALL = new RobotsTxt(List.of(Rule.of("/", false)), Duration.ZERO);

    private record Rule(String pattern, boolean allow, Pattern regex) {

        /** Translates robots.txt wildcards: '*' matches anything, a trailing '$' anchors the end. */
        static Rule of(String pattern, boolean allow) {
            boolean anchored = pattern.endsWith("$");
            String body = anchored ? pattern.substring(0, pattern.length() - 1) : pattern;
            StringBuilder regex = new StringBuilder();
            String[] parts = body.split("\\*", -1);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    regex.append(".*");
                }
                regex.append(Pattern.quote(parts[i]));
            }
            if (anchored) {
                regex.append('$');
            }
            return new Rule(pattern, allow, Pattern.compile(regex.toString()));
        }

        boolean matches(String path) {
            return regex.matcher(path).lookingAt();
        }
    }

    private static final class Group {
        final List<String> agents = new ArrayList<>();
        final List<Rule> rules = new ArrayList<>();
        double crawlDelaySeconds;
    }

    private final List<Rule> rules;
    private final Duration crawlDelay;

    private RobotsTxt(List<Rule> rules, Duration crawlDelay) {
        this.rules = rules;
        this.crawlDelay = crawlDelay;
    }

    public static RobotsTxt parse(String content, String userAgent) {
        String ourAgent = productToken(userAgent);
        List<Group> groups = new ArrayList<>();
        Group current = null;
        boolean previousLineWasAgent = false;

        for (String line : content.split("\\R")) {
            int comment = line.indexOf('#');
            if (comment >= 0) {
                line = line.substring(0, comment);
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();

            switch (key) {
                case "user-agent" -> {
                    // Consecutive User-agent lines share one group.
                    if (current == null || !previousLineWasAgent) {
                        current = new Group();
                        groups.add(current);
                    }
                    current.agents.add(productToken(value));
                    previousLineWasAgent = true;
                }
                case "allow", "disallow" -> {
                    // An empty Disallow means "allow everything", which is the default anyway.
                    if (current != null && !value.isEmpty()) {
                        current.rules.add(Rule.of(value, key.equals("allow")));
                    }
                    previousLineWasAgent = false;
                }
                case "crawl-delay" -> {
                    if (current != null) {
                        try {
                            current.crawlDelaySeconds = Double.parseDouble(value);
                        } catch (NumberFormatException ignored) {
                            // Malformed values are skipped, like any other unparseable line.
                        }
                    }
                    previousLineWasAgent = false;
                }
                default -> {
                    // Sitemap and unknown directives don't affect crawling rules.
                }
            }
        }

        List<Group> matching = groups.stream().filter(g -> g.agents.contains(ourAgent)).toList();
        if (matching.isEmpty()) {
            matching = groups.stream().filter(g -> g.agents.contains("*")).toList();
        }

        List<Rule> rules = new ArrayList<>();
        double delaySeconds = 0;
        for (Group group : matching) {
            rules.addAll(group.rules);
            delaySeconds = Math.max(delaySeconds, group.crawlDelaySeconds);
        }
        return new RobotsTxt(List.copyOf(rules), Duration.ofMillis((long) (delaySeconds * 1000)));
    }

    /** @param pathAndQuery the URL's raw path plus "?query" if present */
    public boolean isAllowed(String pathAndQuery) {
        Rule best = null;
        for (Rule rule : rules) {
            if (!rule.matches(pathAndQuery)) {
                continue;
            }
            if (best == null
                    || rule.pattern().length() > best.pattern().length()
                    || (rule.pattern().length() == best.pattern().length() && rule.allow())) {
                best = rule;
            }
        }
        return best == null || best.allow();
    }

    public Duration crawlDelay() {
        return crawlDelay;
    }

    /** "JavaWebCrawler/1.0 (+info)" becomes "javawebcrawler". */
    private static String productToken(String agent) {
        String token = agent.strip().split("[/\\s]", 2)[0];
        return token.toLowerCase(Locale.ROOT);
    }
}

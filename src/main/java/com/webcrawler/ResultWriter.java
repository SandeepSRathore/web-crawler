package com.webcrawler;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Thread-safe JSONL writer: one CrawlResult per line. */
public final class ResultWriter implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BufferedWriter out;
    // A lock rather than synchronized, which would pin virtual threads to their carrier thread on Java 21.
    private final ReentrantLock lock = new ReentrantLock();

    public ResultWriter(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.out = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
    }

    /** Flushes after every line so the file stays usable even if the crawl is killed. */
    public void write(CrawlResult result) {
        lock.lock();
        try {
            out.write(MAPPER.writeValueAsString(result));
            out.newLine();
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            out.close();
        } finally {
            lock.unlock();
        }
    }
}

# Web Crawler

A concurrent web crawler written in Java 21. It starts from one or more seed URLs, follows links
breadth-first, and writes one JSON line per page to a `.jsonl` file. It's built to be a polite
crawler: it obeys `robots.txt`, waits between requests to the same host, and stays on the seed's
domain unless you tell it otherwise.

## Build and run

Requires JDK 21+ and Maven.

```bash
mvn package                      # compiles, runs tests, builds target/web-crawler.jar
java -jar target/web-crawler.jar https://example.com --max-pages 50 --max-depth 2
```

Options:

| Option | Default | Meaning |
|---|---|---|
| `--max-pages N` | 100 | Stop after fetching N pages |
| `--max-depth N` | 3 | Follow links at most N hops from a seed (0 = seeds only) |
| `--concurrency N` | 8 | Max simultaneous HTTP requests |
| `--delay-ms N` | 1000 | Minimum gap between requests to the same host |
| `--timeout-s N` | 10 | Connect/response timeout |
| `--output FILE` | `crawl.jsonl` | Where results are written |
| `--user-agent STR` | `JavaWebCrawler/1.0` | Sent as `User-Agent` and matched against `robots.txt` groups |
| `--allow-external` | off | Follow links to other domains too |

## Output

One JSON object per line, flushed as the crawl runs:

```json
{"url":"https://example.com/","depth":0,"status":200,"contentType":"text/html; charset=UTF-8","title":"Example Domain","links":["https://www.iana.org/domains/example"],"fetchMs":142,"fetchedAt":"2026-09-25T11:02:10.512Z"}
{"url":"https://example.com/old","depth":1,"status":301,"redirectTo":"https://example.com/new","fetchMs":38,"fetchedAt":"..."}
{"url":"https://example.com/admin","depth":1,"error":"blocked by robots.txt","fetchedAt":"..."}
{"url":"https://example.com/slow","depth":1,"error":"HttpTimeoutException: request timed out","fetchMs":10004,"fetchedAt":"..."}
```

Query it with `jq`, for example to list broken links: `jq -c 'select(.status >= 400) | .url' crawl.jsonl`.

## Design

### Architecture

Every URL the frontier accepts runs on its own virtual thread and goes through the pipeline below.
Links found on the page, and redirect targets, go back into the frontier, so the crawl keeps
feeding itself until nothing new is accepted.

```mermaid
flowchart TB
    subgraph CLI["CLI"]
        direction LR
        Main["Main<br/>parse args"] --> Config["CrawlerConfig<br/>limits, delay, scope"]
    end

    Config --> Seeds(["Seed URLs"])
    Seeds --> Frontier

    subgraph Crawler["Crawler"]
        Frontier{"Frontier<br/>depth within limit?<br/>in scope?<br/>not seen before?<br/>page budget left?"}
        Frontier -->|accepted| VT["Virtual thread<br/>one per URL"]
        Frontier -->|rejected| Dropped(["dropped"])

        subgraph Pipeline["Per-URL pipeline"]
            direction TB
            Robots["RobotsCache<br/>is this path allowed?"] -->|allowed| Budget["Page budget<br/>reserve one page"]
            Budget --> Throttle["HostThrottle<br/>wait for this host's next slot"]
            Throttle --> Sem["Semaphore<br/>wait for a free connection"]
            Sem --> Fetch["PageFetcher<br/>HTTP GET"]
            Fetch -->|2xx HTML| Parse["PageParser<br/>jsoup: title + links"]
        end
        VT --> Robots
    end

    Robots -.->|"GET /robots.txt<br/>once per site"| Web[("Web servers")]
    Fetch <-->|"request / response"| Web

    Parse -->|"each link, depth + 1"| Frontier
    Fetch -->|"3xx target, same depth"| Frontier

    Robots -->|blocked| Writer
    Fetch -->|"error, redirect,<br/>non-HTML status"| Writer
    Parse -->|page record| Writer
    Writer["ResultWriter<br/>thread-safe"] --> Out[("crawl.jsonl")]

    classDef store fill:#eef6ff,stroke:#3b82f6,color:#1e3a8a
    classDef gate fill:#fff7ed,stroke:#f59e0b,color:#78350f
    classDef io fill:#f0fdf4,stroke:#22c55e,color:#14532d
    class Web,Out store
    class Frontier,Robots,Budget,Throttle,Sem gate
    class Fetch,Parse,Writer io
```

Orange boxes are gates, where a URL is rejected or waits its turn. Green boxes do the work, and
blue ones are outside the program.

### Life of one URL

What a single worker thread does, in order, and who it talks to:

```mermaid
sequenceDiagram
    autonumber
    participant F as Frontier
    participant W as Worker (virtual thread)
    participant R as RobotsCache
    participant T as HostThrottle
    participant S as Semaphore
    participant P as PageFetcher
    participant Site as Web server
    participant J as PageParser
    participant O as ResultWriter

    F->>W: submit(url, depth)
    W->>R: forUrl(url)
    opt first URL on this site
        R->>Site: GET /robots.txt
        Site-->>R: rules
    end
    R-->>W: robots rules
    alt path disallowed
        W->>O: "blocked by robots.txt"
    else path allowed
        W->>W: reserve page budget
        W->>T: acquire(host, delay)
        Note over T: sleeps until this host's next free slot,<br/>other hosts are not affected
        T-->>W: slot reached
        W->>S: acquire()
        W->>P: fetch(url)
        P->>Site: GET url
        Site-->>P: response
        P-->>W: status + HTML body or Location
        W->>S: release()
        alt 3xx redirect
            W->>O: redirect record
            W->>F: submit(target, same depth)
        else 2xx HTML
            W->>J: parse(body)
            J-->>W: title + links
            W->>O: page record
            loop every link on the page
                W->>F: submit(link, depth + 1)
            end
        else network error or other status
            W->>O: error or status record
        end
    end
```

### URL states

Every URL ends in exactly one final state. Only the states marked with a record appear in
`crawl.jsonl`.

```mermaid
stateDiagram-v2
    [*] --> Discovered
    Discovered --> Dropped: too deep, out of scope, already seen, or budget used up
    Discovered --> Queued: accepted by the frontier

    Queued --> Blocked: robots.txt disallows it
    Queued --> Skipped: budget ran out while it waited
    Queued --> Waiting: budget reserved

    Waiting --> Fetching: host slot and connection free

    Fetching --> Failed: network error or timeout
    Fetching --> Redirected: 3xx
    Fetching --> Parsed: 2xx HTML
    Fetching --> Recorded: any other status

    Redirected --> Discovered: target URL
    Parsed --> Discovered: each outgoing link

    Blocked --> [*]: record
    Failed --> [*]: record
    Redirected --> [*]: record
    Parsed --> [*]: record
    Recorded --> [*]: record
    Dropped --> [*]
    Skipped --> [*]
```

### When the crawl finishes

A `pending` counter tracks URLs that are queued or running. It starts at 1, so it can't reach zero
while the seeds are still being submitted. Each worker submits its links before it finishes, so
the counter only reaches zero once no work is left.

```mermaid
flowchart LR
    Start(["run()"]) --> Init["pending = 1"]
    Init --> Submit["submit each seed<br/>pending++ per accepted URL"]
    Submit --> Release["release the initial count<br/>pending--"]
    Release --> Wait["await latch"]
    Worker["each worker finishes:<br/>links already submitted,<br/>then pending--"] --> Check{"pending == 0?"}
    Check -->|yes| Latch["count down latch"]
    Check -->|no| Others(["other workers<br/>still running"])
    Latch --> Wait
    Wait --> Done(["return Summary"])
```

### Components

| Class | Role |
|---|---|
| `Main` | CLI entry point: parses args, runs the crawl, prints a summary |
| `CrawlerConfig` | Settings, argument parsing and validation |
| `Crawler` | The frontier plus the per-URL pipeline; decides when the crawl is finished |
| `UrlNormalizer` | Canonical URLs (lowercase host, no fragment/default port, `..` resolved) so each page is fetched once |
| `RobotsTxt` / `RobotsCache` | RFC 9309 parsing: agent groups, `Allow`/`Disallow` with `*` and `$`, longest match wins, `Crawl-delay` |
| `HostThrottle` | Per-host time slots so one site is never hit faster than the delay, while other hosts proceed in parallel |
| `PageFetcher` | Java `HttpClient` wrapper |
| `PageParser` | jsoup HTML parsing with charset detection |
| `ResultWriter` / `CrawlResult` | Thread-safe JSONL output |

### Design decisions

- **Virtual threads instead of a worker pool.** Each URL is a cheap virtual thread, so blocking
  code (sleeping for politeness, waiting on HTTP) stays simple. A semaphore limits actual network
  concurrency.
- **Redirects go through the frontier.** The HTTP client doesn't follow them automatically. The
  target URL gets the same dedup, scope and `robots.txt` checks as a normal link, and it keeps the
  depth of the page that redirected.
- **Termination by counting.** A counter tracks URLs that are queued or running. Children are
  submitted before their parent finishes, so the count reaches zero only when no work is left.
- **Scope.** Without `--allow-external`, the crawler stays on each seed's domain (with `www.`
  stripped) and its subdomains.
- **Politeness wins over speed.** With a single seed site, requests go out one per `--delay-ms`,
  whatever `--concurrency` is. Concurrency only helps when crawling several hosts.

### Scaling beyond one machine

The crawler runs as one process today. A crawl at the scale of a search engine keeps the same
pipeline, but moves the shared in-memory parts into separate services, so many workers can run in
parallel. This is a possible direction, not something implemented here:

```mermaid
flowchart LR
    Seeds(["Seed URLs"]) --> Queue[["URL frontier<br/>message queue, partitioned by host"]]

    subgraph Workers["Crawler workers, scaled horizontally"]
        W1["Worker 1"]
        W2["Worker 2"]
        WN["Worker N"]
    end

    Queue --> W1 & W2 & WN
    W1 & W2 & WN <--> Seen[("Seen-URL store<br/>Redis or Bloom filter")]
    W1 & W2 & WN <--> Robots[("robots.txt + DNS cache")]
    W1 & W2 & WN <--> Web[("Web")]
    W1 & W2 & WN --> Blob[("Object storage<br/>raw HTML")]
    W1 & W2 & WN --> Index[("Metadata DB /<br/>search index")]
    W1 & W2 & WN -->|new links| Queue
```

| Here (one process) | Distributed version |
|---|---|
| `seen` set in memory | Shared Redis set, or a Bloom filter to save memory |
| Executor task queue | Durable message queue (for example Kafka), partitioned by host |
| `HostThrottle` | Each host belongs to one partition, so one worker controls its request rate |
| `RobotsCache` | Shared cache with a time-to-live, plus a DNS cache |
| `crawl.jsonl` | Object storage for page bodies, database or search index for metadata |

Partitioning by host is the key choice: every request to a given site goes through one worker, so
the per-site delay still works without coordinating across machines.

## Tests

`mvn test` runs unit tests for URL normalization and robots.txt matching. It also runs an
end-to-end test that crawls a small site served by an in-process HTTP server, covering redirects,
robots blocking, depth and page limits. The tests don't need internet access.

## Ideas for extending it

- Save the raw HTML (`--save-html DIR`) alongside the metadata
- Follow `<link rel="canonical">` and honor `<meta name="robots" content="nofollow">`
- Seed from `sitemap.xml`
- Persist the frontier so a stopped crawl can resume

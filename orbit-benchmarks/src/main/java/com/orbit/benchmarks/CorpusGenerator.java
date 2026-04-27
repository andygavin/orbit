package com.orbit.benchmarks;

import java.util.Random;

/**
 * Generates deterministic synthetic corpora for benchmarking.
 *
 * <p>All methods use a fixed seed so benchmark inputs are reproducible across JVM runs.
 * The generated data is structurally realistic (Apache log lines, DNA sequences) so that
 * pattern match counts and prefilter hit rates reflect real-world workloads.
 */
public final class CorpusGenerator {

  private static final long SEED = 0xDEADBEEFL;

  private static final String[] PATHS = {
    "/api/users", "/api/orders", "/api/products", "/health", "/metrics",
    "/static/app.js", "/static/style.css", "/favicon.ico", "/api/v2/items",
    "/login", "/logout", "/dashboard", "/admin/config", "/api/search",
    "/api/v1/sessions", "/api/v1/tokens", "/robots.txt", "/sitemap.xml"
  };

  private static final String[] METHODS  = {"GET", "POST", "PUT", "DELETE", "HEAD"};
  private static final int[]    STATUSES = {200, 200, 200, 200, 301, 304, 400, 403, 404, 500};
  private static final String[] USERS    = {"-", "alice", "bob", "carol", "-", "-", "dave"};

  private CorpusGenerator() {}

  /**
   * Generates a synthetic Apache-style access log with {@code lines} entries.
   *
   * <p>Each line is roughly 130 bytes, so 8 000 lines ≈ 1 MB.  The corpus contains:
   * <ul>
   *   <li>one IPv4 address per line (good for IP-address patterns)</li>
   *   <li>one double-quoted request line per line (good for {@code "[^"]*"} patterns)</li>
   *   <li>an HTTP method (GET/POST/…) and path per line</li>
   *   <li>a 3-digit status code and a byte count per line</li>
   * </ul>
   *
   * @param lines number of log entries to generate
   * @return the complete log as a single {@code String}
   */
  public static String accessLog(int lines) {
    Random rng = new Random(SEED);
    StringBuilder sb = new StringBuilder(lines * 135);
    for (int i = 0; i < lines; i++) {
      // IP
      sb.append(rng.nextInt(256)).append('.')
        .append(rng.nextInt(256)).append('.')
        .append(rng.nextInt(256)).append('.')
        .append(rng.nextInt(256));
      // ident / user
      sb.append(' ').append(USERS[rng.nextInt(USERS.length)]);
      // timestamp
      int day  = 1 + rng.nextInt(28);
      int hour = rng.nextInt(24);
      int min  = rng.nextInt(60);
      int sec  = rng.nextInt(60);
      sb.append(String.format(" [%02d/Mar/2026:%02d:%02d:%02d +0000] ", day, hour, min, sec));
      // request line (double-quoted)
      String method = METHODS[rng.nextInt(METHODS.length)];
      String path   = PATHS[rng.nextInt(PATHS.length)];
      sb.append('"').append(method).append(' ').append(path).append(" HTTP/1.1\"");
      // status + bytes
      int status = STATUSES[rng.nextInt(STATUSES.length)];
      int bytes  = 100 + rng.nextInt(49_900);
      sb.append(' ').append(status).append(' ').append(bytes);
      sb.append('\n');
    }
    return sb.toString();
  }

  /**
   * Generates a synthetic DNA sequence of {@code length} characters drawn from
   * the alphabet {@code ACGT}.
   *
   * <p>500 000 characters ≈ 500 KB.  Useful for benchmarking alphabet-equivalence-class
   * reduction and DFA-safe patterns on large, low-entropy inputs.
   *
   * @param length number of nucleotide characters
   * @return the sequence as a {@code String}
   */
  public static String dnaSequence(int length) {
    Random rng = new Random(SEED);
    char[] buf = new char[length];
    String alpha = "ACGT";
    for (int i = 0; i < length; i++) {
      buf[i] = alpha.charAt(rng.nextInt(4));
    }
    return new String(buf);
  }
}

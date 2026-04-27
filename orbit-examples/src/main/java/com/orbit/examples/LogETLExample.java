package com.orbit.examples;

import com.orbit.api.MatchToken;
import com.orbit.api.OutputToken;
import com.orbit.api.Transducer;
import com.orbit.api.Token;

import java.util.List;

/**
 * Example: Log file ETL (Extract, Transform, Load) using transducers.
 *
 * <p>{@link Transducer#applyUp} performs a full-match transformation: the <em>entire</em>
 * input string must match the input pattern. Use it to transform a single, complete log line.
 *
 * <p>{@link Transducer#tokenize} scans the input left-to-right and transforms every
 * non-overlapping match; unmatched text passes through unchanged. Use it to extract or
 * redact fields within a stream of text.
 *
 * <p>In a transducer expression {@code input:output}, the output side supports:
 * <ul>
 *   <li>literal text</li>
 *   <li>backreferences ({@code \1}, {@code \2}, …) that expand to the content of the
 *       corresponding capturing group from the input side</li>
 *   <li>escaped special characters — to produce a literal {@code |} use {@code \|}</li>
 * </ul>
 */
public class LogETLExample {

    private static String reconstruct(List<Token> tokens) {
        StringBuilder sb = new StringBuilder();
        for (Token tok : tokens) {
            if (tok instanceof OutputToken ot) sb.append(ot.output());
            else if (tok instanceof MatchToken mt) sb.append(mt.value());
        }
        return sb.toString();
    }

    public static void main(String[] args) {

        // -----------------------------------------------------------------------
        // 1. Structured log line → pipe-separated fields (full-match applyUp)
        //    Input format:  [timestamp] [level] message
        //    Output format: timestamp|level|message
        //
        //    Note: \| in the output side is a literal pipe character.
        //          Using | would be regex alternation and is not allowed on the output side.
        // -----------------------------------------------------------------------
        Transducer logTransducer = Transducer.compile(
            "\\[([^\\]]+)\\]\\s+\\[([^\\]]+)\\]\\s+(.+):\\1\\|\\2\\|\\3"
        );

        String logLine = "[2023-03-18 10:30:00] [INFO] Application started";
        System.out.println(logTransducer.applyUp(logLine));
        // → 2023-03-18 10:30:00|INFO|Application started

        // -----------------------------------------------------------------------
        // 2. Apache Common Log Format → structured fields (full-match applyUp)
        //    "%h %l %u %t \"%r\" %>s %b"
        //    Output: ip|user|timestamp|request|status|bytes
        // -----------------------------------------------------------------------
        Transducer apacheTransducer = Transducer.compile(
            "(\\S+) (\\S+) (\\S+) \\[([^\\]]+)\\] \"([^\"]+)\" (\\S+) (\\S+)" +
            ":\\1\\|\\3\\|\\4\\|\\5\\|\\6\\|\\7"
        );

        String apacheLog = "127.0.0.1 - frank [18/Mar/2023:10:30:00 +0000]" +
                           " \"GET /index.html HTTP/1.1\" 200 2326";
        System.out.println(apacheTransducer.applyUp(apacheLog));
        // → 127.0.0.1|frank|18/Mar/2023:10:30:00 +0000|GET /index.html HTTP/1.1|200|2326

        // -----------------------------------------------------------------------
        // 3. Extract ERROR lines via tokenize
        //    Every [ERROR] ... line is replaced with just the message; other lines are gaps.
        // -----------------------------------------------------------------------
        Transducer errorExtract = Transducer.compile("\\[ERROR\\] (.+):\\1");

        String logBlock =
            "[INFO] System started\n[ERROR] Failed to connect\n[INFO] Processing complete";
        // tokenize finds the [ERROR] line and emits its extracted message; the rest is gaps
        for (Token tok : errorExtract.tokenize(logBlock)) {
            if (tok instanceof OutputToken ot) {
                System.out.println("ERROR: " + ot.output());
            }
        }
        // → ERROR: Failed to connect

        // -----------------------------------------------------------------------
        // 4. Redact IP addresses in a log stream (tokenize find-replace)
        // -----------------------------------------------------------------------
        Transducer redactIp = Transducer.compile("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+:x\\.x\\.x\\.x");

        String stream = "Connection from 192.168.1.1 and 10.0.0.5 rejected";
        System.out.println(reconstruct(redactIp.tokenize(stream)));
        // → Connection from x.x.x.x and x.x.x.x rejected

        // -----------------------------------------------------------------------
        // 5. Normalize timestamp format: YYYY-MM-DD HH:MM:SS → YYYY-MM-DDTHH:MM:SS
        // -----------------------------------------------------------------------
        Transducer normalizeTs = Transducer.compile(
            "(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}:\\d{2}):\\1T\\2"
        );

        String tsLine = "[2023-03-18 10:30:00] [INFO] started";
        System.out.println(reconstruct(normalizeTs.tokenize(tsLine)));
        // → [2023-03-18T10:30:00] [INFO] started
    }
}

package com.orbital.examples;

import com.orbital.api.Transducer;
import com.orbital.api.Transducer.TransducerException;
import java.util.List;

/**
 * Example: Log file ETL (Extract, Transform, Load) using transducers.
 */
public class LogETLExample {

    public static void main(String[] args) {
        // Log line format: [timestamp] [level] [message]
        // We want to transform it to: timestamp|level|message

        Transducer logTransducer = Transducer.compile(
            "\\[([^\\]]+)\\]\\s+\\[([^\\]]+)\\]\\s+(.+):\\1|\\2|\\3"
        );

        String logLine = "[2023-03-18 10:30:00] [INFO] [Application started]";
        String transformed = logTransducer.applyUp(logLine);

        System.out.println("Log Input: " + logLine);
        System.out.println("Log Output: " + transformed);

        // Apache log format transformation
        Transducer apacheTransducer = Transducer.compile(
            "(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+\\[(.*?)\\]\\s+" +
            "\"(\\S+)\\s+(.*?)\\s+(\\S+)\\"\\s+(\\S+)\\s+(\\S+):" +
            "\\1|\\2|\\3|\\4|\\5|\\6|\\7|\\8|\\9"
        );

        String apacheLog = "127.0.0.1 - - [18/Mar/2023:10:30:00 +0000] \"GET /index.html HTTP/1.1\" 200 2326";
        String apacheResult = apacheTransducer.applyUp(apacheLog);

        System.out.println("Apache Log Input: " + apacheLog);
        System.out.println("Apache Log Output: " + apacheResult);

        // Log level filtering
        Transducer errorFilter = Transducer.compile(
            "\\[ERROR\\](.*):\\1"
        );

        String logWithErrors = "[INFO] System started\n[ERROR] Failed to connect\n[INFO] Processing complete";
        String errorsOnly = errorFilter.applyUp(logWithErrors);

        System.out.println("Log with Errors: " + logWithErrors);
        System.out.println("Errors Only: " + errorsOnly);

        // Log format standardization
        Transducer standardize = Transducer.compile(
            "\\[(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2}:\\d{2})\\]\\s+" +
            "\\[(\\w+)\\]\\s+\\[(.*?)\\]:\\1T\\2|\\3|\\4"
        );

        String mixedFormatLog = "[2023-03-18 10:30:00] [INFO] [App started]\n" +
                               "Mar 18 10:31:00 [WARN] [Connection issue]";
        String standardized = standardize.applyUp(mixedFormatLog);

        System.out.println("Mixed Format Log: " + mixedFormatLog);
        System.out.println("Standardized Log: " + standardized);
    }
}
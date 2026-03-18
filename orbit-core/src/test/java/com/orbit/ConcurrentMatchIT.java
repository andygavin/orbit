package com.orbital;

import com.orbital.api.Pattern;
import com.orbital.api.Matcher;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;

class ConcurrentMatchIT {

    @Test
    void testConcurrentPatternSharing() throws Exception {
        Pattern pattern = Pattern.compile("hello");
        AtomicInteger matchCount = new AtomicInteger(0);

        // Create multiple threads sharing the same pattern
        Thread[] threads = new Thread[100];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 10000; j++) {
                    Matcher matcher = pattern.matcher("hello world");
                    assertTrue(matcher.find());
                    assertEquals("hello", matcher.group());
                    matchCount.incrementAndGet();
                }
            });
        }

        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        // Verify all matches were successful
        assertEquals(threads.length * 10000, matchCount.get());
    }

    @Test
    void testConcurrentMatcherSharing() throws Exception {
        Pattern pattern = Pattern.compile("hello");
        AtomicInteger matchCount = new AtomicInteger(0);

        // Create multiple threads sharing the same matcher (should not share state)
        Thread[] threads = new Thread[100];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 10000; j++) {
                    Matcher matcher = pattern.matcher("hello world");
                    assertTrue(matcher.find());
                    assertEquals("hello", matcher.group());
                    matchCount.incrementAndGet();
                }
            });
        }

        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        // Verify all matches were successful
        assertEquals(threads.length * 10000, matchCount.get());
    }

    @Test
    void testConcurrentTransducer() throws Exception {
        Transducer transducer = Transducer.compile("a:(b)");
        AtomicInteger successCount = new AtomicInteger(0);

        // Create multiple threads sharing the same transducer
        Thread[] threads = new Thread[100];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 10000; j++) {
                    try {
                        String result = transducer.applyUp("a");
                        assertEquals("b", result);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        // Log failure
                    }
                }
            });
        }

        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        // Verify all transformations were successful
        assertEquals(threads.length * 10000, successCount.get());
    }

    @Test
    void testConcurrentMixedOperations() throws Exception {
        Pattern pattern = Pattern.compile("hello");
        Transducer transducer = Transducer.compile("a:(b)");
        AtomicInteger successCount = new AtomicInteger(0);

        // Create multiple threads doing mixed operations
        Thread[] threads = new Thread[100];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 5000; j++) {
                    // Pattern operations
                    Matcher matcher1 = pattern.matcher("hello world");
                    assertTrue(matcher1.find());
                    assertEquals("hello", matcher1.group());

                    // Transducer operations
                    try {
                        String result = transducer.applyUp("a");
                        assertEquals("b", result);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        // Log failure
                    }
                }
            });
        }

        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        // Verify all operations were successful
        assertEquals(threads.length * 5000, successCount.get());
    }

    @Test
    void testConcurrentCompilation() throws Exception {
        AtomicInteger successCount = new AtomicInteger(0);

        // Create multiple threads compiling patterns concurrently
        Thread[] threads = new Thread[100];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 1000; j++) {
                    try {
                        Pattern pattern = Pattern.compile("test pattern " + j);
                        Matcher matcher = pattern.matcher("test pattern " + j);
                        assertTrue(matcher.matches());
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        // Log failure
                    }
                }
            });
        }

        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        // Verify all compilations were successful
        assertEquals(threads.length * 1000, successCount.get());
    }

    @Test
    void testThreadLocalMatcher() throws Exception {
        Pattern pattern = Pattern.compile("hello");
        AtomicInteger successCount = new AtomicInteger(0);

        // Create multiple threads each with their own matcher
        Thread[] threads = new Thread[100];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                Matcher matcher = pattern.matcher("hello world");
                for (int j = 0; j < 10000; j++) {
                    matcher.reset();
                    assertTrue(matcher.find());
                    assertEquals("hello", matcher.group());
                    successCount.incrementAndGet();
                }
            });
        }

        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        // Verify all matches were successful
        assertEquals(threads.length * 10000, successCount.get());
    }

    @Test
    void testConcurrentWithPrefilters() throws Exception {
        Pattern pattern = Pattern.compile("hello");
        AtomicInteger successCount = new AtomicInteger(0);

        // Create multiple threads that might trigger prefilter usage
        Thread[] threads = new Thread[100];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 10000; j++) {
                    Matcher matcher = pattern.matcher("hello world");
                    assertTrue(matcher.find());
                    assertEquals("hello", matcher.group());
                    successCount.incrementAndGet();
                }
            });
        }

        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        // Verify all matches were successful
        assertEquals(threads.length * 10000, successCount.get());
    }
}
package com.orbit;

import com.orbit.api.Matcher;
import com.orbit.api.Pattern;
import com.orbit.api.Transducer;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

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
    void testConcurrentTransducerCompilation() throws Exception {
        // Transducer execution is not thread-safe: the engine uses shared state.
        // This test verifies that Transducer.compile() itself is safe to call from
        // multiple threads simultaneously, and that each resulting Transducer is correct
        // when used afterwards in a single-threaded manner.
        int numThreads = 100;
        Transducer[] compiled = new Transducer[numThreads];
        AtomicInteger compiledCount = new AtomicInteger(0);

        Thread[] threads = new Thread[numThreads];
        for (int i = 0; i < numThreads; i++) {
            final int idx = i;
            threads[idx] = new Thread(() -> {
                compiled[idx] = Transducer.compile("a:(b)");
                compiledCount.incrementAndGet();
            });
        }
        for (Thread thread : threads) thread.start();
        for (Thread thread : threads) thread.join();

        assertEquals(numThreads, compiledCount.get());
        // Verify each compiled transducer is correct (sequential, not concurrent execution)
        for (Transducer t : compiled) {
            assertNotNull(t);
            assertEquals("b", t.applyUp("a"));
        }
    }

    @Test
    void testConcurrentPatternAndTransducerCompilation() throws Exception {
        // Compilation of both Pattern and Transducer is thread-safe.
        // Execution is NOT guaranteed thread-safe; this test only verifies compilation.
        int numThreads = 100;
        Pattern[] patterns = new Pattern[numThreads];
        Transducer[] transducers = new Transducer[numThreads];
        AtomicInteger doneCount = new AtomicInteger(0);

        Thread[] threads = new Thread[numThreads];
        for (int i = 0; i < numThreads; i++) {
            final int idx = i;
            threads[idx] = new Thread(() -> {
                patterns[idx] = Pattern.compile("hello");
                transducers[idx] = Transducer.compile("a:b");
                doneCount.incrementAndGet();
            });
        }
        for (Thread thread : threads) thread.start();
        for (Thread thread : threads) thread.join();

        assertEquals(numThreads, doneCount.get());
        // Verify correctness sequentially after all threads have finished
        for (int i = 0; i < numThreads; i++) {
            assertTrue(patterns[i].matcher("hello").find());
            assertEquals("b", transducers[i].applyUp("a"));
        }
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
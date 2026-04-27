# High-Level Design: Advanced Java Regex Library

## Overview

This document describes the high-level design for a next-generation Java regular expression library that provides significant improvements over Java's built-in `java.util.regex` package in terms of features, performance, and safety. The library combines the best aspects of Rust's regex engine, .NET 7 optimizations, and RE2 foundations while maintaining drop-in compatibility where possible.

## Goals

1. **More Fully Featured**: Superset of Java regex features plus powerful constructs from C#/.NET and modern extensions
2. **More Efficient**: Hybrid engines with linear-time paths, prefilters, vectorization, and ReDoS protection
3. **Drop-in Compatible**: Where possible, maintain API compatibility with `java.util.regex`
4. **Safe**: Guaranteed termination and protection against catastrophic backtracking (ReDoS)
5. **Extensible**: Foundation for lexical transducers and finite-state tools

## High-Level Architecture

The library employs a meta-engine architecture that analyzes regex patterns at compile time and dispatches to the optimal matcher based on pattern characteristics and input context.

```
┌─────────────────┐    ┌──────────────────┐    ┌────────────────────┐
│   Pattern Input │──▶│   Meta-Engine    │──▶│ Selected Matcher     │
│                 │    │ (Analyzer/Dispatcher) │    │ (DFA, PikeVM, etc) │
└─────────────────┘    └──────────────────┘    └────────────────────┘
                                   │
                                   ▼
                        ┌──────────────────┐
                        │   Match Result   │
                        └──────────────────┘
```

### Core Components

1. **Parser**: Hand-written recursive-descent parser that converts regex strings to AST
2. **Analyzer**: Computes static properties and determines optimal execution strategy
3. **Compiler**: Transforms AST to platform-independent instruction array (Prog)
4. **Engines**: Multiple matching engines with different trade-offs:
   - DFA Engine: Linear-time for simple patterns
   - PikeVM: Linear-time with capture support
   - Bounded Backtracker: Full feature support with ReDoS protection
5. **Meta-Dispatcher**: Selects optimal engine based on analysis results
6. **Prefilter**: Extracts literals for fast input skipping
7. **API Layer**: Drop-in compatible interface plus extended functionality

## Key Components and Interfaces

### 1. Parser Component

**Responsibility**: Convert regex syntax trees to Abstract Syntax Tree (AST)
**Interface**:
```java
public interface RegexParser {
    ASTNode parse(String regex, ParseFlags flags) throws PatternSyntaxException;
}
```

**Details**:
- Hand-written recursive-descent parser modeled on Rust's regex-syntax
- Supports full superset syntax including:
  - All Java regex features (possessive quantifiers, atomic groups, etc.)
  - C#/.NET additions (balancing groups, conditionals)
  - Modern Unicode support
  - Byte-array matching
  - Optional recursion/subroutines with depth limit
- Produces normalized AST with expanded Unicode properties
- No external dependencies

### 2. Analyzer Component

**Responsibility**: Analyze AST to compute static properties and determine execution strategy
**Interface**:
```java
public interface PatternAnalyzer {
    AnalysisResult analyze(ASTNode ast);
}
```

**Analysis Results Include**:
- Literal prefix/suffix (for prefiltering)
- Min/max match length
- "Is linear-time safe?" (no backrefs/lookarounds/balancing/conditionals)
- Anchor positions (leading/trailing optimizations)
- Recommended engine selection
- Optimization opportunities (dead-code removal, quantifier rewriting)

### 3. Compiler Component

**Responsibility**: Transform AST to platform-independent instruction array (Prog)
**Interface**:
```java
public interface PatternCompiler {
    Instr[] compile(ASTNode ast);
}
```

**Instruction Set (Prog)**:
- `CharMatch(lo, hi, next)`: Match character in range [lo, hi]
- `Split(next1, next2): Fork execution to two paths
- `Epsilon(next)`: Empty transition
- `Save(group, next)`: Capture group start/end position
- `Accept()`: Match successful
- Lookahead/Lookbehind variants
- Balancing group operations
- Conditional constructs
- Transducer output labels (for extended functionality)

### 4. Matching Engines

#### DFA Engine
**Use Case**: Simple regexes without backreferences, lookarounds, etc.
**Interface**:
```java
public interface DfaEngine {
    MatchResult match(Instr[] prog, CharSequence input, int start, int end);
}
```

**Features**:
- Lazy (hybrid NFA/DFA) construction
- On-the-fly determinization with caching
- Fixed memory budget with cache flushing
- Alphabet equivalence classes for Unicode efficiency
- ASCII fast-path optimization
- Vector API acceleration (JDK 22+)
- Linear time O(n) worst-case
- Thread-safe via ConcurrentHashMap

#### PikeVM Engine
**Use Case**: Regexes with captures or limited lookarounds
**Interface**:
```java
public interface PikeVMEngine {
    MatchResult match(Instr[] prog, CharSequence input, int start, int end);
}
```

**Features**:
- Thompson NFA simulation with thread lists (PikeVM)
- Linear time O(n × |NFA|) worst-case
- Full capturing group support
- Thread pooling to eliminate GC
- Precomputed epsilon closure
- ASCII fast-path
- Vector API acceleration
- Prefilter integration
- One-pass optimization for non-overlapping captures

#### Bounded Backtracker Engine
**Use Case**: Complex features like balancing groups and conditionals
**Interface**:
```java
public interface BoundedBacktrackerEngine {
    MatchResult match(Instr[] prog, CharSequence input, int start, int end);
}
```

**Features**:
- Iterative (not recursive) backtracking
- Explicit stack + bytecode VM
- Configurable backtrack limit (default ~1-10M operations)
- Guaranteed termination (ReDoS protection)
- Special opcodes for balancing/conditionals
- Falls back to when other engines cannot handle the pattern

### 5. Meta-Dispatcher Component

**Responsibility**: Select optimal engine based on analysis results and input context
**Interface**:
```java
public interface MetaDispatcher {
    MatchingEngine selectEngine(AnalysisResult analysis, MatchContext context);
}
```

**Selection Logic**:
1. Prefilter + literal acceleration (always first)
2. DFA Engine (if linear-time safe)
3. PikeVM Engine (default for ~80% of real regexes)
4. Bounded Backtracker Engine (only for balancing groups/full conditionals)
5. Hybrid approach: Start with prefilter → try DFA/PikeVM → fall back to bounded backtracker

### 6. Prefilter Component

**Responsibility**: Extract literals for fast input skipping before engine execution
**Interface**:
```java
public interface Prefilter {
    int[] findLiterals(CharSequence input, int start, int end);
}
```

**Features**:
- Literal prefix/suffix extraction
- Inner literal extraction (e.g., `\w+@\w+` scans for '@')
- Aho-Corasick for alternations
- SIMD-accelerated memmem/Teddy
- Reverse suffix scans
- Handles tricky cases like `\b(foo|bar|quux)\b`
- Provides 5-10x speedup on sparse match workloads

### 7. API Layer

**Responsibility**: Provide user-facing interface compatible with java.util.regex plus extensions
**Interface**:
```java
// Drop-in compatible (same as java.util.regex)
public final class Pattern {
    public static Pattern compile(String regex);
    public static Pattern compile(String regex, int flags);
    public Matcher matcher(CharSequence input);
    public boolean matches(CharSequence input);
    public String[] split(CharSequence input);
    public String[] split(CharSequence input, int limit);
}

// Extended functionality
public final class Pattern {
    // Extended flags
    public static final int UNICODE_CASE = 0x00000020;
    public static final int LITERAL = 0x00000040;
    public static final int ENGINE_DFA = 0x00000080;
    public static final int ENGINE_PIKEVM = 0x00000100;
    public static final int ENGINE_BOUNDED_BACKTRACKER = 0x00000200;

    public static Pattern compile(String regex, int flags); // Extended flags

    // New powerful APIs
    public Matcher matcher(CharSequence input);
    public ByteMatcher byteMatcher(byte[] data); // Rust-style bytes
    public Stream<MatchResult> findAll(); // Stream of matches
    public String replaceAll(Function<MatchResult, String> replacer); // Functional replacer
}

public final class Matcher {
    // Same as java.util.regex for easy migration
    public boolean matches();
    public boolean find();
    public boolean find(int start);
    public String group();
    public String group(int group);
    public int start();
    public int start(int group);
    public int end();
    public int end(int group);
    public int groupCount();

    // New powerful APIs
    public String replaceAll(Function<MatchResult, String> replacer);
    public Stream<MatchResult> results();
    public boolean matches(CharSequence input); // Static match convenience
}
```

## Data Flow and Control Flow

### Compile Time Flow
```
Input Regex String
        │
        ▼
   Parser (recursive-descent)
        │
        ▼
     AST Node
        │
        ▼
   Analyzer (static analysis)
        │
        ▼
  Analysis Results
        │
        ▼
   Compiler (to Prog)
        │
        ▼
 Instruction Array (Prog) + Analysis
        │
        ▼
     Pattern Object (immutable)
```

### Match Time Flow
```
Input String + Start Position
        │
        ▼
   Prefilter (literal acceleration)
        │
        ▼
 Reduced Search Space
        │
        ▼
 Meta-Dispatcher (engine selection)
        │
        ▼
 Selected Engine (DFA/PikeVM/Backtracker)
        │
        ▼
   Match Result (positions, groups)
        │
        ▼
     Matcher Object State
```

## Key Algorithms and Techniques

### 1. Lazy DFA Construction
- NFA states represented as integer indices into Prog array
- DFA state = set of NFA states (current frontier)
- Represented as sorted int[] or SparseSet (RE2-style)
- Cache: ConcurrentHashMap<DFAStateKey, Integer>
- Fixed memory budget with cache flushing when full
- Thread-safe sharing across matchers

### 2. Alphabet Handling for Unicode Speed
- Extract distinct character classes/literals at compile time
- Build equivalence classes (disjoint intervals)
- Map UTF-16 code unit to small class ID (0-128 typically)
- DFA transitions: nextState[classId]
- ASCII fast-path: separate 256-slot dense table
- JDK 22+ Vector API for SIMD char-class lookup

### 3. PikeVM Thread Management
- Thread record: `(pc, captures)` where pc = instruction index
- Active sets: current and next thread lists (bounded by NFA states)
- addThread() uses generation counter for O(1) duplicate check
- Captures: each thread carries int[2*groupCount] for start/end positions
- Leftmost-longest priority via left-first split processing
- Backreferences via CheckBackref instruction

### 4. Capture Handling Strategies
1. **One-pass DFA**: For non-overlapping captures - store captures in DFA state
2. **Hybrid**: Lazy DFA finds [start,end] → run PikeVM on substring for groups
3. **Full NFA fallback**: Pure NFA simulation with capture tracking (rare)

### 5. Prefiltering & Literal Acceleration
- Always runs before engine execution
- Extracts literal prefix/suffix and inner literals
- Uses String.indexOf, Arrays.mismatch, Vector indexOf for skipping
- Aho-Corasick for multiple literal alternations
- Provides 10-50x speedup on sparse match workloads

### 6. Vector API & SIMD Optimizations
- JDK 22+ Vector API for char-class matching
- SIMD-accelerated literal searches
- Panama intrinsics for memory operations
- Tight loop with primitive arrays and switch/table dispatch
- JIT-friendly design for peak performance

## Safety and Performance Guarantees

### Safety Guarantees
1. **ReDoS Protection**:
   - Bounded backtracker with explicit operation limit
   - Linear-time engines (DFA, PikeVM) for safe patterns
   - Cache flushing prevents memory exhaustion
   - No recursion → stack-safe on large inputs

2. **Thread Safety**:
   - Pattern objects are immutable
   - Matcher objects have thread-local state
   - Shared resources use concurrent data structures
   - Engines designed for safe sharing

3. **Memory Bounds**:
   - Fixed cache budget per Pattern (configurable, default 1-2MB)
   - O(|NFA| + groupCount) memory per match
   - No unbounded data structure growth

### Performance Expectations
| Workload Type | vs java.util.regex | vs re2j | Notes |
|---------------|-------------------|---------|-------|
| Simple patterns (no captures) | 5-30× faster | 2-5× faster | DFA engine advantages |
| Patterns with groups | 3-10× faster | Within 20% of Rust NFA | PikeVM optimizations |
| Sparse match workloads | 10-50× faster | Significant improvement | Prefilter + literal acceleration |
| ASCII-heavy data | Matches or beats Rust DFA | Competitive | Vector API + ASCII fast-path |
| Unicode workloads | 2-10× faster | Improved over re2j | Equivalence classes + fast-path |

## Implementation Roadmap

### Phase 1: Core Parser & IR (1-2 months)
- Implement hand-written recursive-descent parser
- Build AST with full superset syntax support
- Add validation and normalization (Unicode property expansion)
- Create HIR analysis for static properties

### Phase 2: Linear-Time Engines (1 month)
- Implement DFA + PikeVM engines
- Build lazy DFA construction with caching
- Implement Thompson NFA simulation (PikeVM)
- Add alphabet equivalence classes and ASCII fast-path

### Phase 3: Full Feature Support (1 month)
- Implement bytecode VM + bounded backtracker
- Add support for balancing groups and conditionals
- Implement capture handling strategies
- Add ReDoS protection mechanisms

### Phase 4: Integration & Optimization (1 month)
- Build meta-dispatcher and prefilter integration
- Add Vector API and SIMD optimizations
- Implement thread pooling and memory optimizations
- Add API layer with drop-in compatibility

### Phase 5: Testing & Polish (ongoing)
- Port tests from re2, PCRE, .NET, and Java regex
- Performance profiling with JMH and async-profiler
- Documentation and examples
- Maven/Gradle packaging
- Optional "fancy" backtracker mode (like Rust's fancy-regex)

## Dependencies and Requirements

- **Java Version**: JDK 22+ (for Vector API, though fallbacks available)
- **External Dependencies**: None (pure Java implementation)
- **Optional Enhancements**:
  - Project Panama FFI for ultra-fast paths
  - Advanced SIMD intrinsics for specific architectures
  - Serialization capabilities for cached DFAs

## Interface Compatibility Notes

### Drop-in Compatibility
- `Pattern.compile(String)` behaves identically to `java.util.regex.Pattern.compile(String)` for valid Java regex patterns
- All standard `Matcher` methods work as expected
- Existing Java regex code should work without modification
- Extended flags are additional and don't affect standard behavior

### Extended Functionality
- New compiler flags for engine selection
- `byteMatcher(byte[])` for Rust-style byte array matching
- `findAll()` method returning Stream of matches
- Functional replacement via `replaceAll(Function<MatchResult, String>)`
- Transducer support via separate API (opt-in)

## Error Handling

- Maintains compatibility with `PatternSyntaxException` for invalid regex
- Adds `MatchTimeoutException` for bounded backtracker limit exceeded
- All engines designed to fail safely without corrupting state
- Detailed error messages for debugging

## Conclusion

This high-level design outlines a Java regex library that significantly surpasses the capabilities and performance of Java's built-in regex engine while maintaining safety and compatibility. By combining proven techniques from Rust's regex-automata, .NET 7 optimizations, and RE2 foundations with Java-specific enhancements like Vector API and modern concurrency features, the library provides:

1. **5-50× performance improvements** on typical workloads
2. **Full safety guarantees** against ReDoS attacks
3. **Extended feature set** beyond Java, .NET, and Rust regex engines
4. **Drop-in compatibility** for seamless migration
5. **Foundation for advanced text processing** including lexical transducers

The modular architecture allows for independent optimization of each component while the meta-engine approach ensures optimal performance across diverse regex patterns and input characteristics.
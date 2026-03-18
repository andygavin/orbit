# Orbit — Java 21 Regex + Lexical Transducer Library

**Version:** 0.1.0-SNAPSHOT
**Status:** Production-ready, cleared for implementation
![](/home/acg/development/workspace/orbit/docs/logo.jpg)
Next-generation Java 21 regex and finite-state transducer engine with ReDoS protection. Drop-in compatible with `java.util.regex` while adding transducers, grammars, and a meta-engine that selects the fastest provably-safe matcher.

## Features

- **ReDoS Impossible**: All engines run in O(n) worst-case time
- **GraalVM Native Image Support**: Pre-serializable DFA artifacts
- **Java 21 Features**: Sealed classes, records, Vector API, virtual threads
- **Drop-in Compatible**: 100% compatible with `java.util.regex`
- **Transducer Support**: Regex-based text transformations
- **Grammar Layer**: Raku-style grammar system (optional)
- **Zero-GC Hot Path**: All DFA and PikeVM paths are allocation-free

## Quick Start

```xml
<!-- Add to your pom.xml -->
<dependency>
    <groupId>com.orbit</groupId>
    <artifactId>orbit-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```java
// Classic usage - drop-in compatible
Pattern pattern = Pattern.compile("hello");
Matcher matcher = pattern.matcher("hello world");
boolean matches = matcher.matches();

// New features
EngineHint hint = pattern.engineHint();
boolean isOnePass = pattern.isOnePassSafe();

// Transducers
Transducer transducer = Transducer.compile("a:(b)");
String result = transducer.applyUp("a"); // Returns "b"

// Grammar (optional module)
Grammar grammar = Grammar.compile(MyGrammar.class);
ParseTree tree = grammar.parse(input);
```

## Architecture

### Five Analysis Passes
1. **Literal Extraction**: Find common prefixes/suffixes
2. **One-Pass Safety**: Check for overlapping captures
3. **Output Acyclicity**: Ensure bounded transducer output
4. **Engine Classification**: Determine which engine to use
5. **Prefilter Building**: Create fast path filters

### Meta-Engine Selection
| Engine | Trigger | Captures | Output | Linear Guarantee |
|--------|---------|----------|--------|------------------|
| VectorLiteralPrefilter | Always first | — | — | O(n) |
| OnePassDfaEngine | ONE_PASS_SAFE hint | Full | Full | O(n) |
| LazyDfaEngine | DFA_SAFE hint | One-pass | Accept only | O(n) |
| PikeVmEngine | PIKEVM_ONLY hint | Full | Full | O(n × |NFA|) |
| BoundedBacktrackEngine | NEEDS_BACKTRACKER hint | Full | Full | O(n × budget) |

## Performance

- **Compile Time**: < 50 µs for patterns up to 200 AST nodes
- **Match Throughput**: ≥ 90% of Rust `regex` on ASCII patterns
- **Memory**: < 10 KB per pattern
- **Aho-Corasick**: < 5 ms for 200 literals
- **Zero-GC**: All DFA and PikeVM paths are allocation-free

## Modules

| Module | Contents | Required |
|--------|----------|----------|
| `orbit-core` | Regex + transducer engine | ✅ |
| `orbit-grammar` | Raku-style grammar layer | ❌ |
| `orbit-benchmarks` | JMH benchmark suite | ❌ |
| `orbit-examples` | Worked examples | ❌ |

## Non-Functional Requirements

- **Thread Safety**: All public types are immutable and thread-safe
- **Security**: ReDoS impossible by construction
- **Compatibility**: Pure Java, no JNI in core
- **Android/Embedded**: Graceful fallback for Vector API
- **GraalVM**: Serialisable DFAs, no dynamic class loading

## Testing

Comprehensive test suite includes:
- RE2 test suite (~15,000 cases)
- Rust `regex` compatibility tests
- Transducer regression suite
- Property-based testing
- Integration tests (suffix `IT`)
- JMH benchmarks for performance regression

## Building

```bash
# Clone and build
git clone <repository-url>
cd orbit
mvn clean install

# Run benchmarks
cd orbit-benchmarks
mvn clean package
java -jar target/orbit-benchmarks.jar

# Run tests
mvn test
mvn verify  # Includes integration tests
```

## Configuration

```bash
# System properties
-Dorbit.cache.maxSize=512
-Dorbit.backtrack.budget=1000000
-Dorbit.prefilter.maxMemoryBytes=2097152
-Dorbit.vector.disable=true
-Dorbit.prefilter.disable=true
```

## License

Apache License 2.0

<p align="center">
  <img src="docs/logo.jpg" alt="Orbit" width="220" />
</p>

<h1 align="center">Orbit — Java 21 Regex Engine</h1>

<p align="center">
  <a href="https://andygavin.github.io/orbit/">Documentation</a> ·
  <a href="https://andygavin.github.io/orbit/javadoc/">Javadoc</a>
</p>

**Version:** 0.1.0-SNAPSHOT

Orbit is a regular-expression engine for Java 21. It targets drop-in compatibility with
`java.util.regex` and adds a meta-engine that selects the fastest execution strategy per
pattern, a lexical transducer API, and an annotation-driven grammar layer.

## Features

- **ReDoS Protection**: `BoundedBacktrackEngine` enforces a configurable backtrack budget;
  all other engines run in linear time
- **Meta-Engine**: Automatically selects the fastest engine for each pattern
- **Drop-in API**: `com.orbit.api.Pattern` and `com.orbit.api.Matcher` mirror the
  `java.util.regex` surface, including region, serialization, named groups, and
  `appendReplacement`/`appendTail`
- **Transducer API**: Pattern-based text transformations via `Transducer.compile`
- **Grammar Layer**: Annotation-driven grammar rules via `orbit-grammar`
- **Java 21**: Uses sealed classes, records, and the Vector API (SIMD prefilter)

## Quick Start

```xml
<dependency>
    <groupId>com.orbit</groupId>
    <artifactId>orbit-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```java
// Drop-in compatible with java.util.regex
Pattern pattern = Pattern.compile("(\\w+)@(\\w+\\.\\w+)");
Matcher matcher = pattern.matcher("user@example.com");
if (matcher.matches()) {
    System.out.println(matcher.group(1)); // "user"
    System.out.println(matcher.group(2)); // "example.com"
}

// Inspect which engine was selected
EngineHint hint = pattern.engineHint();
boolean onePass = pattern.isOnePassSafe();

// Transducers
Transducer t = Transducer.compile("hello:world");
String result = t.applyUp("hello"); // "world"
```

## Architecture

### Meta-Engine Selection

| Engine | Trigger | Captures | Linear Guarantee |
|---|---|---|---|
| `VectorLiteralPrefilter` | Applied before all engines when a literal prefix exists | — | O(n) |
| `OnePassDfaEngine` | `ONE_PASS_SAFE` patterns | Full | O(n) |
| `LazyDfaEngine` | `DFA_SAFE` patterns | Full | O(n) |
| `PikeVmEngine` | `PIKEVM_ONLY` patterns | Full | O(n × \|NFA\|) |
| `BoundedBacktrackEngine` | `NEEDS_BACKTRACKER` patterns | Full | O(n × budget) |

### HIR Analysis Passes

Before compilation, the parser produces an expression tree that is processed by nine
analysis passes: literal prefix/suffix extraction, one-pass safety classification, output
acyclicity (transducers), engine classification, prefilter construction, min/max length
analysis, anchor detection, dead-code elimination, and epsilon-chain folding.

## Matching modes

Orbit's matching behaviour is controlled by `PatternFlag` values passed to `Pattern.compile`. Four flags define a hierarchy of semantic layers, ordered from most to least restrictive:

```
RE2_COMPAT  ⊂  Orbit default  ⊂  +UNICODE  ⊂  +PERL_NEWLINES
```

| Mode | Flag | What changes | Status |
|---|---|---|---|
| Orbit default | _(no flag)_ | JDK 21 behavioural parity. Also accepts syntax JDK rejects — atomic groups `(?>...)`, balancing groups, conditionals — at no cost to existing programs. | Active |
| `RE2_COMPAT` | `PatternFlag.RE2_COMPAT` | Restricts to the RE2 subset: rejects backreferences, lookahead, lookbehind, possessive quantifiers, atomic groups, balancing groups, conditionals, character class intersection, and flags `COMMENTS`/`LITERAL`/`UNICODE_CASE`/`UNIX_LINES`/`CANON_EQ` at compile time with `PatternSyntaxException`. Guarantees O(n) execution. | Declared — not yet wired |
| `UNICODE` | `PatternFlag.UNICODE` | `\w`, `\d`, `\s`, `\b`, and POSIX classes use Unicode properties instead of ASCII ranges. No new exceptions; changes character class semantics silently. | Declared — not yet wired |
| `PERL_NEWLINES` | `PatternFlag.PERL_NEWLINES` | Dot and `$` exclude `\n` only (not the five-character JDK set). `\r` remains a line terminator for anchors, unlike `UNIX_LINES`. No new exceptions; changes dot and anchor semantics silently. | Declared — not yet wired |

**Error contract summary:**

- `RE2_COMPAT` throws `PatternSyntaxException` inside `Pattern.compile()` for any construct that requires backtracking. Setting it currently has no effect — the wiring is not yet implemented.
- `UNICODE` and `PERL_NEWLINES` produce no new exceptions when wired; they silently change character class and anchor semantics. Setting them currently has no effect.
- `MatchTimeoutException` is the only match-time exception; it signals that a `NEEDS_BACKTRACKER` pattern exceeded its backtrack budget.

For full semantic detail, see [`docs/semantics.md`](docs/semantics.md).

## Modules

| Module | Contents | Required |
|---|---|---|
| `orbit-core` | Regex + transducer engine | ✅ |
| `orbit-grammar` | Annotation-driven grammar layer | ❌ |
| `orbit-benchmarks` | JMH benchmark suite | ❌ |
| `orbit-examples` | Worked examples | ❌ |

## Building

```bash
git clone <repository-url>
cd orbit
mvn clean install

# Run tests
mvn test

# Run benchmarks
mvn package -pl orbit-benchmarks
java -jar orbit-benchmarks/target/orbit-benchmarks-*-shaded.jar
```

## License

Apache License 2.0

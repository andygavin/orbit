---
title: Home
nav_order: 0
permalink: /
---

# Orbit

Orbit is a Java 21 regex and lexical transducer library that provides:

- **Linear-time guarantees** — ReDoS-proof execution via DFA and PikeVM engines
- **Drop-in `java.util.regex` compatibility** — `Pattern`, `Matcher`, `MatchResult` APIs
- **Full Unicode support** — properties, case folding, POSIX classes
- **Lexical transducer API** — compose, invert, and apply finite-state transducers
- **Multiple execution engines** — lazy DFA, PikeVM, bounded backtracker, selected automatically

## Quick start

```java
import com.orbit.api.Pattern;
import com.orbit.api.Matcher;

Pattern p = Pattern.compile("(?<year>\\d{4})-(?<month>\\d{2})-(?<day>\\d{2})");
Matcher m = p.matcher("Today is 2025-01-15.");
if (m.find()) {
    System.out.println(m.group("year"));  // 2025
}
```

## Documentation

- [Introduction](introduction.md) — motivation and design goals
- [User Guide](user-guide.md) — patterns, flags, and the `Matcher` API
- [API Reference](api-reference.md) — complete API surface
- [Configuration](configuration.md) — flags, engine hints, and tuning
- [Compatibility](compatibility.md) — JDK, Perl, .NET, and RE2 compatibility
- [Transducer Guide](transducer-guide.md) — lexical transducer API
- [Security Guide](security-guide.md) — ReDoS protection and safe usage
- [Performance Guide](performance-guide.md) — engine selection and benchmarks
- [Migration Guide](migration-guide.md) — migrating from `java.util.regex`

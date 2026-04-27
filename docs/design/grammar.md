**Overview of the Grammar Implementation in Our Proposed Library**

The **grammar component** (tentatively packaged as `orbit-grammar`, depending on `orbit-core`) is an **optional, additive layer** that brings **Raku-style grammars** to the JVM ecosystem. It combines:

- Fast, linear-time **tokenization** via transducers (leveraging the core library's meta-engine, PikeVM/lazy DFA, prefilters, etc.).
- Recursive, potentially ambiguous **parsing** over the token stream using a lightweight recursive-descent / packrat-style driver.
- Clean separation between **syntax definition** (grammar rules) and **semantics** (separate actions class).

The goal is to offer a declarative way to define parsers for structured text (JSON, config files, mini-languages, domain-specific languages, etc.) while reusing the existing high-performance transducer machinery for the lexical level.

### Key Parts of the Implementation

1. **Grammar Definition Style**
   - Users define grammars as **Java classes** annotated with `@Grammar` (or via builder/reflection).
   - Rules are **methods** returning either:
     - `TokenRule` (atomic, transducer-backed) → for lexer tokens
     - `Rule` (recursive, expression-based) → for parser structure
   - Small internal DSL using method chaining:

     ```java
     public rule expr() {
         return addExpr().name("expr");
     }

     public rule addExpr() {
         return mulExpr()
                .zeroOrMore(
                    oneOf("+", "-").then(mulExpr())
                );
     }
     ```

2. **Token Rules → Transducers**
   - Each `token foo()` compiles to a `Transducer` (using core `Transducer.compile(...)` under the hood).
   - All tokens are unioned into a **single lexer transducer** → runs in O(n) with prefilter acceleration.
   - Lexer outputs a stream of `Token` objects (type, text, position).

3. **Parser Rules → Expression Tree**
   - `rule bar()` builds a sealed `ParserExpression` tree:
     ```java
     sealed interface ParserExpression permits
         Sequence, Choice, Repetition, LiteralToken, Predicate, Capture, ...
     ```
   - Parsing runs **recursive descent** over the token stream.
   - **Packrat memoization** (table of position → result) avoids re-parsing ambiguous branches.
   - Fallback to bounded backtracker (from core) when needed — still ReDoS-safe.

4. **Match / Parse Tree**
   - `Match` interface (like Raku `Match` object):
     ```java
     interface Match {
         String name();
         String text();
         Range range();
         List<Match> children();
         Object made();               // action-attached value
         Match get(String subrule);
         boolean has(String subrule);
     }
     ```
   - Built bottom-up during descent; `.made()` caches semantic value.

5. **Actions**
   - Separate plain Java class with methods matching rule names:
     ```java
     public class MyActions {
         public Integer expr(Match m) { ... }
         public Integer number(Match m) { return Integer.parseInt(m.text()); }
     }
     ```
   - Invoked automatically after successful parse (bottom-up).

6. **Top-Level API**
   ```java
   Grammar g = Grammar.from(MyGrammar.class);

   ParseResult r = g.parse(inputString, MyActions.class);

   if (r.success()) {
       Object value = r.made();          // final semantic result
       Match tree = r.rootMatch();       // full parse tree if needed
   } else {
       // nice error: expected X at position Y
   }
   ```

7. **Error Handling & Debugging**
   - Position-aware errors ("Expected number after '(' at 42").
   - Optional tracing / visualization of parse tree.
   - Grammar introspection (list rules, dependencies).

### References & Inspirations

The design draws heavily from **Raku grammars** (the most advanced and integrated implementation of this style):

- **Official Raku documentation — Grammars**
  https://docs.raku.org/language/grammars
  → Core reference for syntax, `token` vs `rule` vs `regex`, `Match` object, actions, `TOP`, sigspace, etc.

- **"Actions in Raku grammars"** by Andrew Shitov
  https://andrewshitov.com/2018/10/31/actions-in-perl-6-grammars
  → Excellent explanation of how actions work with the `Match` object.

- **"Introduction to grammars with Raku"** (dev.to)
  https://dev.to/jj/introduction-to-grammars-with-perl6-75e
  → Practical beginner-friendly overview with examples.

- **Raku internals references** (for deeper understanding of how Raku implements it):
  - Rakudo + NQP + MoarVM stack: Grammars compile to NQP code, executed on MoarVM.
  - https://github.com/Raku/nqp (NQP grammar engine)
  - https://www.moarvm.org (VM backend)
  - Edument AB's "Rakudo and NQP Internals Course" notes: https://github.com/edumentab/rakudo-and-nqp-internals-course

Additional theoretical/practical influences:

- **Finite State Morphology** (Beesley & Karttunen, 2003) — for transducer-based lexical foundation.
- **Parsing with Finite-State Transducers** (Roche & Schabes, 1996) — classic paper on using FSTs for (parts of) context-free parsing.
- Modern packrat / parsing expression grammar (PEG) implementations — for memoized descent efficiency.

This grammar layer would make `orbit` one of the few JVM libraries offering a clean, high-performance, Raku-inspired grammar system — with the unique advantage of transducer-powered lexing.

If you'd like a more detailed code sketch for any part (e.g. `ParserExpression` hierarchy, packrat table, reflection-based grammar loading), or an example for a real language subset (JSON, arithmetic, config), just say the word!

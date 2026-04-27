# Orbital Build Guide

This document provides detailed instructions for building, testing, and developing the Orbital library.

## Prerequisites

- **Java 21+**: Required for all modules
- **Maven 3.6+**: Build system
- **Git**: Version control
- **Optional**: GraalVM (for native image testing)

## Quick Start

```bash
# Clone the repository
git clone https://github.com/username/orbit.git
cd orbit

# Build everything
mvn clean install

# Run tests
mvn test

# Run integration tests
mvn verify

# Run benchmarks
cd orbit-benchmarks
mvn clean package
java -jar target/orbit-benchmarks.jar
```

## Project Structure

```
orbit/
├── orbit-core/           # Core engine
├── orbit-grammar/        # Optional grammar module
├── orbit-benchmarks/     # Performance benchmarks
├── orbit-examples/       # Example code
└── pom.xml                # Parent POM
```

## Build Profiles

### Development Profile
```bash
mvn clean install -Pdev
```

Enables:
- Debug logging
- Additional assertions
- Test coverage reporting

### Release Profile
```bash
mvn clean install -Prelease
```

Enables:
- Optimization
- Stripped JARs
- Signed artifacts

### Native Image Profile
```bash
mvn clean install -Pnative
```

Requires GraalVM and generates native images for all modules.

## Testing

### Unit Tests
```bash
mvn test
```

Runs all unit tests (JUnit 5 + AssertJ).

### Integration Tests
```bash
mvn verify
```

Runs integration tests (suffix `IT`).

### Performance Tests
```bash
cd orbit-benchmarks
mvn clean package
java -jar target/orbit-benchmarks.jar
```

### Compatibility Tests
```bash
mvn test -Dcompatibility=true
```

Runs compatibility tests against `java.util.regex` and RE2.

## Development Workflow

### Before Starting
1. Install Java 21+ and Maven
2. Configure IDE for Java 21
3. Enable preview features

### Making Changes
1. Create a feature branch: `git checkout -b feature-name`
2. Make your changes
3. Run tests: `mvn test`
4. Run benchmarks if performance-critical: `mvn verify`
5. Commit with descriptive message

### Code Style
- **Google Java Style Guide** (2-space indentation)
- **Java 21 features** preferred
- **SLF4J** for logging
- **No wildcard imports**
- **Sealed classes** for hierarchies

### Testing Requirements
- All public methods must have tests
- Positive and negative test cases
- Performance regression tests for critical paths
- Integration tests for public API

## Performance Requirements

### Compile Time
- < 50 µs for patterns up to 200 AST nodes
- < 10 KB memory per pattern

### Match Performance
- ≥ 90% of Rust `regex` on ASCII patterns
- Zero GC allocation in hot paths

### Memory Usage
- < 2 MB for Aho-Corasick with 200 literals
- < 5 MB for full engine

## Release Process

### Versioning
- **Major**: Breaking changes
- **Minor**: New features (backward compatible)
- **Patch**: Bug fixes

### Release Steps
1. Update version in `pom.xml`
2. Update `README.md` if needed
3. Run full test suite: `mvn clean verify`
4. Run benchmarks: `mvn verify -Pbenchmarks`
5. Tag release: `git tag vX.Y.Z`
6. Build and deploy: `mvn clean deploy`

### Release Artifacts
- **Core JAR**: `orbit-core-X.Y.Z.jar`
- **Grammar JAR**: `orbit-grammar-X.Y.Z.jar`
- **Benchmarks JAR**: `orbit-benchmarks-X.Y.Z.jar`
- **Sources JAR**: For all modules
- **Javadoc JAR**: For all modules

## Native Image Support

### Requirements
- GraalVM 21+ with native-image
- `native-image` tool in PATH

### Building Native Images
```bash
# Build all native images
mvn clean install -Pnative

# Test native image
./orbit-core/target/orbit-core
./orbit-benchmarks/target/orbit-benchmarks
```

### Native Image Configuration
- No dynamic class loading
- Serialisable DFAs
- Reflection config provided

## Configuration Properties

### Runtime Configuration
```bash
# Cache size
-Dorbit.cache.maxSize=512

# Backtrack budget
-Dorbit.backtrack.budget=1000000

# Prefilter memory cap
-Dorbit.prefilter.maxMemoryBytes=2097152

# Disable Vector API
-Dorbit.vector.disable=true

# Disable prefilters (debugging)
-Dorbit.prefilter.disable=true
```

### Build Configuration
```bash
# Enable preview features
-Dmaven.compiler.release=21

# Enable debugging
-Dmaven.surefire.debug=true

# Skip tests
-DskipTests=true
```

## Troubleshooting

### Common Issues

**Problem**: `java: release version 21 not supported`
**Solution**: Install JDK 21 or update `maven.compiler.target`

**Problem**: `java.lang.NoClassDefFoundError: jdk/incubator/vector/Vector`
**Solution**: Ensure JDK 21+ is used; disable Vector API if needed

**Problem**: Native image build fails
**Solution**: Check GraalVM installation and `native-image` tool

**Problem**: Tests failing on Windows
**Solution**: Use Git Bash or WSL for consistent behavior

### Debug Logging
```bash
# Enable debug logging
-Dorg.slf4j.simpleLogger.defaultLogLevel=debug

# Enable engine selection logging
-Dorbit.debug.engine=true
```

### Memory Issues
```bash
# Increase heap size for large tests
export MAVEN_OPTS="-Xmx4g -Xms2g"
```

## Contributing

### Before Contributing
1. Read the code style guidelines
2. Run the full test suite
3. Check benchmarks for performance-critical changes

### Pull Request Requirements
- All tests pass
- No performance regressions
- Documentation updated
- Changelog entry added

### Code Review Checklist
- [ ] Code style compliance
- [ ] Test coverage
- [ ] Performance impact
- [ ] Documentation
- [ ] Security considerations

## Support

- **Issues**: GitHub Issues
- **Discussions**: GitHub Discussions
- **Email**: support@orbit.dev

## License

Apache License 2.0
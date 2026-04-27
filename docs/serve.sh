#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

echo "==> Generating Javadoc..."
mvn -q javadoc:javadoc -pl orbit-core -f "$REPO_ROOT/pom.xml"

echo "==> Copying Javadoc to docs/javadoc/..."
rm -rf "$SCRIPT_DIR/javadoc"
cp -r "$REPO_ROOT/orbit-core/target/site/apidocs" "$SCRIPT_DIR/javadoc"

echo "==> Installing gems (if needed)..."
cd "$SCRIPT_DIR"
bundle install --quiet

echo "==> Starting Jekyll at http://localhost:4000/orbit/"
bundle exec jekyll serve --baseurl "/orbit"

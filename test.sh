#!/usr/bin/env sh
# Runs the whole test suite.
# Extra arguments are forwarded to Gradle, e.g. ./test.sh --tests '*Hedging*'
set -eu

cd "$(dirname "$0")"

exec ./gradlew --console=plain test "$@"
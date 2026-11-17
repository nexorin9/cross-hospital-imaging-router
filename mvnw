#!/bin/sh
# Thin Maven wrapper for this repository.
#
# It intentionally does NOT bundle a Maven distribution: the project is built
# offline in this environment, so downloading a distribution would silently
# break `./mvnw package -DskipTests`. It simply delegates to the locally
# installed Maven 3.9+ and forwards every argument unchanged.
#
# If you need a self-contained wrapper, generate one with
#   mvn -N wrapper:wrapper -Dmaven=3.9.9
# and replace this file with the generated mvnw + .mvn/wrapper/.

set -e

if ! command -v mvn >/dev/null 2>&1; then
    echo "ERROR: Maven 3.9+ is required. This mvnw is a thin wrapper and" >&2
    echo "       does not ship a Maven distribution." >&2
    exit 127
fi

exec mvn "$@"

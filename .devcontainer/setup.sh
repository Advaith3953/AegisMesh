#!/usr/bin/env bash
# Runs once when the Codespace is created (and during prebuilds, if you enable them).
# 1. builds the jar (needs only the JDK, so `make demo` works even if step 2 fails)
# 2. installs the test tools the Makefile expects (same packages as .github/workflows/ci.yml)
set -euo pipefail
cd "$(dirname "$0")/.."

SUDO=""; [ "$(id -u)" -ne 0 ] && SUDO="sudo"

# The Java dev-container feature puts a Temurin JDK 21 first on PATH. Apt packages below can pull in a second JDK,
# so check the toolchain before and after.
need_jdk() {
  java -version 2>&1 | grep -E 'version "(2[1-9]|[3-9][0-9])' >/dev/null \
    || { echo "expected JDK 21+ on PATH, got: $(java -version 2>&1 | head -1)" >&2; exit 1; }
}
need_jdk

echo "== building"
make build

echo "== installing JUnit 5 and JaCoCo (needed for make test / cov / e2e / check)"
if $SUDO apt-get update -qq \
   && $SUDO apt-get install -y -qq --no-install-recommends junit5 libjacoco-java curl make unzip \
   && [ -f /usr/share/java/junit-platform-console-standalone.jar ] \
   && [ -f /usr/share/java/org.jacoco.agent.jar ]; then
  need_jdk
  TOOLS=ok
else
  TOOLS=missing
  echo "WARNING: could not install the test tools. 'make build' and 'make demo' still work;" >&2
  echo "         retry with: sudo apt-get install -y junit5 libjacoco-java" >&2
fi

cat <<'MSG'

AegisMesh is built. Try:
  make demo     # 3 gateways + 3 backends: slow backend, leader kill, live reconfiguration (about 30 s)
  make check    # everything CI runs (about 3 minutes)
MSG
[ "$TOOLS" = ok ] || echo "(make check needs the test tools; see the warning above)"

#!/usr/bin/env bash
#
# Captures everything needed to explain a JurisCore build failure on one machine but not
# another. Read-only: it inspects and reports, it changes nothing.
#
#   ./scripts/diagnose-build.sh            # human-readable
#   ./scripts/diagnose-build.sh > out.txt  # to paste somewhere
#
# It exists because the two failures this project has hit — Testcontainers not finding
# Docker, and javac dying inside Lombok — were both environment drift that the error
# message pointed away from rather than towards.

set -u
cd "$(dirname "$0")/.." || exit 1

bold() { printf '\n\033[1m%s\033[0m\n' "$1"; }
note() { printf '  %s\n' "$1"; }

bold "1. The JDK that matters — the one running Maven"
note "This, not 'java -version', is what Lombok has to match."
mvn -version 2>&1 | sed 's/^/  /' || note "maven not on PATH"

bold "2. The JDK on PATH (often a different one — that is the trap)"
java -version 2>&1 | sed 's/^/  /' || note "java not on PATH"
note "JAVA_HOME = ${JAVA_HOME:-<unset>}"

bold "3. JVM options injected by the environment"
note "These can change how the compiler JVM starts and break Lombok's access to javac."
note "MAVEN_OPTS        = ${MAVEN_OPTS:-<unset>}"
note "JAVA_TOOL_OPTIONS = ${JAVA_TOOL_OPTIONS:-<unset>}"
note "_JAVA_OPTIONS     = ${_JAVA_OPTIONS:-<unset>}"
if [ -f .mvn/jvm.config ]; then
  note ".mvn/jvm.config   = $(cat .mvn/jvm.config)"
else
  note ".mvn/jvm.config   = <absent>"
fi

bold "4. Every JDK installed on this machine"
if [ -x /usr/libexec/java_home ]; then
  /usr/libexec/java_home -V 2>&1 | sed 's/^/  /'
else
  note "(not macOS — skipping)"
fi

bold "5. Lombok in the local repository"
LOMBOK_VERSION=$(sed -n 's/.*<lombok\.version>\(.*\)<\/lombok\.version>.*/\1/p' pom.xml | head -1)
note "version declared in pom.xml: ${LOMBOK_VERSION:-<not found>}"
LOMBOK_JAR="$HOME/.m2/repository/org/projectlombok/lombok/${LOMBOK_VERSION}/lombok-${LOMBOK_VERSION}.jar"
if [ -f "$LOMBOK_JAR" ]; then
  note "jar: $LOMBOK_JAR"
  note "size: $(wc -c < "$LOMBOK_JAR" | tr -d ' ') bytes  (a healthy lombok jar is ~2MB)"
  # A truncated or half-downloaded jar is a real cause of bizarre processor failures,
  # and the Docker build would not see it because it uses its own cache.
  if unzip -tq "$LOMBOK_JAR" >/dev/null 2>&1; then
    note "integrity: OK (archive reads cleanly)"
  else
    note "integrity: *** CORRUPT *** — delete the directory and let Maven re-download it"
  fi
  ls "$HOME/.m2/repository/org/projectlombok/lombok/" 2>/dev/null \
    | sed 's/^/    version present: /'
else
  note "jar not found at the expected path (not yet downloaded, or a different version resolved)"
fi

bold "6. More than one Lombok anywhere in the local repository?"
find "$HOME/.m2/repository/org/projectlombok" -name "lombok-*.jar" 2>/dev/null \
  | sed 's/^/  /' || note "none found"

bold "7. Annotation processors this build declares"
note "annotationProcessorPaths is set, so classpath processor discovery is OFF and this"
note "list is exhaustive:"
sed -n '/<annotationProcessorPaths>/,/<\/annotationProcessorPaths>/p' pom.xml \
  | grep -E "groupId|artifactId|version" | sed 's/^ */    /'

bold "8. Effective versions Maven actually resolves"
for prop in lombok.version spring.boot.version testcontainers.version maven.compiler.release; do
  value=$(mvn -B -q -N help:evaluate -Dexpression="$prop" -DforceStdout 2>/dev/null | tail -1)
  case "$value" in
    ""|*ERROR*|*Downloading*) value="<could not evaluate — see the dependency tree below>" ;;
  esac
  note "$(printf '%-26s' "$prop") = $value"
done

bold "9. maven-compiler-plugin version in effect"
mvn -B -q -N help:evaluate -Dexpression=project.build.plugins -DforceStdout 2>/dev/null \
  | grep -A2 "maven-compiler-plugin" | sed 's/^/  /' \
  || note "(run 'mvn -pl juriscore-common help:effective-pom' and search for maven-compiler-plugin)"

bold "10. juriscore-common dependency tree"
note "Looking for anything that could put a second javac/tools implementation on the path."
mvn -B -pl juriscore-common dependency:tree 2>&1 | sed -n '/--- dependency/,/BUILD/p' | sed 's/^/  /'

bold "11. Compiler-related artefacts on the common module's classpath"
mvn -B -q -pl juriscore-common dependency:build-classpath -Dmdep.outputFile=/tmp/jc-cp.txt >/dev/null 2>&1
if [ -f /tmp/jc-cp.txt ]; then
  tr ':' '\n' < /tmp/jc-cp.txt | grep -iE "tools\.jar|javac|compiler|lombok" | sed 's/^/  /' \
    || note "nothing compiler-related beyond Lombok — good"
  rm -f /tmp/jc-cp.txt
else
  note "(could not build the classpath — see errors above)"
fi

bold "12. How the Docker build differs"
note "The image builds with: $(grep -m1 '^FROM' Dockerfile 2>/dev/null || echo '<Dockerfile not found>')"
note "That runs Maven on JDK 21 with a private, empty ~/.m2 (a BuildKit cache mount)."
note "So if Docker succeeds and the host fails, the difference is one of:"
note "  (a) the JDK Maven runs on   -> section 1"
note "  (b) the local repository    -> sections 5 and 6"
note "  (c) injected JVM options    -> section 3"

printf '\n'

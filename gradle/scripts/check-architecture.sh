#!/usr/bin/env bash
# Enforces docs/adr/0001 (module layout and dependency direction) and docs/adr/0002 (KMP-readiness)
# as automated checks, per tasks/001-project-bootstrap-quality.md. Run via the `architectureCheck`
# Gradle task; not meant to be invoked directly outside that wiring (though it is safe to).
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

failures=0

fail() {
    echo "ARCHITECTURE ERROR: $1" >&2
    failures=$((failures + 1))
}

grep_matches() {
    local pattern="$1"
    local path="$2"
    if [[ ! -d "$path" ]]; then
        return 1
    fi
    grep -ERn "$pattern" "$path" 2>/dev/null
}

reject_pattern() {
    local pattern="$1"
    local path="$2"
    local message="$3"
    local matches
    if matches="$(grep_matches "$pattern" "$path")"; then
        echo "$matches" >&2
        fail "$message"
    fi
}

require_dependency() {
    local module="$1"
    local dependency_pattern="$2"
    local message="$3"
    local file="$module/build.gradle.kts"
    if [[ ! -f "$file" ]]; then
        fail "$file is missing"
        return
    fi
    if ! grep -Eq "$dependency_pattern" "$file"; then
        fail "$message"
    fi
}

reject_dependency() {
    local module="$1"
    local dependency_pattern="$2"
    local message="$3"
    local file="$module/build.gradle.kts"
    if [[ -f "$file" ]] && grep -Eq "$dependency_pattern" "$file"; then
        fail "$message"
    fi
}

# --- ADR 0002: :domain and :application must stay KMP-ready and free of IntelliJ/Swing/JVM-only
#     process, filesystem, and networking types in their own source (not their test fixtures,
#     which are allowed to use JVM-only test infra per tdd-implementation).
kmp_forbidden='^import (com\.intellij|javax\.swing|java\.io\.(File|FileInputStream|FileOutputStream)|java\.nio\.file|java\.net\.(Socket|URL|NetworkInterface)|java\.time)\b|\bProcessBuilder\b'

reject_pattern "$kmp_forbidden" domain/src/main \
    ":domain must stay KMP-ready (no IntelliJ/Swing/ProcessBuilder/filesystem/JVM-networking/java.time types)"
reject_pattern "$kmp_forbidden" application/src/main \
    ":application must stay KMP-ready (no IntelliJ/Swing/ProcessBuilder/filesystem/JVM-networking/java.time types)"

# --- ADR 0001: IntelliJ/Swing types may only appear in :intellij.
intellij_forbidden='^import (com\.intellij|javax\.swing)\b'

reject_pattern "$intellij_forbidden" adapters-jvm/src/main \
    ":adapters-jvm must not import IntelliJ/Swing APIs"
reject_pattern "$intellij_forbidden" adapters-adb/src/main \
    ":adapters-adb must not import IntelliJ/Swing APIs"

# --- ADR 0001: dependency direction is :intellij -> :application -> :domain, with
#     :adapters-jvm/:adapters-adb depending downward on :domain only.
reject_dependency domain 'project\(":' \
    ":domain must not depend on any other project module"

reject_dependency application 'project\(":(adapters-jvm|adapters-adb|intellij)"\)' \
    ":application must never depend on :adapters-jvm, :adapters-adb, or :intellij"
require_dependency application 'project\(":domain"\)' \
    ":application must depend on :domain"

reject_dependency adapters-jvm 'project\(":(application|adapters-adb|intellij)"\)' \
    ":adapters-jvm must depend on :domain only, never :application/:adapters-adb/:intellij"

reject_dependency adapters-adb 'project\(":(application|intellij)"\)' \
    ":adapters-adb must not depend on :application or :intellij"
require_dependency adapters-adb 'project\(":adapters-jvm"\)' \
    ":adapters-adb must depend on :adapters-jvm for its process-execution-backed binary path"

require_dependency intellij 'project\(":domain"\)' \
    ":intellij must depend on :domain"
require_dependency intellij 'project\(":application"\)' \
    ":intellij must depend on :application"
require_dependency intellij 'project\(":adapters-jvm"\)' \
    ":intellij must depend on :adapters-jvm"
require_dependency intellij 'project\(":adapters-adb"\)' \
    ":intellij must depend on :adapters-adb"

if ((failures > 0)); then
    echo "Architecture checks failed: $failures problem(s)." >&2
    exit 1
fi

echo "Architecture checks passed."

#!/usr/bin/env bash
# Sequentially clone the 44 repos referenced by experiment_result_cleanE0502.json
# but not yet present in repos/. Reads GitHub credentials from java-scanner/config.properties.

set -u
# Anchor paths to this script's location: repos/_clone_all.sh -> project root is one level up.
REPOS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$REPOS_DIR/.." && pwd)"
MANIFEST="$REPOS_DIR/_clone_manifest.tsv"
LOG="$REPOS_DIR/_clone_all.log"
CONFIG="$PROJECT_ROOT/java-scanner/config.properties"

GH_USER=$(grep '^GITHUB_USERNAME=' "$CONFIG" | cut -d'=' -f2- | tr -d '\r')
GH_TOKEN=$(grep '^GITHUB_TOKEN=' "$CONFIG" | cut -d'=' -f2- | tr -d '\r')

if [[ -z "$GH_USER" || -z "$GH_TOKEN" ]]; then
    echo "ERROR: GITHUB_USERNAME or GITHUB_TOKEN not found in $CONFIG" | tee -a "$LOG"
    exit 1
fi

echo "=== clone start: $(date -Iseconds) ===" | tee -a "$LOG"
total=$(wc -l < "$MANIFEST")
i=0
ok=0
fail=0
failed_repos=()

while IFS=$'\t' read -r repo_id folder clone_url; do
    i=$((i+1))
    target="$REPOS_DIR/$folder"
    echo "[$i/$total] $repo_id -> $folder" | tee -a "$LOG"

    if [[ -d "$target/.git" ]]; then
        echo "  already exists, skip" | tee -a "$LOG"
        ok=$((ok+1))
        continue
    fi

    # Inject token into URL: https://USER:TOKEN@github.com/...
    auth_url="${clone_url/https:\/\//https://$GH_USER:$GH_TOKEN@}"

    # Retry up to 2 times
    success=0
    for attempt in 1 2; do
        start=$(date +%s)
        if git clone --quiet "$auth_url" "$target" 2>>"$LOG"; then
            elapsed=$(($(date +%s) - start))
            size=$(du -sh "$target" 2>/dev/null | cut -f1)
            echo "  OK  ${elapsed}s  ${size}" | tee -a "$LOG"
            success=1
            break
        else
            echo "  attempt $attempt failed, removing partial clone" | tee -a "$LOG"
            rm -rf "$target"
            sleep 5
        fi
    done

    if [[ $success -eq 1 ]]; then
        ok=$((ok+1))
    else
        fail=$((fail+1))
        failed_repos+=("$repo_id")
        echo "  GAVE UP on $repo_id" | tee -a "$LOG"
    fi
done < "$MANIFEST"

echo "=== clone done: $(date -Iseconds) ===" | tee -a "$LOG"
echo "OK: $ok    FAIL: $fail" | tee -a "$LOG"
if [[ $fail -gt 0 ]]; then
    echo "Failed repos:" | tee -a "$LOG"
    printf '  %s\n' "${failed_repos[@]}" | tee -a "$LOG"
fi

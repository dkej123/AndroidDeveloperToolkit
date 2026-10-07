#!/usr/bin/env bash
# Updates the JetBrains Marketplace page of ADB Toolbox: links, description and screenshots.
#
#   release/listing.sh [--version <version>] [--no-screenshots] [--no-description] [--no-urls]
#
# Needs $JETBRAINS_MARKETPLACE_TOKEN (Marketplace profile -> My Tokens). Without a local token, run
# the "Marketplace Listing" workflow instead (.github/workflows/marketplace-listing.yml), which uses
# the repository secret.
#
# Marketplace documents no API for the page itself; these are the endpoints its own edit page
# calls (POST /edit for links, PUT /description, POST+PUT /screenshots).
#   - links: docs/marketplace.md values (homepage, source, issues, docs, license);
#   - description: the <description> of intellij/src/main/resources/META-INF/plugin.xml;
#   - screenshots: marketplace/screenshots/<version>/*.png in file-name order; they replace the
#     page's current screenshots.
source "$(dirname "$0")/lib.sh"

version="$(plugin_version)"
do_screenshots=1 do_description=1 do_urls=1
while (( $# )); do
    case "$1" in
        --version) version="${2:?--version needs a value}"; shift ;;
        --no-screenshots) do_screenshots=0 ;;
        --no-description) do_description=0 ;;
        --no-urls) do_urls=0 ;;
        *) rdie "unknown argument: $1" ;;
    esac
    shift
done
[[ -n "${JETBRAINS_MARKETPLACE_TOKEN:-}" ]] || rdie "set JETBRAINS_MARKETPLACE_TOKEN (Marketplace profile -> My Tokens)"

api="https://plugins.jetbrains.com/api/plugins/$MARKETPLACE_PLUGIN_ID"
repo="https://github.com/dkej123/AndroidDeveloperToolkit"
auth=(-H "Authorization: Bearer $JETBRAINS_MARKETPLACE_TOKEN")

# curl wrapper: prints the response body, fails with it on a non-2xx status.
call() {
    local out status
    out="$(mktemp)"
    status="$(curl -sS -o "$out" -w '%{http_code}' "${auth[@]}" "$@")" || { rm -f "$out"; rdie "curl $* failed"; }
    if [[ "$status" != 2* ]]; then
        cat "$out" >&2; echo >&2; rm -f "$out"
        rdie "HTTP $status from $*"
    fi
    cat "$out"; rm -f "$out"
}

if (( do_urls )); then
    rlog "links"
    call -X POST "$api/edit" -H 'Content-Type: application/json' --data @- >/dev/null <<JSON
{"urls": {
  "url": "$repo",
  "sourceCodeUrl": "$repo",
  "bugtrackerUrl": "$repo/issues",
  "docUrl": "$repo#readme",
  "licenseUrl": "$repo/blob/main/LICENSE"
}}
JSON
fi

if (( do_description )); then
    rlog "description from plugin.xml"
    python3 - "$RELEASE_ROOT/intellij/src/main/resources/META-INF/plugin.xml" <<'PY' >"$RELEASE_LOGS/listing-description.json"
import json, re, sys
xml = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r"<description><!\[CDATA\[(.*?)\]\]></description>", xml, re.S)
if not m:
    sys.exit("no <description><![CDATA[...]]> in plugin.xml")
print(json.dumps({"description": m.group(1).strip()}))
PY
    call -X PUT "$api/description" -H 'Content-Type: application/json' \
        --data @"$RELEASE_LOGS/listing-description.json" >/dev/null
fi

if (( do_screenshots )); then
    dir="$RELEASE_ROOT/marketplace/screenshots/$version"
    shopt -s nullglob
    files=("$dir"/*.png)
    (( ${#files[@]} )) || rdie "no screenshots in $dir (release/screenshots.sh)"
    rlog "uploading ${#files[@]} screenshots from $dir"
    form=()
    for f in "${files[@]}"; do form+=(-F "$(basename "$f")=@$f;type=image/png"); done
    uploaded="$(call -X POST "$api/screenshots" "${form[@]}")"
    # The upload adds images; PUT sets the page's list (and order) to exactly these.
    order="$(python3 -c 'import json,sys; print(json.dumps([{"url": s["url"]} for s in json.load(sys.stdin)]))' <<<"$uploaded")"
    call -X PUT "$api/screenshots" -H 'Content-Type: application/json' --data "$order" >/dev/null
    rlog "screenshots set: $(python3 -c 'import json,sys; print(len(json.load(sys.stdin)))' <<<"$order")"
fi

rlog "done: https://plugins.jetbrains.com/plugin/$MARKETPLACE_PLUGIN_ID"

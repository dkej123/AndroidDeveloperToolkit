---
name: marketplace-listing
description: Update the ADB Toolbox page on JetBrains Marketplace (plugins.jetbrains.com/plugin/34857) — screenshots, description, links (source, issues, docs, license) — through its API, without the web UI. Use when asked to fill, refresh or fix the store page, upload/replace store screenshots, change the Marketplace description or links, or after new screenshots were generated. Does not upload plugin versions (that is the release-plugin skill).
---

# Marketplace listing — ADB Toolbox

Page: https://plugins.jetbrains.com/plugin/34857-adb-toolbox (numeric ID `34857`, `MARKETPLACE_PLUGIN_ID`
in `release/lib.sh`; plugin ID `com.github.dkwasniak.adbtoolbox`).

## How

The token exists only as the GitHub secret `JETBRAINS_MARKETPLACE_TOKEN` (secrets cannot be read back
and the user must never paste it into the chat), so run the workflow, not the script:

```bash
gh workflow run marketplace-listing.yml --ref main                       # links + description + screenshots of pluginVersion
gh workflow run marketplace-listing.yml --ref main -f version=1.0.1      # another screenshot set
gh workflow run marketplace-listing.yml --ref main -f screenshots=false  # text/links only
sleep 8; id=$(gh run list --workflow marketplace-listing.yml -L1 --json databaseId --jq '.[0].databaseId')
gh run watch "$id" --exit-status; gh run view "$id" --log | grep -E '\[release\]|HTTP|statusCode'
```

The workflow runs `release/listing.sh` on `ubuntu-latest` (curl + python3, no Gradle). Locally the
script works the same with `$JETBRAINS_MARKETPLACE_TOKEN` set. Changes to the script or the
screenshots must be **committed and pushed first** — the workflow checks out `main`.

What it sets:
- **Links** from the script: source, issues, docs (`#readme`), license (`LICENSE` on GitHub).
- **Description** = `<description>` CDATA of `intellij/src/main/resources/META-INF/plugin.xml`. Edit
  the text there (single source of truth; every version upload overwrites the page with it anyway).
- **Screenshots** = `marketplace/screenshots/<version>/*.png` in file-name order; they **replace**
  all current screenshots. Look at every image before uploading.

Verify afterwards (public, no token):
```bash
curl -s "https://plugins.jetbrains.com/api/plugins/34857?fullInfo=true" | python3 -c "import json,sys;d=json.load(sys.stdin);print(d['urls']);print(len(d.get('screens') or []),'screens')"
```

## API notes (undocumented — the endpoints the Marketplace edit page calls)

Base `https://plugins.jetbrains.com/api/plugins/34857`, header `Authorization: Bearer <token>`:
- `POST /edit` JSON `{"urls": {...}}` — partial update of links. `url` (homepage) is silently ignored.
- `PUT /description?preserveUIEdits=false` JSON `{"description": "<html>"}` — the query parameter
  is required (400 "Please specify whether changes from the ui should always be applied").
- `POST /screenshots` multipart, one part per file (part name = file name) → JSON list with `url`s;
  then `PUT /screenshots` JSON `[{"url": ...}, ...]` sets the final list and order.
- Not tried yet: tags (`POST /tags` `{"tags": ["<tag id>", ...]}`, ids from `GET /api/tags`).
- Found by reading the frontend bundles (`/static/versions/<n>/index.js`: `/api/plugins/${id}/...`).
  If an endpoint starts failing, re-check them there; 403 "token is invalid" = bad/expired token.

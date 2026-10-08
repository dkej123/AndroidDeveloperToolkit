#!/usr/bin/env bash
# Publishes the version in gradle.properties to JetBrains Marketplace.
#
#   release/publish.sh                  # stable: tag v<version>, CI publishes (default)
#   release/publish.sh --channel beta   # beta:   tag beta-v<version>, CI publishes to the beta channel
#   release/publish.sh --local          # upload from this machine with $JETBRAINS_MARKETPLACE_TOKEN
#
# Pushing the tag is a production release: .github/workflows/publish-plugin.yml uploads the ZIP to
# Marketplace and release.yml creates the GitHub release. Asks for confirmation (RELEASE_YES=1 skips).
source "$(dirname "$0")/lib.sh"

channel=stable
local_upload=0
while (( $# )); do
    case "$1" in
        --channel) channel="${2:?--channel needs stable|beta}"; shift ;;
        --local) local_upload=1 ;;
        *) rdie "unknown argument: $1" ;;
    esac
    shift
done
[[ "$channel" == stable || "$channel" == beta ]] || rdie "--channel must be stable or beta"

cd "$RELEASE_ROOT"
version="$(plugin_version)"
plugin_id="com.github.dkwasniak.adbtoolbox"
tag="$([[ "$channel" == beta ]] && echo "beta-v$version" || echo "v$version")"

# --- preflight ------------------------------------------------------------------------------------
grep -q "^## \[$version\]" CHANGELOG.md || rdie "CHANGELOG.md has no '## [$version]' section"
[[ -z "$(git status --porcelain)" ]] || rdie "working tree is not clean; commit the release first"
git fetch -q origin --tags
[[ "$(git rev-parse HEAD)" == "$(git rev-parse '@{u}' 2>/dev/null)" ]] || rdie "HEAD is not pushed / not in sync with its upstream"
if [[ "$channel" == stable ]]; then
    [[ "$(git rev-parse --abbrev-ref HEAD)" == main ]] || rdie "stable releases are tagged on main"
fi
git rev-parse -q --verify "refs/tags/$tag" >/dev/null && rdie "tag $tag already exists"

# JetBrains requires the very first version of a new plugin ID to be uploaded through the website.
# Ask by numeric ID: /plugins/list leaves out a plugin that is still awaiting its first approval,
# while /api/plugins/<id> answers for it too.
if ! curl -fs "https://plugins.jetbrains.com/api/plugins/$MARKETPLACE_PLUGIN_ID" | grep -q "\"xmlId\":\"$plugin_id\""; then
    zip="$(plugin_zip)"
    cat >&2 <<MSG
[release] $plugin_id is not on JetBrains Marketplace yet. The first upload must be manual:
  1. release/build.sh   (produces $zip)
  2. https://plugins.jetbrains.com/plugin/add  -> upload the ZIP, fill the page from docs/marketplace.md,
     add the screenshots from marketplace/screenshots/$version/
  3. After JetBrains approves it, later versions go through release/publish.sh.
MSG
    exit 2
fi
curl -fs "https://plugins.jetbrains.com/plugins/list?pluginId=$plugin_id" | grep -q "<version>$version</version>" \
    && rdie "version $version is already on Marketplace; bump pluginVersion"

# --- publish --------------------------------------------------------------------------------------
if (( local_upload )); then
    [[ -n "${JETBRAINS_MARKETPLACE_TOKEN:-}" ]] || rdie "set JETBRAINS_MARKETPLACE_TOKEN (Marketplace profile -> My Tokens)"
    confirm "Upload ADB Toolbox $version to the $channel channel from this machine?" || rdie "cancelled"
    ensure_java21
    channel_arg=()
    [[ "$channel" == beta ]] && channel_arg=(-PmarketplaceChannel=beta)
    ORG_GRADLE_PROJECT_intellijPlatformPublishingToken="$JETBRAINS_MARKETPLACE_TOKEN" \
        gradle publish :intellij:publishPlugin "${channel_arg[@]}"
    rlog "uploaded $version to the $channel channel; JetBrains reviews it before it is listed"
    exit 0
fi

command -v gh >/dev/null || rdie "gh CLI required to follow the publish workflow"
gh secret list | grep -q '^JETBRAINS_MARKETPLACE_TOKEN' \
    || rdie "repository secret JETBRAINS_MARKETPLACE_TOKEN is missing: gh secret set JETBRAINS_MARKETPLACE_TOKEN"
confirm "Push tag $tag? This publishes ADB Toolbox $version to the $channel channel." || rdie "cancelled"
git tag -a "$tag" -m "ADB Toolbox $version"
git push origin "$tag"
rlog "pushed $tag; following the Publish Plugin workflow"
sleep 10
run_id="$(gh run list --workflow publish-plugin.yml --limit 1 --json databaseId --jq '.[0].databaseId')"
gh run watch "$run_id" --exit-status || rdie "Publish Plugin failed: gh run view $run_id --log-failed"
rlog "published $version ($channel); JetBrains reviews it before it is listed"

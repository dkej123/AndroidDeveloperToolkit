#!/usr/bin/env bash
# The whole release in one go (release/README.md):
#   version bump -> CHANGELOG check -> build -> full tests (unit + E2E) -> Marketplace screenshots
#   -> release commit + push -> publish (tag -> CI).
#
#   release/release.sh 1.1.0                 # stable
#   release/release.sh 1.1.0-beta.1 --beta   # beta channel
#   options: --skip-e2e (not for stable), --no-screenshots, --no-publish
source "$(dirname "$0")/lib.sh"

version="${1:-}"; [[ -n "$version" ]] || rdie "usage: release/release.sh <version> [--beta] [--skip-e2e] [--no-screenshots] [--no-publish]"
shift
channel=stable; skip_e2e=0; screenshots=1; publish=1
for arg in "$@"; do
    case "$arg" in
        --beta) channel=beta ;;
        --skip-e2e) skip_e2e=1 ;;
        --no-screenshots) screenshots=0 ;;
        --no-publish) publish=0 ;;
        *) rdie "unknown argument: $arg" ;;
    esac
done
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || rdie "version must look like 1.2.3 or 1.2.3-beta.1"
[[ "$channel" == beta || "$version" != *-* ]] || rdie "prerelease versions go to --beta"
if (( skip_e2e )) && [[ "$channel" == stable ]]; then rdie "stable releases run the full E2E suite"; fi

cd "$RELEASE_ROOT"
[[ -z "$(git status --porcelain)" ]] || rdie "working tree is not clean"

rlog "1/6 version $version"
sed -i.bak -E "s/^pluginVersion=.*/pluginVersion=$version/" gradle.properties && rm -f gradle.properties.bak
grep -q "^## \[$version\]" CHANGELOG.md || rdie "add a '## [$version] - $(date +%F)' section to CHANGELOG.md first"

rlog "2/6 build";       "$RELEASE_ROOT/release/build.sh"
rlog "3/6 tests";       "$RELEASE_ROOT/release/test.sh" $( (( skip_e2e )) && echo --skip-e2e )
"$RELEASE_ROOT/release/build.sh"   # test.sh ran `clean`: rebuild the ZIP that gets published
if (( screenshots )); then
    rlog "4/6 screenshots"; "$RELEASE_ROOT/release/screenshots.sh"
else
    rlog "4/6 screenshots skipped"
fi

rlog "5/6 release commit"
git add gradle.properties CHANGELOG.md marketplace/screenshots 2>/dev/null || true
git diff --cached --quiet || git commit -q -m "Release $version"
git push -q origin HEAD

if (( publish )); then
    rlog "6/6 publish"
    "$RELEASE_ROOT/release/publish.sh" --channel "$channel"
else
    rlog "6/6 publish skipped; run release/publish.sh --channel $channel when ready"
fi

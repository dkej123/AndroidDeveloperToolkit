# 056 — Deep links and application permissions: handoff

Date: 2026-09-27

## Current status

The implementation is substantial but **not yet complete against the full product plan**. The
repository builds successfully and all unit/platform tests and coverage/architecture gates pass.
The App Details E2E navigation regressions are fixed, and dedicated device-backed deep-link and
permission tests pass. The remaining gaps are primarily URI-form UX, private-host confirmation,
Android 17 diagnostics, presentation grouping, and broader integration coverage.

## Implemented

### App Details UI

- Added dedicated **Deep Links** and **Permissions** tabs.
- Deep Links has on-demand **Analyze APK**, search, a result table, URI input and **Open**.
- Permissions has per-user state, flags, and Grant/Revoke/Reset decision actions.
- Async results use the existing App Details generation guard, so a result from an old
  app/device selection cannot overwrite the current view.

### Installed APK deep-link analysis

- Added domain models for catalogs, targets, URI patterns, parameters, dynamic rules and app-link
  verification.
- The JVM adapter executes `pm path --user`, pulls base and split APKs through binary-safe
  `exec-out cat`, runs `apkanalyzer manifest print` for every APK, and runs
  `apksigner verify --print-certs`.
- Temporary APKs are always deleted in `finally`; APK bytes are never cached.
- The manifest parser:
  - accepts only exported Activity/Activity Alias entries with VIEW + BROWSABLE + URI data;
  - records missing DEFAULT;
  - preserves Android's combined semantics for all `<data>` elements in one intent filter;
  - supports schemes, hosts/wildcards, ports, MIME, SSP, and literal/prefix/suffix/pattern/
    advanced-pattern path, query and fragment matcher families.
- Open uses a package-restricted implicit intent:
  `am start -W -a VIEW -c BROWSABLE -d <uri> -p <package>` (typed shell tokens, no `-n`).
- Open is blocked when the URI does not match a static filter or is excluded by a valid dynamic
  rule.

### App Links / Dynamic App Links

- Reads and parses `pm get-app-links --user cur <package>`, including verified/approved/denied/
  none/legacy failure and vendor codes.
- Fetches only `https://host/.well-known/assetlinks.json`, does not follow redirects, sets connect
  and request timeouts, caps responses at 1 MiB, and uses no cookie or credential store.
- Refuses loopback/link-local/private/multicast targets. Wildcard hosts are fetched from the root
  domain.
- Validates relation, Android namespace, package and at least one APK certificate.
- Parses ordered Dynamic App Links components including exclude, path, query and fragment; no
  matching dynamic rule means exclusion.
- A successful remote JSON is retained in cache and shown as stale when restored.
- A failed refresh now retains the last good validation and its timestamp, marks it stale, and
  preserves the latest network error through another cache restore.
- HTTP uses Android Studio's explicit proxy selector rather than the ambient JVM selector.

### Android Studio project enrichment

- Matches the installed package to the Android module whose active model has the same
  `applicationId`.
- Reads Android Studio's active-variant merged manifest and active source-provider resource roots.
- Recursively follows Navigation XML `include` graphs and parses destination `navArgument`
  type/default/nullability plus named path/query/fragment placeholders.
- Merges matching metadata into APK targets with a Project source. Project-only targets are shown
  as Project + Runtime unknown instead of being presented as device-confirmed.

### Cache

- Stored outside the project under the IDE system directory (`adb-toolbox/deep-links`).
- Versioned cache key includes project hash, device serial, Android user, package, versionCode,
  lastUpdateTime and codePath.
- Stores parsed inputs/results (manifest XML, certificates, app-link state and last good remote
  assetlinks JSON), not APK files.
- Cache lookup does no ADB/process/network work; Analyze APK is always a refresh.
- LRU pruning keeps at most 100 cache directories.

### Permissions

- Added NOT_REQUESTED, GRANTED, GRANTED_ONE_TIME, DENIED, DENIED_PERMANENTLY,
  FIXED_READ_ONLY and UNKNOWN states.
- Parses current Android user and USER_SET, USER_FIXED, ONE_TIME, POLICY_FIXED and SYSTEM_FIXED.
- Runtime Grant/Revoke use `pm ... --user`; Reset runs revoke followed by
  `pm clear-permission-flags --user ... user-set user-fixed`.
- Install and policy/system-fixed entries are read-only.
- Every successful mutation re-reads `dumpsys package`; failed ADB operations do not update the
  permission optimistically.
- Package names, permission names and URIs are validated and passed as typed/quoted arguments.
- On Android versions whose `dumpsys package` omits a denied/not-yet-requested runtime permission
  from the runtime block, the dangerous-permission catalog keeps it classified as mutable
  `NOT_REQUESTED`.

### E2E fixture and coverage

- Provisioning now installs pinned Build Tools/platform components required by `apkanalyzer`.
- A source-built, signed fixture APK provides a BROWSABLE custom-scheme link and CAMERA runtime
  permission without checking an opaque APK binary into the repository.
- E2E selects App Details tabs by title and covers Analyze APK, package-restricted Open, and Grant
  with independent device read-back.

## Verification completed

- Focused domain/application/adapter/IntelliJ tests: pass.
- `./gradlew build`: pass (includes all module tests, `architectureCheck`, all `koverVerify`
  tasks, plugin build and distribution verification).
- `git diff --check`: pass.
- Full E2E run completed with:
  `E2E_HOME=/work/.e2e e2e/scripts/run-e2e.sh`.
  66/69 passed. Two failures were the documented no-KVM dark-theme/density command timeouts. The
  third exposed the Android 9 `NOT_REQUESTED` parsing gap and was fixed afterward.
- Focused real-IDE rerun:
  `e2e/scripts/run-e2e.sh --tests '*DeepLinksPermissionsE2ETest*'`: **2/2 pass**.

## Remaining work required for the full plan

1. Complete the URI form UX:
   - fields for named parameters and wildcards;
   - editable extra query parameters;
   - live final URI preview and separate static/dynamic match indicators;
   - visible Runtime unknown warning and persistence of last form values per link.
2. Add a one-time confirmation flow for private/loopback/link-local assetlinks hosts. The current
   safe behavior refuses them with an explanatory error.
3. Add redirect/timeout/oversize integration tests around the HTTP adapter (network-stale is now
   covered). The explicit IDE proxy selector is wired, though the baseline 2024.2 API is deprecated.
4. Add Android 17+ optional `am start --debug-link` diagnostics.
5. Improve presentation to group by activity/domain and replace the current explicit textual
   APK/Project/Dynamic/Runtime unknown labels with visual badges.
6. Expand tests for split deduplication, cache version-change/LRU/project-device-user isolation,
   malformed/timeout/redirect HTTP behavior, delayed selection changes, read-only permission UI,
   qualifier precedence, and all remaining Navigation XML cases from the plan.

## Useful commands

```bash
source /work/.unit-env.sh
./gradlew build

source /work/.e2e-env.sh
E2E_HOME=/work/.e2e e2e/scripts/run-e2e.sh
```

Do not run `verifyPlugin`; repository rules explicitly prohibit that local task. `build` already
runs the supported local verification gates.

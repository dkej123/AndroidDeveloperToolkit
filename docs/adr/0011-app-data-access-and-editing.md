# 0011 — Reading and editing an app's private files (shared prefs, databases)

## Status

Accepted

## Context

The Apps view gained a detail page that shows everything `dumpsys package` reports about an app
and lets the user edit its `shared_prefs/*.xml` and `databases/*` files. On Android an app's data
directory is private to its uid; adb's shell user cannot read it. The user asked for editing that
works on real phones as well as emulators, with purpose-built editors (a table for preferences, a
table browser plus SQL for databases).

## Decision

- **Access** is probed per opened app, in this order, and reported in the UI:
  1. a **root adb shell** (`id -u` is 0: `adb root` on emulators without Google Play and on
     userdebug builds) — commands run with `sh -c` in `/data/data/<pkg>`;
  2. **`run-as <pkg>`** when the app is debuggable (the normal case for the user's own debug
     builds on any phone);
  3. **`su 0`** on rooted devices.
  Otherwise the editors explain that the files are unreachable; nothing is attempted.
- **Shared preferences** are read with `cat`, parsed and re-serialized by a small platform-free
  parser for Android's fixed `XmlUtils` dialect (`SharedPrefsXml`, `:domain`).
- **Writes never need stdin** (the `AdbTransport` port has none): content is sent as base64 chunks
  into a temp file inside the data directory, then copied over the target with `cat >`. Overwriting
  in place keeps the file's owner and SELinux label, so the app can still write it — which a `mv`
  of a root-written file would break.
- **Databases are edited on the host**, not on the device: `sqlite3` exists on emulators but not on
  production phones. The database (and its `-wal`) is streamed to a host temp directory through the
  binary (`exec-out`) path, opened with the xerial SQLite JDBC driver (`:adapters-jvm`), and on save
  checkpointed and pushed back via `/data/local/tmp` (`adb push`, then an app-side `cat >`), after
  which the device's stale `-wal`/`-shm`/`-journal` are removed.
- **Saving force-stops the app first**, so its in-memory copy of the preferences or its open
  database connection cannot overwrite the change.

## Consequences

- Works on any phone for debuggable apps, and for every app on the test emulator (root).
- The plugin bundles the SQLite JDBC driver (~14 MB with its native libraries).
- A save replaces the whole file; concurrent writes by a running app are prevented only by the
  force-stop, which the UI states next to the Save button.

## Rejected alternatives

- **Android Studio's Database Inspector / App Inspection APIs**: tied to a running, debuggable app
  and to Studio-internal APIs; no shared-preferences editing.
- **On-device `sqlite3`**: missing on production builds.
- **Streaming writes through stdin (`adb exec-in`)**: not available through the transport port or
  ddmlib's shell API.

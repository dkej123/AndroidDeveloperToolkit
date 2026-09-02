# Local reference repositories

Reference source trees in this directory are local-only and ignored by Git. They must never be
committed, packaged, or copied into this project.

## ADBHelper

ADBHelper is a read-only behavioral and ddmlib reference for selected ADB tasks:

- upstream: `https://github.com/classops/ADBHelper`
- reviewed revision: `237d525cee8d55f26df7aa046e54cca7d5c26bf2`
- local path: `references/ADBHelper`

If the directory is absent, bootstrap it once from the repository root:

```shell
git clone https://github.com/classops/ADBHelper.git references/ADBHelper
git -C references/ADBHelper checkout 237d525cee8d55f26df7aa046e54cca7d5c26bf2
```

The upstream declares no license. Treat it as all-rights-reserved: inspect behavior and architecture
only. Do not copy or adapt its code, parsers, strings, names, resources, or UI. Do not modify the local
checkout. See `.claude/skills/adb-development/references/adbhelper.md` for task routing and the patterns
that must not be inherited.

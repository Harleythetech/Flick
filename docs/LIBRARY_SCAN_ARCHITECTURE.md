# Library Scan Architecture

## Overview

The scanner is split into three layers:

1. `TwoPhaseScanner`
   Performs a full filesystem walk, reads only `path`, `size`, and `last_modified_ms`, then diffs that snapshot against the shared file database.

2. `EventDrivenScanner`
   Uses OS-native watcher backends through `notify`:
   - Linux: `inotify`
   - macOS: `FSEvents` / `kqueue` fallback
   - Windows: `ReadDirectoryChangesW`

   It coalesces live events into batch updates so the database lock is taken once per flush instead of once per file.

3. `HybridScanner`
   Runs the bootstrap scan first, seeds the database, starts the watcher, and exposes a manual rescan path for recovery.

## Data Model

```text
FileFingerprint
  path: String
  size: u64
  last_modified_ms: i64
```

The fingerprint intentionally excludes expensive tag parsing. Metadata extraction can happen later, only for files classified as `NEW` or `MODIFIED`.

## Flow

```text
                 +----------------------+
                 | SharedFileDatabase   |
                 | path -> fingerprint  |
                 +----------+-----------+
                            ^
                            |
     bootstrap              | batch upserts/deletes
                            |
+-----------------+   diff   |   +----------------------+
| TwoPhaseScanner +----------+---+ EventDrivenScanner   |
| WalkDir + rayon |              | notify watcher       |
| stat only       |              | event coalescing     |
+--------+--------+              +----------+-----------+
         |                                  |
         | manual rescan                    | live create/modify/delete
         +----------------------------------+
```

## Complexity

- Bootstrap scan: `O(n)` filesystem walk and stat collection, where `n` is the number of files under the watched roots.
- Diffing: `O(n)` with hash map lookups against the in-memory snapshot.
- Live updates: `O(k)` for `k` changed files/events, usually far smaller than `n`.

## Expected Behavior

- 1k to 10k files:
  Initial bootstrap is typically dominated by directory traversal and file stat calls, usually well under a second on SSD-backed local storage.
- 10k to 100k files:
  Bootstrap cost grows linearly, but unchanged files are still cheap because they are never opened for metadata parsing.
- Live watcher phase:
  Cost is proportional to actual churn. If no files change, there is effectively no scan work after initialization.

## Trade-offs

- Polling/bootstrap scan
  - Simple and deterministic.
  - Reliable for initial state.
  - Wasteful if repeated frequently because every pass touches the whole tree.

- Event-driven watcher
  - Scales with actual change volume.
  - Near-real-time updates.
  - Needs overflow/recovery handling, which is why the hybrid design keeps a manual rescan path.

---
summary: "Session persistence - reuse existing sessions by chat_id, consolidate multiple sessions, and restore context"
read_when:
  - Configuring session management for multi-user inboxes
  - Debugging session continuity issues
  - Implementing session lookup by chat_id
title: "Session Persistence"
---

# Session Persistence

OpenClaw supports **persistent session mapping** to reuse existing sessions for the same chat_id instead of creating new sessions on every message.

## Problem

By default, OpenClaw may create new sessions for the same chat_id (e.g., `telegram:8547302115`) due to:
- Session lookup failures at Gateway startup
- Race conditions in session routing
- No persistent chat_id → session_id index

This leads to:
- Lost conversation context
- Exponential session growth
- Wasted disk/memory resources

## Solution

Enable `session.persistence` to maintain a persistent index of chat_id → session_id mappings.

## Configuration

```json5
{
  session: {
    dmScope: "per-channel-peer",
    persistence: {
      enabled: true,                    // Enable session persistence
      indexPath: "./session_index.json", // Path to index file
      autoConsolidate: false,           // Auto-consolidate old sessions
      consolidateThreshold: 10,         // Consolidate after N sessions
      contextRestore: {
        enabled: true,                  // Restore context on session start
        ragSearch: true,                // Search RAG for context
        memoryLines: 100,               // Lines from MEMORY.md
        checkpointRestore: true,        // Restore from checkpoint
      }
    }
  }
}
```

## CLI Commands

### Lookup Session by chat_id

```bash
openclaw sessions lookup --chat-id "telegram:8547302115"
```

Output:
```
✅ Found session for telegram:8547302115:
   Session ID: unified_telegram_8547302115_20260307_125152
   Source: index
```

### Consolidate Sessions

Merge multiple sessions for the same chat_id into one unified session:

```bash
openclaw sessions consolidate --chat-id "telegram:8547302115"
```

Output:
```
🔀 Consolidating 4 sessions for telegram:8547302115...
   📄 Merging 96fa62e5-....jsonl...
   📄 Merging 304f4e7c-....jsonl...
   📄 Merging 611757a2-....jsonl...
   📄 Merging 0a49734f-....jsonl...
✅ Created unified session: unified_telegram_8547302115_20260307_125152 (324 entries)
📝 Session index updated
```

### Rebuild Session Index

Scan all existing sessions and rebuild the index:

```bash
openclaw sessions index --rebuild
```

Output:
```
🔨 Rebuilding session index...
   Sessions directory: /Users/music/.openclaw/agents/main/sessions
   Index path: ./session_index.json
✅ Index rebuilt: 1 chat_id mappings

📊 Summary:
   telegram:8547302115 → unified_telegram_8547302115_20260307_125152
```

### List Session Index

```bash
openclaw sessions index --list
```

Output:
```
📋 Session Index (1 mappings):
   Created: 2026-03-07T12:45:29.676Z
   Last rebuilt: 2026-03-07T12:45:29.676Z
   Last updated: 2026-03-07T12:52:00.000Z

   telegram:8547302115 → unified_telegram_8547302115_20260307_125152
```

## Session Index Format

The session index is a JSON file with the following structure:

```json
{
  "version": 1,
  "created_at": "2026-03-07T12:45:29.676Z",
  "last_rebuilt": "2026-03-07T12:45:29.676Z",
  "last_updated": "2026-03-07T12:52:00.000Z",
  "mappings": {
    "telegram:8547302115": "unified_telegram_8547302115_20260307_125152"
  },
  "total_sessions": 1
}
```

## Context Restore

When `session.persistence.contextRestore.enabled` is true, OpenClaw automatically restores context on session start:

1. **Session Index Lookup** - Find existing session by chat_id
2. **RAG Search** - Search semantic index for recent context
3. **MEMORY.md** - Read last N lines from long-term memory
4. **Checkpoint Restore** - Restore from latest checkpoint (if exists)

The restored context is injected into the system prompt for continuity.

## Migration

### Existing Sessions

Existing sessions are automatically scanned and indexed on first run:

```bash
openclaw sessions index --rebuild
```

No manual migration needed.

### Config

The `session.persistence` option is **optional** and defaults to disabled. Existing configs remain valid.

## Implementation Details

### Session Index Module

```typescript
import {
  lookupSessionByChatId,
  registerSessionMapping,
  rebuildIndex,
  scanSessionsForChatId,
} from "./config/sessions/session-index.js";
```

### Session Lookup Flow

1. Check session index (fast path)
2. Scan session transcripts (fallback)
3. Update index if found during scan
4. Return session_id or null

### Session Consolidation

1. Find all sessions for chat_id
2. Sort by modification time (oldest first)
3. Create unified session with metadata
4. Merge messages from all sessions
5. Update session index
6. (Optional) Delete old sessions

## Best Practices

1. **Enable persistence early** - Add config before session proliferation
2. **Regular index rebuilds** - Run `index --rebuild` weekly
3. **Consolidate proactively** - Merge sessions before they grow too large
4. **Monitor disk usage** - Check session store size periodically

## Troubleshooting

### Session not found in index

```bash
# Rebuild index from scratch
openclaw sessions index --rebuild

# Manually lookup (scans transcripts)
openclaw sessions lookup --chat-id "telegram:123"
```

### Too many sessions

```bash
# Consolidate all sessions for a chat_id
openclaw sessions consolidate --chat-id "telegram:123"

# List all sessions to identify candidates
openclaw sessions --active 10080  # Last 7 days
```

### Context not restored

1. Check `session.persistence.contextRestore.enabled: true`
2. Verify MEMORY.md exists
3. Check RAG index (run `semantic_search.py "test"`)
4. Verify checkpoint directory exists

## Related

- [Session Management](/concepts/session)
- [Session Pruning](/concepts/session-pruning)
- [Memory](/concepts/memory)
- [Compaction](/concepts/compaction)

---

*Added: 7 Mar 2026 | Author: Adrian S (@adrian-stefanescu)*

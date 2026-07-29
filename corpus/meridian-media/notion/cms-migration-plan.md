---
id: P_CMS_MIGRATION
title: "Video CMS Migration Plan"
created_time: 2026-03-10T14:20:00Z
last_edited_time: 2026-03-10T17:05:00Z
created_by: "Alex Chen"
last_edited_by: "Alex Chen"
visibility: PUBLIC
url: https://www.notion.so/meridian/P_CMS_MIGRATION
---

# Video CMS Migration Plan

Moving video off the legacy CMS and onto Mux.

## Why now

The legacy CMS has been the top source of on-call pages for three consecutive
weeks. Every incident traces to the same transcode queue deadlock, and we have
patched around it twice without fixing it.

## Cost

Roughly neutral at current volume. Mux usage cost is offset by retiring the two
EC2 instances running our own transcode workers.

## Plan

1. Dual-write new uploads to both systems — 2 weeks
2. Backfill the existing library — 4 weeks, throttled
3. Cut reads over to Mux behind a flag
4. Delete the legacy transcode path

## Risks

- Backfill of the 2019–2021 library may hit files with no usable master
- Player embed URLs change; anything hardcoded in old articles needs a rewrite

---
id: US-01-V2-LAZY-LOADING-CORE
title: US-01 DEFAULT_V2 lazy-loading acceptance suite
surface: android
includes:
  - US-01-V2-LAZY-LOADING-001
---

## Objective
Validate the US-01 flow end-to-end after introducing DEFAULT_V2 and lazy loading for countries and servers.

## Scope
- DEFAULT_V2 source selection visibility and persistence.
- Country list load path from v2 endpoint.
- On-demand server list loading by country.
- Main-screen server selection and navigation stability.
- No parsing regression (`JSONException`) for wrapped v2 payload.

## Execution Order
1. US-01-V2-LAZY-LOADING-001

## Exit Criteria
- Case passes with complete screenshot/log evidence.
- No blocker defects in ME-3/4/8/9/10/11/12.

# Accepted architecture decisions

Read this page when changing an invariant below. Use CodeGraph or Graphify to
locate the current implementation. Earlier ADRs, the six-phase Beebot refactor
plan and past audits are in Git at `6d0b1480:docs/architecture/`.

| Area | Decision |
| --- | --- |
| LoL models and HTTP | Domain and success models live in `lol.model`. Spring owns routing, parameters, configuration and HTTP errors. Use `LolApiParameters`, `ApiResult` and `LolApiResponses` where applicable. An HTTP-only wrapper must have an explicit justification. |
| Profiles and matches | One chain from `Summoner` through `SummonerView` to `SummonerLeaderboard`. Match lists and detail share the complete match and participant payload, with timeline only in detail. Profile projections share `{puuid, filterKey}` using the full summoner filter key. Persist canonical statistics leaves and derive presentation and rankings from them. |
| Persistence and cache | MongoDB owns LoL durable data, including canonical matches and rebuildable facets and aggregates. The historical LoL MariaDB path is retired. Redis holds disposable caches and index data. `RedisKey` owns LoL key patterns and TTLs. Manual Mongo index management is an operator responsibility. |
| Async work | Use the global `QueueHandler` and its schedulers for work with lifecycle, deduplication or retry. Keep Riot fetch, compute and synchronization responsibilities distinct. Expose existing partial and pending states where data is incomplete. |
| Analysis and builds | Profile and champion services coordinate loading and refresh. Analyzers handle calculations. Champion builds and statistics share the bounded match/event join, with separate persistence for builds and stats. Tier lists and contextual metrics derive from canonical ready statistics. Arena uses the same participant outcome as champion stats, with its own placement metrics, boots-and-prismatic core, and inventory fallback for matches without ordered acquisitions. |
| Ranking | Canonical ranks write before the derived Mongo competitive projection, followed by Redis publication. Leaderboard sorted sets are disposable. Records use actual metric values in Mongo. Response-only ranks and positions stay out of the canonical rank document. |
| Beebot | Discord and HTTP share cache and mutation owners. `discord.auth` owns Discord authentication and `AuthDB` owns its SQL. The shared application exception is translated at the HTTP boundary. Sound visibility, guild administration and ordinary guild membership have distinct access rules. |
| Guild roles | `GET /api/beebot/guilds/{guildId}` returns `roles[].color` as `null` for the theme default, or as `style` (`solid`, `gradient`, `holographic`) and hex `primary`, `secondary`, `tertiary` values. Unused colors are `null`. |
| Linked Riot profiles | `GET /api/beebot/me` includes the connected profiles. `POST`, `PATCH` and `DELETE /api/beebot/me/lol/account` each change one profile through the same `UserCache` and `UserData` operations as Discord. MongoDB assigns an unlinked PUUID atomically and applies tracking and unlink changes only to profiles owned by the caller. Summoner refreshes preserve the link and tracking state. |
| Sound catalog | `GET /api/beebot/public/search` pages sounds, boards and tags independently. Board matches can use a contained sound only when it is visible in the board detail. `POST /api/beebot/comments` and `PUT /api/beebot/votes` resolve a typed source and use the same transactions as the older resource routes. |

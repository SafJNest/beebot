# Beebot operations

`Settings` is the shared entry point for the bot, HTTP server, databases and
integrations. It loads one YAML configuration at startup with Jackson. The file
contains `runtime`, `api`, `bot` and `services`. Each integration keeps its
settings type under `com.safjnest.config`.

## Choose the instance configuration

Copy [the example YAML](../beebot.example.yml) to `beebot.yml` in the project
root for local development. Git ignores `beebot.yml`. On the VPS, set
`BEEBOT_CONFIG` in each service to its own file, such as
`/etc/beebot/canary.yml` or `/etc/beebot/production.yml`. An explicit path takes
precedence over the local file. Relative paths start from the working directory.

List the bot profiles under `bot` and set `runtime.bot` to the profile to use,
such as `beebot` or `canary`. The loader reads the settings and credentials of
the selected bot. Restart Beebot after changing the profile. `runtime.testing`
controls test behavior independently of the bot profile.

For existing deployments, when both `BEEBOT_CONFIG` and `beebot.yml` are absent,
Beebot loads `config.properties`, `rsc/settings.json` and optional
`spring.properties`. Blank legacy OAuth and CDN properties still use their
existing environment variable names.

To migrate an instance, copy `testing` and `host` from `config.properties`
under `runtime`. Copy the profiles from `rsc/settings.json` → `bots` under
`bot` and set `runtime.bot` to the corresponding profile. Copy the settings
for current integrations under `services`. Set
`api.port` from `server.port` and `api.cdnUrl` from `CDN_URL` when used. Move the
effective Discord client ID, secret, encryption key and redirect URLs into
`api.discord`. Stop and start the service to apply the selected file.

Each new file sets `services.database` to the MariaDB host, credentials and
catalog for that instance. `runtime.testing` still selects the application's
test behavior. Deployments carrying a `services.testDatabase` block keep its
catalog selection in test mode. `api.port` configures the embedded Tomcat server
and defaults to `8080`. HTTP requests and responses keep the existing JSON
contract.

## Supply secrets as files

A YAML value such as `"credential:discord-bot-token"` reads the text file
`discord-bot-token` from `CREDENTIALS_DIRECTORY`. With systemd, supply each file
with `LoadCredential=` or `LoadCredentialEncrypted=` in the service unit.
For local development or another service manager, set
`BEEBOT_CREDENTIALS_DIRECTORY` to a directory readable by the service user.
On systemd, `CREDENTIALS_DIRECTORY` takes precedence. Keep credential files
outside the checkout and use separate service users or credentials per instance
where the environments are isolated.

The loader trims a final line ending. A missing or empty file reports its
credential name. Restart the process after replacing a credential or changing
the configuration. The application keeps the resolved configuration for the
life of the process. Existing files can use `credential:` references as well.

## Discord OAuth and R2

Set `api.discord.clientId`, `clientSecret`, `encryptionKey` and
`allowedRedirectUris` when enabling OAuth. Omit the `discord` section on an
instance without Discord OAuth. The encryption key is the Base64 encoding of
32 bytes. When moving an existing installation to the new format, preserve
the exact key bytes so active sessions remain readable. Instances serving the
same sessions share the auth database and key. Rotating the key requires users
to log in again.

Set `services.r2.enabled` and `cdnUrl` to enable CDN reads. Add `soundBucket`,
`accountId`, `accessKeyId` and `secretAccessKey` for uploads. `api.cdnUrl` is
the optional fallback for instances without R2. See [R2 credentials](https://developers.cloudflare.com/r2/api/tokens/).

## Database and deployment

- Under `services.mongo`, set `uri`, `league_collection` and
  `league_test_collection`. These names identify MongoDB databases.
  `runtime.testing: true` selects `league_test_collection`. Production uses
  `league_collection`. Move any existing scalar Mongo URI into `uri` and set
  both database names before using League features.
- For dashboard authentication, review the SQL files under [database/sql/auth/](../database/sql/auth/).
  A fresh installation starts with `001-dashboard-auth.sql`. For an existing
  auth catalog, check for the OAuth transaction table and typed API-key columns
  before applying the corresponding migrations. The MariaDB account needs
  access to the dedicated `auth` database. Redirect URLs are copied into new
  application keys at creation. Review existing keys when changing callbacks.
- For sound upgrades, compare the target MariaDB schema with the migration
  files under [database/sql/berbit/sound/](../database/sql/berbit/sound/). Older catalogs
  can use different ID types and column names. Review preconditions before
  running scripts on the selected catalog.
- For MongoDB, select the intended database before running owner-only
  maintenance or index commands. The application does not manage secondary
  indexes. Compare `explain("executionStats")` using representative queries
  and verify plans before retiring existing indexes. For current rebuild
  commands, inspect `MongoMigration` and the bot's test command.
- MongoDB maintenance and index operations are not run automatically by the
  application. Check the current MongoDB code and deployment before applying
  operator commands.
- R2 object keys are described in [database/r2/README.md](../database/r2/README.md).

## Champion refresh diagnostics

The champion API and Discord `/champion` default to Ranked Solo/Duo on the
current patch, across all ranks and regions. `rank` and `region` are `null` in
the resolved filter for those defaults. In queues with positions, an omitted
role uses the champion's most played role from the current champion indexables,
when available. This role is part of the build key. The statistics scope is
shared across roles. ARAM and Arena have no role filter.
Discord exposes `champion`, optional `role` and optional `queue`. The queue
choices are Ranked Solo/Duo, Ranked Flex, Draft Pick, ARAM and Arena.
Recommendations exclude regular Boots and Slightly Magical Footwear. Discord
shows up to three starters, three completed boots and four alternatives for
each displayed slot after the core.

For `GET /api/lol/champion/{champion}`, `202` includes `metadata.filter`,
`metadata.missing` (`stats`, `build`, or both) and `metadata.jobIds`. Follow those
PIDs on `/api/status` and in the `ChampionJob` and `ChampionInput` logs. Invalid
stored data returns `502`. A failed lookup or recent job failure returns `503`.

Standard queues return `builds` at the top level with `defaultBuildId` and
`bestAdjustedWinrateBuildId` when available. Each ID matches the ID of the single
core in its build at `coreBuilds[0].id`. A completed snapshot with no eligible
variants returns `200` with `builds: []`. Arena returns its dedicated `build` field.

Labels are added to the finished snapshot during normal build refreshes.
Standard queues, including ARAM, expose a short `name` such as `On-Hit` only
when item styles distinguish the returned builds. Naming can consult the
third item when enough games reach it. If there is no clear item style,
distinct dominant keystones can name the variants and are exposed as
`nameKeystoneId`.

`MOST_POPULAR` marks the global default and, when names distinguish categories,
the most played build in each category. `BEST_ADJUSTED_WINRATE` follows
`bestAdjustedWinrateBuildId`. A homogeneous set can be displayed using its
badges alone. The list order, core IDs, selection IDs, and build statistics are
fixed before labeling.

For standard builds, `runes[].configuration.statShards` lists the shards in
Offense, Flex, Defense order. Regenerate existing build snapshots to update
their shard order.

Each standard build returns at most one entry in `skillOrders`. Each match votes
for every observed prefix. The recommendation follows the most popular
continuation at each step and ends when the chosen branch has no observed
continuation. A skill event without a type counts as `NORMAL`. The participant's
stored skill order contributes votes without timestamps when the timeline has
no normal upgrades.

With `timingMethod: PREFIX_AVERAGE_INTERVALS`, `averageUpgradeTimeSeconds` is
the estimated elapsed time obtained by adding the mean interval between
consecutive points. For the first point, the interval starts at match start.
Each interval uses matches that follow the recommended prefix through that
point. `timedMatches` counts those matches. `skill` is the one-based position
in `order` and `abilitySlot` identifies the ability. `level` gives the most
commonly observed champion level at the event among matches with that prefix.
Multiple points can share the same champion level. The API omits `level` when
the level-up events do not establish it. Older snapshots without `timingMethod`
contain absolute sample averages.

`skillEvolutions` uses the same fields and absolute averages over the longest
timed prefix of the most played evolution sequence. For stored snapshots in the
previous format, the API reads `level` as `skill` and `championLevel` as `level`.
Regenerate those snapshots to populate evolution levels from timelines. `spell1`
and `spell2` appear under `summonerSpells`.

### Arena builds

Arena returns `build.items` and `build.boots` for all picks of the champion in
the selected scope. `coreBuilds[].items` identifies the core as boots followed
by the first prismatic from the timeline or, as a fallback, from the final
inventory. `coreBuilds[].name` uses the prismatic name and includes boots to
distinguish duplicate names. If a catalog name is missing, its item ID is shown.
The most played core carries the `MOST_POPULAR` badge. Each core exposes
`boots`, `itemOptions`, `prismatics`, `augments`,
`builds`, and `slots` for its own sample. `slots[0]` describes the first
observed legendary acquisition. Inventory-only combinations appear in
`builds` with `inventoryFallback: true`. Their item IDs are sorted and do
not contribute to slot or timing statistics.

Each item ID counts once per champion pick in the global or core item options.
Global option and core pick rates divide by all Arena picks of the champion in
the selected scope. Inside a core, the pick rates for options, combinations,
augments, and slots divide by all picks of that core. Win rates divide by the
row's matches and use the participant outcome shared with champion statistics.
Average placement and first-place rate use that row's known placements and are
`null` when none are available. Timings use observed events. Core completion
requires timestamps for both boots and the first prismatic.

`skillOrders` and `skillEvolutions` are available globally and inside each
core when there are observations. As in the standard build, `skill` is the
position in the sequence and `level` is the observed champion level.
`stats.synergies` reports exact ally compositions. `stats.allySynergies`
counts each distinct allied champion once per pick from those same compositions.
Ally pick rates divide by `stats.overview.picks`.

- `coverage.matches` counts distinct match IDs with a pick of the champion.
  `build.games` counts the picks themselves, including repeated picks of that
  champion in one match.
- `coreGames` and `missingCoreGames` partition those picks by whether both core
  items could be identified.
- `firstPrismaticFallbackGames` counts all picks that needed the inventory to
  identify a first prismatic, including picks without boots.
- `buildTimelineGames` counts picks with at least one attributable item event.
  `orderedItemGames` requires at least one observed legendary acquisition.
- `coreGamesWithOrderedItems`, `coreGamesWithInventoryOnlyItems`, and
  `coreGamesWithoutItems` partition `coreGames`. The first two groups contribute
  one combination per pick. A pick in the third group had no recognized
  legendary acquisition or final inventory item.

Older Arena snapshots are recomputed on demand through the existing build job.

The champion reader holds up to 2,000 match IDs per group and requests event
cursor batches of up to 1,000 documents. Its logs report Mongo cursor time, event
validation and parse time, input consumption time and timeline coverage.

## Cloudflare and media

- Use the same R2 bucket and CDN base URL for sound audio and covers. The
  standard key is `sounds/<id>/<id>.<ext>`, with covers under the same ID
  prefix. Soundboard covers use `soundboard/thumbnail/<id>/`. Stored keys
  are relative to the CDN. Check live object prefixes before changing
  `soundPrefix` or `cdnUrl`. Catalog visibility is enforced by the API.
  Anyone with a direct public CDN link can open the object.
- The bot retains complete local audio copies. Removing a catalog entry keeps
  its storage objects. Review retention before configuring lifecycle rules or
  a local cleanup job.
- Cloudflare cache rules are separate from origin settings. Completed LoL
  matches can use a long shared TTL for successful responses. Use bypass for
  personalized or temporary responses and for profile reads that must reflect
  refreshes. Check actual rules and compare `CF-Cache-Status`, `Age` and
  response data in canary. Earlier options and rationale are in Git at
  `6d0b1480:docs/architecture/lol-http-cache-analysis.md`.

## Local graph setup

Read [AGENTS.md](../AGENTS.md#find-the-code) for tool selection. If the owner
authorizes Graphify setup, install it with SQL support. The existing local
setup used Python 3.12 and these commands:

```sh
uv tool install 'graphifyy[sql]==0.9.114'
graphify extract . --code-only
graphify cluster-only . --no-label
```

The generated map and report live in the ignored `graphify-out/` directory.
Use `graphify update .` after source changes. If it refuses to replace a
larger obsolete graph after removals, use `graphify update . --force`.

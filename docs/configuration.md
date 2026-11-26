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
`accountId`, `accessKeyId` and `secretAccessKey` for uploads. The optional
`endpoint` overrides the default account R2 endpoint. `!test archive-r2` uses
these credentials for direct PUTs to the private `iodid` bucket and does not
need a CDN URL. The test uploads at most 1,000 matches first under `matches/`,
then their corresponding events under `events/`. `api.cdnUrl` is
the optional fallback for instances without R2. See [R2 credentials](https://developers.cloudflare.com/r2/api/tokens/).

## Database and deployment

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

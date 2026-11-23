<img src="/rsc/assets/logo_cropped.png" alt="safjnest_logo" width="200"/>

# Beebot

Beebot is a Discord bot for communities that want music, custom sounds, server management and League of Legends tools in one place. The same application also exposes HTTP APIs for the Beebot dashboard and League of Legends clients.

## Invite and usage

- [Invite Beebot to your server](https://discord.com/oauth2/authorize?client_id=938487470339801169&scope=bot)
- The default prefix is `!`; individual servers can configure their own prefix.
- Use `/help` or `!help` to discover commands. The bot supports both slash commands and prefix commands; availability depends on the command.

## Features

- **Music:** play and search tracks, manage the queue, pause, skip, go back and create playlists. Playback uses Lavaplayer and integrations for sources such as YouTube, SoundCloud and Spotify.
- **Sounds and soundboards:** upload and play custom sounds, organize soundboards, set greeting sounds and configure sound overlap. Sounds can be stored locally or through Cloudflare R2.
- **Server management:** moderation commands, warnings, automated actions, member and server information, and configurable welcome, leave and boost messages.
- **Community and utilities:** experience and leaderboards, Twitch and Spotify integrations, text-to-speech, weather, calculator and other commands.
- **League of Legends:** summoner profiles, ranked data, live games, champions, items and tracking. The HTTP API also serves matches, leaderboards, champion statistics and other LoL data.

Some features require external services, credentials, Discord permissions or existing data. Check `/help` for the commands available on your instance.

## Requirements

- Java 25 and Maven.
- A Discord application and bot token. Enable the **Message Content**, **Server Members** and **Presence** privileged gateway intents in the Discord Developer Portal: the bot requests them at startup. Invite your application with the permissions needed for the features you intend to use, including voice permissions for audio.
- MariaDB for bot data, MongoDB for League of Legends data, and Redis for caching. Redis currently connects to `localhost:6379` in code.
- Credentials for the integrations you use, such as Riot, Twitch and Spotify. YouTube availability can depend on external authentication and service restrictions.

The SQL files in [`database/`](database/) cover multiple domains and include schema changes. Review the schemas and migrations needed by your instance before starting; the repository does not provide a documented one-command database bootstrap. See [database operations](docs/configuration.md#database-and-deployment) for index management.

## Getting started

Run these commands from the repository root:

```bash
cp beebot.example.yml beebot.yml
```

Edit `beebot.yml` with your instance settings and credentials. The example contains placeholder IDs and `credential:` references: replace them or provide the corresponding credential files as described below. Prepare the databases and services needed by your instance, then build and start the application:

```bash
mvn package
java -jar target/beebot-10.0-jar-with-dependencies.jar
```

The version in the JAR filename follows the version in [`pom.xml`](pom.xml). Start the process from the repository root so relative configuration and asset paths resolve correctly. On startup, Beebot loads its settings, starts the embedded HTTP server and background services, and connects to Discord. The API port in the example is `8096`.

## Configuration

[`beebot.example.yml`](beebot.example.yml) is the starting point for an instance. `beebot.yml` is ignored by Git. Alternatively, set `BEEBOT_CONFIG` to the path of another YAML file, for example:

```bash
export BEEBOT_CONFIG=/etc/beebot/production.yml
java -jar target/beebot-10.0-jar-with-dependencies.jar
```

| Section | Purpose |
| --- | --- |
| `runtime` | Select the bot profile with `bot`, set the host and enable test behavior with `testing`. Testing and profile selection are independent. |
| `api` | Configure the embedded HTTP server port and, when needed, Discord OAuth credentials and redirect URIs. |
| `bot` | Define one or more named profiles with a Discord token, prefix, owner IDs, activity, color and playlist limits. `runtime.bot` must match a profile name. |
| `services.database` | Configure the MariaDB host, database name, username and password. |
| `services.mongo`, `services.riot` | Configure the MongoDB connection URI and Riot API key for LoL functionality. |
| `services.twitch`, `services.spotify`, `services.spotifyApi` | Configure Twitch and the separate Spotify playback and account-linking integrations. |
| `services.lavalink` | Configure YouTube playback options, including PO token and rotor flags. |
| `services.r2` | Configure optional R2 storage and CDN access for sounds. |
| Other `services` keys | Configure integrations such as text-to-speech, weather and NASA. |

The loader requires a selected bot profile with a token and a MariaDB configuration with host, username and active database name. The example's other placeholders are not working credentials. Remove unused optional settings or replace their placeholders appropriately; every retained `credential:` reference is resolved at startup.

### Secret files

A value such as `"credential:discord-bot-token"` reads the file named `discord-bot-token` from a credentials directory. For local use, set `BEEBOT_CREDENTIALS_DIRECTORY` to a directory outside the repository and create a file for each retained reference:

```bash
export BEEBOT_CREDENTIALS_DIRECTORY=/path/to/beebot-credentials
```

For systemd, use `LoadCredential=` or `LoadCredentialEncrypted=`; the resulting `CREDENTIALS_DIRECTORY` takes precedence. Missing or empty credential files prevent configuration loading. Restart Beebot after changing configuration or credentials.

Discord OAuth is optional: remove `api.discord` if it is not being configured. When enabled, it requires a client ID, client secret and an encryption key containing 32 bytes encoded as Base64, along with the appropriate allowed redirect URIs. R2 CDN reads and uploads have different credential requirements. See [Discord OAuth and R2](docs/configuration.md#discord-oauth-and-r2).

## How it works

- **Discord:** JDA connects to Discord and routes commands, buttons, autocomplete and other events to the bot's handlers. Guild settings and command permissions determine the behavior available in each server.
- **Audio:** the player manages playback and queues per server; sound storage and metadata connect the Discord commands and the sound API.
- **League of Legends:** services read Riot data and maintain LoL models in MongoDB, with Redis used for caching. `QueueHandler` routes asynchronous work to the Riot, compute and synchronization schedulers.
- **HTTP API:** Spring MVC runs on embedded Tomcat in the same process. It exposes Beebot and LoL endpoints, as well as operational status at `GET /api/status`.

## Technology and documentation

Built with Java 25, JDA, Spring MVC, embedded Tomcat, Lavaplayer, MariaDB, MongoDB and Redis. Maven builds the executable JAR with its dependencies.

- [Development workflow and code navigation](AGENTS.md)
- [Configuration and operations](docs/configuration.md)
- [Accepted architecture decisions](docs/architecture/README.md)

## ***Contacts***
### Lorenzo Sanseverino 
- Founder and CEO of SafJNest
- Developer and designer
- lorenzosanseverino117@gmail.com
- Git: <a href="https://github.com/NeutronSun">NeutronSun</a> 
- Discord: sunyx117
- Website: <a href="https://sunyx.dev">portfolio</a>
### Leonardo Panichi
- Co-Founder, COO, CFO, CMO, CTO, CIO, CSO and CCO of SafJNest
- Developer and analyst
- leonardopanichi4@gmail.com
- Git: <a href="https://github.com/Leon412">Leon412</a> 
- Discord: leon4_

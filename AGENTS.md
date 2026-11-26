# Beebot agent instructions

Codex, Claude Code and Gemini CLI use this file as the shared starting point.
When the owner explicitly asks for the [Beebot agent](.agents/agents/beebot-agent.md),
use the project role registered as `beebot agent` in Codex or `beebot-agent`
in Claude Code and Gemini CLI.

## Find the code

- Start with the current checkout and `git status --short`. For a known route, command, class or method, use CodeGraph to find the source, callers, callees and impact. For a cross-subsystem question or an unknown entry point, use Graphify to orient yourself, then CodeGraph for the specific call path. Keep the query focused and read only the relevant implementations.
- Check that the chosen CLI and its index exist in this checkout. Use `codegraph status` and `codegraph sync` if stale. For Graphify, compare the commit in `graphify-out/GRAPH_REPORT.md` with the checkout, account for local source edits and use `graphify update .` if stale. Ask before installing either tool or initializing a missing index. Use the other tool if it is ready and suits the question.
- For LoL code changes, run CodeGraph `explore` and `impact` before editing. Use `trace` for a concrete flow where available. Read the specific file if the index is stale, the symbol is unresolved or an annotation, configuration or dynamic call is outside the graph. Graphify edges can combine calls, containment and other relationships.
- For HTTP, start with method and route. Read the controller, auth filters, validation and the service or query that determines the result. Follow the response model and serialization for the wire shape. For Discord, follow command registration, the handler and the shared owner. Use the real SQL schema and migrations for persistence assumptions.
- Read [accepted decisions](docs/architecture/README.md) when changing ownership, architecture or an invariant they cover. Read the [operations guide](docs/configuration.md) for deploy, credentials, storage and indexes. Local agent references to archived docs should defer to this file. Use the canonical local agent definition when editing an agent, and leave tool-specific shims alone.

## How to work

- “Solo analisi”, “ragioniamo” or “dimmi cosa faresti” calls for inspection and examples. For a substantial change, show the current path, the proposed path, file moves or removals and how to verify it. An explicit implementation request or “procedi” authorizes the agreed work. After a correction, adjust the affected part of the diff and preserve useful existing work.
- During a design discussion, “potresti riscrivere tutta la classe” describes an option. Explain the change and its impact first. Start editing when the owner requests implementation.
- Trace the same use case through Discord, Spring, cache, domain and persistence before deciding where it belongs. Reuse existing owners, helpers, parsers and enum resolvers. Share behavior when semantics agree, including permissions, error handling and lifecycle. Migrate callers before removing old code. Add a layer when it gives a responsibility a clear home.
- Preserve local edits and established public contracts. A data change preserves command syntax, embeds, field order, text, formatting and layout unless the task authorizes a presentation change. Update affected callers with a contract change. Give method, URL, auth, defaults, request and response examples when the task needs a frontend handoff. Derive endpoint reference material from the implementation.
- Check failures, concurrency and existing data when touching state. Keep transactions at the smallest unit that has to succeed together. Publish cached state after persistence. Check the actual MariaDB dialect and column types, measure query plans before optimizing, and distinguish SQL time from HTTP wait time.
- Review the diff for incidental changes. Report what was verified in code, build or local tests and what was verified in a real environment. Keep credentials and sensitive settings out of commits, output, documentation and memory.
- Commit only the phase the owner explicitly requests. A local commit and push have separate authorization. At the end of a refactor phase say “fai commit prima che inzii con una nuova fase refactor”.

## Java style

- Use explicit local types and imports. Use `var` only in a lambda if it improves readability. Use lowercase `instance` for singleton fields and `final` for immutable fields. Prefer static factories and stateless utilities. Use switch expressions with `->` and comma-separated cases, `List.of` or `Arrays.asList` for static lists, and a loop when it is clearer than a stream. Keep operational classes free of Lombok, new DI frameworks and `Optional` wrappers.
- Preserve field and constructor groups, blank lines and ASCII banners. Apply the same spacing to new code. Separate guards, preparation, loading, transformation and return. Put the body of `if`, `else`, `for` and `while` on the next indented line, even for one statement. Keep related declarations together.
- In services order constants, fields and constructors, public methods, one large separator and private methods. Reserve further comments for invariants and external behavior. In handlers get typed context first, make the cases visible and delegate actions to their owners. In analytics separate accumulation and cursor handling from response assembly.

## Patterns to reuse

For a shared edit, keep the entry point and mutation short. A single persistent setter can save directly. For a multi-field edit of one alert, use the approved pattern:

```java
boolean saved = GuildCache.withGuild(guildId, guild -> {
    AlertData edit = guild.getAlert(AlertType.LEAVE).deferEdit();

    edit.setMessage(message);
    edit.setPrivateMessage(privateMessage);

    return edit.update();
});
```

- Bot and API share the cache and model. Bulk reads load only missing IDs and coordinate overlapping requests. Keep independent settings outcomes per block or item. The deferred copy writes explicit SQL for its block and publishes after commit. Avoid generic field-to-column maps and a single transaction for the whole settings request.
- Keep existing command and player entry points. For audio, callers use `TrackScheduler.play(track, type)` and `TrackScheduler.stop()`. An overlap change belongs behind those entry points. Follow the effect callback and verify that Stop covers music and effects before changing it. Declare Discord command options and help in `rsc/commands.json`.
- Keep canonical LoL models in `lol.model`. Use `Summoner`, `Rank`, `Mastery`, and string `riotId`. `SummonerView` is the complete profile and `SummonerLeaderboard` wraps it. Lists and detail both use complete `Match` and `Participant` with bans. Only detail includes timeline events. `MatchResult` is internal. Spring owns HTTP concerns. Review the affected API surface for LoL data or behavior changes.

## JavaScript style

- For Custom Elements order static constants, getters/setters, static factory, constructor, lifecycle callbacks, then other methods. Use getters for changing DOM references and avoid private `#` fields. Prefer `async`/`await` and handle async lifecycle errors.
- Use CSS for selector-driven state. Use JavaScript for dynamic numeric CSS variables when needed. Use one `innerHTML` assignment for assembled markup and `res.text()` with `DOMParser` for HTML fetches where the integration uses parsed markup.

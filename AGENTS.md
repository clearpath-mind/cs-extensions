# Agent workflow — cs-extensions

## Build policy: NEVER build locally

- Do NOT run `./gradlew` builds (`makePluginsJson`, `assemble`, etc.) on this machine.
- Push to GitHub and let the `Build` GitHub Actions workflow compile.
- Verify with the `gh` CLI:
  - `gh run list --workflow Build --limit 5`
  - `gh run watch <run-id>` (wait for finish)
  - `gh run view <run-id> --log` (inspect failures)
- Read-only local checks are fine: `read`, `git status`, `git diff`.

## CI notes (`.github/workflows/build.yml`)

- Triggers on push to `main` (`*.md` and `YacineTV/team_aliases.json` changes ignored) + `workflow_dispatch`.
- Needs JDK 17 and the `TMDB_API` secret (written to `local.properties` in CI).
- Artifacts (`*.cs3`, `plugins.json`, `repo.json`) are force-pushed to the `builds` branch.

## Versioning

- Bump `version = N` in the plugin's `build.gradle.kts` on EVERY functional change
  (e.g. `YacineTV/build.gradle.kts`). Convention: `git commit` message
  `"<Plugin> v<N>: <what changed>"`.
- `team_aliases.json` (YacineTV) is fetched at RUNTIME from `main` on every cold
  start — alias-only updates need NO version bump and trigger NO build
  (CI skips them via `paths-ignore`); just commit and push.

## YacineTV team aliases

- New fixtures often bring new Yacine team ids. Diff live `/events`
  `team_1`/`team_2` ids against `YacineTV/team_aliases.json` and add missing ones.
- Alias value MUST equal the exact TheSportsDB `strTeam`
  (provider does exact match; e.g. `57` → `"AC Milan"`, not `"Milan"`;
  `88` → `"Lyon"`, not `"Olympique Lyonnais"`).
- Verify each alias via `searchteams.php?t=<alias>` AND `searchevents.php`
  day-matching `strThumb` for the fixture pairing (both team orders).

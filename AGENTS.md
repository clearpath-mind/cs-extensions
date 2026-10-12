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

- Triggers on push to `main` (`*.md` changes ignored) + `workflow_dispatch`.
- Needs JDK 17 and the `TMDB_API` secret (written to `local.properties` in CI).
- Artifacts (`*.cs3`, `plugins.json`, `repo.json`) are force-pushed to the `builds` branch.

## Versioning

- Bump `version = N` in the plugin's `build.gradle.kts` on EVERY functional change
  (e.g. `YacineTV/build.gradle.kts`). Convention: `git commit` message
  `"<Plugin> v<N>: <what changed>"`.

## Upstream CloudStream source (extractor/CF debugging)

- The `com.lagradost:cloudstream3:pre-release` jar hides extractor flows and
  mirror lists. Read the real source on GitHub with the `gh` CLI:
  - `gh api repos/recloudstream/cloudstream/contents/<path> --jq '.[].name'`
    (list dir, e.g. `.../extractors`)
  - `gh api repos/recloudstream/cloudstream/contents/<file> --jq '.content' | base64 -d`
    (read file)
  - `gh search code "<symbol>" --repo recloudstream/cloudstream --json path`
    (locate, e.g. `WebViewResolver`)
- Proven findings to reuse, don't re-derive:
  - Mirror families live in upstream subclasses (`DoodExtractor.kt`:
    `Dsvplay`/`Ds2play`/`Playmogo`/…; `StreamWishExtractor.kt`: `Hgcloudto`/…).
    Pin same-host `mainUrl` per mirror for playback Referer.
  - `WebViewResolver` (library, usable from plugins): real device WebView UA
    ("setting user agent will make cloudflare break"), `/cdn-cgi/` passthrough,
    `useOkhttp=false` for CF hosts. `CloudflareKiller` is app-module (NOT
    accessible from extensions).
  - Upstream `StreamWishExtractor` already has packed-parse + 15s WebView
    fallback; route wish-family hosts to a same-host subclass before custom
    WebView sniffing.

## Local reference repos (`/home/imad/Projects/cs-repos`, read-only)

- `StreamPlay/` (`com.Phisher98`) — architecture template Streamly mirrors.
  Port patterns from here, don't re-derive: `StreamPlayCache.kt`
  (adaptive timeout + circuit breaker → `StreamlyCache.kt`),
  `StreamPlayConcurrency.kt` (→ `StreamlyConcurrency.kt`),
  `ProvidersList.kt`, `Extractors.kt`.
- `re-3arabi/` — per-site provider implementations. Streamly's
  FaselHD/MyCima/EgyDead/TopCinema logic tracks `Faselhd/`,
  `MyCimaProvider/`, `Egydead/`, `Topcinema/`, `Wecima/` respectively.
  Check here first when a site changes selectors, headers, or AJAX flows
  (e.g. client-hint + fetch-metadata headers were ported from re-3arabi Faselhd).
- `CricifyProvider/` (`com.cncverse`) — live-sports provider
  (Firebase Remote Config, crypto utils, event manager). Reference for live
  event patterns only, not VOD extractor work.

## Extension ecosystem reference (CloudStream Wiki)

- Full list of maintained repos + plugins lives here, don't copy it into
  this file: `https://cloudstream.miraheze.org/wiki/List_of_extensions`
  (plugin list updated 12 Oct 2026).
- Key: `⭐` = works well, `🛑` = anti-features (closed-source/ads),
  `~~strikethrough~~` = disabled/broken.
- Directly relevant to this repo (`Streamly/`, `YacineTV/`, `FreeSidePlus/`):
  - `re-3arabi` (`arb` / `343`, open) — `github.com/Abodabodd/re-3arabi` —
    Arabic VOD, tracks FaselHD/MyCima/EgyDead/TopCinema.
  - `Phisher Repo` (`phisherrepo` / `864`, 🛑 closed-source) —
    `github.com/phisher98/cloudstream-extensions-phisher` — StreamPlay
    template source, behavior-only reference.
  - `CNC Verse` (`cncv`, 🛑 closed-source) —
    `github.com/NivinCNC/CNCVerse-Cloud-Stream-Extension` — Cricify /
    live-sports pattern reference.
  - Official `CloudStream providers repository` (`cspr` / `0094`) —
    `github.com/recloudstream/extensions`.
  - `Mega repository` (`megarepo` / `3737`) — bulk-installs all repos for
    testing.
- Workflow: before adding a new provider, check the wiki for an existing
  implementation to port from. Prefer open-source repos for code reading;
  use closed-source repos for behavior comparison only.
- See also: `List of unmaintained repositories` on same wiki +
  `https://rentry.org/cs3-repos` for complete list.

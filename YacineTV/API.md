# YacineTV API responses (verified 2026-09-10)

Base URLs (tried in order):
- `https://def.ycnapi.com/api` (primary)
- `https://deft.yacinelive.com/api` (fallback)

All responses are Base64 + XOR-encrypted. Decrypt with
`key = "c!xZj+N9&G@Ev@vw" + response.header["t"]`, then
`plain[i] = decoded[i] XOR key[i % key.length]`.
Request header: `User-Agent: okhttp/4.12.0`, timeout 10s.
Stream `?t=...&e=...` tokens expire quickly (values below redacted).

Wrong paths return 404: `/matches`, `/match`, `/games`, `/channels`,
`/event` (no id), `/matches/today`. The matches endpoint is
`/events` + `/event/{id}` (same as old `yacinelive.com/api/events`).

## `GET /categories`

12 categories. `beIN SPORTS (1080P/720P/360P/244P)` (ids 4,5,6,7) are
merged by the provider into one `beIN SPORTS` row (20 channels).

```json
{"data": [
  {"child_count": 0, "id": 4, "logo": "", "name": "beIN SPORTS (1080P)"},
  {"child_count": 0, "id": 5, "logo": "", "name": "beIN SPORTS (720P)"},
  {"child_count": 0, "id": 6, "logo": "", "name": "beIN SPORTS (360P)"},
  {"child_count": 0, "id": 7, "logo": "", "name": "beIN SPORTS (244P)"},
  {"child_count": 0, "id": 8, "logo": "", "name": "beIN ENTERTAINMENT"},
  {"child_count": 20, "id": 9, "logo": "", "name": "ARABIC CHANNELS"},
  {"child_count": 0, "id": 11, "logo": "", "name": "MBC CHANNELS"},
  {"child_count": 0, "id": 12, "logo": "", "name": "FRANCE CHANNELS"},
  {"child_count": 0, "id": 88, "logo": "", "name": "TURKISH CHANNELS"},
  {"child_count": 0, "id": 13, "logo": "", "name": "KIDS CHANNELS"},
  {"child_count": 0, "id": 87, "logo": "", "name": "WEYYAK"},
  {"child_count": 0, "id": 94, "logo": "", "name": "SHAHID VIP"}
], "vt": 0}
```

## `GET /categories/4/channels` (20 items, first 2 shown)

Same channel names repeat in ids 4/5/6/7 with different channel ids,
e.g. `beIN SPORTS 1` = `(4→1424, 5→4, 6→24, 7→44)`.

```json
{"data": [
  {"id": 1424, "is_hide": 0,
   "logo": "https://assets.bein.com/mena/sites/4/2015/06/beIN_SPORTS1_DIGITAL_Mono.png",
   "name": "beIN SPORTS 1", "priority": 5},
  {"id": 1425, "is_hide": 0,
   "logo": "https://assets.bein.com/mena/sites/4/2021/02/beIN_SPORTS2_DIGITAL_Mono.png",
   "name": "beIN SPORTS 2", "priority": 5}
]}
```

## `GET /channel/1424` (beIN SPORTS 1)

```json
{"data": [{
  "drm": null, "event_channel_id": null,
  "headers": {
    "Referer": "https://x.com/",
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
  },
  "name": "HD",
  "referer": "https://x.com/",
  "url": "http://re.new-redirect.online/live/918454578001/index.m3u8?t=<token>&e=<exp>",
  "url_type": 3,
  "user_agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
}]}
```

## `GET /events` (8 items live, first shown)

```json
{"data": [{
  "champions": "دوري أبطال آوروبا",
  "channel": "beIN SPORTS 1",
  "commentary": "حفيظ دراجي",
  "end_time": 1789065900, "start_time": 1789058700,
  "id": 2863227001,
  "team_1": {"id": 322,
    "logo": "https://ssl.gstatic.com/onebox/media/sports/logos/HOQreZgFrmtQiLi8HKb9zA_96x96.png",
    "name": "فنربخشه"},
  "team_2": {"id": 66,
    "logo": "https://ssl.gstatic.com/onebox/media/sports/logos/BQdP4jUBFJfG7U_JBsFIMg_96x96.png",
    "name": "روما"}
}]}
```

## Provider-side fallbacks (not API)

- **Morocco/SNRT** (`snrtlive.ma` pages, `url_type` 5): page HTML contains
  `snrt.player.easybroadcast.io/events/{slug}` iframe;
  `GET https://snrt.player.easybroadcast.io/api/events/{slug}` returns
  `{"stream": "...playlist_dvr.m3u8", "stream_no_timeshift": "...playlist.m3u8"}`.
- **MBC**: when `channel/{id}` is empty or only dead embeds, the provider
  falls back to verified iptv-org EdgeNext CDN streams
  (`shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-*`), including the USA
  feed for MBC 3. MBC 2 / Action / Max have no reachable public stream
  (Free-TV restreams time out from both CI and device) and were removed.

## Homepage pagination (no duplicates)

All rows fit on one page (no API pagination). `getMainPage` answers
single-row drill-ins by `request.name` and returns `hasNext = false`;
`page > 1` returns an empty list so end-of-list scrolling never appends
the same items twice.

## Match banners

`Today's Matches` cards use on-device 1280x720 composite banners —
competition pill top-center, VS pill (64sp) in the middle, crests on
white disc backdrops with shadows (`matchBanner()`/`renderBanner()`),
cached under `cacheDir/yacine_banners` per event id (`match_{id}_v4.png`)
with API-logo fallback.

## Match details

Plot is `شاهد البث المباشر لمباراة {title}`. Competition, kickoff (Latin
digits, `dd/MM - HH:mm`), commentator, and broadcast channel — in that
order — ride in `LinkData` and render as tags on the detail page. The
detail hero shows the same homepage banner thumbnail.

## Recommendations

Card `LinkData` carries up to 12 row siblings (matches → other matches,
channels → same-row channels) in `related`; `load()` exposes them as
`recommendations` with zero extra network. Channel plots use
`شاهد البث المباشر لقناة {name}` everywhere.

## Dead masters

Tokenized multivariant masters (`shahid ?t=&e=`) whose variants 404 (stale
CDN cache) are never emitted — the player would only error with "M3u8 must
contain TS files". Media playlists (no variants) still emit raw. Verified
Kids backups (Spacetoon/Taha/Atfal) cover the fallbacks that exist; CN
Arabic, Gulli Arabic, Rotana Kids, Disney XD have no public backup.
## `GET /event/2863227001` (also `/event/{id}/servers`, same list)


```json
{"data": [
  {"drm": null, "event_channel_id": 40,
   "headers": {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"},
   "name": "HD", "referer": "",
   "url": "http://re.new-redirect.online/live/918454578001/index.m3u8?t=<token>&e=<exp>",
   "url_type": 3,
   "user_agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"},
  {"drm": null, "event_channel_id": 40,
   "headers": {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"},
   "name": "SD", "referer": "",
   "url": "http://re.new-redirect.online/live/978480008877005/index.m3u8?t=<token>&e=<exp>",
   "url_type": 3,
   "user_agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"},
  {"drm": null, "event_channel_id": 40,
   "headers": {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"},
   "name": "Low", "referer": "",
   "url": "http://re.new-redirect.online/live/31009988005/index.m3u8?t=<token>&e=<exp>",
   "url_type": 3,
   "user_agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"}
]}
```

## Provider-side fallbacks (not API)

- **Morocco/SNRT** (`snrtlive.ma` pages, `url_type` 5): page HTML contains
  `snrt.player.easybroadcast.io/events/{slug}` iframe;
  `GET https://snrt.player.easybroadcast.io/api/events/{slug}` returns
  `{"stream": "...playlist_dvr.m3u8", "stream_no_timeshift": "...playlist.m3u8"}`.
- **MBC**: when `channel/{id}` is empty or only dead embeds, the provider
  falls back to verified iptv-org EdgeNext CDN streams
  (`shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-*`), including the USA
  feed for MBC 3. MBC 2 / Action / Max have no reachable public stream
  (Free-TV restreams time out from both CI and device) and were removed.
- **Morocco**: 2M (`channel/546`) is a direct m3u8 and plays; the 7 SNRT
  channels (`snrtlive.ma` pages, `url_type` 5) resolve via the EasyBroadcast
  slug (`extractEasyBroadcastSlug` tries several markup variants) and the
  stream URL is signed via `GET https://token.easybroadcast.io/all?url=...`
  (CDN has `token_authentication: true`; unsigned playlists 403). Players
  drop the `?token` query on relative variant URLs, so the provider emits
  the signed best variant (`bestSignedVariant()`, highest `BANDWIDTH`);
  `.ts` segments play ungated. Generic extractor attempt remains as last
  resort. Medi 1 embeds serve a JWPlayer page with no static file URL, so
  the provider maps the 3 Medi 1 channels straight to their EasyBroadcast
  CDN bases (`medi1Streams`, verified) through the same sign → best-variant
  pipeline. Télé Maroc is a multi-iframe portal, so it goes through the
  extractor registry and may fail upstream.
- **Morocco thumbnails**: SNRT channels use official snrtlive.ma vignette
  arts (`moroccoThumbs`, verified 200); 2M uses the Wikimedia `2M_TV_logo`
  (verified 200); other Morocco entries keep API logos.
- **Kids**: most entries are direct m3u8 (`url_type` 3) and play; Almajd
  Kids/Bassma/Rawda embeds are dead upstream (elahmad `Bad Gateway`,
  taghtia redirect chain empty) and have no public direct stream.
- **Logos**: channel posters use the API `logo` fields as-is (no overrides).

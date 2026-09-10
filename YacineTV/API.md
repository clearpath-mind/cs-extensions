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

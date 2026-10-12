package com.streamly

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.util.Locale

/**
 * Subtitle-only link sources ported from StreamPlay (StreamPlayExtractor
 * invokeWYZIESubs / invokeSubtitleAPI). They emit via [subtitleCallback] and
 * never produce video links, so callers must return true when the fetch
 * completes without exception (a zero-result fetch is still a success —
 * only skips and failures return false).
 *
 * Both APIs are keyed by IMDb id, which [LinkData.imdbId] already carries
 * from TMDB external_ids.
 */
private const val SUBTITLES_TAG = "StreamlySubs"
private const val SUBTITLES_API = "https://opensubtitles-v3.strem.io"
private const val WYRIE_SUBS_API = "https://sub.wyzie.ru"

private data class OpenSubItem(
    val id: String,
    val url: String,
    @JsonProperty("SubEncoding") val subEncoding: String? = null,
    val lang: String,
    val m: String? = null,
    val g: String? = null,
)

private data class OpenSubResponse(
    val subtitles: List<OpenSubItem> = emptyList(),
    val cacheMaxAge: Long = 0,
)

private data class WyzieSubtitle(
    @JsonProperty("url") val url: String,
    @JsonProperty("display") val display: String? = null,
    @JsonProperty("language") val language: String? = null,
)

/** Wyzie access key, set from the Subtitles settings row (empty = skip). */
private fun wyzieKey(): String? =
    Streamly.companionSharedPref?.getString("wyzie_key", null)?.takeIf { it.isNotBlank() }

/** OpenSubtitles-strem.io subtitle dump for an IMDb id (movie or S/E). */
suspend fun invokeSubtitleApi(
    imdbId: String?,
    season: Int?,
    episode: Int?,
    subtitleCallback: (SubtitleFile) -> Unit,
): Boolean {
    if (imdbId.isNullOrBlank()) return false
    return runCatching {
        val url = if (season == null) {
            "$SUBTITLES_API/subtitles/movie/$imdbId.json"
        } else {
            "$SUBTITLES_API/subtitles/series/$imdbId:$season:$episode.json"
        }
        val res = app.get(
            url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
            ),
            timeout = 10000,
        )
        if (res.code != 200) return false
        val subs = runCatching { parseJson<OpenSubResponse>(res.text).subtitles }.getOrNull().orEmpty()
        subs.forEach {
            val label = subtitleLanguage(it.lang)
                .replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale.getDefault()) else c.toString() }
            subtitleCallback(newSubtitleFile(label, it.url))
        }
        Log.d(SUBTITLES_TAG, "SubtitleAPI $imdbId s=${season}e=${episode} -> ${subs.size} subs")
        true
    }.getOrDefault(false)
}

/** Wyzie subtitle search (needs a user key from settings). */
suspend fun invokeWyzieSubs(
    imdbId: String?,
    season: Int?,
    episode: Int?,
    subtitleCallback: (SubtitleFile) -> Unit,
): Boolean {
    val key = wyzieKey() ?: return false
    if (imdbId.isNullOrBlank()) return false
    return runCatching {
        val url = if (season != null) {
            "$WYRIE_SUBS_API/search?id=$imdbId&season=$season&episode=$episode&source=all&key=$key"
        } else {
            "$WYRIE_SUBS_API/search?id=$imdbId&source=all&key=$key"
        }
        val text = app.get(url, timeout = 10000).text
        val subs = runCatching { parseJson<Array<WyzieSubtitle>>(text) }.getOrNull().orEmpty()
        subs.forEach { sub ->
            val lang = sub.language ?: return@forEach
            subtitleCallback(newSubtitleFile(sub.display ?: subtitleLanguage(lang), sub.url))
        }
        Log.d(SUBTITLES_TAG, "Wyzie $imdbId s=${season}e=${episode} -> ${subs.size} subs")
        true
    }.getOrDefault(false)
}

/** ISO code (or Wyzie display string) -> English label (StreamPlay map). */
fun subtitleLanguage(code: String): String {
    val lower = code.lowercase()
    return subtitleLanguageMap.entries.firstOrNull { lower in it.value }?.key ?: "UnKnown"
}

private val subtitleLanguageMap: Map<String, Set<String>> = mapOf(
    "Afrikaans" to setOf("af", "afr"),
    "Albanian" to setOf("sq", "sqi", "alb"),
    "Amharic" to setOf("am", "amh"),
    "Arabic" to setOf("ar", "ara"),
    "Armenian" to setOf("hy", "hye", "arm"),
    "Azerbaijani" to setOf("az", "aze"),
    "Basque" to setOf("eu", "eus", "baq"),
    "Belarusian" to setOf("be", "bel"),
    "Bengali" to setOf("bn", "ben"),
    "Bosnian" to setOf("bs", "bos"),
    "Bulgarian" to setOf("bg", "bul"),
    "Catalan" to setOf("ca", "cat"),
    "Chinese" to setOf("zh", "zho", "chi"),
    "Croatian" to setOf("hr", "hrv", "scr"),
    "Czech" to setOf("cs", "ces", "cze"),
    "Danish" to setOf("da", "dan"),
    "Dutch" to setOf("nl", "nld", "dut"),
    "English" to setOf("en", "eng"),
    "Estonian" to setOf("et", "est"),
    "Filipino" to setOf("tl", "tgl"),
    "Finnish" to setOf("fi", "fin"),
    "French" to setOf("fr", "fra", "fre"),
    "Galician" to setOf("gl", "glg"),
    "Georgian" to setOf("ka", "kat", "geo"),
    "German" to setOf("de", "deu", "ger"),
    "Greek" to setOf("el", "ell", "gre"),
    "Gujarati" to setOf("gu", "guj"),
    "Hebrew" to setOf("he", "heb"),
    "Hindi" to setOf("hi", "hin"),
    "Hungarian" to setOf("hu", "hun"),
    "Icelandic" to setOf("is", "isl", "ice"),
    "Indonesian" to setOf("id", "ind"),
    "Italian" to setOf("it", "ita"),
    "Japanese" to setOf("ja", "jpn"),
    "Kannada" to setOf("kn", "kan"),
    "Kazakh" to setOf("kk", "kaz"),
    "Korean" to setOf("ko", "kor"),
    "Latvian" to setOf("lv", "lav"),
    "Lithuanian" to setOf("lt", "lit"),
    "Macedonian" to setOf("mk", "mkd", "mac"),
    "Malay" to setOf("ms", "msa", "may"),
    "Malayalam" to setOf("ml", "mal"),
    "Maltese" to setOf("mt", "mlt"),
    "Marathi" to setOf("mr", "mar"),
    "Mongolian" to setOf("mn", "mon"),
    "Nepali" to setOf("ne", "nep"),
    "Norwegian" to setOf("no", "nor"),
    "Persian" to setOf("fa", "fas", "per"),
    "Polish" to setOf("pl", "pol"),
    "Portuguese" to setOf("pt", "por"),
    "Punjabi" to setOf("pa", "pan"),
    "Romanian" to setOf("ro", "ron", "rum"),
    "Russian" to setOf("ru", "rus"),
    "Serbian" to setOf("sr", "srp", "scc"),
    "Sinhala" to setOf("si", "sin"),
    "Slovak" to setOf("sk", "slk", "slo"),
    "Slovenian" to setOf("sl", "slv"),
    "Spanish" to setOf("es", "spa"),
    "Swahili" to setOf("sw", "swa"),
    "Swedish" to setOf("sv", "swe"),
    "Tamil" to setOf("ta", "tam"),
    "Telugu" to setOf("te", "tel"),
    "Thai" to setOf("th", "tha"),
    "Turkish" to setOf("tr", "tur"),
    "Ukrainian" to setOf("uk", "ukr"),
    "Urdu" to setOf("ur", "urd"),
    "Uzbek" to setOf("uz", "uzb"),
    "Vietnamese" to setOf("vi", "vie"),
    "Welsh" to setOf("cy", "cym", "wel"),
    "Yiddish" to setOf("yi", "yid"),
)

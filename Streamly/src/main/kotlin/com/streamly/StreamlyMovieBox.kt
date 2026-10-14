package com.streamly

import android.util.Base64
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val MOVIEBOX_TAG = "MovieBox"
private const val MOVIEBOX_HOST = "https://api3.aoneroom.com"

private val movieBoxRandom = SecureRandom()
private val movieBoxMapper = jacksonObjectMapper()

private val movieBoxSecretKeyDefaultB64 = "NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw=="
private val movieBoxSecretKeyAltB64 = "WHFuMm5uTzQxL0w5Mm8xaXVYaFNMSFRiWHZZNFo1Wlo2Mm04bVNMQQ=="

private fun movieBoxSecretKey(useAlt: Boolean = false): ByteArray {
    val b64 = if (useAlt) movieBoxSecretKeyAltB64 else movieBoxSecretKeyDefaultB64
    return Base64.decode(b64, Base64.DEFAULT)
}

private val movieBoxDeviceId: String by lazy {
    ByteArray(16).also { movieBoxRandom.nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
}

private var movieBoxBearerToken: String? = null

private fun decodeJwtExpiry(token: String): Long {
    return try {
        val payload = token.split(".").getOrNull(1) ?: return 0L
        val padded = payload.replace("-", "+").replace("_", "/")
            .let { it + "=".repeat((4 - it.length % 4) % 4) }
        val json = Base64.decode(padded, Base64.DEFAULT).toString(Charsets.UTF_8)
        JSONObject(json).getLong("exp")
    } catch (_: Exception) {
        0L
    }
}

private fun isMovieBoxTokenValid(token: String?): Boolean {
    if (token.isNullOrBlank()) return false
    val exp = decodeJwtExpiry(token)
    return exp > System.currentTimeMillis() / 1000 + 3600
}

private suspend fun getMovieBoxToken(): String? {
    if (isMovieBoxTokenValid(movieBoxBearerToken)) return movieBoxBearerToken
    val url = "$MOVIEBOX_HOST/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=1"
    val xClientToken = generateMovieBoxXClientToken()
    val xTrSignature = generateMovieBoxXTrSignature("GET", "application/json", "application/json", url)
    val headers = mapOf(
        "user-agent" to "com.community.oneroom/50020088 (Linux; U; Android 13; en_US; Subsystem for Android(TM); Build/TQ3A.230901.001; Cronet/145.0.7582.0)",
        "accept" to "application/json",
        "content-type" to "application/json",
        "x-client-token" to xClientToken,
        "x-tr-signature" to xTrSignature,
        "x-client-info" to """{"package_name":"com.community.oneroom","version_name":"3.0.13.0325.03","version_code":50020088,"os":"android","os_version":"13","install_ch":"ps","device_id":"$movieBoxDeviceId","install_store":"ps","gaid":"1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d","brand":"Windows","model":"Subsystem for Android(TM)","system_language":"en","net":"NETWORK_WIFI","region":"US","timezone":"Asia/Calcutta","sp_code":""}""",
        "x-client-status" to "0",
    )
    return try {
        val response = app.get(url, headers = headers, timeout = 15000)
        val xUser = response.headers["x-user"].orEmpty().ifBlank { response.headers["X-User"].orEmpty() }
        val token = if (!xUser.isNullOrBlank()) {
            runCatching { movieBoxMapper.readTree(xUser)["token"]?.asText() }.getOrNull()
        } else null
        if (token != null && isMovieBoxTokenValid(token)) {
            movieBoxBearerToken = token
            token
        } else {
            movieBoxBearerToken
        }
    } catch (_: Exception) {
        movieBoxBearerToken
    }
}

fun generateMovieBoxXClientToken(hardcodedTimestamp: Long? = null): String {
    val timestamp = (hardcodedTimestamp ?: System.currentTimeMillis()).toString()
    val reversed = timestamp.reversed()
    val hash = md5ByteArray(reversed.toByteArray())
    return "$timestamp,$hash"
}

fun generateMovieBoxXTrSignature(
    method: String,
    accept: String? = "application/json",
    contentType: String? = "application/json",
    url: String,
    body: String? = null,
    useAltKey: Boolean = false,
    hardcodedTimestamp: Long? = null,
): String {
    val timestamp = hardcodedTimestamp ?: System.currentTimeMillis()
    val canonical = buildMovieBoxCanonicalString(method, accept, contentType, url, body, timestamp)
    val secretBytes = movieBoxSecretKey(useAltKey)
    val mac = Mac.getInstance("HmacMD5").apply { init(SecretKeySpec(secretBytes, "HmacMD5")) }
    val rawSignature = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
    val signatureBase64 = Base64.encodeToString(rawSignature, Base64.NO_WRAP)
    return "$timestamp|2|$signatureBase64"
}

private fun buildMovieBoxCanonicalString(
    method: String,
    accept: String?,
    contentType: String?,
    url: String,
    body: String?,
    timestamp: Long,
): String {
    val uri = runCatching { java.net.URI(url) }.getOrNull()
    val path = uri?.path.orEmpty()
    val params = uri?.query?.split("&").orEmpty().mapNotNull { part ->
        val idx = part.indexOf("=")
        if (idx <= 0) null else part.substring(0, idx) to part.substring(idx + 1)
    }.groupBy({ it.first }, { it.second })
    val query = params.keys.sorted().joinToString("&") { key ->
        params.getValue(key).joinToString("&") { value -> "$key=$value" }
    }
    val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path
    val bodyBytes = body?.toByteArray(Charsets.UTF_8)
    val bodyHash = if (bodyBytes != null) {
        val trimmed = if (bodyBytes.size > 102400) bodyBytes.copyOfRange(0, 102400) else bodyBytes
        md5ByteArray(trimmed)
    } else ""
    val bodyLength = bodyBytes?.size?.toString() ?: ""
    return "${method.uppercase()}\n" +
        "${accept ?: ""}\n" +
        "${contentType ?: ""}\n" +
        "$bodyLength\n" +
        "$timestamp\n" +
        "$bodyHash\n" +
        canonicalUrl
}

private fun md5ByteArray(input: ByteArray): String {
    return MessageDigest.getInstance("MD5").digest(input).joinToString("") { "%02x".format(it) }
}

/** MovieBox ported from StreamPlay (v689). */
suspend fun invokeMovieBox(
    res: LinkData,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val title = res.title ?: return false
    val token = getMovieBoxToken() ?: return false
    var foundLinks = false
    val season = res.season ?: 0
    val episode = res.episode ?: 0

    try {
        val url = "$MOVIEBOX_HOST/wefeed-mobile-bff/subject-api/search/v2"
        val jsonBody = """{"page":1,"perPage":10,"keyword":"$title"}"""
        val xClientToken = generateMovieBoxXClientToken()
        val xTrSignature = generateMovieBoxXTrSignature(
            "POST",
            "application/json",
            "application/json; charset=utf-8",
            url,
            jsonBody,
        )
        val headers = mapOf(
            "user-agent" to "com.community.oneroom/50020088 (Linux; U; Android 13; en_US; Subsystem for Android(TM); Build/TQ3A.230901.001; Cronet/145.0.7582.0)",
            "accept" to "application/json",
            "content-type" to "application/json",
            "x-client-token" to xClientToken,
            "x-tr-signature" to xTrSignature,
            "x-client-info" to """{"package_name":"com.community.oneroom","version_name":"3.0.13.0325.03","version_code":50020088,"os":"android","os_version":"13","install_ch":"ps","device_id":"$movieBoxDeviceId","install_store":"ps","gaid":"1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d","brand":"Windows","model":"Subsystem for Android(TM)","system_language":"en","net":"NETWORK_WIFI","region":"US","timezone":"Asia/Calcutta","sp_code":"","X-Play-Mode":"1","X-Idle-Data":"1","X-Family-Mode":"0","X-Content-Mode":"0"}""",
            "x-client-status" to "0",
            "Authorization" to "Bearer $token",
        )

        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
        val response = app.post(url, headers = headers, requestBody = requestBody, timeout = 15000)
        if (response.code != 200) return false

        val root = movieBoxMapper.readTree(response.text)
        val results = root["data"]?.get("results") ?: return false
        val matchingIds = mutableListOf<String>()
        for (result in results) {
            val subjects = result["subjects"] ?: continue
            for (subject in subjects) {
                val name = subject["title"]?.asText() ?: continue
                val id = subject["subjectId"]?.asText() ?: continue
                val type = subject["subjectType"]?.asInt() ?: 0
                if (name.contains(title, ignoreCase = true) && (type == 1 || type == 2)) {
                    matchingIds.add(id)
                }
            }
        }
        if (matchingIds.isEmpty()) return false

        for (id in matchingIds) {
            try {
                val subjectUrl = "$MOVIEBOX_HOST/wefeed-mobile-bff/subject-api/get?subjectId=$id"
                val subjectXToken = generateMovieBoxXClientToken()
                val subjectXSign = generateMovieBoxXTrSignature("GET", "application/json", "application/json", subjectUrl)
                val subjectHeaders = headers + mapOf(
                    "x-client-token" to subjectXToken,
                    "x-tr-signature" to subjectXSign,
                    "Authorization" to "Bearer $token",
                )
                val subjectRes = app.get(subjectUrl, headers = subjectHeaders, timeout = 15000)
                val xUserHeader = subjectRes.headers["x-user"].orEmpty().ifBlank { subjectRes.headers["X-User"].orEmpty() }
                var authToken: String = token
                if (!xUserHeader.isNullOrBlank()) {
                    val newToken = runCatching { movieBoxMapper.readTree(xUserHeader)["token"]?.asText() }.getOrNull()
                    if (newToken != null && isMovieBoxTokenValid(newToken)) {
                        authToken = newToken
                        movieBoxBearerToken = newToken
                    }
                }
                if (subjectRes.code != 200) continue

                val subjectJson = movieBoxMapper.readTree(subjectRes.text)
                val subjectData = subjectJson["data"]
                val subjectIds = mutableListOf<Pair<String, String>>()
                var originalLanguageName = "Original"
                val dubs = subjectData?.get("dubs")
                if (dubs != null && dubs.isArray) {
                    for (dub in dubs) {
                        val dubId = dub["subjectId"]?.asText()
                        val lanName = dub["lanName"]?.asText()
                        if (dubId != null && lanName != null) {
                            if (dubId == id) {
                                originalLanguageName = lanName
                            } else {
                                subjectIds.add(Pair(dubId, lanName))
                            }
                        }
                    }
                }
                subjectIds.add(0, Pair(id, originalLanguageName))

                for ((subjectId, language) in subjectIds) {
                    val playUrl =
                        "$MOVIEBOX_HOST/wefeed-mobile-bff/subject-api/play-info?subjectId=$subjectId&se=$season&ep=$episode"
                    val playToken = generateMovieBoxXClientToken()
                    val playSign = generateMovieBoxXTrSignature("GET", "application/json", "application/json", playUrl)
                    val playHeaders = headers + mapOf(
                        "x-client-token" to playToken,
                        "x-tr-signature" to playSign,
                        "Authorization" to "Bearer $authToken",
                    )

                    val playRes = app.get(playUrl, headers = playHeaders, timeout = 15000)
                    if (playRes.code != 200) continue
                    val playRoot = movieBoxMapper.readTree(playRes.text)
                    val streams = playRoot["data"]?.get("streams") ?: continue
                    if (!streams.isArray) continue

                    for (stream in streams) {
                        val streamId = stream["id"]?.asText() ?: "$subjectId|$season|$episode"
                        val format = stream["format"]?.asText() ?: ""
                        val signCookie = stream["signCookie"]?.asText()?.takeIf { it.isNotEmpty() }
                        val resolutionNodes = stream["resolutionList"] ?: stream["resolutions"]

                        if (resolutionNodes != null && resolutionNodes.isArray) {
                            for (resNode in resolutionNodes) {
                                val resUrl = resNode["resourceLink"]?.asText() ?: continue
                                val quality = resNode["resolution"]?.asInt() ?: 0
                                callback.invoke(
                                    newExtractorLink(
                                        source = "MovieBox ${language.replace("dub", "Audio")}",
                                        name = "MovieBox (${language.replace("dub", "Audio")})",
                                        url = resUrl,
                                        type = when {
                                            resUrl.startsWith("magnet:", true) -> ExtractorLinkType.MAGNET
                                            resUrl.endsWith(".mpd", true) -> ExtractorLinkType.DASH
                                            resUrl.endsWith(".torrent", true) -> ExtractorLinkType.TORRENT
                                            format.equals("HLS", true) || resUrl.endsWith(".m3u8", true) -> ExtractorLinkType.M3U8
                                            else -> INFER_TYPE
                                        }
                                    ) {
                                        this.headers = mapOf("Referer" to MOVIEBOX_HOST) +
                                            (if (signCookie != null) mapOf("Cookie" to signCookie) else emptyMap())
                                        this.quality = getQualityFromName("$quality")
                                    },
                                )
                                foundLinks = true
                            }
                        } else {
                            val singleUrl = stream["url"]?.asText() ?: continue
                            val resText = stream["resolutions"]?.asText() ?: ""
                            callback.invoke(
                                newExtractorLink(
                                    source = "MovieBox ${language.replace("dub", "Audio")}",
                                    name = "MovieBox (${language.replace("dub", "Audio")})",
                                    url = singleUrl,
                                    type = when {
                                        singleUrl.startsWith("magnet:", true) -> ExtractorLinkType.MAGNET
                                        singleUrl.endsWith(".mpd", true) -> ExtractorLinkType.DASH
                                        singleUrl.endsWith(".torrent", true) -> ExtractorLinkType.TORRENT
                                        format.equals("HLS", true) || singleUrl.endsWith(".m3u8", true) -> ExtractorLinkType.M3U8
                                        else -> INFER_TYPE
                                    }
                                ) {
                                    this.headers = mapOf("Referer" to MOVIEBOX_HOST) +
                                        (if (signCookie != null) mapOf("Cookie" to signCookie) else emptyMap())
                                    this.quality = getQualityFromName(resText)
                                },
                            )
                            foundLinks = true
                        }

                        // subtitles
                        val subLinks = listOf(
                            "$MOVIEBOX_HOST/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=$subjectId&streamId=$streamId",
                            "$MOVIEBOX_HOST/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$subjectId&resourceId=$streamId&episode=$episode",
                        )
                        for (subLink in subLinks) {
                            val subToken = generateMovieBoxXClientToken()
                            val subSign = generateMovieBoxXTrSignature("GET", "", "", subLink)
                            val subHeaders = mapOf(
                                "User-Agent" to "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; sdk_gphone64_x86_64; Build/BP22.250325.006; Cronet/133.0.6876.3)",
                                "Accept" to "",
                                "Content-Type" to "",
                                "X-Client-Info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"16","device_id":"da2b99c821e6ea023e4be55b54d5f7d8","install_store":"ps","gaid":"d7578036d13336cc","brand":"google","model":"sdk_gphone64_x86_64","system_language":"en","net":"NETWORK_WIFI","sp_code":""}""",
                                "X-Client-Status" to "0",
                                "x-client-token" to subToken,
                                "x-tr-signature" to subSign,
                                "Authorization" to "Bearer $authToken",
                            )
                            val subRes = app.get(subLink, headers = subHeaders, timeout = 15000)
                            if (subRes.code != 200) continue
                            val subRoot = movieBoxMapper.readTree(subRes.text)
                            val captions = subRoot["data"]?.get("extCaptions")
                            if (captions != null && captions.isArray) {
                                for (caption in captions) {
                                    val captionUrl = caption["url"]?.asText() ?: continue
                                    val lang = caption["language"]?.asText()
                                        ?: caption["lanName"]?.asText()
                                        ?: caption["lan"]?.asText()
                                        ?: "Unknown"
                                    subtitleCallback.invoke(
                                        newSubtitleFile(
                                            url = captionUrl,
                                            lang = "$lang (${language.replace("dub", "Audio")})",
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
                } catch (_: Exception) {
                    // ignore per-item failures
                }
            }

        return foundLinks
    } catch (e: Exception) {
        Log.e(MOVIEBOX_TAG, "failed: ${e.message}")
        return foundLinks
    }
}

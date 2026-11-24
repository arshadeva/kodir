package id.oscarmandala.otacix.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Port Kotlin dari server Ocalix (index.js): cari lagu lewat YouTube InnerTube,
 * ambil URL audio lewat beberapa client (VISIONOS -> ANDROID), cadangan Piped, dan iTunes (pratinjau 30 dtk).
 * Catatan: ini API tidak resmi YouTube, bisa berubah/berhenti sewaktu-waktu.
 */
object Yt {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private const val KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private val JSON_MT = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).build()
    private val quick = client.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()

    /** Alamat API Piped milikmu sendiri (opsional), mis. listOf("https://pipedapi.domainmu.com") */
    @Volatile var customPiped: List<String> = emptyList()

    private class Resp(val code: Int, val body: String)

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { cont.resume(response) }
        })
    }

    private suspend fun http(c: OkHttpClient, url: String, headers: Map<String, String> = emptyMap(), body: String? = null): Resp {
        val rb = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> rb.header(k, v) }
        if (body != null) rb.post(body.toRequestBody(JSON_MT))
        c.newCall(rb.build()).await().use { r ->
            val text = withContext(Dispatchers.IO) { r.body?.string().orEmpty() }
            return Resp(r.code, text)
        }
    }

    // ------------------------------------------------------------------ PENCARIAN
    private val searchCache = ConcurrentHashMap<String, Pair<Long, SearchResult>>()

    suspend fun search(q: String): SearchResult {
        val key = q.trim().lowercase()
        searchCache[key]?.let { if (System.currentTimeMillis() - it.first < 600_000) return it.second }
        var items = emptyList<Track>()
        var src = "youtube"
        try { items = youtube(q) } catch (e: CancellationException) { throw e } catch (e: Exception) { }
        if (items.isEmpty()) {
            src = "preview"
            try { items = itunes(q) } catch (e: CancellationException) { throw e } catch (e: Exception) { }
        }
        val res = SearchResult(items, src)
        if (items.isNotEmpty()) searchCache[key] = System.currentTimeMillis() to res
        return res
    }

    private suspend fun youtube(q: String): List<Track> {
        val ctx = JSONObject().put("client", JSONObject()
            .put("clientName", "WEB").put("clientVersion", "2.20240726.00.00").put("hl", "id").put("gl", "ID"))
        val body = JSONObject().put("context", ctx).put("query", q).put("params", "EgIQAQ==").toString()
        val r = http(client, "https://www.youtube.com/youtubei/v1/search?prettyPrint=false&key=$KEY",
            mapOf("Origin" to "https://www.youtube.com"), body)
        val secs = JSONObject(r.body).getJSONObject("contents").getJSONObject("twoColumnSearchResultsRenderer")
            .getJSONObject("primaryContents").getJSONObject("sectionListRenderer").getJSONArray("contents")
        val out = ArrayList<Track>()
        for (a in 0 until secs.length()) {
            val items = secs.getJSONObject(a).optJSONObject("itemSectionRenderer")?.optJSONArray("contents") ?: continue
            for (b in 0 until items.length()) {
                val v = items.getJSONObject(b).optJSONObject("videoRenderer") ?: continue
                val lt = v.optJSONObject("lengthText")?.optString("simpleText").orEmpty()
                if (lt.isEmpty()) continue
                val d = lt.split(":").fold(0) { acc, s -> acc * 60 + (s.toIntOrNull() ?: 0) }
                if (d <= 0 || d > 1500) continue
                val id = v.getString("videoId")
                val runs = v.getJSONObject("title").getJSONArray("runs")
                val title = (0 until runs.length()).joinToString("") { runs.getJSONObject(it).optString("text") }
                val artist = v.optJSONObject("ownerText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                    .orEmpty().removeSuffix(" - Topic")
                out += Track(id, title, artist, d, "https://i.ytimg.com/vi/$id/hqdefault.jpg")
            }
        }
        return out
    }

    private suspend fun itunes(q: String): List<Track> {
        val r = http(client, "https://itunes.apple.com/search?entity=song&limit=25&term=" + URLEncoder.encode(q, "UTF-8"))
        val arr = JSONObject(r.body).getJSONArray("results")
        val out = ArrayList<Track>()
        for (i in 0 until arr.length()) {
            val x = arr.getJSONObject(i)
            val prev = x.optString("previewUrl")
            if (prev.isEmpty()) continue
            out += Track("it" + x.optLong("trackId"), x.optString("trackName"), x.optString("artistName"), 30,
                x.optString("artworkUrl100").replace("100x100", "600x600"), prev)
        }
        return out
    }

    // ------------------------------------------------------------------ URL AUDIO
    class Stream(val url: String, val ua: String, val t: Long)

    private class VC(val n: Int, val ua: String, val name: String, val version: String, val extra: () -> JSONObject)

    private val clients = listOf(
        VC(101, "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15",
            "VISIONOS", "1.02") {
            JSONObject().put("deviceMake", "Apple").put("deviceModel", "RealityDevice17,1")
                .put("osName", "visionOS").put("osVersion", "26.5.23O471")
        },
        VC(3, "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip", "ANDROID", "20.10.38") {
            JSONObject().put("androidSdkVersion", 30).put("osName", "Android").put("osVersion", "11")
        },
    )

    private val streams = ConcurrentHashMap<String, Stream>()
    private val neg = ConcurrentHashMap<String, Long>()
    @Volatile private var vcSkipUntil = 0L
    @Volatile private var goodPiped: String? = null

    fun invalidate(id: String) { streams.remove(id); neg.remove(id) }

    /** Dipanggil dari thread pemuat ExoPlayer (boleh memblokir). */
    fun resolveBlocking(id: String, fresh: Boolean = false): Stream? = runBlocking(Dispatchers.IO) { resolve(id, fresh) }

    suspend fun resolve(id: String, fresh: Boolean = false): Stream? {
        val now = System.currentTimeMillis()
        if (!fresh) {
            streams[id]?.let { if (now - it.t < 7_200_000) return it }
            if ((neg[id] ?: 0L) > now) return null
        }
        var o: Stream? = null
        if (now >= vcSkipUntil) {
            for (v in clients) {
                try {
                    val f = pick(playerCall(v, id))
                    val u = f?.optString("url").orEmpty()
                    if (u.isNotEmpty() && isGoogleVideo(u)) { o = Stream(u, v.ua, now); break }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { }
            }
            if (o == null) vcSkipUntil = now + 300_000
        }
        if (o == null) o = try { piped(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        if (o != null) streams[id] = o else neg[id] = now + 60_000
        return o
    }

    private fun isGoogleVideo(u: String) = u.toHttpUrlOrNull()?.host?.endsWith(".googlevideo.com") == true

    private suspend fun playerCall(v: VC, id: String): JSONObject {
        val cl = JSONObject().put("hl", "en").put("gl", "US").put("clientName", v.name).put("clientVersion", v.version)
        val ex = v.extra()
        for (k in ex.keys()) cl.put(k, ex.get(k))
        val body = JSONObject().put("context", JSONObject().put("client", cl)).put("videoId", id)
            .put("contentCheckOk", true).put("racyCheckOk", true).toString()
        val r = http(client, "https://www.youtube.com/youtubei/v1/player?prettyPrint=false&key=$KEY", mapOf(
            "User-Agent" to v.ua, "Origin" to "https://www.youtube.com",
            "X-YouTube-Client-Name" to v.n.toString(), "X-YouTube-Client-Version" to v.version), body)
        return JSONObject(r.body)
    }

    private fun pick(j: JSONObject): JSONObject? {
        val fs = j.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats") ?: return null
        return (0 until fs.length()).map { fs.getJSONObject(it) }
            .filter { it.has("url") && it.optString("mimeType").startsWith("audio/") }
            .minByOrNull { if (it.optInt("itag") == 140) 0 else if (it.optString("mimeType").contains("mp4")) 1 else 2 }
    }

    // ---- cadangan: instance Piped ----
    private val PIPED = listOf(
        "https://pipedapi.kavin.rocks", "https://pipedapi.leptons.xyz", "https://pipedapi.nosebs.ru",
        "https://pipedapi-libre.kavin.rocks", "https://piped-api.privacy.com.de", "https://pipedapi.adminforge.de",
        "https://api.piped.yt", "https://pipedapi.drgns.space",
    )

    private suspend fun piped(id: String): Stream? {
        val list = (customPiped + listOfNotNull(goodPiped) + PIPED).distinct().take(8)
        for (chunk in list.chunked(4)) {
            val r = firstOf(chunk.map { base -> suspend { pipedOne(base, id) } })
            if (r != null) return r
        }
        return null
    }

    private suspend fun pipedOne(base: String, id: String): Stream? {
        val r = http(quick, "$base/streams/$id", mapOf("Accept" to "application/json"))
        if (r.code != 200) return null
        val arr = JSONObject(r.body).optJSONArray("audioStreams") ?: return null
        val best = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("url").startsWith("https://") && !it.optBoolean("videoOnly") && it.optString("mimeType").contains("mp4") }
            .minByOrNull { val b = it.optInt("bitrate"); if (b > 192_000) 1_000_000_000 else -b }
            ?: return null
        goodPiped = base
        return Stream(best.getString("url"), UA, System.currentTimeMillis())
    }

    private suspend fun <T : Any> firstOf(jobs: List<suspend () -> T?>): T? = coroutineScope {
        val ch = Channel<T?>(jobs.size)
        val running = jobs.map { j -> launch { ch.send(try { j() } catch (e: Exception) { null }) } }
        var res: T? = null
        for (i in jobs.indices) {
            val r = ch.receive()
            if (r != null) { res = r; break }
        }
        running.forEach { it.cancel() }
        res
    }
}

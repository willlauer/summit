package com.willlauer.summit.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class HttpError(val code: Int, val body: String) : Exception("HTTP $code")

/** The Summit server's JSON API. [base] is e.g. https://my-mac.tailnet.ts.net or http://127.0.0.1:8766 (adb reverse). */
class Api(val http: OkHttpClient, var base: String) {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val JSON = "application/json".toMediaType()

    fun streamUrl(id: String) = "$base/stream/$id"
    fun artUrl(id: String, size: Int = 256) = "$base/art/$id?s=$size"

    private suspend fun call(method: String, path: String, body: JsonElement? = null, timeoutS: Long = 30): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(base + path)
            .method(method, body?.let { it.toString().toRequestBody(JSON) } ?: if (method == "GET" || method == "DELETE") null else "{}".toRequestBody(JSON))
            .build()
        val client = if (timeoutS == 30L) http else http.newBuilder().readTimeout(timeoutS, TimeUnit.SECONDS).build()
        client.newCall(req).execute().use { r ->
            val text = r.body.string()
            if (!r.isSuccessful) throw HttpError(r.code, text)
            text
        }
    }

    suspend fun version(): String = json.parseToJsonElement(call("GET", "/api/version")).jsonObject["version"]?.jsonPrimitive?.content ?: "?"
    suspend fun libraryRaw(): String = call("GET", "/api/library?lite=1", timeoutS = 60)
    fun parseLibrary(raw: String): LibraryPayload = json.decodeFromString(raw)
    suspend fun playlists(): List<Playlist> = json.decodeFromString(call("GET", "/api/playlists"))
    suspend fun folders(): List<Folder> = json.decodeFromString(call("GET", "/api/folders"))
    /** [mode]: both | vibe (by sound only) | name. */
    suspend fun search(q: String, n: Int = 40, mode: String = "both"): List<SearchHit> =
        json.decodeFromString(call("GET", "/api/search?q=${URLEncoder.encode(q, "UTF-8")}&n=$n&mode=$mode"))
    suspend fun neighbors(i: Int, k: Int, space: String): List<Int> = json.decodeFromString(call("GET", "/api/neighbors?i=$i&k=$k&space=$space"))
    suspend fun suggest(ids: List<String>, k: Int, space: String): List<Int> = json.decodeFromString(call("POST", "/api/suggest", buildJsonObject {
        putJsonArray("ids") { ids.forEach { add(it) } }; put("k", k); put("space", space)
    }))
    suspend fun like(id: String, liked: Boolean) { call("POST", "/api/likes", buildJsonObject { put("id", id); put("liked", liked) }) }
    suspend fun prefetch(ids: List<String>) { call("POST", "/api/prefetch", buildJsonObject { putJsonArray("ids") { ids.forEach { add(it) } } }) }

    suspend fun createPlaylist(name: String, ids: List<String>, folder: Int? = null): Created = json.decodeFromString(call("POST", "/api/playlists", buildJsonObject {
        put("name", name); putJsonArray("ids") { ids.forEach { add(it) } }; put("kind", "playlist"); put("folder", folder ?: 0)
    }))
    // Folders: parent / folder 0 means the top level.
    suspend fun createFolder(name: String, parent: Int?): Folder = json.decodeFromString(call("POST", "/api/folders", buildJsonObject {
        put("name", name); put("parent", parent ?: 0)
    }))
    suspend fun updateFolder(id: Int, name: String? = null, parent: Int? = null): Folder = json.decodeFromString(call("PUT", "/api/folders/$id", buildJsonObject {
        if (name != null) put("name", name); if (parent != null) put("parent", parent)
    }))
    suspend fun deleteFolder(id: Int) { call("DELETE", "/api/folders/$id") }
    suspend fun deletePlaylist(id: Int) { call("DELETE", "/api/playlists/$id") }
    /** Write a playlist based on [base] version. A 409 (changed on another device) throws [ConflictException].
     *  [folder]: move it into that folder (0: the top level). */
    suspend fun updatePlaylist(id: Int, base: Int, ids: List<String>? = null, name: String? = null, folder: Int? = null): Playlist {
        try {
            return json.decodeFromString(call("PUT", "/api/playlists/$id", buildJsonObject {
                put("base", base)
                if (ids != null) putJsonArray("ids") { ids.forEach { add(it) } }
                if (name != null) put("name", name)
                if (folder != null) put("folder", folder)
            }))
        } catch (e: HttpError) {
            if (e.code == 409) throw ConflictException(json.decodeFromString<Conflict>(e.body).playlist)
            throw e
        }
    }
}

class ConflictException(val current: Playlist) : Exception("playlist changed elsewhere")

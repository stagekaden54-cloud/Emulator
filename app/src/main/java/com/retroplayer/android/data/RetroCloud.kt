package com.retroplayer.android.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class CloudSyncReport(
    val gamesSynced: Int,
    val savesUploaded: Int,
    val savesDownloaded: Int,
    val skippedLargeSaves: Int
)

class RetroCloud(private val context: Context) {
    private val store = SecureSessionStore(context)
    private val refreshMutex = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()
    private val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val apiKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val maxSaveBytes = 5L * 1024L * 1024L

    fun currentSession(): CloudSession? = store.read()

    suspend fun signIn(email: String, password: String) {
        authenticate("/auth/v1/token?grant_type=password", email, password)
    }

    suspend fun signUp(email: String, password: String) {
        authenticate("/auth/v1/signup", email, password)
    }

    suspend fun recoverPassword(email: String) {
        authRequest("/auth/v1/recover", JSONObject().put("email", email))
    }

    suspend fun signOut() {
        val session = validSession()
        try {
            if (session != null) authRequest("/auth/v1/logout", JSONObject(), session.accessToken)
        } finally {
            store.clear()
        }
    }

    suspend fun syncAll(database: GameDatabase): CloudSyncReport {
        val session = validSession() ?: throw IllegalStateException("Sign in to sync Retro Player.")
        val localGames = database.allGames()
        val remoteGames = fetchLibrary(session)
        val merged = mergeGames(localGames, remoteGames)
        merged.forEach(database::upsert)
        writeLibrary(validSession() ?: session, merged)
        pushRecentGames(validSession() ?: session, merged)
        val saveCounts = syncSaveBackups(validSession() ?: session)
        return CloudSyncReport(merged.size, saveCounts.first, saveCounts.second, saveCounts.third)
    }

    private suspend fun authenticate(path: String, email: String, password: String) {
        require(email.contains("@")) { "Enter a valid email address." }
        require(password.length >= 8) { "Use a password with at least 8 characters." }
        val body = JSONObject().put("email", email.trim()).put("password", password)
        val result = authRequest(path, body)
        val access = result.optString("access_token")
        val refresh = result.optString("refresh_token")
        val user = result.optJSONObject("user")
        if (access.isBlank() || refresh.isBlank() || user == null || user.optString("id").isBlank()) {
            throw IllegalStateException(
                "Supabase did not return an active session. Check your email confirmation settings and try signing in."
            )
        }
        val expires = result.optLong(
            "expires_at",
            System.currentTimeMillis() / 1000L + result.optLong("expires_in", 3600L)
        )
        store.write(
            CloudSession(
                accessToken = access,
                refreshToken = refresh,
                userId = user.getString("id"),
                email = user.optString("email", email),
                expiresAtSeconds = expires
            )
        )
    }

    private suspend fun validSession(): CloudSession? = refreshMutex.withLock {
        val current = store.read() ?: return@withLock null
        if (current.expiresAtSeconds > System.currentTimeMillis() / 1000L + 90L) {
            return@withLock current
        }
        val response = authRequest(
            "/auth/v1/token?grant_type=refresh_token",
            JSONObject().put("refresh_token", current.refreshToken)
        )
        val access = response.optString("access_token")
        val refresh = response.optString("refresh_token")
        val user = response.optJSONObject("user")
        if (access.isBlank() || refresh.isBlank()) {
            store.clear()
            return@withLock null
        }
        val expires = response.optLong(
            "expires_at",
            System.currentTimeMillis() / 1000L + response.optLong("expires_in", 3600L)
        )
        val updated = CloudSession(
            accessToken = access,
            refreshToken = refresh,
            userId = user?.optString("id")?.takeIf { it.isNotBlank() } ?: current.userId,
            email = user?.optString("email")?.takeIf { it.isNotBlank() } ?: current.email,
            expiresAtSeconds = expires
        )
        store.write(updated)
        updated
    }

    private suspend fun authRequest(
        path: String,
        body: JSONObject,
        bearer: String? = null
    ): JSONObject {
        val builder = Request.Builder()
            .url(baseUrl + path)
            .header("apikey", apiKey)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonType))
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer)
        return execute(builder.build())
    }

    private suspend fun restRequest(
        path: String,
        method: String,
        body: String? = null,
        prefer: String? = null
    ): String {
        var session = validSession() ?: throw IllegalStateException("Your Retro Player session has expired. Sign in again.")
        var response = runRest(path, method, body, prefer, session)
        if (response.first == 401) {
            session = refreshMutex.withLock {
                val latest = store.read()
                if (latest != null && latest.expiresAtSeconds > System.currentTimeMillis() / 1000L + 90L) {
                    latest
                } else {
                    val current = latest ?: throw IllegalStateException("Sign in again to sync.")
                    val tokenResponse = authRequest(
                        "/auth/v1/token?grant_type=refresh_token",
                        JSONObject().put("refresh_token", current.refreshToken)
                    )
                    val access = tokenResponse.optString("access_token")
                    val refresh = tokenResponse.optString("refresh_token")
                    if (access.isBlank() || refresh.isBlank()) {
                        store.clear()
                        throw IllegalStateException("Your session expired. Sign in again.")
                    }
                    val user = tokenResponse.optJSONObject("user")
                    val next = CloudSession(
                        access,
                        refresh,
                        user?.optString("id")?.takeIf { it.isNotBlank() } ?: current.userId,
                        user?.optString("email")?.takeIf { it.isNotBlank() } ?: current.email,
                        tokenResponse.optLong("expires_at", System.currentTimeMillis() / 1000L + tokenResponse.optLong("expires_in", 3600L))
                    )
                    store.write(next)
                    next
                }
            }
            response = runRest(path, method, body, prefer, session)
        }
        if (response.first !in 200..299) {
            throw IllegalStateException("Cloud request failed (" + response.first + "): " + response.second.take(280))
        }
        return response.second
    }

    private suspend fun runRest(
        path: String,
        method: String,
        body: String?,
        prefer: String?,
        session: CloudSession
    ): Pair<Int, String> {
        val builder = Request.Builder()
            .url(baseUrl + path)
            .header("apikey", apiKey)
            .header("Authorization", "Bearer " + session.accessToken)
            .header("Accept", "application/json")
        if (prefer != null) builder.header("Prefer", prefer)
        val requestBody = body?.toRequestBody(jsonType)
        when (method.uppercase()) {
            "POST" -> builder.post(requestBody ?: "{}".toRequestBody(jsonType))
            "PATCH" -> builder.patch(requestBody ?: "{}".toRequestBody(jsonType))
            "DELETE" -> builder.delete(requestBody)
            else -> builder.get()
        }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            client.newCall(builder.build()).execute().use { response ->
                response.code to (response.body?.string() ?: "")
            }
        }
    }

    private suspend fun execute(request: Request): JSONObject =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = runCatching { JSONObject(text).optString("msg").ifBlank { JSONObject(text).optString("message") } }.getOrNull()
                    throw IllegalStateException(message?.ifBlank { null } ?: "Authentication failed (" + response.code + ").")
                }
                if (text.isBlank()) JSONObject() else JSONObject(text)
            }
        }

    private suspend fun fetchLibrary(session: CloudSession): List<Game> {
        val path = "/rest/v1/account_sync?select=data&user_id=eq." +
            session.userId + "&item_key=eq.meta%3Aandroid-library-v1&limit=1"
        val raw = restRequest(path, "GET")
        if (raw.isBlank()) return emptyList()
        val rows = JSONArray(raw)
        if (rows.length() == 0) return emptyList()
        val data = rows.getJSONObject(0).optString("data")
        if (data.isBlank()) return emptyList()
        val array = JSONObject(data).optJSONArray("games") ?: return emptyList()
        val result = mutableListOf<Game>()
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            result += row.toGame()
        }
        return result
    }

    private fun mergeGames(local: List<Game>, remote: List<Game>): List<Game> {
        val localById = local.associateBy { it.id }
        val remoteById = remote.associateBy { it.id }
        val result = LinkedHashMap<String, Game>()
        (localById.keys + remoteById.keys).forEach { id ->
            val onDevice = localById[id]
            val inCloud = remoteById[id]
            val chosen = when {
                onDevice == null -> inCloud!!
                inCloud == null -> onDevice
                onDevice.updatedAt >= inCloud.updatedAt -> onDevice
                else -> inCloud.copy(filePath = onDevice.filePath)
            }
            result[id] = chosen
        }
        return result.values.toList()
    }

    private suspend fun writeLibrary(session: CloudSession, games: List<Game>) {
        val gameArray = JSONArray()
        games.forEach { game ->
            gameArray.put(
                JSONObject()
                    .put("id", game.id)
                    .put("title", game.title)
                    .put("systemId", game.systemId)
                    .put("fileName", game.fileName)
                    .put("sizeBytes", game.sizeBytes)
                    .put("importedAt", game.importedAt)
                    .put("lastPlayedAt", game.lastPlayedAt)
                    .put("playCount", game.playCount)
                    .put("playtimeSeconds", game.playtimeSeconds)
                    .put("favorite", game.favorite)
                    .put("updatedAt", game.updatedAt)
            )
        }
        val data = JSONObject().put("schema", 1).put("games", gameArray).toString()
        val row = JSONArray().put(
            JSONObject()
                .put("user_id", session.userId)
                .put("item_key", "meta:android-library-v1")
                .put("data", data)
                .put("content_hash", sha256(data))
        ).toString()
        restRequest(
            "/rest/v1/account_sync?on_conflict=user_id%2Citem_key",
            "POST",
            row,
            "resolution=merge-duplicates,return=minimal"
        )
    }

    private suspend fun pushRecentGames(session: CloudSession, games: List<Game>) {
        val recent = games.filter { it.playCount > 0 && it.lastPlayedAt > 0L }
        if (recent.isEmpty()) return
        val array = JSONArray()
        recent.forEach { game ->
            array.put(
                JSONObject()
                    .put("user_id", session.userId)
                    .put("game_id", game.id)
                    .put("game_title", game.title)
                    .put("cover_url", JSONObject.NULL)
                    .put("last_played_at", java.time.Instant.ofEpochMilli(game.lastPlayedAt).toString())
                    .put("play_count", game.playCount.coerceAtLeast(1))
            )
        }
        restRequest(
            "/rest/v1/recent_games?on_conflict=user_id%2Cgame_id",
            "POST",
            array.toString(),
            "resolution=merge-duplicates,return=minimal"
        )
    }

    private suspend fun syncSaveBackups(session: CloudSession): Triple<Int, Int, Int> {
        val dir = File(context.filesDir, "save-backups").apply { mkdirs() }
        val localFiles = dir.listFiles()?.filter { it.isFile }.orEmpty()
        val remotePath = "/rest/v1/account_sync?select=item_key,data,updated_at&user_id=eq." +
            session.userId + "&item_key=like.android-save%3A%25"
        val remoteRows = JSONArray(restRequest(remotePath, "GET"))
        val remoteByKey = (0 until remoteRows.length()).map { remoteRows.getJSONObject(it) }
            .associateBy { it.optString("item_key") }

        var uploaded = 0
        var downloaded = 0
        var skippedLarge = 0
        val localByKey = localFiles.associateBy { saveKey(it.name) }

        for ((key, row) in remoteByKey) {
            val local = localByKey[key]
            val payload = runCatching { JSONObject(row.optString("data")) }.getOrNull() ?: continue
            val name = safeSaveName(payload.optString("name"))
            val encoded = payload.optString("base64")
            if (name.isBlank() || encoded.isBlank()) continue
            val remoteTime = payload.optLong("updatedAt", 0L)
            val target = File(dir, name)
            if (local == null || remoteTime > local.lastModified()) {
                val bytes = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull() ?: continue
                if (bytes.size.toLong() > maxSaveBytes) {
                    skippedLarge++
                    continue
                }
                target.writeBytes(bytes)
                target.setLastModified(remoteTime.takeIf { it > 0L } ?: System.currentTimeMillis())
                downloaded++
            }
        }

        for (file in localFiles) {
            if (file.length() > maxSaveBytes) {
                skippedLarge++
                continue
            }
            val key = saveKey(file.name)
            val remote = remoteByKey[key]
            val remoteTime = runCatching {
                JSONObject(remote?.optString("data").orEmpty()).optLong("updatedAt", 0L)
            }.getOrDefault(0L)
            if (remote == null || file.lastModified() > remoteTime) {
                val payload = JSONObject()
                    .put("name", file.name)
                    .put("updatedAt", file.lastModified())
                    .put("base64", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
                val data = payload.toString()
                val row = JSONArray().put(
                    JSONObject()
                        .put("user_id", session.userId)
                        .put("item_key", key)
                        .put("data", data)
                        .put("content_hash", sha256(data))
                ).toString()
                restRequest(
                    "/rest/v1/account_sync?on_conflict=user_id%2Citem_key",
                    "POST",
                    row,
                    "resolution=merge-duplicates,return=minimal"
                )
                uploaded++
            }
        }
        return Triple(uploaded, downloaded, skippedLarge)
    }

    private fun saveKey(name: String): String =
        "android-save:" + Base64.encodeToString(name.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun safeSaveName(name: String): String {
        val clean = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._() -]"), "_")
            .take(120)
        return clean.takeIf { it.isNotBlank() && it != "." && it != ".." }.orEmpty()
    }

    private fun JSONObject.toGame() = Game(
        id = getString("id"),
        title = optString("title", "Untitled game"),
        systemId = optString("systemId"),
        filePath = "",
        fileName = optString("fileName"),
        sizeBytes = optLong("sizeBytes"),
        importedAt = optLong("importedAt", System.currentTimeMillis()),
        lastPlayedAt = optLong("lastPlayedAt"),
        playCount = optInt("playCount"),
        favorite = optBoolean("favorite"),
        updatedAt = optLong("updatedAt", optLong("importedAt", 0L)),
        playtimeSeconds = optLong("playtimeSeconds")
    )

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

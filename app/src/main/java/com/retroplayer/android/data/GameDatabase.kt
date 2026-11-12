package com.retroplayer.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

class GameDatabase(context: Context) : SQLiteOpenHelper(context, "retro-player.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE games (
                id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                system_id TEXT NOT NULL,
                file_path TEXT NOT NULL DEFAULT '',
                file_name TEXT NOT NULL,
                size_bytes INTEGER NOT NULL DEFAULT 0,
                imported_at INTEGER NOT NULL,
                last_played_at INTEGER NOT NULL DEFAULT 0,
                play_count INTEGER NOT NULL DEFAULT 0,
                favorite INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL,
                playtime_seconds INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE games ADD COLUMN playtime_seconds INTEGER NOT NULL DEFAULT 0")
        }
    }

    @Synchronized
    fun allGames(): List<Game> {
        val result = mutableListOf<Game>()
        readableDatabase.rawQuery("SELECT * FROM games ORDER BY imported_at DESC", null).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toGame()
        }
        return result
    }

    @Synchronized
    fun upsert(game: Game) {
        val values = ContentValues().apply {
            put("id", game.id)
            put("title", game.title)
            put("system_id", game.systemId)
            put("file_path", game.filePath)
            put("file_name", game.fileName)
            put("size_bytes", game.sizeBytes)
            put("imported_at", game.importedAt)
            put("last_played_at", game.lastPlayedAt)
            put("play_count", game.playCount)
            put("favorite", if (game.favorite) 1 else 0)
            put("updated_at", game.updatedAt)
            put("playtime_seconds", game.playtimeSeconds)
        }
        writableDatabase.insertWithOnConflict("games", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun setFavorite(id: String, favorite: Boolean) {
        writableDatabase.update(
            "games",
            ContentValues().apply {
                put("favorite", if (favorite) 1 else 0)
                put("updated_at", System.currentTimeMillis())
            },
            "id=?",
            arrayOf(id)
        )
    }

    @Synchronized
    fun recordPlayed(id: String) {
        writableDatabase.execSQL(
            "UPDATE games SET play_count=play_count+1, last_played_at=?, updated_at=? WHERE id=?",
            arrayOf<Any>(System.currentTimeMillis(), System.currentTimeMillis(), id)
        )
    }

    @Synchronized
    fun addPlaytime(id: String, seconds: Long) {
        if (seconds <= 0L) return
        writableDatabase.execSQL(
            "UPDATE games SET playtime_seconds=playtime_seconds+?, updated_at=? WHERE id=?",
            arrayOf<Any>(seconds, System.currentTimeMillis(), id)
        )
    }

    @Synchronized
    fun removeGame(id: String): String? {
        val path = readableDatabase.rawQuery("SELECT file_path FROM games WHERE id=?", arrayOf(id)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        writableDatabase.delete("games", "id=?", arrayOf(id))
        return path
    }

    @Synchronized
    fun saves(context: Context): List<SaveBackup> {
        val folder = File(context.filesDir, "save-backups")
        return folder.listFiles()?.filter { it.isFile }?.map {
            SaveBackup(it.name, it.absolutePath, it.length(), it.lastModified())
        }?.sortedByDescending { it.updatedAt } ?: emptyList()
    }

    private fun android.database.Cursor.toGame() = Game(
        id = getString(getColumnIndexOrThrow("id")),
        title = getString(getColumnIndexOrThrow("title")),
        systemId = getString(getColumnIndexOrThrow("system_id")),
        filePath = getString(getColumnIndexOrThrow("file_path")),
        fileName = getString(getColumnIndexOrThrow("file_name")),
        sizeBytes = getLong(getColumnIndexOrThrow("size_bytes")),
        importedAt = getLong(getColumnIndexOrThrow("imported_at")),
        lastPlayedAt = getLong(getColumnIndexOrThrow("last_played_at")),
        playCount = getInt(getColumnIndexOrThrow("play_count")),
        favorite = getInt(getColumnIndexOrThrow("favorite")) == 1,
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
        playtimeSeconds = getLong(getColumnIndexOrThrow("playtime_seconds"))
    )
}

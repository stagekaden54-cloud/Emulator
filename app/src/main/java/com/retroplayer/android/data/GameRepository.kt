package com.retroplayer.android.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class AmbiguousRomException(val displayName: String) : Exception("Choose a system for " + displayName)

class GameRepository(private val context: Context, private val database: GameDatabase) {
    fun importUris(uris: List<Uri>, systemForBin: RetroSystem? = null): List<Game> {
        require(uris.isNotEmpty()) { "Choose at least one game file." }
        val folder = File(context.filesDir, "roms/" + UUID.randomUUID().toString()).apply { mkdirs() }
        val names = uris.map { queryName(it) }
        val hasCue = names.any { it.substringAfterLast('.', "").equals("cue", true) }
        val imported = mutableListOf<Game>()

        uris.forEachIndexed { index, uri ->
            val name = sanitizeName(names[index])
            val extension = name.substringAfterLast('.', "").lowercase()
            if (extension == "bin" && hasCue) {
                copyUri(uri, File(folder, name))
                return@forEachIndexed
            }
            val system = if (extension == "bin") systemForBin else RetroSystem.fromExtension(extension)
            if (system == null) throw AmbiguousRomException(name)
            val target = File(folder, name)
            copyUri(uri, target)
            val title = name.substringBeforeLast('.', name).replace('_', ' ').trim().ifBlank { name }
            val size = target.length()
            val id = sha256(system.id + "|" + name.lowercase() + "|" + size)
            val now = System.currentTimeMillis()
            val game = Game(
                id = id,
                title = title,
                systemId = system.id,
                filePath = target.absolutePath,
                fileName = name,
                sizeBytes = size,
                importedAt = now,
                updatedAt = now
            )
            database.upsert(game)
            imported += game
        }
        if (imported.isEmpty()) throw IllegalArgumentException("No playable game file was selected.")
        return imported
    }

    fun importBios(uri: Uri): File {
        val folder = File(context.filesDir, "bios").apply { mkdirs() }
        val file = File(folder, "ps1-bios.bin")
        copyUri(uri, file)
        context.getSharedPreferences("retro-player-settings", Context.MODE_PRIVATE)
            .edit().putString("ps1_bios_path", file.absolutePath).apply()
        return file
    }

    fun importSave(uri: Uri): File {
        val folder = File(context.filesDir, "save-backups").apply { mkdirs() }
        val name = sanitizeName(queryName(uri))
        val target = File(folder, name)
        copyUri(uri, target)
        return target
    }

    fun copySaveToUri(filePath: String, uri: Uri) {
        val source = File(filePath)
        require(source.isFile) { "The selected backup no longer exists." }
        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: throw IllegalStateException("Android could not open the destination.")
    }

    fun biosPath(): String? {
        val value = context.getSharedPreferences("retro-player-settings", Context.MODE_PRIVATE)
            .getString("ps1_bios_path", null)
        return value?.takeIf { File(it).isFile }
    }

    private fun queryName(uri: Uri): String {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "game.rom"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
        return name
    }

    private fun copyUri(uri: Uri, destination: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException("Could not read " + destination.name)
        if (destination.length() == 0L) {
            destination.delete()
            throw IllegalArgumentException("The selected file is empty.")
        }
    }

    private fun sanitizeName(value: String): String {
        val clean = value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._() -]"), "_")
            .take(120)
            .trim()
        return clean.ifBlank { "imported-file.rom" }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

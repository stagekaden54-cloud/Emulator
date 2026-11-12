package com.retroplayer.android.data

import java.io.File

enum class RetroSystem(
    val id: String,
    val title: String,
    val shortTitle: String,
    val extensions: Set<String>
) {
    PS1("psx", "PlayStation 1", "PS1", setOf("cue", "chd", "pbp", "iso", "img")),
    NES("nes", "Nintendo Entertainment System", "NES", setOf("nes", "fds")),
    SNES("snes", "Super Nintendo", "SNES", setOf("sfc", "smc")),
    GB("gb", "Game Boy", "GB", setOf("gb", "gbc")),
    GBA("gba", "Game Boy Advance", "GBA", setOf("gba")),
    N64("n64", "Nintendo 64", "N64", setOf("z64", "n64", "v64")),
    GENESIS("segaMD", "Sega Genesis", "Genesis", setOf("md", "gen", "smd")),
    MASTER_SYSTEM("segaMS", "Sega Master System", "Master System", setOf("sms")),
    GAME_GEAR("segaGG", "Sega Game Gear", "Game Gear", setOf("gg"));

    companion object {
        fun fromExtension(extension: String): RetroSystem? {
            val ext = extension.lowercase().removePrefix(".")
            return entries.firstOrNull { it.extensions.contains(ext) }
        }

        fun fromId(id: String): RetroSystem? = entries.firstOrNull { it.id == id }
    }
}

data class Game(
    val id: String,
    val title: String,
    val systemId: String,
    val filePath: String,
    val fileName: String,
    val sizeBytes: Long,
    val importedAt: Long,
    val lastPlayedAt: Long = 0L,
    val playCount: Int = 0,
    val favorite: Boolean = false,
    val updatedAt: Long = importedAt,
    val playtimeSeconds: Long = 0L
) {
    val system: RetroSystem?
        get() = RetroSystem.fromId(systemId)

    val isAvailableOnDevice: Boolean
        get() = filePath.isNotBlank() && File(filePath).isFile
}

data class SaveBackup(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val updatedAt: Long
)

package com.simonov.skinvault

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object SkinStorage {

    private val logger = LoggerFactory.getLogger("SkinVault/Storage")
    private val json   = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val configDir = FabricLoader.getInstance().configDir.resolve("skinvault")
    private val dataFile  = configDir.resolve("skins.json")
    private val tempFile  = configDir.resolve("skins.json.tmp")

    // ── Versioned wrapper — bump CURRENT_VERSION when the schema changes ──
    private const val CURRENT_VERSION = 1

    @Serializable
    private data class StorageFile(
        val version: Int,
        val skins: List<SkinEntry>
    )

    @Serializable
    data class SkinEntry(
        val name: String,
        val url: String
    )

    // ── Public API ────────────────────────────────────────────────────────

    fun load(): MutableList<SkinEntry> {
        if (!Files.exists(dataFile)) return mutableListOf()

        return try {
            val file = json.decodeFromString<StorageFile>(Files.readString(dataFile))
            migrate(file).skins.toMutableList()
        } catch (e: Exception) {
            logger.error("Failed to load skins.json: ${e.message}")
            // Keep a copy before the next save() overwrites the unreadable file.
            backupUnreadableFile()
            mutableListOf()
        }
    }

    fun save(skins: List<SkinEntry>) {
        try {
            Files.createDirectories(configDir)
            val file = StorageFile(version = CURRENT_VERSION, skins = skins)

            // Write to a temp file first, then move it into place, so a crash
            // mid-write can never leave a half-written skins.json behind.
            Files.writeString(tempFile, json.encodeToString(file))
            try {
                Files.move(
                    tempFile, dataFile,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tempFile, dataFile, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            logger.error("Failed to save skins.json: ${e.message}")
        }
    }

    /**
     * Only plain http(s) URLs without whitespace or control characters are accepted.
     * The URL is later sent as part of a chat command, so a stray newline or space
     * must never get through.
     */
    fun isValidSkinUrl(url: String): Boolean {
        if (url.isEmpty() || url.any { it.isWhitespace() || it.isISOControl() }) return false
        return try {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase()
            (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()
        } catch (e: Exception) {
            false
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun backupUnreadableFile() {
        try {
            val backup = configDir.resolve("skins.json.corrupt-${System.currentTimeMillis()}.bak")
            Files.copy(dataFile, backup, StandardCopyOption.REPLACE_EXISTING)
            logger.warn("Saved a copy of the unreadable skins.json to {}", backup)
        } catch (e: Exception) {
            logger.error("Could not back up unreadable skins.json: ${e.message}")
        }
    }

    // ── Migration ladder — add a branch here for each version bump ────────
    private fun migrate(file: StorageFile): StorageFile {
        var current = file

        // v0 → v1: nothing to migrate yet, placeholder for future changes
        // if (current.version < 1) {
        //     current = current.copy(version = 1, skins = current.skins.map { ... })
        // }

        if (current.version > CURRENT_VERSION) {
            logger.warn("skins.json is version ${current.version}, storage is $CURRENT_VERSION — some data may be ignored")
        }

        return current
    }
}
package dev.digi.xflixtv.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.addonData by preferencesDataStore("addons")
private val Context.libraryData by preferencesDataStore("library")

/** Installed addons, persisted locally. No account involved. */
class AddonStore(private val context: Context) {
    private val KEY = stringPreferencesKey("installed")

    fun installed(): Flow<List<String>> =
        context.addonData.data.map { it[KEY]?.split('\n')?.filter { u -> u.isNotBlank() } ?: emptyList() }

    suspend fun snapshot(): List<String> = installed().first()

    suspend fun add(url: String) {
        val cleaned = url.trim().removeSuffix("/")
        val current = snapshot().toMutableList()
        if (current.none { it.equals(cleaned, true) }) {
            current.add(cleaned)
            context.addonData.edit { it[KEY] = current.joinToString("\n") }
        }
    }

    suspend fun remove(url: String) {
        val current = snapshot().filterNot { it.equals(url, true) }
        context.addonData.edit { it[KEY] = current.joinToString("\n") }
    }

    suspend fun seedDefaultsIfEmpty() {
        val current = snapshot().toMutableList()
        var changed = false
        for (addon in AddonRepository.defaultAddons) {
            val base = addon.removeSuffix("/manifest.json").removeSuffix("/")
            if (current.none { it.removeSuffix("/manifest.json").removeSuffix("/").equals(base, true) }) {
                current.add(0, addon)
                changed = true
            }
        }
        // Retire addons whose configured URL is now known to be worse than the
        // current default. A provider-pinned Torrentio URL returns ~39 streams
        // where the plain endpoint returns ~287 for the same film; leaving the old
        // entry installed kept "missing sources" alive on upgraded devices
        // because seeding only ever adds.
        val superseded = current.filter { url -> AddonRepository.supersededAddons.any { url.contains(it) } }
        if (superseded.isNotEmpty()) {
            // Only drop the stale variant if the replacement is present.
            val replacementPresent = current.any { url ->
                AddonRepository.defaultAddons.any { def ->
                    val d = def.substringAfter("//").substringBefore("/")
                    url.substringAfter("//").substringBefore("/").equals(d, true)
                }
            }
            if (replacementPresent) {
                current.removeAll(superseded.toSet())
                changed = true
            }
        }
        if (changed || current.isEmpty()) {
            val toSave = if (current.isEmpty()) AddonRepository.defaultAddons else current
            context.addonData.edit { it[KEY] = toSave.joinToString("\n") }
        }
    }
}

/** Playback progress + library, persisted locally. */
class LibraryStore(private val context: Context) {
    private val LIB_KEY = stringPreferencesKey("library")
    private val PROGRESS_KEY = stringPreferencesKey("progress")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val metaList = ListSerializer(Meta.serializer())
    private val progressMap = MapSerializer(String.serializer(), Long.serializer())

    fun library(): Flow<List<Meta>> =
        context.libraryData.data.map {
            runCatching { json.decodeFromString(metaList, it[LIB_KEY] ?: "[]") }
                .getOrDefault(emptyList<Meta>())
        }

    suspend fun addToLibrary(meta: Meta) {
        val current = library().first().filterNot { it.id == meta.id } + meta
        context.libraryData.edit { it[LIB_KEY] = json.encodeToString(metaList, current) }
    }

    /** Rewrite the whole library at once, e.g. after refreshing stale metadata. */
    suspend fun replaceAll(items: List<Meta>) {
        context.libraryData.edit { it[LIB_KEY] = json.encodeToString(metaList, items) }
    }

    suspend fun removeFromLibrary(id: String) {
        val current = library().first().filterNot { it.id == id }
        context.libraryData.edit { it[LIB_KEY] = json.encodeToString(metaList, current) }
    }

    fun progress(): Flow<Map<String, Long>> =
        context.libraryData.data.map {
            runCatching { json.decodeFromString(progressMap, it[PROGRESS_KEY] ?: "{}") }
                .getOrDefault(emptyMap<String, Long>())
        }

    suspend fun saveProgress(id: String, positionMs: Long) {
        val current = progress().first().toMutableMap()
        current[id] = positionMs
        context.libraryData.edit { it[PROGRESS_KEY] = json.encodeToString(progressMap, current) }
    }
}
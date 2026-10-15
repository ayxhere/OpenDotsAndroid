package imayx.opendots.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists [AiProviderConfig]s (metadata only — keys stay in TokenStore) as
 * JSON in app-private storage. Survives app/backend restarts. Thread-safe
 * enough for UI use: all mutations go through [mutate] on IO dispatcher.
 */
class AiProviderStore(context: Context) {
    private val file = File(context.filesDir, "ai-providers.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private var cache: MutableList<AiProviderConfig>? = null

    suspend fun load(): List<AiProviderConfig> = withContext(Dispatchers.IO) {
        cache?.let { return@withContext it.toList() }
        val list = if (file.exists()) {
            try {
                json.decodeFromString(
                    ListSerializer(AiProviderConfig.serializer()),
                    file.readText(),
                ).toMutableList()
            } catch (_: Exception) {
                mutableListOf()
            }
        } else mutableListOf()
        cache = list
        list.toList()
    }

    private suspend fun save(list: List<AiProviderConfig>) = withContext(Dispatchers.IO) {
        cache = list.toMutableList()
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(ListSerializer(AiProviderConfig.serializer()), list))
    }

    suspend fun add(config: AiProviderConfig): List<AiProviderConfig> {
        val list = load().toMutableList()
        list.removeAll { it.id == config.id }
        list.add(config)
        save(list)
        return list
    }

    suspend fun update(config: AiProviderConfig): List<AiProviderConfig> {
        val list = load().toMutableList()
        val i = list.indexOfFirst { it.id == config.id }
        if (i >= 0) list[i] = config else list.add(config)
        save(list)
        return list
    }

    suspend fun remove(id: String): List<AiProviderConfig> {
        val list = load().filterNot { it.id == id }
        save(list)
        return list
    }

    /** Reorders by the given id sequence; priorities are rewritten 0..n. */
    suspend fun reorder(idsInOrder: List<String>): List<AiProviderConfig> {
        val byId = load().associateBy { it.id }
        val list = idsInOrder.mapNotNull { byId[it] }
            .mapIndexed { index, c -> c.copy(priority = index) } +
            byId.values.filterNot { it.id in idsInOrder.toSet() }
                .mapIndexed { index, c -> c.copy(priority = idsInOrder.size + index) }
        save(list.sortedBy { it.priority })
        return load()
    }
}

/** Router-level settings, persisted alongside providers. */
@kotlinx.serialization.Serializable
data class AiRouterSettings(
    /** Try providers in priority order when the selected one fails. */
    val autoFailover: Boolean = true,
    /** Only fall back to models known to be free. */
    val freeModelOnlyFallback: Boolean = false,
    /** Optional USD cap per chat/group run; null = no cap. */
    val maxCostUsd: Double? = null,
    /** Per-attempt timeout seconds for proxied provider calls. */
    val attemptTimeoutSec: Int = 120,
    /** Max provider attempts per chat turn (1 = no failover). */
    val maxAttempts: Int = 3,
)

class AiRouterSettingsStore(context: Context) {
    private val file = File(context.filesDir, "ai-router-settings.json")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): AiRouterSettings = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext AiRouterSettings()
        try {
            json.decodeFromString(AiRouterSettings.serializer(), file.readText())
        } catch (_: Exception) {
            AiRouterSettings()
        }
    }

    suspend fun save(settings: AiRouterSettings) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(AiRouterSettings.serializer(), settings))
    }
}

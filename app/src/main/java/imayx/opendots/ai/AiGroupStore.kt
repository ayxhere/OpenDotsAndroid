package imayx.opendots.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists AI groups and their conversation histories as JSON in app-private
 * storage. Survives app/backend restarts.
 */
class AiGroupStore(context: Context) {
    private val dir = File(context.filesDir, "ai-groups").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    private fun groupFile(id: String) = File(dir, "group-$id.json")
    private fun historyFile(id: String) = File(dir, "history-$id.json")

    suspend fun loadGroups(): List<AiGroup> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.name.startsWith("group-") }?.mapNotNull { f ->
            try {
                json.decodeFromString(AiGroup.serializer(), f.readText())
            } catch (_: Exception) { null }
        }?.sortedBy { it.name } ?: emptyList()
    }

    suspend fun saveGroup(group: AiGroup) = withContext(Dispatchers.IO) {
        groupFile(group.id).writeText(json.encodeToString(AiGroup.serializer(), group))
    }

    suspend fun deleteGroup(id: String) = withContext(Dispatchers.IO) {
        groupFile(id).delete()
        historyFile(id).delete()
    }

    suspend fun loadHistory(groupId: String): MutableList<GroupChatMessage> =
        withContext(Dispatchers.IO) {
            val f = historyFile(groupId)
            if (!f.exists()) return@withContext mutableListOf()
            try {
                json.decodeFromString(
                    ListSerializer(GroupChatMessage.serializer()),
                    f.readText(),
                ).toMutableList()
            } catch (_: Exception) {
                mutableListOf()
            }
        }

    suspend fun saveHistory(groupId: String, history: List<GroupChatMessage>) =
        withContext(Dispatchers.IO) {
            // Bound history: keep the last 200 messages.
            val bounded = history.takeLast(200)
            historyFile(groupId).writeText(
                json.encodeToString(ListSerializer(GroupChatMessage.serializer()), bounded)
            )
        }

    suspend fun clearHistory(groupId: String) = withContext(Dispatchers.IO) {
        historyFile(groupId).delete()
    }
}

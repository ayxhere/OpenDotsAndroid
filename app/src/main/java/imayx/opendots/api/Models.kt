package imayx.opendots.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Mirrors src/shared/types.ts and the /api/* JSON shapes. Unknown fields are
// ignored so the client stays forward-compatible with the server.

@Serializable
data class HealthResponse(
    val ok: Boolean = false,
    val ready: Boolean = false,
    val version: String? = null,
)

@Serializable
data class SetupStatus(
    val intelligence: Boolean = false,
    val model: Boolean = false,
    val browser: Boolean = false,
    val voice: Boolean = false,
    // Upstream contract (src/shared/types.ts): slack is a status string such as
    // "not_configured" | "setup_required" | "activation_failed" | "online".
    val slack: String = "",
    val missing: List<String> = emptyList(),
)

@Serializable
data class ApiState(
    val mode: String? = null,
    // Upstream contract (State.configured: boolean, src/shared/types.ts and
    // configured() in src/server/research.ts): /api/state returns a boolean.
    // The rich setup object lives at GET /api/workspace -> WorkspaceResponse.setup.
    val configured: Boolean = false,
)

@Serializable
data class Space(
    val id: String,
    val name: String,
    val description: String? = null,
    // Upstream contract: epoch-millis number (SQLite INTEGER).
    val createdAt: Long? = null,
)

@Serializable
data class Dot(
    val id: String,
    val spaceId: String? = null,
    val spaceIds: List<String> = emptyList(),
    val name: String,
    val instructions: String = "",
    val researchAllowed: Boolean = false,
    val memoryAllowed: Boolean = false,
    // Upstream contract: epoch-millis number (SQLite INTEGER).
    val createdAt: Long? = null,
)

@Serializable
data class Conversation(
    val id: String,
    val dotId: String,
    val ownerId: String? = null,
    val title: String? = null,
    // Upstream contract: epoch-millis number (SQLite INTEGER).
    val createdAt: Long? = null,
)

@Serializable
data class WorkspaceResponse(
    val spaces: List<Space> = emptyList(),
    val dots: List<Dot> = emptyList(),
    val conversations: List<Conversation> = emptyList(),
    val setup: SetupStatus = SetupStatus(),
)

@Serializable
data class Page(
    val id: String,
    val spaceId: String,
    val parentId: String? = null,
    val title: String,
    val content: String = "",
    val revision: Int = 1,
    // Upstream contract (src/server/pages.ts): epoch-millis numbers.
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val sourceThreadId: String? = null,
)

@Serializable
data class ApiError(
    val error: String? = null,
    val message: String? = null,
)

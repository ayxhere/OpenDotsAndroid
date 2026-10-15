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
    val slack: Boolean = false,
    val missing: List<String> = emptyList(),
)

@Serializable
data class ApiState(
    val mode: String? = null,
    val configured: SetupStatus = SetupStatus(),
)

@Serializable
data class Space(
    val id: String,
    val name: String,
    val description: String? = null,
    val createdAt: String? = null,
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
    val createdAt: String? = null,
)

@Serializable
data class Conversation(
    val id: String,
    val dotId: String,
    val ownerId: String? = null,
    val title: String? = null,
    val createdAt: String? = null,
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
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val sourceThreadId: String? = null,
)

@Serializable
data class ApiError(
    val error: String? = null,
    val message: String? = null,
)

package imayx.opendots

import imayx.opendots.api.ApiError
import imayx.opendots.api.ApiState
import imayx.opendots.api.HealthResponse
import imayx.opendots.api.Page
import imayx.opendots.api.WorkspaceResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM unit tests for the API models in `in.imayx.opendots.api`.
 *
 * No network, no Android framework: these only verify kotlinx.serialization
 * decoding of realistic OpenDots backend payloads — forward-compat (unknown
 * fields ignored) and defaulting of absent optional fields.
 */
class ModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ---- GET /api/workspace ----

    @Test
    fun workspaceResponse_parsesFullPayload() {
        val payload = """
            {
              "spaces": [
                {"id": "sp_01", "name": "Work", "description": "Work docs",
                 "createdAt": "2026-10-14T10:00:00.000Z", "archived": false}
              ],
              "dots": [
                {"id": "dot_01", "spaceId": "sp_01", "spaceIds": ["sp_01"],
                 "name": "Researcher", "instructions": "Research deeply.",
                 "researchAllowed": true, "memoryAllowed": false,
                 "createdAt": "2026-10-14T10:01:00.000Z",
                 "learningContainerId": "lc_9"}
              ],
              "conversations": [
                {"id": "conv_01", "dotId": "dot_01", "ownerId": "opendots-owner",
                 "title": "Q3 planning", "createdAt": "2026-10-14T11:00:00.000Z"}
              ],
              "setup": {
                "intelligence": false, "model": true, "browser": false,
                "voice": false, "slack": false,
                "missing": ["INTELLIGENCE_API_KEY"], "telemetryOptIn": true
              },
              "calls": []
            }
        """.trimIndent()

        val ws = json.decodeFromString<WorkspaceResponse>(payload)

        assertEquals(1, ws.spaces.size)
        assertEquals("Work", ws.spaces[0].name)
        assertEquals("Work docs", ws.spaces[0].description)

        assertEquals(1, ws.dots.size)
        val dot = ws.dots[0]
        assertEquals("dot_01", dot.id)
        assertEquals("sp_01", dot.spaceId)
        assertEquals(listOf("sp_01"), dot.spaceIds)
        assertTrue(dot.researchAllowed)
        assertFalse(dot.memoryAllowed)

        assertEquals(1, ws.conversations.size)
        assertEquals("dot_01", ws.conversations[0].dotId)
        assertEquals("Q3 planning", ws.conversations[0].title)

        assertFalse(ws.setup.intelligence)
        assertTrue(ws.setup.model)
        assertEquals(listOf("INTELLIGENCE_API_KEY"), ws.setup.missing)
        // Unknown fields (archived, learningContainerId, calls, telemetryOptIn)
        // must not break decoding.
    }

    @Test
    fun workspaceResponse_defaultsWhenFieldsAbsent() {
        val ws = json.decodeFromString<WorkspaceResponse>("{}")

        assertTrue(ws.spaces.isEmpty())
        assertTrue(ws.dots.isEmpty())
        assertTrue(ws.conversations.isEmpty())
        assertFalse(ws.setup.intelligence)
        assertFalse(ws.setup.model)
        assertTrue(ws.setup.missing.isEmpty())
    }

    @Test
    fun workspaceResponse_ignoresUnknownFields() {
        val ws = json.decodeFromString<WorkspaceResponse>(
            """{"spaces": [], "dots": [], "futureField": {"nested": [1, 2, 3]}}"""
        )
        assertTrue(ws.spaces.isEmpty())
        assertTrue(ws.dots.isEmpty())
    }

    // ---- GET /api/state ----

    @Test
    fun apiState_parsesSetupStatus() {
        val payload = """
            {
              "mode": "intelligence",
              "configured": {
                "intelligence": true, "model": true, "browser": false,
                "voice": false, "slack": false, "missing": []
              },
              "tasks": [], "memories": []
            }
        """.trimIndent()

        val state = json.decodeFromString<ApiState>(payload)

        assertEquals("intelligence", state.mode)
        assertTrue(state.configured.intelligence)
        assertTrue(state.configured.model)
        assertTrue(state.configured.missing.isEmpty())
    }

    @Test
    fun apiState_defaultsWhenFieldsAbsent() {
        val state = json.decodeFromString<ApiState>("{}")

        assertNull(state.mode)
        assertFalse(state.configured.intelligence)
        assertTrue(state.configured.missing.isEmpty())
    }

    // ---- Pages ----

    @Test
    fun page_parsesRevisionAndNullParent() {
        val payload = """
            {
              "id": "pg_01", "spaceId": "sp_01", "parentId": null,
              "title": "Notes", "content": "# Hello\n\n- [ ] todo",
              "revision": 7,
              "createdAt": "2026-10-14T10:00:00.000Z",
              "updatedAt": "2026-10-14T12:00:00.000Z",
              "sourceThreadId": "conv_01", "deleted": false
            }
        """.trimIndent()

        val page = json.decodeFromString<Page>(payload)

        assertEquals("pg_01", page.id)
        assertEquals("sp_01", page.spaceId)
        assertNull(page.parentId)
        assertEquals("Notes", page.title)
        assertEquals("# Hello\n\n- [ ] todo", page.content)
        assertEquals(7, page.revision)
        assertEquals("conv_01", page.sourceThreadId)
    }

    @Test
    fun page_defaultsWhenMinimal() {
        val page = json.decodeFromString<Page>(
            """{"id": "pg_02", "spaceId": "sp_01", "title": "Untitled"}"""
        )

        assertEquals("", page.content)
        assertEquals(1, page.revision)
        assertNull(page.parentId)
        assertNull(page.sourceThreadId)
        assertNull(page.createdAt)
        assertNull(page.updatedAt)
    }

    // ---- GET /api/health ----

    @Test
    fun healthResponse_parses() {
        val health = json.decodeFromString<HealthResponse>(
            """{"ok": true, "ready": true, "version": "0.1.0"}"""
        )

        assertTrue(health.ok)
        assertTrue(health.ready)
        assertEquals("0.1.0", health.version)
    }

    @Test
    fun healthResponse_defaultsWhenMinimal() {
        val health = json.decodeFromString<HealthResponse>("{}")

        assertFalse(health.ok)
        assertFalse(health.ready)
        assertNull(health.version)
    }

    // ---- Error bodies ----

    @Test
    fun apiError_parsesErrorAndMessage() {
        val withError = json.decodeFromString<ApiError>("""{"error": "unauthorized"}""")
        assertEquals("unauthorized", withError.error)
        assertNull(withError.message)

        val withMessage = json.decodeFromString<ApiError>("""{"message": "bad token"}""")
        assertNull(withMessage.error)
        assertEquals("bad token", withMessage.message)

        val empty = json.decodeFromString<ApiError>("{}")
        assertNull(empty.error)
        assertNull(empty.message)
    }
}

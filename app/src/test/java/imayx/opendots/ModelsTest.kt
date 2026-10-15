package imayx.opendots

import imayx.opendots.api.ApiError
import imayx.opendots.api.ApiState
import imayx.opendots.api.Conversation
import imayx.opendots.api.Dot
import imayx.opendots.api.HealthResponse
import imayx.opendots.api.Page
import imayx.opendots.api.Space
import imayx.opendots.api.WorkspaceResponse
import imayx.opendots.api.buildJsonString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM unit tests for the API models in `imayx.opendots.api`.
 *
 * No network, no Android framework: these only verify kotlinx.serialization
 * decoding of the REAL OpenDots backend payloads — forward-compat (unknown
 * fields ignored) and defaulting of absent optional fields.
 *
 * Contract authority is `backend/opendots-src/src/shared/types.ts`:
 * - `State.configured` is a BOOLEAN (research.ts `configured()`), not an object.
 * - `createdAt`/`updatedAt` are epoch-millis NUMBERS (SQLite INTEGER).
 * - `SetupStatus.slack` is a status STRING ("not_configured", "setup_required",
 *   "activation_failed", "online"), not a boolean.
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
                 "createdAt": 1729000000000, "archived": false}
              ],
              "dots": [
                {"id": "dot_01", "spaceId": "sp_01", "spaceIds": ["sp_01"],
                 "name": "Researcher", "instructions": "Research deeply.",
                 "researchAllowed": true, "memoryAllowed": false,
                 "createdAt": 1729000060000,
                 "learningContainerId": "lc_9"}
              ],
              "conversations": [
                {"id": "conv_01", "dotId": "dot_01", "ownerId": "opendots-owner",
                 "title": "Q3 planning", "createdAt": 1729003600000}
              ],
              "setup": {
                "intelligence": false, "model": true, "browser": false,
                "voice": false, "slack": "not_configured",
                "missing": ["INTELLIGENCE_API_KEY"], "telemetryOptIn": true
              },
              "calls": []
            }
        """.trimIndent()

        val ws = json.decodeFromString<WorkspaceResponse>(payload)

        assertEquals(1, ws.spaces.size)
        assertEquals("Work", ws.spaces[0].name)
        assertEquals("Work docs", ws.spaces[0].description)
        assertEquals(1729000000000L, ws.spaces[0].createdAt)

        assertEquals(1, ws.dots.size)
        val dot = ws.dots[0]
        assertEquals("dot_01", dot.id)
        assertEquals("sp_01", dot.spaceId)
        assertEquals(listOf("sp_01"), dot.spaceIds)
        assertTrue(dot.researchAllowed)
        assertFalse(dot.memoryAllowed)
        assertEquals(1729000060000L, dot.createdAt)

        assertEquals(1, ws.conversations.size)
        assertEquals("dot_01", ws.conversations[0].dotId)
        assertEquals("Q3 planning", ws.conversations[0].title)
        assertEquals(1729003600000L, ws.conversations[0].createdAt)

        assertFalse(ws.setup.intelligence)
        assertTrue(ws.setup.model)
        assertEquals("not_configured", ws.setup.slack)
        assertEquals(listOf("INTELLIGENCE_API_KEY"), ws.setup.missing)
        // Unknown fields (archived, learningContainerId, calls, telemetryOptIn)
        // must not break decoding.
    }

    @Test
    fun workspaceResponse_parsesSlackOnline() {
        val ws = json.decodeFromString<WorkspaceResponse>(
            """{"setup": {"intelligence": true, "model": true, "browser": false,
               "voice": false, "slack": "online", "missing": []}}"""
        )
        assertEquals("online", ws.setup.slack)
        assertTrue(ws.setup.missing.isEmpty())
    }

    @Test
    fun workspaceResponse_emptyLists() {
        val ws = json.decodeFromString<WorkspaceResponse>(
            """{"spaces": [], "dots": [], "conversations": [],
               "setup": {"intelligence": false, "model": false, "browser": false,
               "voice": false, "slack": "not_configured",
               "missing": ["INTELLIGENCE_API_KEY", "OPENAI_API_KEY", "OPENAI_MODEL"]},
               "calls": []}"""
        )
        assertTrue(ws.spaces.isEmpty())
        assertTrue(ws.dots.isEmpty())
        assertTrue(ws.conversations.isEmpty())
        assertEquals(3, ws.setup.missing.size)
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

    // ---- POST /api/spaces, /api/dots, /api/conversations ----

    @Test
    fun space_createResponse_parsesEpochCreatedAt() {
        val space = json.decodeFromString<Space>(
            """{"id": "sp_42", "name": "New space", "description": "D",
               "createdAt": 1729000000000}"""
        )
        assertEquals("sp_42", space.id)
        assertEquals("New space", space.name)
        assertEquals(1729000000000L, space.createdAt)
    }

    @Test
    fun dot_createResponse_parsesEpochCreatedAt() {
        val dot = json.decodeFromString<Dot>(
            """{"id": "dot_42", "spaceId": "sp_42", "spaceIds": ["sp_42"],
               "name": "Helper", "instructions": "Help.",
               "researchAllowed": false, "memoryAllowed": true,
               "createdAt": 1729000060000}"""
        )
        assertEquals("dot_42", dot.id)
        assertEquals(1729000060000L, dot.createdAt)
        assertTrue(dot.memoryAllowed)
    }

    @Test
    fun conversation_createResponse_parsesEpochCreatedAt() {
        val conv = json.decodeFromString<Conversation>(
            """{"id": "conv_42", "dotId": "dot_42", "ownerId": "opendots-owner",
               "title": "Hello", "createdAt": 1729003600000}"""
        )
        assertEquals("conv_42", conv.id)
        assertEquals(1729003600000L, conv.createdAt)
    }

    // ---- GET /api/state ----

    @Test
    fun apiState_parsesBooleanConfigured_true() {
        // Regression: the real backend returns "configured" as a BOOLEAN.
        // Decoding it as an object used to crash with
        // "Unexpected JSON token at path $.configured".
        val payload = """
            {
              "settings": {"name": "o", "paused": false, "researchAllowed": true, "memoryAllowed": true},
              "tasks": [], "memories": [],
              "mode": "live",
              "configured": true
            }
        """.trimIndent()

        val state = json.decodeFromString<ApiState>(payload)

        assertEquals("live", state.mode)
        assertTrue(state.configured)
    }

    @Test
    fun apiState_parsesBooleanConfigured_false() {
        val state = json.decodeFromString<ApiState>(
            """{"mode": "sample", "configured": false, "tasks": [], "memories": []}"""
        )

        assertEquals("sample", state.mode)
        assertFalse(state.configured)
    }

    @Test
    fun apiState_defaultsWhenFieldsAbsent() {
        val state = json.decodeFromString<ApiState>("{}")

        assertNull(state.mode)
        assertFalse(state.configured)
    }

    // ---- Pages ----

    @Test
    fun page_parsesRevisionAndNullParent() {
        // Regression: createdAt/updatedAt are epoch-millis NUMBERS.
        // Decoding them as strings used to crash with
        // "Expected quotation mark at path $.spaces[0].createdAt" (same shape).
        val payload = """
            {
              "id": "pg_01", "spaceId": "sp_01", "parentId": null,
              "title": "Notes", "content": "# Hello\n\n- [ ] todo",
              "revision": 7,
              "createdAt": 1729000000000,
              "updatedAt": 1729007200000,
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
        assertEquals(1729000000000L, page.createdAt)
        assertEquals(1729007200000L, page.updatedAt)
        assertEquals("conv_01", page.sourceThreadId)
    }

    @Test
    fun pageList_parsesArray() {
        // GET /api/spaces/:id/pages returns a bare JSON array.
        val pages = json.decodeFromString<List<Page>>(
            """[
                 {"id": "pg_01", "spaceId": "sp_01", "title": "A", "content": "",
                  "revision": 1, "createdAt": 1729000000000, "updatedAt": 1729000000000},
                 {"id": "pg_02", "spaceId": "sp_01", "title": "B", "content": "x",
                  "revision": 3, "createdAt": 1729000100000, "updatedAt": 1729000200000}
               ]"""
        )
        assertEquals(2, pages.size)
        assertEquals(1729000000000L, pages[0].createdAt)
        assertEquals(3, pages[1].revision)
    }

    @Test
    fun pageList_emptyArray() {
        val pages = json.decodeFromString<List<Page>>("[]")
        assertTrue(pages.isEmpty())
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
    fun healthResponse_androidProbeWithoutReady() {
        // The Android backend's unauthenticated probe returns no "ready" field.
        val health = json.decodeFromString<HealthResponse>(
            """{"ok": true, "version": "0.1.0"}"""
        )

        assertTrue(health.ok)
        assertFalse(health.ready)
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

    // ---- Request body builder ----

    @Test
    fun buildJsonString_escapesMultilineContent() {
        // The old hand-rolled builder emitted raw newlines -> invalid JSON ->
        // backend 400 on multiline page content. The body must round-trip.
        val body = buildJsonString(
            "title" to "My page",
            "content" to "line one\nline \"two\"\ttab\\backslash",
            "expectedRevision" to 3,
            "parentId" to null,
        )
        val parsed = json.parseToJsonElement(body).jsonObject
        assertEquals(JsonPrimitive("My page"), parsed["title"])
        assertEquals(JsonPrimitive("line one\nline \"two\"\ttab\\backslash"), parsed["content"])
        assertEquals(JsonPrimitive(3), parsed["expectedRevision"])
        assertFalse(parsed.containsKey("parentId"))
    }

    @Test
    fun buildJsonString_encodesNumbersAndBooleans() {
        val body = buildJsonString(
            "researchAllowed" to true,
            "count" to 42,
            "ratio" to 1.5,
        )
        val parsed = json.parseToJsonElement(body).jsonObject
        assertEquals(JsonPrimitive(true), parsed["researchAllowed"])
        assertEquals(JsonPrimitive(42), parsed["count"])
        assertEquals(JsonPrimitive(1.5), parsed["ratio"])
    }
}

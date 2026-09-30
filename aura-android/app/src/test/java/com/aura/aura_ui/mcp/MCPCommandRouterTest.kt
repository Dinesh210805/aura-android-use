package com.aura.aura_ui.mcp

import android.content.Context
import com.aura.aura_ui.network.ConnectionManager
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MCPCommandRouterTest {

    private lateinit var context: Context
    private lateinit var connectionManager: ConnectionManager
    private lateinit var testScope: TestScope
    private lateinit var router: MCPCommandRouter

    @Before
    fun setup() {
        context = mock(Context::class.java)
        connectionManager = mock(ConnectionManager::class.java)
        testScope = TestScope()
        router = MCPCommandRouter(context, connectionManager, testScope)
    }

    @Test
    fun `dispatch returns false for invalid JSON`() {
        val result = router.dispatch("invalid json")
        assertFalse(result)
    }

    @Test
    fun `dispatch returns false for unknown type`() {
        val result = router.dispatch("""{"type":"unknown_type_123"}""")
        assertFalse(result)
    }

    @Test
    fun `dispatch returns true for DEVICE_INFO_ACK`() {
        val result = router.dispatch("""{"type":"device_info_ack"}""")
        assertTrue(result)
    }

    @Test
    fun `dispatch invokes onUIMessage for UI messages`() = runTest {
        var callbackInvoked = false
        var invokedType = ""

        router.onUIMessage = { type, _ ->
            callbackInvoked = true
            invokedType = type
        }

        val result = router.dispatch("""{"type":"hitl_question", "question":"Do you confirm?"}""")
        
        assertTrue(result)
        assertTrue(callbackInvoked)
        assertEquals("hitl_question", invokedType)
    }

    @Test
    fun `dispatch routes TASK_PROGRESS to onUIMessage`() = runTest {
        var callbackInvoked = false
        var invokedType = ""

        router.onUIMessage = { type, _ ->
            callbackInvoked = true
            invokedType = type
        }

        val result = router.dispatch("""{"type":"task_progress", "goal":"Test"}""")
        
        assertTrue(result)
        assertTrue(callbackInvoked)
        assertEquals("task_progress", invokedType)
    }

    @Test
    fun `dispatch routes REQUEST_UI_TREE and returns true`() {
        // Even if the inner coroutine fails due to unmocked service, dispatch should return true synchronously
        val result = router.dispatch("""{"type":"request_ui_tree", "request_id":"req_1"}""")
        assertTrue(result)
    }

    @Test
    fun `dispatch routes REQUEST_SCREENSHOT and returns true`() {
        val result = router.dispatch("""{"type":"request_screenshot", "request_id":"req_2"}""")
        assertTrue(result)
    }

    @Test
    fun `dispatch returns false for CONNECTION_REQUEST to let caller handle it`() {
        val result = router.dispatch("""{"type":"connection_request"}""")
        assertFalse(result)
    }
}

package com.veilframe.app.tools

import org.junit.Assert.*
import org.junit.Test

class ToolTargetOwnerTest {
    @Test fun targetRevisionAndIdentityBothGatePublication() {
        val original = ToolSessionState()
        val ticket = ToolTargetOwner(original)
        assertTrue(ticket.owns(original))
        assertFalse(ticket.owns(original.copy()))
        original.targetRevision++
        assertFalse(ticket.owns(original))
        val next = ToolTargetOwner(original)
        assertTrue(next.owns(original))
        original.targetRevision++
        assertFalse(next.owns(original))
    }
}

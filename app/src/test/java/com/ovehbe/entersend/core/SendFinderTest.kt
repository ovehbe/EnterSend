package com.ovehbe.entersend.core

import com.ovehbe.entersend.data.AppRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendFinderTest {
    private val fieldBox = Box(10, 600, 600, 680)
    private val field = Field("com.x", "hi", 2, 2, 0, false, null, null, fieldBox)

    private fun node(desc: String? = null, id: String? = null, text: String? = null, bounds: Box = Box(610, 600, 700, 680)) =
        NodeSnap(viewId = id, desc = desc, text = text, clickable = true, bounds = bounds)

    private fun root(vararg n: NodeSnap) = NodeSnap(bounds = Box(0, 0, 720, 720), children = n.toList())

    @Test fun labelsThatMeanSend() {
        for (label in listOf("Send", "send", "Send message", "Send SMS", "Gönder", "GÖNDER", "Mesajı gönder", "Gonder")) {
            assertTrue(label, SendFinder.looksLikeSend(node(desc = label)))
        }
        assertTrue(SendFinder.looksLikeSend(node(id = "com.x:id/send_button")))
    }

    @Test fun labelsThatAreNotSend() {
        for (label in listOf("Sent", "Sending", "Gönderildi", "Send voice message", "Sesli mesaj gönder", "Send money", "Attach", "Mic")) {
            assertFalse(label, SendFinder.looksLikeSend(node(desc = label)))
        }
        assertFalse(SendFinder.looksLikeSend(node(id = "com.x:id/sender_name")))
        assertFalse(SendFinder.looksLikeSend(node(id = "com.x:id/send", desc = "Record voice message")))
    }

    @Test fun picksTheNearestSendAndIgnoresFarOnes() {
        val near = node(desc = "Send")
        val far = node(desc = "Send", bounds = Box(600, 0, 700, 60))
        assertEquals(near, SendFinder.auto(root(far, near), field))
        assertNull(SendFinder.auto(root(far), field))
    }

    @Test fun nonClickableLabelUsesClickableParent() {
        val icon = NodeSnap(desc = "Send", bounds = Box(620, 610, 690, 670))
        val button = NodeSnap(clickable = true, bounds = Box(610, 600, 700, 680), children = listOf(icon))
        assertEquals(button, SendFinder.auto(root(button), field))
    }

    @Test fun disabledOrHiddenSendIsIgnored() {
        val disabled = NodeSnap(desc = "Send", clickable = true, enabled = false, bounds = Box(610, 600, 700, 680))
        val hidden = NodeSnap(desc = "Send", clickable = true, visible = false, bounds = Box(610, 600, 700, 680))
        assertNull(SendFinder.auto(root(disabled, hidden), field))
    }

    @Test fun learnedRuleRequiresEveryStoredAttribute() {
        val rule = AppRule("com.x", sendViewId = "com.x:id/go", sendDesc = "Send")
        val sameIdOtherDesc = node(id = "com.x:id/go", desc = "Record")
        val match = node(id = "com.x:id/go", desc = "Send")
        assertNull(SendFinder.learned(root(sameIdOtherDesc), field, rule))
        assertEquals(match, SendFinder.learned(root(sameIdOtherDesc, match), field, rule))
    }

    @Test fun learnedClassPathMustCoverTheTapSpot() {
        val rule = AppRule("com.x", sendClassPath = "android.widget.FrameLayout>android.view.View", tapX = 650, tapY = 640)
        val button = NodeSnap(className = "android.view.View", clickable = true, bounds = Box(610, 600, 700, 680))
        val other = NodeSnap(className = "android.view.View", clickable = true, bounds = Box(0, 0, 100, 100))
        val tree = NodeSnap(className = "android.widget.FrameLayout", bounds = Box(0, 0, 720, 720), children = listOf(other, button))
        assertEquals(button, SendFinder.learned(tree, field, rule))
    }
}

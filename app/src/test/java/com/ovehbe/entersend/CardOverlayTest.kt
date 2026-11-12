package com.ovehbe.entersend

import android.view.WindowManager
import com.ovehbe.entersend.service.Card
import com.ovehbe.entersend.service.CardOverlay
import com.ovehbe.entersend.service.EnterSendService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl

@RunWith(RobolectricTestRunner::class)
class CardOverlayTest {
    @Test fun everyCardIsANonFocusableOverlay() {
        val service = Robolectric.setupService(EnterSendService::class.java)
        val overlay = CardOverlay(service)
        val wm = Shadow.extract<ShadowWindowManagerImpl>(service.getSystemService(WindowManager::class.java))
        for (card in listOf(Card.Ask(false), Card.Ask(true), Card.Teaching, Card.Confirm(solid(0xFF000000.toInt()), "Send"), Card.Confirm(null, "Send"))) {
            overlay.show(card) {}
            val views = wm.views
            assertEquals(1, views.size)
            val lp = views[0].layoutParams as WindowManager.LayoutParams
            assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, lp.type)
            assertTrue(lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        }
        overlay.hide()
        assertTrue(wm.views.isEmpty())
    }
}

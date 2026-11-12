package com.ovehbe.entersend.ui

import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.ovehbe.entersend.R
import com.ovehbe.entersend.context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SetupScreenTest {
    private val a = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
    private fun check(id: Int) = a.findViewById<TextView>(id).text.toString()
    private val done get() = a.getString(R.string.check_done)

    @Test fun stepOneIsOpenWhileServiceIsOff() {
        assertEquals(a.getString(R.string.check_pending), check(R.id.step1_check))
        a.findViewById<Button>(R.id.step1_button).performClick()
        assertNotNull(shadowOf(a).nextStartedActivity)
    }

    @Test fun stepTwoNeedsBothEnterAndShiftEnter() {
        val box = a.findViewById<View>(R.id.key_box)
        box.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        assertEquals(a.getString(R.string.check_pending), check(R.id.step2_check))
        box.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, KeyEvent.META_SHIFT_ON))
        assertEquals(done, check(R.id.step2_check))
    }

    @Test fun stepThreeChecksSendAndNewline() {
        val input = a.findViewById<EditText>(R.id.message_input)
        input.setText("hello 😀")
        a.findViewById<View>(R.id.send_button).performClick()
        assertEquals("", input.text.toString())
        assertTrue(a.findViewById<TextView>(R.id.sent_log).text.contains("hello 😀"))
        assertEquals(a.getString(R.string.check_pending), check(R.id.step3_check))

        input.setText("line one\nline two")
        assertEquals(done, check(R.id.step3_check))
    }

    @Test fun emptyTestBoxSendsNothing() {
        a.findViewById<View>(R.id.send_button).performClick()
        assertEquals(View.GONE, a.findViewById<View>(R.id.sent_log).visibility)
    }

    @Test fun testBoxLooksLikeAChatFieldToTheService() {
        val input = a.findViewById<EditText>(R.id.message_input)
        assertTrue(a.resources.getResourceEntryName(input.id).contains("message"))
        assertEquals(a.getString(R.string.send), a.findViewById<View>(R.id.send_button).contentDescription)
    }

    @Test fun everyControlIsKeyboardFocusableWithAVisibleRing() {
        for (id in listOf(R.id.step1_button, R.id.key_box, R.id.message_input, R.id.send_button, R.id.done)) {
            val v = a.findViewById<View>(id)
            assertTrue(a.resources.getResourceEntryName(id), v.isFocusable)
            assertNotNull(a.resources.getResourceEntryName(id), v.foreground)
        }
    }

    @Test fun doneMarksSetupFinished() {
        a.findViewById<View>(R.id.done).performClick()
        assertTrue(MainActivity.prefs(context()).getBoolean(MainActivity.KEY_SETUP_DONE, false))
        assertTrue(a.isFinishing)
    }
}

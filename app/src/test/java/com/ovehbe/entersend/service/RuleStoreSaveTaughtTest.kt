package com.ovehbe.entersend.service

import com.ovehbe.entersend.core.Pixels
import com.ovehbe.entersend.data.AppRule
import com.ovehbe.entersend.data.Crops
import com.ovehbe.entersend.direct
import com.ovehbe.entersend.memoryDb
import com.ovehbe.entersend.solid
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * saveTaught must write the new crop, then the rule, then delete the old crop — never leave a
 * rule pointing at a file that was already deleted.
 */
@RunWith(RobolectricTestRunner::class)
class RuleStoreSaveTaughtTest {
    private val db = memoryDb()
    private val files = mutableMapOf<String, Pixels>()
    private val deleted = mutableListOf<String>()
    private var failAfterSave = false

    private val crops = object : Crops {
        override fun load(path: String) = files[path]
        override fun save(pkg: String, pixels: Pixels): String {
            val path = "/crops/$pkg-${files.size}.png"
            files[path] = pixels
            if (failAfterSave) error("simulated crash after new crop was written")
            return path
        }
        override fun delete(path: String) {
            deleted += path
            files.remove(path)
        }
    }

    @Before fun seed() {
        db.rules().upsert(AppRule("com.x", cropPath = "/crops/old.png", sendDesc = "Send"))
        files["/crops/old.png"] = solid(0xFF0000FF.toInt())
        deleted.clear()
        failAfterSave = false
    }

    @After fun tearDown() = db.close()

    @Test fun newCropIsWrittenBeforeOldCropIsDeleted() {
        val store = RuleStore(db.rules(), direct, direct, crops)
        store.saveTaught(AppRule("com.x", sendDesc = "Send", tapX = 1, tapY = 2), solid(0xFF00FF00.toInt()))
        val saved = db.rules().get("com.x")!!
        assertNotNull(saved.cropPath)
        assertTrue(saved.cropPath in files)
        assertEquals(listOf("/crops/old.png"), deleted)
        assertTrue("/crops/old.png" !in files)
    }

    @Test fun failureAfterNewCropLeavesRuleStillPointingAtExistingOldFile() {
        failAfterSave = true
        val store = RuleStore(db.rules(), direct, direct, crops)
        try {
            store.saveTaught(AppRule("com.x", sendDesc = "Send"), solid(0xFF00FF00.toInt()))
            fail("expected crash")
        } catch (_: IllegalStateException) {
        }
        // New file may exist on disk, but the rule must still reference the old file that remains.
        assertEquals("/crops/old.png", db.rules().get("com.x")!!.cropPath)
        assertTrue("/crops/old.png" in files)
        assertTrue(deleted.isEmpty())
    }
}

package com.meowgi.entersend

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import com.meowgi.entersend.core.Box
import com.meowgi.entersend.core.NodeSnap
import com.meowgi.entersend.core.Pixels
import com.meowgi.entersend.data.AppDb
import com.meowgi.entersend.data.AppRule
import com.meowgi.entersend.service.Card
import com.meowgi.entersend.service.Choice
import com.meowgi.entersend.service.EnterSendService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * 0.3.20 scenario matrix for the two Q25 teach bugs:
 * 1. A taught app must never get the teach UI back automatically on a lookup / send miss.
 * 2. Step 1 must save the send button on the first real send, even though Android delivers the
 *    box-cleared TEXT_CHANGED before the VIEW_CLICKED of the same tap (View.performClick order).
 *
 * Each row drives the real service (EnterHandler + TeachFlow + RuleStore) through fakes and
 * compares a compact [Outcome].
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
class TeachReliabilityMatrixTest(
    private val name: String,
    private val row: Row,
) {
    enum class Saved { NONE, NODE, TAP }

    data class Outcome(
        /** Any teach bar / capture overlay shown during the action. */
        val teachUi: Boolean,
        /** What the database holds for [PKG] afterwards. */
        val saved: Saved,
        /**
         * "Didn't catch that…" step-2 bars left waiting for the user — a bar immediately replaced
         * by Saved for the same send is not counted.
         */
        val step2Bars: Int = 0,
        /** Full-screen tap capture shown. */
        val capture: Boolean = false,
        val teachingAtEnd: Boolean = false,
    )

    class Row(val expected: Outcome, val action: Harness.() -> Unit)

    class Harness {
        val db: AppDb = memoryDb()
        val device = FakeDevice()
        var overlay = FakeOverlay()
        val crops = FakeCrops()
        var day = 20_000L
        var t = 10_000L
        lateinit var s: EnterSendService
        /** Cards shown before the action began (seeding / setup are not counted). */
        var mark = 0

        val step2Text: String get() = context().getString(R.string.teach_step2)

        fun start(): EnterSendService {
            s = service(device, db, crops, overlay, today = { day })
            idle(1)
            return s
        }

        /** New process: fresh service + overlay, rules come back from the DB. */
        fun restart() {
            overlay = FakeOverlay()
            start()
            mark = 0
        }

        fun seed(rule: AppRule, crop: Pixels? = null) {
            if (crop != null && rule.cropPath != null) crops.files[rule.cropPath] = crop
            db.rules().upsert(rule)
        }

        fun enter(): Boolean {
            t += 1_000
            return s.press(t).first()
        }

        fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

        fun type(text: String, pkg: String = PKG) {
            device.field = chatField(text, pkg = pkg, viewId = "$pkg:id/entry", hint = null)
            s.teach!!.onTextChanged(pkg)
        }

        /** Real Android order: onClick clears the box (TEXT_CHANGED), then VIEW_CLICKED. */
        fun tapSendAndroidOrder(node: NodeSnap = sendNode(), pkg: String = PKG) {
            type("", pkg)
            s.teach!!.onClicked(pkg, node)
        }

        fun teachShownSinceMark(): Boolean =
            overlay.shown.drop(mark).any { it is Card.TeachingBar || it is Card.Capture }

        fun outcome(): Outcome {
            val rule = db.rules().get(PKG)
            val saved = when {
                rule == null -> Saved.NONE
                rule.hasNodeRule -> Saved.NODE
                rule.hasTapSpot -> Saved.TAP
                else -> Saved.NONE
            }
            val shown = overlay.shown.drop(mark)
            return Outcome(
                teachUi = teachShownSinceMark(),
                saved = saved,
                step2Bars = shown.indices.count { i ->
                    val c = shown[i]
                    c is Card.TeachingBar && c.message == step2Text && shown.getOrNull(i + 1) !is Card.Saved
                },
                capture = shown.any { it is Card.Capture },
                teachingAtEnd = s.teach!!.isTeaching,
            )
        }
    }

    private lateinit var h: Harness

    @Before fun setUp() {
        installLauncher(PKG)
        installLauncher(OTHER)
        h = Harness()
        h.device.field = chatField("hello", pkg = PKG, viewId = "$PKG:id/entry", hint = null)
        h.device.root = window()
        h.device.live = solid(BLUE)
    }

    @After fun tearDown() = h.db.close()

    @Test fun scenario() {
        row.action(h)
        assertEquals(name, row.expected, h.outcome())
    }

    companion object {
        const val PKG = "com.example.chat"
        const val OTHER = "com.other.chat"
        val BLUE = 0xFF2196F3.toInt()
        val RED = 0xFFF44336.toInt()

        fun sendNode(pkg: String = PKG) = sendButton(viewId = "$pkg:id/send")

        val NODE_RULE = AppRule(PKG, sendViewId = "$PKG:id/send", sendDesc = "Send")

        /** Tap spot at (650,640) next to FIELD_BOX with an 80×80 blue crop. */
        val TAP_RULE = AppRule(
            PKG,
            tapX = 650, tapY = 640, tapDxDp = 50f, tapDyDp = -40f,
            orientation = 1, windowWidth = 720, windowHeight = 720, keyboardShown = false,
            cropPath = "/crops/$PKG-taught.png", cropLeft = 610, cropTop = 600, cropRight = 690, cropBottom = 680,
        )
        val TAP_CROP = solid(BLUE, 80, 80)

        private fun installLauncher(pkg: String) {
            val ai = ApplicationInfo().apply { packageName = pkg }
            val pm = shadowOf(context().packageManager)
            pm.installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = ai })
            pm.addResolveInfoForIntent(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg),
                ResolveInfo().apply {
                    activityInfo = ActivityInfo().apply { packageName = pkg; name = "$pkg.Main"; applicationInfo = ai }
                },
            )
        }

        private val NO_TEACH_NODE = Outcome(teachUi = false, saved = Saved.NODE)
        private val NO_TEACH_TAP = Outcome(teachUi = false, saved = Saved.TAP)
        private val FIRST_TRY_NODE = Outcome(teachUi = true, saved = Saved.NODE)
        private fun tapMode(step2: Int = 1) = Outcome(teachUi = true, saved = Saved.TAP, step2Bars = step2, capture = true)

        /** Step 1 → step 2 via "box emptied, no usable click" → type → capture → tap → Done. */
        private fun Harness.tapModeTeach(retries: Int) {
            s.teach!!.startTeaching(PKG)
            type("hi")
            type("")
            type("hi again")
            repeat(retries) {
                overlay.screenTap(600, 600)
                idle(50)
                overlay.tap(Choice.RETRY)
            }
            overlay.screenTap(650, 640)
            idle(50)
            overlay.tap(Choice.YES)
        }

        private val rows: List<Pair<String, Row>> = listOf(
            // ---- Bug 1: Enter-time lookup misses ----
            "untaught + lookup miss → teach offered (baseline)" to Row(
                Outcome(teachUi = true, saved = Saved.NONE, teachingAtEnd = true),
            ) { start(); enter() },
            "untaught + auto finds send → click, no teach" to Row(Outcome(false, Saved.NONE)) {
                device.root = window(sendNode()); start(); enter()
            },
            "untaught + declined → no teach" to Row(Outcome(false, Saved.NONE)) {
                seed(AppRule(PKG, teachDeclined = true)); start(); enter()
            },
            "taught node + transient miss → no teach UI" to Row(NO_TEACH_NODE) {
                seed(NODE_RULE); start(); enter()
            },
            "taught node + transient miss on a new day → no teach UI" to Row(NO_TEACH_NODE) {
                seed(NODE_RULE.copy(lastAskedDay = 1)); day = 20_001; start(); enter()
            },
            "taught node + 5 misses over 5 days → no teach UI" to Row(NO_TEACH_NODE) {
                seed(NODE_RULE); start()
                repeat(5) { day++; enter(); idle(600) }
            },
            "taught node + miss then hit → click works, no teach" to Row(NO_TEACH_NODE) {
                seed(NODE_RULE); start(); enter()
                device.root = window(sendNode()); device.field = device.field!!.copy(text = "hello")
                check(enter()) { "learned hit must consume" }
                check(device.clicks.size == 1) { "learned node clicked once" }
            },
            "taught node + window root missing → no teach UI" to Row(NO_TEACH_NODE) {
                seed(NODE_RULE); device.root = null; start(); enter()
            },
            "taught tap + 3 consecutive failures → no teach UI" to Row(NO_TEACH_TAP) {
                seed(TAP_RULE.copy(consecutiveFailures = 3), TAP_CROP); start(); enter()
            },
            "taught tap + no on-screen candidates → no teach UI" to Row(NO_TEACH_TAP) {
                seed(TAP_RULE.copy(tapDxDp = 5_000f, tapX = 5_000), TAP_CROP); start(); enter()
            },
            "taught tap + crop file missing → no teach UI" to Row(NO_TEACH_TAP) {
                seed(TAP_RULE); start(); enter()
            },
            "taught tap + screenshot null twice → no teach UI" to Row(NO_TEACH_TAP) {
                seed(TAP_RULE, TAP_CROP); device.nullCapturesRemaining = 2; start(); enter(); idle(1_000)
            },
            "taught tap + crop mismatch → no teach UI" to Row(NO_TEACH_TAP) {
                seed(TAP_RULE, TAP_CROP); device.live = solid(RED); start(); enter(); idle(1_000)
            },
            "taught tap + send not verified (3rd failure) → no teach UI" to Row(NO_TEACH_TAP) {
                seed(TAP_RULE.copy(consecutiveFailures = 2), TAP_CROP)
                device.textAfterSend = "hello"; start(); enter(); idle(1_000)
            },
            "taught tap + crop match → tap sent, no teach" to Row(NO_TEACH_TAP) {
                seed(TAP_RULE, TAP_CROP); start(); enter(); idle(1_000)
                check(device.taps == listOf(650 to 640)) { "taps=${device.taps}" }
            },
            "process restart + taught node + miss → no teach UI" to Row(NO_TEACH_NODE) {
                seed(NODE_RULE); start(); restart(); enter()
            },
            "explicit re-teach of taught app → teach UI, first-try save" to Row(FIRST_TRY_NODE) {
                seed(NODE_RULE.copy(sendViewId = "$PKG:id/old")); start()
                s.teach!!.startTeaching(PKG)
                type("hi"); tapSendAndroidOrder()
                check(db.rules().get(PKG)!!.sendViewId == "$PKG:id/send") { "re-teach replaced rule" }
            },

            // ---- Bug 2: teach flow ----
            "teach attempt 1: box clears before VIEW_CLICKED (Android order)" to Row(FIRST_TRY_NODE) {
                start(); s.teach!!.startTeaching(PKG); type("hi"); tapSendAndroidOrder()
            },
            "teach attempt 1: VIEW_CLICKED before box clears" to Row(FIRST_TRY_NODE) {
                start(); s.teach!!.startTeaching(PKG); type("hi")
                s.teach!!.onClicked(PKG, sendNode()); type("")
            },
            "teach attempt 1: box clear seen via CONTENT_CHANGED then click" to Row(FIRST_TRY_NODE) {
                start(); s.teach!!.startTeaching(PKG); type("hi")
                device.field = device.field!!.copy(text = ""); s.teach!!.onContentChanged(PKG)
                s.teach!!.onClicked(PKG, sendNode())
            },
            "teach attempt 1: VIEW_CLICKED delivered 300 ms late" to Row(FIRST_TRY_NODE) {
                start(); s.teach!!.startTeaching(PKG); type("hi"); type(""); idle(300)
                s.teach!!.onClicked(PKG, sendNode())
            },
            "teach attempt 1: field loses focus on send, then click" to Row(FIRST_TRY_NODE) {
                start(); s.teach!!.startTeaching(PKG); type("hi")
                device.field = null; s.teach!!.onContentChanged(PKG)
                s.teach!!.onClicked(PKG, sendNode())
            },
            "teach started from Enter offer: first send saves" to Row(FIRST_TRY_NODE) {
                start(); enter(); type("hello"); tapSendAndroidOrder()
            },
            "teach then Enter: learned click, no re-teach" to Row(FIRST_TRY_NODE) {
                start(); s.teach!!.startTeaching(PKG); type("hi"); tapSendAndroidOrder(); idle(2_000)
                device.root = window(sendNode()); type("next")
                check(enter()) { "Enter consumed via learned node" }
                check(device.clicks.size == 1) { "clicks=${device.clicks.size}" }
            },
            "tap-mode fallback: no click event → step 2 once, capture, Done" to Row(tapMode()) {
                start(); tapModeTeach(retries = 0)
            },
            "tap-mode fallback: null click source → step 2 once, capture, Done" to Row(tapMode()) {
                start(); s.teach!!.startTeaching(PKG); type("hi"); type("")
                s.teach!!.onMissedClick()
                type("again"); overlay.screenTap(650, 640); idle(50); overlay.tap(Choice.YES)
            },
            "tap-mode fallback: unusable click (not clickable) → step 2 once" to Row(tapMode()) {
                start(); s.teach!!.startTeaching(PKG); type("hi")
                s.teach!!.onClicked(PKG, NodeSnap(className = "android.view.View", bounds = Box(610, 600, 700, 680)))
                type(""); type("again"); overlay.screenTap(650, 640); idle(50); overlay.tap(Choice.YES)
            },
            "tap-mode attempt 2 (one Retry) → saved" to Row(tapMode()) { start(); tapModeTeach(retries = 1) },
            "tap-mode attempt 3 (two Retries) → saved" to Row(tapMode()) { start(); tapModeTeach(retries = 2) },
            "step 2: stray usable click 2 s later is not saved as send" to Row(
                Outcome(teachUi = true, saved = Saved.NONE, step2Bars = 1, teachingAtEnd = true),
            ) {
                start(); s.teach!!.startTeaching(PKG); type("hi"); type(""); idle(2_000)
                s.teach!!.onClicked(PKG, sendNode())
            },
            "step 2: other app's send click right after box clears is ignored" to Row(
                Outcome(teachUi = true, saved = Saved.NONE, step2Bars = 1, teachingAtEnd = true),
            ) {
                start(); s.teach!!.startTeaching(PKG); type("hi"); type("")
                s.teach!!.onClicked(OTHER, sendNode(OTHER))
                check(db.rules().get(OTHER)?.isTaught != true) { "other app not taught" }
            },
            "cancel + new session: late-click window not inherited" to Row(
                Outcome(teachUi = true, saved = Saved.NONE, step2Bars = 2, teachingAtEnd = true),
            ) {
                start(); s.teach!!.startTeaching(PKG); type("hi"); type("")
                overlay.tap(Choice.CLOSE)
                s.teach!!.startTeaching(PKG)
                device.field = chatField("", pkg = PKG, viewId = "$PKG:id/entry", hint = null)
                s.teach!!.onClicked(PKG, NodeSnap(className = "android.view.View", bounds = Box(0, 0, 10, 10)))
                check(s.teach!!.isStep2Bar) { "unusable click → step 2 with no late-click window" }
                s.teach!!.onClicked(PKG, sendNode())
            },
            "Enter while teaching passes through and keeps step 1" to Row(FIRST_TRY_NODE) {
                start(); s.teach!!.startTeaching(PKG); type("hi")
                check(!enter()) { "Enter must pass through while teaching" }
                check(s.teach!!.isStep1) { "still step 1" }
                tapSendAndroidOrder()
            },
            "app switch mid-teach: other app's send ignored, then first-try save" to Row(FIRST_TRY_NODE) {
                start(); s.teach!!.startTeaching(PKG); type("hi")
                type("yo", OTHER); tapSendAndroidOrder(sendNode(OTHER), OTHER)
                check(s.teach!!.isStep1) { "other app must not advance teach" }
                check(db.rules().get(OTHER)?.isTaught != true) { "other app not taught" }
                type("hi"); tapSendAndroidOrder()
            },
            "process restart mid-teach: session dropped, nothing saved" to Row(Outcome(false, Saved.NONE)) {
                start(); s.teach!!.startTeaching(PKG); type("hi")
                restart(); type("")
                s.teach!!.onClicked(PKG, sendNode())
            },
            "process restart mid-teach then explicit teach → first-try save" to Row(FIRST_TRY_NODE) {
                start(); s.teach!!.startTeaching(PKG); type("hi")
                restart(); s.teach!!.startTeaching(PKG); type("hi"); tapSendAndroidOrder()
            },
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun data(): List<Array<Any>> = rows.map { (n, r) -> arrayOf(n, r) }
    }
}

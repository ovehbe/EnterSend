package com.meowgi.entersend

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.meowgi.entersend.core.Box
import com.meowgi.entersend.core.Field
import com.meowgi.entersend.core.NodeSnap
import com.meowgi.entersend.core.Pixels
import com.meowgi.entersend.core.WindowState
import com.meowgi.entersend.data.AppDb
import com.meowgi.entersend.data.Crops
import com.meowgi.entersend.service.Card
import com.meowgi.entersend.service.Choice
import com.meowgi.entersend.service.Overlay
import com.meowgi.entersend.service.Device
import com.meowgi.entersend.service.EnterSendService
import com.meowgi.entersend.service.RuleStore
import org.robolectric.Robolectric
import java.util.concurrent.Executor

val direct = Executor { it.run() }

const val KEYBOARD = 3
const val VIRTUAL = -1
const val CHAT_TYPE = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

val FIELD_BOX = Box(10, 600, 600, 680)
val SEND_BOX = Box(610, 600, 700, 680)

fun chatField(
    text: String,
    pkg: String = "com.whatsapp",
    selStart: Int = text.length,
    selEnd: Int = selStart,
    inputType: Int = CHAT_TYPE,
    viewId: String? = "$pkg:id/entry",
    hint: String? = "Message",
    isPassword: Boolean = false,
) = Field(pkg, text, selStart, selEnd, inputType, isPassword, viewId, hint, FIELD_BOX)

fun sendButton(
    viewId: String? = "com.whatsapp:id/send",
    desc: String? = "Send",
    text: String? = null,
    bounds: Box = SEND_BOX,
    className: String = "android.widget.ImageButton",
) = NodeSnap(viewId = viewId, desc = desc, text = text, className = className, clickable = true, bounds = bounds)

fun window(vararg children: NodeSnap) = NodeSnap(
    className = "android.widget.FrameLayout",
    bounds = Box(0, 0, 720, 720),
    children = listOf(NodeSnap(className = "android.widget.EditText", editable = true, bounds = FIELD_BOX)) + children,
)

fun solid(color: Int, w: Int = 48, h: Int = 48) = Pixels(w, h, IntArray(w * h) { color })

class FakeDevice : Device {
    var field: Field? = null
    var root: NodeSnap? = null
    var window: WindowState? = WindowState(1, 720, 720, keyboardShown = false, density = 1f)
    var live: Pixels? = null
    /** Optional: return pixels based on the capture rect (multiline / moved-button fixtures). */
    var liveForRect: ((Box) -> Pixels?)? = null
    /** Field text after a click or tap; null means the app did not send. */
    var textAfterSend: String? = ""

    val clicks = mutableListOf<NodeSnap>()
    val taps = mutableListOf<Pair<Int, Int>>()
    val focuses = mutableListOf<Field>()
    val captureRects = mutableListOf<Box>()
    var captures = 0
    var imeEnters = 0
    var vibrations = 0
    val notices = mutableListOf<String>()
    /** Unfocused message-field candidates for [findMessageField] (auto-focus tests). */
    var messageFields: List<Field> = emptyList()

    override fun focusedField() = field
    override fun windowRoot(field: Field) = root
    override fun windowState(field: Field) = window

    override fun click(node: NodeSnap): Boolean {
        clicks += node
        sent()
        return true
    }

    /** When false, [focus] returns false so tests can exercise click / tap fallback. */
    var focusSucceeds: Boolean = true
    /** When false, [clickField] returns false. */
    var clickFieldSucceeds: Boolean = true
    val fieldClicks = mutableListOf<Field>()

    override fun focus(field: Field): Boolean {
        if (!focusSucceeds) return false
        focuses += field
        this.field = field
        return true
    }

    override fun focusForTyping(field: Field): Boolean = focus(field) || clickField(field)

    override fun clickField(field: Field): Boolean {
        if (!clickFieldSucceeds) return false
        fieldClicks += field
        this.field = field
        return true
    }

    override fun listEditableFields(pkg: String): List<Field> {
        val fromList = messageFields.filter { it.packageName == pkg }
        if (fromList.isNotEmpty()) return fromList
        return root?.flatten()?.filter { it.editable && it.visible }?.map { n ->
            Field(pkg, "", 0, 0, CHAT_TYPE, false, n.viewId, "Message", n.bounds, n.ref, n.className)
        }.orEmpty()
    }

    override fun windowStateForPackage(pkg: String): WindowState? = window

    override fun findMessageField(pkg: String, preferredViewId: String?): Field? {
        val fromList = messageFields.filter { it.packageName == pkg }
        if (preferredViewId != null) {
            fromList.firstOrNull { it.viewId == preferredViewId }?.let { return it }
            val match = root?.flatten()?.firstOrNull {
                it.editable && it.visible && it.viewId == preferredViewId
            } ?: return null
            return Field(
                pkg, "", 0, 0, CHAT_TYPE, false,
                match.viewId, null, match.bounds, match.ref, match.className,
            )
        }
        fromList.firstOrNull()?.let { return it }
        val n = root?.flatten()?.firstOrNull { it.editable && it.visible } ?: return null
        return Field(
            pkg, "", 0, 0, CHAT_TYPE, false,
            n.viewId, "Message", n.bounds, n.ref, n.className,
        )
    }

    override fun setText(field: Field, text: String, cursor: Int): Boolean {
        this.field = field.copy(text = text, selStart = cursor, selEnd = cursor)
        return true
    }

    override fun readText(field: Field) = this.field?.text

    override fun tap(x: Int, y: Int) {
        taps += x to y
        sent()
    }

    override fun imeEnter(field: Field) {
        imeEnters++
    }

    /** When set, the next [nullCount] captures return null (screenshot throttle tests). */
    var nullCapturesRemaining: Int = 0

    override fun capture(rect: Box, callback: (Pixels?) -> Unit) {
        captures++
        captureRects += rect
        if (nullCapturesRemaining > 0) {
            nullCapturesRemaining--
            callback(null)
            return
        }
        liveForRect?.invoke(rect)?.let { callback(it); return }
        val full = live
        if (full == null) {
            callback(null)
            return
        }
        if (full.width == rect.width && full.height == rect.height) {
            callback(full)
            return
        }
        // Legacy unit tests supply a small solid crop as [live] while production now captures
        // full-screen then crops candidates — expand a uniform buffer to the request size.
        if (full.width < rect.width || full.height < rect.height) {
            val color = full.argb[0]
            if (full.argb.all { it == color }) {
                callback(Pixels(rect.width, rect.height, IntArray(rect.width * rect.height) { color }))
                return
            }
        }
        callback(full.crop(rect))
    }

    override fun vibrate() {
        vibrations++
    }

    override fun notice(message: String) {
        notices += message
    }

    private fun sent() {
        textAfterSend?.let { t -> field = field?.copy(text = t, selStart = t.length, selEnd = t.length) }
    }
}

fun context(): Context = ApplicationProvider.getApplicationContext()

fun memoryDb(): AppDb = Room.inMemoryDatabaseBuilder(context(), AppDb::class.java).allowMainThreadQueries().build()

class FakeCrops(initial: Map<String, Pixels> = emptyMap()) : Crops {
    val files = initial.toMutableMap()
    override fun load(path: String) = files[path]
    override fun save(pkg: String, pixels: Pixels) = "/crops/$pkg-${files.size}.png".also { files[it] = pixels }
    override fun delete(path: String) {
        files.remove(path)
    }
}

class FakeOverlay : Overlay {
    var card: Card? = null
    val shown = mutableListOf<Card>()
    private var onChoice: ((Choice) -> Unit)? = null
    private var onTap: ((Int, Int) -> Unit)? = null

    override fun show(card: Card, onChoice: (Choice) -> Unit) {
        this.card = card
        this.onChoice = onChoice
        shown += card
    }

    override fun setTapListener(listener: ((x: Int, y: Int) -> Unit)?) {
        onTap = listener
    }

    override fun hide() {
        card = null
        onChoice = null
    }

    /** Simulates a touch on one of the card's buttons. */
    fun tap(choice: Choice) = onChoice!!(choice)

    fun screenTap(x: Int, y: Int) = onTap?.invoke(x, y)
}

fun service(
    device: Device,
    db: AppDb,
    crops: Crops = FakeCrops(),
    overlay: Overlay = FakeOverlay(),
    today: () -> Long = { 20_000L },
    io: Executor = direct,
): EnterSendService =
    Robolectric.setupService(EnterSendService::class.java).apply {
        attach(device, RuleStore(db.rules(), io, direct, crops), overlay, today)
    }

fun key(
    action: Int,
    down: Long,
    at: Long = down,
    code: Int = KeyEvent.KEYCODE_ENTER,
    repeat: Int = 0,
    meta: Int = 0,
    deviceId: Int = KEYBOARD,
) = KeyEvent(down, at, action, code, repeat, meta, deviceId, 0)

/** A physical press: down at [t], up at [t] + 80 ms. Returns whether each event was consumed. */
fun EnterSendService.press(t: Long, code: Int = KeyEvent.KEYCODE_ENTER, meta: Int = 0): List<Boolean> = listOf(
    onKeyEvent(key(KeyEvent.ACTION_DOWN, t, code = code, meta = meta)),
    onKeyEvent(key(KeyEvent.ACTION_UP, t, t + 80, code = code, meta = meta)),
)

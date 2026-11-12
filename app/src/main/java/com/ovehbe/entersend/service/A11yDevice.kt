package com.ovehbe.entersend.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.ovehbe.entersend.core.Box
import com.ovehbe.entersend.core.Field
import com.ovehbe.entersend.core.NodeSnap
import com.ovehbe.entersend.core.Pixels
import com.ovehbe.entersend.core.WindowState

class A11yDevice(private val service: AccessibilityService) : Device {

    override fun focusedField(): Field? {
        val n = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        if (!n.isEditable) return null
        return Field(
            packageName = n.packageName?.toString() ?: return null,
            text = textOf(n),
            selStart = n.textSelectionStart,
            selEnd = n.textSelectionEnd,
            inputType = n.inputType,
            isPassword = n.isPassword,
            viewId = n.viewIdResourceName,
            hint = n.hintText?.toString(),
            bounds = boundsOf(n),
            ref = n,
        )
    }

    override fun windowRoot(field: Field): NodeSnap? = rootOf(field)?.let { snap(it, 0, intArrayOf(0)) }

    override fun windowState(field: Field): WindowState? {
        val root = rootOf(field) ?: return null
        val b = boundsOf(root)
        return WindowState(service.resources.configuration.orientation, b.width, b.height)
    }

    override fun click(node: NodeSnap): Boolean =
        (node.ref as? AccessibilityNodeInfo)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true

    override fun setText(field: Field, text: String, cursor: Int): Boolean {
        val n = field.ref as? AccessibilityNodeInfo ?: return false
        val set = n.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) },
        )
        if (set) {
            n.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                },
            )
        }
        return set
    }

    override fun readText(field: Field): String? {
        val n = field.ref as? AccessibilityNodeInfo ?: return null
        if (!n.refresh() || !n.isVisibleToUser) return null
        return textOf(n)
    }

    override fun tap(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        service.dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 40)).build(),
            null,
            null,
        )
    }

    override fun imeEnter(field: Field) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        (field.ref as? AccessibilityNodeInfo)
            ?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    override fun capture(rect: Box, callback: (Pixels?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(null)
            return
        }
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val shot = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    buffer.close()
                    callback(shot?.let { crop(it, rect) })
                }

                override fun onFailure(errorCode: Int) = callback(null)
            },
        )
    }

    override fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            service.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            service.getSystemService(Vibrator::class.java)
        }
        vibrator?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun rootOf(field: Field): AccessibilityNodeInfo? =
        (field.ref as? AccessibilityNodeInfo)?.window?.root ?: service.rootInActiveWindow

    private fun snap(n: AccessibilityNodeInfo, depth: Int, count: IntArray): NodeSnap {
        count[0]++
        val kids = if (depth >= MAX_DEPTH) emptyList() else (0 until n.childCount).mapNotNull { i ->
            if (count[0] >= MAX_NODES) null else n.getChild(i)?.let { snap(it, depth + 1, count) }
        }
        return NodeSnap(
            viewId = n.viewIdResourceName,
            text = n.text?.toString(),
            desc = n.contentDescription?.toString(),
            className = n.className?.toString(),
            clickable = n.isClickable,
            enabled = n.isEnabled,
            visible = n.isVisibleToUser,
            editable = n.isEditable,
            bounds = boundsOf(n),
            children = kids,
            ref = n,
        )
    }

    companion object {
        private const val MAX_DEPTH = 40
        private const val MAX_NODES = 1500

        fun textOf(n: AccessibilityNodeInfo): String {
            val text = n.text?.toString() ?: return ""
            if (n.isShowingHintText || text == n.hintText?.toString()) return ""
            return text
        }

        /** The clicked node, with its parent attached so [NodeSnap.classPath] works. */
        fun clickedSnap(n: AccessibilityNodeInfo): NodeSnap {
            val self = NodeSnap(
                viewId = n.viewIdResourceName,
                text = n.text?.toString(),
                desc = n.contentDescription?.toString(),
                className = n.className?.toString(),
                clickable = n.isClickable,
                enabled = n.isEnabled,
                editable = n.isEditable,
                bounds = boundsOf(n),
                ref = n,
            )
            n.parent?.let { p -> NodeSnap(className = p.className?.toString(), bounds = boundsOf(p), children = listOf(self)) }
            return self
        }

        fun boundsOf(n: AccessibilityNodeInfo): Box {
            val r = Rect()
            n.getBoundsInScreen(r)
            return Box(r.left, r.top, r.right, r.bottom)
        }

        fun crop(shot: Bitmap, rect: Box): Pixels? {
            val l = rect.left.coerceIn(0, shot.width)
            val t = rect.top.coerceIn(0, shot.height)
            val r = rect.right.coerceIn(0, shot.width)
            val b = rect.bottom.coerceIn(0, shot.height)
            if (r - l <= 0 || b - t <= 0) return null
            val argb = IntArray((r - l) * (b - t))
            shot.getPixels(argb, 0, r - l, l, t, r - l, b - t)
            return Pixels(r - l, b - t, argb)
        }
    }
}

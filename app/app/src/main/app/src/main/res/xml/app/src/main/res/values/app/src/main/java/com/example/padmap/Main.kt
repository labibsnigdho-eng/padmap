package com.example.padmap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.app.Activity
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        box.addView(TextView(this).apply {
            text = "1. Tap the button, find PadMap controller, turn it ON.\n" +
                   "2. Connect your controller.\n" +
                   "3. Open FIFA Mobile (landscape) and play."
        })
        box.addView(Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        setContentView(box)
    }
}

// Positions are fractions of the screen (landscape): x left->right, y top->bottom.
sealed class Act {
    data class Tap(val x: Float, val y: Float) : Act()
    data class Swipe(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val ms: Long = 150) : Act()
}

object Mapping {
    const val JOY_X = 0.14f
    const val JOY_Y = 0.70f
    const val JOY_RADIUS = 0.10f

    val buttons: Map<Int, Act> = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to Act.Tap(0.88f, 0.82f),
        KeyEvent.KEYCODE_BUTTON_B to Act.Tap(0.80f, 0.68f),
        KeyEvent.KEYCODE_BUTTON_X to Act.Tap(0.94f, 0.65f),
        KeyEvent.KEYCODE_BUTTON_Y to Act.Tap(0.80f, 0.88f),
        KeyEvent.KEYCODE_BUTTON_R1 to Act.Tap(0.70f, 0.82f),
        KeyEvent.KEYCODE_BUTTON_L1 to Act.Tap(0.30f, 0.82f),
        KeyEvent.KEYCODE_BUTTON_R2 to Act.Swipe(0.94f, 0.65f, 0.80f, 0.45f, 200),
        KeyEvent.KEYCODE_BUTTON_L2 to Act.Swipe(0.88f, 0.82f, 0.88f, 0.55f, 150),
        KeyEvent.KEYCODE_BUTTON_THUMBR to Act.Swipe(0.60f, 0.50f, 0.75f, 0.50f, 120)
    )
}

class PadService : AccessibilityService() {
    private var stick: StrokeDescription? = null
    private var lastX = 0f
    private var lastY = 0f
    private var tgtX = 0f
    private var tgtY = 0f
    private var lastSend = 0L

    private fun w() = resources.displayMetrics.widthPixels.toFloat()
    private fun h() = resources.displayMetrics.heightPixels.toFloat()

    override fun onServiceConnected() {
        if (Build.VERSION.SDK_INT >= 34) {
            serviceInfo = serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_SEND_MOTION_EVENTS
                motionEventSources = InputDevice.SOURCE_JOYSTICK
            }
        }
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        val act = Mapping.buttons[e.keyCode] ?: return false
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            val s = when (act) {
                is Act.Tap -> StrokeDescription(pt(act.x, act.y), 0, 40)
                is Act.Swipe -> StrokeDescription(line(act.x1, act.y1, act.x2, act.y2), 0, act.ms)
            }
            send(s)
        }
        return true
    }

    override fun onMotionEvent(event: MotionEvent) {
        val x = event.getAxisValue(MotionEvent.AXIS_X)
        val y = event.getAxisValue(MotionEvent.AXIS_Y)
        val cx = Mapping.JOY_X * w()
        val cy = Mapping.JOY_Y * h()
        val r = Mapping.JOY_RADIUS * h()

        if (hypot(x, y) < 0.25f) {
            stick?.let {
                val end = it.continueStroke(pt(lastX, lastY), 0, 10, false)
                dispatchGesture(GestureDescription.Builder().addStroke(end).build(), null, null)
            }
            stick = null
            return
        }
        tgtX = cx + x * r
        tgtY = cy + y * r
        val now = System.currentTimeMillis()
        if (now - lastSend < 50) return
        if (stick == null) { lastX = cx; lastY = cy }
        send(null)
    }

    private fun send(extra: StrokeDescription?) {
        val b = GestureDescription.Builder()
        val holding = tgtX != 0f && (stick != null || extra == null)
        if (holding) {
            val p = line(lastX, lastY, tgtX, tgtY)
            val s = stick?.continueStroke(p, 0, 60, true) ?: StrokeDescription(p, 0, 60, true)
            stick = s
            lastX = tgtX
            lastY = tgtY
            b.addStroke(s)
        }
        extra?.let { b.addStroke(it) }
        lastSend = System.currentTimeMillis()
        try { dispatchGesture(b.build(), null, null) } catch (_: Exception) {}
    }

    private fun pt(fx: Float, fy: Float) = Path().apply { moveTo(fx.px(), fy.py()); lineTo(fx.px(), fy.py()) }
    private fun line(fx1: Float, fy1: Float, fx2: Float, fy2: Float) =
        Path().apply { moveTo(fx1.px(), fy1.py()); lineTo(fx2.px(), fy2.py()) }
    private fun Float.px() = if (this <= 1f) this * w() else this
    private fun Float.py() = if (this <= 1f) this * h() else this

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}

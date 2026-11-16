package com.example.monkeyclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class BotService : AccessibilityService() {
    // ---- Tuning ----
    private val AD_WAIT_MS = 30000L   // ad countdown ke liye wait
    private val MONKEY_Y = 0.88f      // bandar ki height (screen ka %)
    private val adWords = listOf(
        "adactivity", ".ads.", "applovin", "unity3d.services", "unity3d.ads", "vungle",
        "mintegral", "ironsource", "adunit", "interstitial", "rewarded", "adcolony",
        "chartboost", "inmobi", "fyber", "pangle", "bytedance", "admob"
    )
    private val closeSet = setOf("x", "✕", "×", "✖", "close", "skip", "skip ad", "close ad", "dismiss")

    private val h = Handler(Looper.getMainLooper())
    private var running = false
    private var busy = false
    private var gestureBusy = false
    private var state = 0 // 0 = game, 1 = ad
    private var adStart = 0L
    private var adTry = 0
    private var lastTap = 0L
    private var monkeyX = 0.5f
    private var sw = 1080
    private var sh = 2400
    private var lastClass = ""
    private var box: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var status: TextView? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            step()
            h.postDelayed(this, 150)
        }
    }

    override fun onServiceConnected() {
        sw = resources.displayMetrics.widthPixels
        sh = resources.displayMetrics.heightPixels
        addOverlay()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        box?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val cn = (e.className?.toString() ?: "").lowercase()
        if (cn.isEmpty() || cn.startsWith("android.") || cn.startsWith("com.android.systemui")) return
        lastClass = cn
        val isAd = adWords.any { cn.contains(it) }
        if (isAd) {
            if (state == 0) {
                state = 1
                adStart = System.currentTimeMillis()
                adTry = 0
            }
        } else if (state == 1) {
            state = 0
        }
        setStatus()
    }

    // ---------- Overlay (START / STOP) ----------
    private fun addOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xAA000000.toInt())
        }
        val handle = TextView(this).apply {
            text = "≡"; textSize = 24f; setTextColor(Color.WHITE); setPadding(24, 0, 24, 0)
        }
        val start = Button(this).apply {
            text = "START"; setTextColor(Color.WHITE); setBackgroundColor(0xFF1565C0.toInt())
            setOnClickListener { startBot() }
        }
        val stop = Button(this).apply {
            text = "STOP"; setTextColor(Color.WHITE); setBackgroundColor(0xFFC62828.toInt())
            setOnClickListener { stopBot() }
        }
        val st = TextView(this).apply {
            textSize = 10f; setTextColor(Color.WHITE); setPadding(12, 0, 12, 0); maxEms = 12
        }
        status = st
        b.addView(handle); b.addView(start); b.addView(stop); b.addView(st)

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = sw / 4
        p.y = 0
        lp = p
        box = b

        var sx = 0; var sy = 0; var dx = 0f; var dy = 0f
        handle.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) {
                sx = p.x; sy = p.y; dx = ev.rawX; dy = ev.rawY
            } else if (ev.action == MotionEvent.ACTION_MOVE) {
                p.x = sx + (ev.rawX - dx).toInt()
                p.y = sy + (ev.rawY - dy).toInt()
                wm.updateViewLayout(b, p)
            }
            true
        }
        wm.addView(b, p)
        setStatus()
    }

    private fun setStatus() {
        status?.text = (if (running) "ON " else "OFF ") + (if (state == 1) "AD " else "GAME ") + lastClass.takeLast(22)
    }

    private fun startBot() {
        if (running) return
        running = true; state = 0; monkeyX = 0.5f
        setStatus()
        h.post(tick)
    }

    private fun stopBot() {
        running = false
        h.removeCallbacks(tick)
        setStatus()
    }

    // ---------- Main loop ----------
    private fun step() {
        if (state == 1) { handleAd(); return }
        if (busy) return
        busy = true
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                    try {
                        val hb = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                        if (hb != null) {
                            val soft = hb.copy(Bitmap.Config.ARGB_8888, false)
                            hb.recycle()
                            r.hardwareBuffer.close()
                            if (soft != null && running) process(soft)
                        } else {
                            r.hardwareBuffer.close()
                        }
                    } catch (ex: Exception) {
                    }
                    busy = false
                }
                override fun onFailure(code: Int) { busy = false }
            })
    }

    private fun process(bmp: Bitmap) {
        sw = bmp.width; sh = bmp.height
        val w = 54
        val hh = bmp.height * w / bmp.width
        val s = Bitmap.createScaledBitmap(bmp, w, hh, true)
        bmp.recycle()
        val px = IntArray(w * hh)
        s.getPixels(px, 0, w, 0, 0, w, hh)
        s.recycle()
        val now = System.currentTimeMillis()
        if (now - lastTap > 1200 && tryButtons(px, w, hh)) return
        play(px, w, hh)
    }

    // ---------- Claim / Continue / Try Again ----------
    private fun tryButtons(px: IntArray, w: Int, hh: Int): Boolean {
        val n = findNode(rootInActiveWindow, 0) { t ->
            (t.contains("claim") && !t.contains("claimed")) || t.contains("continue") || t.contains("try again")
        }
        if (n != null) {
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.width() > 0) { tap(r.exactCenterX(), r.exactCenterY()); return true }
        }
        val b = greenBlob(px, w, hh)
        if (b != null) { tap(b[0] * sw, b[1] * sh); return true }
        return false
    }

    private fun greenBlob(px: IntArray, w: Int, hh: Int): FloatArray? {
        val g = BooleanArray(w * hh)
        val hsv = FloatArray(3)
        for (i in px.indices) {
            Color.colorToHSV(px[i], hsv)
            g[i] = hsv[0] in 85f..150f && hsv[1] > 0.45f && hsv[2] > 0.55f
        }
        val seen = BooleanArray(w * hh)
        val q = IntArray(w * hh)
        var best: FloatArray? = null
        var bestA = 0
        for (st in px.indices) {
            if (!g[st] || seen[st]) continue
            var qh = 0; var qt = 0
            q[qt++] = st; seen[st] = true
            var minX = w; var maxX = 0; var minY = hh; var maxY = 0; var a = 0
            while (qh < qt) {
                val c = q[qh++]
                val x = c % w; val y = c / w
                a++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0 && g[c - 1] && !seen[c - 1]) { seen[c - 1] = true; q[qt++] = c - 1 }
                if (x < w - 1 && g[c + 1] && !seen[c + 1]) { seen[c + 1] = true; q[qt++] = c + 1 }
                if (y > 0 && g[c - w] && !seen[c - w]) { seen[c - w] = true; q[qt++] = c - w }
                if (y < hh - 1 && g[c + w] && !seen[c + w]) { seen[c + w] = true; q[qt++] = c + w }
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val fill = a.toFloat() / (bw * bh)
            val asp = bw.toFloat() / bh
            if (a >= w * hh * 0.015 && fill > 0.6f && asp in 1.8f..8f &&
                bw < w * 0.8 && maxY < hh - 1 && a > bestA) {
                bestA = a
                best = floatArrayOf((minX + maxX + 1) / 2f / w, (minY + maxY + 1) / 2f / hh)
            }
        }
        return best
    }

    // ---------- Gameplay ----------
    private fun play(px: IntArray, w: Int, hh: Int) {
        if (gestureBusy) return
        val cols = 18
        val cw = w / cols
        val fruitY = IntArray(cols) { -1 }
        val fruitN = IntArray(cols)
        val bombN = IntArray(cols)
        val y0 = (hh * 0.10f).toInt()
        val y1 = (hh * 0.97f).toInt()
        val mx0 = ((monkeyX - 0.12f) * w).toInt()
        val mx1 = ((monkeyX + 0.12f) * w).toInt()
        val my0 = (hh * 0.80f).toInt()

        var ox0 = -1; var ox1 = -1; var oy0 = -1; var oy1 = -1
        val p = lp; val b = box
        if (p != null && b != null) {
            ox0 = (p.x - 20) * w / sw; ox1 = (p.x + b.width + 20) * w / sw
            oy0 = (p.y - 20) * hh / sh; oy1 = (p.y + b.height + 120) * hh / sh
        }
        val hsv = FloatArray(3)
        for (y in y0 until y1) {
            for (x in 0 until w) {
                if (y >= my0 && x in mx0..mx1) continue
                if (x in ox0..ox1 && y in oy0..oy1) continue
                val c = px[y * w + x]
                val col = minOf(x / cw, cols - 1)
                if (maxOf(Color.red(c), Color.green(c), Color.blue(c)) < 55) {
                    bombN[col]++
                    continue
                }
                Color.colorToHSV(c, hsv)
                if (hsv[1] > 0.55f && hsv[2] > 0.6f && (hsv[0] <= 65f || hsv[0] >= 335f)) {
                    fruitN[col]++
                    if (y > fruitY[col]) fruitY[col] = y
                }
            }
        }
        val bomb = BooleanArray(cols) { bombN[it] >= 3 }
        val unsafe = BooleanArray(cols) { c ->
            (c - 1..c + 1).any { it in 0 until cols && bomb[it] }
        }
        val cur = (monkeyX * cols).toInt().coerceIn(0, cols - 1)
        var target = -1
        var bestY = -1
        for (c in 0 until cols) {
            if (fruitN[c] >= 3 && !unsafe[c]) {
                val better = target < 0 || fruitY[c] > bestY + 2 ||
                    (abs(fruitY[c] - bestY) <= 2 && abs(c - cur) < abs(target - cur))
                if (better) { bestY = fruitY[c]; target = c }
            }
        }
        if (target < 0 && unsafe[cur]) {
            var bd = -1
            for (c in 0 until cols) {
                var d = cols
                for (k in 0 until cols) if (bomb[k]) d = minOf(d, abs(k - c))
                if (target < 0 || d > bd || (d == bd && abs(c - cur) < abs(target - cur))) {
                    bd = d; target = c
                }
            }
        }
        if (target >= 0 && target != cur) swipeTo((target + 0.5f) / cols)
    }

    private fun swipeTo(nx: Float) {
        val path = Path().apply {
            moveTo(monkeyX * sw, sh * MONKEY_Y)
            lineTo(nx * sw, sh * MONKEY_Y)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 90)).build()
        gestureBusy = true
        val ok = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gd: GestureDescription?) { gestureBusy = false }
            override fun onCancelled(gd: GestureDescription?) { gestureBusy = false }
        }, null)
        if (!ok) gestureBusy = false
        monkeyX = nx
    }

    // ---------- Ad ----------
    private fun handleAd() {
        val now = System.currentTimeMillis()
        val el = now - adStart
        if (el > 150000) { state = 0; setStatus(); return }
        if (now - lastTap < 1500) return
        if (el > 5000) {
            val n = findNode(rootInActiveWindow, 0) { t ->
                t in closeSet || t.contains("close ad") || t.contains("skip ad")
            }
            if (n != null) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() > 0) { tap(r.exactCenterX(), r.exactCenterY()); return }
            }
        }
        if (el > AD_WAIT_MS) {
            val pts = arrayOf(
                floatArrayOf(0.93f, 0.07f), floatArrayOf(0.92f, 0.045f),
                floatArrayOf(0.07f, 0.07f), floatArrayOf(0.93f, 0.10f)
            )
            val p = pts[adTry++ % pts.size]
            tap(p[0] * sw, p[1] * sh)
        }
    }

    // ---------- Helpers ----------
    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(),
            null, null
        )
        lastTap = System.currentTimeMillis()
    }

    private fun findNode(n: AccessibilityNodeInfo?, d: Int, pred: (String) -> Boolean): AccessibilityNodeInfo? {
        if (n == null || d > 25) return null
        val t = ((n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")).lowercase().trim()
        if (t.isNotEmpty() && pred(t)) return n
        for (i in 0 until n.childCount) {
            val r = findNode(n.getChild(i), d + 1, pred)
            if (r != null) return r
        }
        return null
    }
}

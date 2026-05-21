package com.hbx.shortapp

import android.app.*
import android.content.*
import android.content.res.Resources
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.view.GestureDetector
import android.view.View
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayService : Service() {

    companion object {
        const val ACTION_START  = "START_BUBBLE"
        const val ACTION_STOP   = "STOP_BUBBLE"
        const val NOTIF_ID      = 9001
        const val CHANNEL_ID    = "hbx_bubble_fg"
        var isRunning = false

        fun syncConfigFromPrefs(ctx: Context) {
            // No-op — config synced via Intent on startBubble
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var wm: WindowManager
    private lateinit var bubbleView: View
    private var panelView: View? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var expanded = false
    private var config: JSONObject = JSONObject()
    private val prefs by lazy { getSharedPreferences("xenox_overlay", Context.MODE_PRIVATE) }

    // Drag state
    private var dragStartX = 0f; private var dragStartY = 0f
    private var dragStartPx = 0; private var dragStartPy = 0
    private var dragged = false

    // Long-press state
    private var lpJob: Job? = null
    private var lpCol: String? = null

    // Toast-style bottom message
    private var msgView: View? = null
    private var msgJob: Job? = null

    // ── dp helper ────────────────────────────────────────────
    private val Float.dp get() = (this * Resources.getSystem().displayMetrics.density).toInt()
    private val Int.dp get() = (this * Resources.getSystem().displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        isRunning = true
        startForeground(NOTIF_ID, buildFgNotification())
        createBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val configStr = intent?.getStringExtra("config") ?: prefs.getString("xenox_float_config", "{}") ?: "{}"
        try { config = JSONObject(configStr) } catch (_: Exception) {}
        prefs.edit().putString("xenox_float_config", configStr).apply()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        try { wm.removeView(bubbleView) } catch (_: Exception) {}
        try { panelView?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { msgView?.let { wm.removeView(it) } } catch (_: Exception) {}
        super.onDestroy()
    }

    // ── Build foreground notification ────────────────────────
    private fun buildFgNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Float Bubble", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HBX Float Bubble")
            .setContentText("Tap to open app")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pi)
            .build()
    }

    // ── Columns from config ───────────────────────────────────
    private fun getCols(): List<String> {
        val raw = config.optString("columns", "A,B,C")
        return raw.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.take(8)
    }

    // ── Create main bubble view ───────────────────────────────
    private fun createBubble() {
        val size = config.optInt("launcherSize", 62).dp
        val dm = Resources.getSystem().displayMetrics
        val startX = dm.widthPixels - size - 14.dp
        val startY = dm.heightPixels - size - 200.dp

        val winType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        bubbleParams = WindowManager.LayoutParams(
            size, size, winType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX; y = startY
        }

        bubbleView = makeBubbleView(size)
        setupBubbleTouchDrag(bubbleView, size)
        wm.addView(bubbleView, bubbleParams)
    }

    private fun makeBubbleView(size: Int): View {
        val ctx = this
        val frame = FrameLayout(ctx)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#060606"))
        }
        frame.background = bg
        frame.elevation = 12f

        // Glow ring
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(3.dp, Color.parseColor("#8B5CF6"))
            setColor(Color.TRANSPARENT)
        }
        val ringView = View(ctx)
        ringView.background = ring
        frame.addView(ringView, FrameLayout.LayoutParams(size, size))

        // Icon
        val icon = ImageView(ctx)
        try {
            val bmp = android.graphics.BitmapFactory.decodeStream(assets.open("flutter_assets/assets/launcher_icon.png"))
            if (bmp != null) icon.setImageBitmap(bmp)
            else icon.setImageResource(R.mipmap.ic_launcher)
        } catch (_: Exception) {
            icon.setImageResource(R.mipmap.ic_launcher)
        }
        icon.scaleType = ImageView.ScaleType.CENTER_CROP
        val iconP = FrameLayout.LayoutParams(size, size)
        val pad = (size * 0.05f).toInt()
        icon.setPadding(pad, pad, pad, pad)
        frame.addView(icon, iconP)

        return frame
    }

    // ── Touch / Drag on bubble ────────────────────────────────
    private fun setupBubbleTouchDrag(view: View, size: Int) {
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.rawX; dragStartY = event.rawY
                    dragStartPx = bubbleParams.x; dragStartPy = bubbleParams.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartX; val dy = event.rawY - dragStartY
                    if (abs(dx) > 8f || abs(dy) > 8f) {
                        dragged = true
                        val dm = Resources.getSystem().displayMetrics
                        bubbleParams.x = (dragStartPx + dx).toInt()
                            .coerceIn(0, dm.widthPixels - size)
                        bubbleParams.y = (dragStartPy + dy).toInt()
                            .coerceIn(0, dm.heightPixels - size)
                        try { wm.updateViewLayout(view, bubbleParams) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) toggleExpanded()
                    true
                }
                else -> false
            }
        }
    }

    // ── Expand / Collapse ─────────────────────────────────────
    private fun toggleExpanded() {
        if (expanded) collapse() else expand()
    }

    private fun expand() {
        if (expanded) return
        expanded = true
        updateBubbleAppearance(true)
        showPanel()
    }

    private fun collapse() {
        if (!expanded) return
        expanded = false
        updateBubbleAppearance(false)
        removePanel()
    }

    private fun updateBubbleAppearance(open: Boolean) {
        (bubbleView as? FrameLayout)?.let { frame ->
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (open) Color.parseColor("#DC2626") else Color.parseColor("#060606"))
            }
            frame.background = bg
            // Show minus icon when open
            val iconView = frame.getChildAt(1) as? ImageView
            if (open) {
                iconView?.setImageResource(android.R.drawable.ic_delete)
                iconView?.setColorFilter(Color.WHITE)
            } else {
                try {
                    val bmp = android.graphics.BitmapFactory.decodeStream(
                        assets.open("flutter_assets/assets/launcher_icon.png"))
                    iconView?.setImageBitmap(bmp)
                    iconView?.clearColorFilter()
                } catch (_: Exception) {
                    iconView?.setImageResource(R.mipmap.ic_launcher)
                    iconView?.clearColorFilter()
                }
            }
        }
    }

    // ── Panel with column buttons ─────────────────────────────
    private fun showPanel() {
        val cols = getCols()
        val btnSize = config.optInt("buttonSize", 52).dp
        val gap = 10.dp
        val panelH = (btnSize + gap) * (cols.size + 2) + gap
        val panelW = btnSize + 20.dp

        val winType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val pParams = WindowManager.LayoutParams(
            panelW, panelH, winType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Position panel above bubble
            x = bubbleParams.x - (panelW - config.optInt("launcherSize", 62).dp) / 2
            y = bubbleParams.y - panelH - 8.dp
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(10.dp, 8.dp, 10.dp, 8.dp)
        }

        // ① 2FA button
        panel.addView(makeCircleBtn("☯", btnSize, "#1e2535", clickable = true) {
            collapse()
            handle2FA()
        })
        panel.addView(spacer(gap))

        // ② Column buttons
        for (col in cols) {
            val btn = makeCircleBtn(col, btnSize, "#1e2535", clickable = true, onClick = null)
            // Short tap = APPEND, Long press = DELETE
            setupColBtn(btn, col)
            panel.addView(btn)
            panel.addView(spacer(gap))
        }

        // ③ Close button
        panel.addView(makeCircleBtn("−", btnSize, "#2a2f40", clickable = true) {
            collapse()
        })

        panelView = panel
        wm.addView(panel, pParams)

        // Dismiss on outside touch via bubble touch listener watching outside
        // (handled by watching ACTION_OUTSIDE in bubbleView if needed)
    }

    private fun removePanel() {
        try { panelView?.let { wm.removeView(it) } } catch (_: Exception) {}
        panelView = null
    }

    // ── Circle button factory ─────────────────────────────────
    private fun makeCircleBtn(
        label: String,
        size: Int,
        bgHex: String,
        clickable: Boolean,
        onClick: (() -> Unit)?
    ): FrameLayout {
        val frame = FrameLayout(this)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(bgHex))
        }
        frame.background = bg
        frame.elevation = 8f

        val tv = TextView(this).apply {
            text = label
            textSize = size * 0.28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        frame.addView(tv, FrameLayout.LayoutParams(size, size).also { it.gravity = Gravity.CENTER })

        val lp = LinearLayout.LayoutParams(size, size)
        frame.layoutParams = lp

        if (clickable && onClick != null) {
            frame.setOnClickListener { onClick() }
        }
        return frame
    }

    private fun spacer(h: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams(1, h)
        return v
    }

    // ── Column button touch handling ──────────────────────────
    private fun setupColBtn(btn: FrameLayout, col: String) {
        btn.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lpCol = col
                    lpJob = scope.launch {
                        delay(550)
                        // Long press → DELETE
                        withContext(Dispatchers.Main) {
                            lpJob = null
                            setColBtnDeleting(btn, true)
                            handleColDelete(col, btn)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (lpJob != null) {
                        // Short tap → APPEND
                        lpJob?.cancel(); lpJob = null
                        collapse()
                        handleColTap(col)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    lpJob?.cancel(); lpJob = null; true
                }
                else -> false
            }
        }
    }

    private fun setColBtnDeleting(btn: FrameLayout, deleting: Boolean) {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (deleting) Color.parseColor("#7C2D12") else Color.parseColor("#1e2535"))
        }
        btn.background = bg
        (btn.getChildAt(0) as? TextView)?.text = if (deleting) "🗑" else null
    }

    // ── APPEND: Clipboard → Sheet ─────────────────────────────
    private fun handleColTap(col: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
        if (text.isEmpty()) { showMsg("⚠ Clipboard খালি — কিছু copy করুন"); return }

        val isLocal = config.optString("saveMode", "google-sheet") == "local"
        if (isLocal) {
            val row = appendToLocalCol(col, text)
            showMsg("✓ $col → row $row")
        } else {
            showMsg("⏳ Saving to $col…")
            scope.launch(Dispatchers.IO) {
                try {
                    val result = postToSheet(col, "APPEND", text)
                    val row = result.optInt("savedRow", 0)
                    withContext(Dispatchers.Main) { showMsg("✓ $col → row $row") }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { showMsg("❌ ${e.message?.take(60)}") }
                }
            }
        }
    }

    // ── DELETE: Remove last entry ─────────────────────────────
    private fun handleColDelete(col: String, btn: FrameLayout) {
        val isLocal = config.optString("saveMode", "google-sheet") == "local"
        scope.launch {
            try {
                if (isLocal) {
                    val count = deleteLastLocalCol(col)
                    showMsg("🗑 $col deleted — count: $count")
                } else {
                    showMsg("⏳ Deleting from $col…")
                    val result = withContext(Dispatchers.IO) { postToSheet(col, "DELETE", null) }
                    val count = result.optInt("currentCount", 0)
                    showMsg("🗑 $col deleted — count: $count")
                }
            } catch (e: Exception) {
                showMsg("❌ Delete failed: ${e.message?.take(50)}")
            } finally {
                delay(800)
                withContext(Dispatchers.Main) {
                    collapse()
                }
            }
        }
    }

    // ── 2FA ───────────────────────────────────────────────────
    private fun handle2FA() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val secret = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
        if (secret.isEmpty()) { showMsg("⚠ 2FA secret copy করুন"); return }
        try {
            val code = generateTotp(secret)
            val clip = android.content.ClipData.newPlainText("2FA", code)
            clipboard.setPrimaryClip(clip)
            showMsg("Copy = $code")
        } catch (_: Exception) {
            showMsg("❌ Invalid 2FA secret key")
        }
    }

    // ── Google Apps Script POST ───────────────────────────────
    private fun postToSheet(col: String, action: String, text: String?): JSONObject {
        val webAppUrl = config.optString("webAppUrl", "")
        if (webAppUrl.isEmpty()) throw Exception("Web App URL নেই — Float Sheet Set-Up করুন")

        val body = JSONObject().apply {
            put("sheetId", config.optString("extractedSheetId", config.optString("sheetLinkOrId", "")))
            put("sheetName", config.optString("sheetName", "Sheet1"))
            put("column", col)
            put("action", action)
            if (text != null) put("text", text.take(400))
        }.toString()

        val url = URL(webAppUrl)
        val conn = url.openConnection() as HttpsURLConnection
        conn.apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "text/plain;charset=utf-8")
            connectTimeout = 15000; readTimeout = 15000
            instanceFollowRedirects = true
            doOutput = true
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val response = conn.inputStream.bufferedReader().use { it.readText() }
        return try { JSONObject(response) } catch (_: Exception) { JSONObject("{\"result\":\"success\",\"savedRow\":0,\"currentCount\":0}") }
    }

    // ── Local Sheet (SharedPreferences) ───────────────────────
    private fun getLocalSheet(): JSONObject {
        val raw = prefs.getString("xenox_local_sheet_data", "{}") ?: "{}"
        return try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
    }

    private fun saveLocalSheet(sheet: JSONObject) {
        prefs.edit().putString("xenox_local_sheet_data", sheet.toString()).apply()
    }

    private fun appendToLocalCol(col: String, text: String): Int {
        val sheet = getLocalSheet()
        val arr = if (sheet.has(col)) sheet.getJSONArray(col) else JSONArray()
        arr.put(text.take(400))
        sheet.put(col, arr)
        saveLocalSheet(sheet)
        return arr.length()
    }

    private fun deleteLastLocalCol(col: String): Int {
        val sheet = getLocalSheet()
        if (!sheet.has(col)) return 0
        val arr = sheet.getJSONArray(col)
        if (arr.length() == 0) return 0
        val newArr = JSONArray()
        for (i in 0 until arr.length() - 1) newArr.put(arr.getString(i))
        if (newArr.length() == 0) sheet.remove(col) else sheet.put(col, newArr)
        saveLocalSheet(sheet)
        return newArr.length()
    }

    // ── TOTP Generation ───────────────────────────────────────
    private fun generateTotp(secret: String): String {
        val base32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val cleaned = secret.uppercase().replace(" ", "").trimEnd('=')
        var buffer = 0L; var bitsLeft = 0
        val bytes = mutableListOf<Byte>()
        for (c in cleaned) {
            val idx = base32.indexOf(c); if (idx < 0) continue
            buffer = (buffer shl 5) or idx.toLong(); bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8; bytes.add((buffer shr bitsLeft).toByte())
                buffer = buffer and ((1L shl bitsLeft) - 1)
            }
        }
        val key = bytes.toByteArray()
        val time = System.currentTimeMillis() / 1000L / 30L
        val msg = ByteArray(8) { i -> (time shr ((7 - i) * 8)).toByte() }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "RAW"))
        val hash = mac.doFinal(msg)
        val offset = hash[hash.size - 1].toInt() and 0x0f
        val code = ((hash[offset].toInt() and 0x7f) shl 24) or
                ((hash[offset + 1].toInt() and 0xff) shl 16) or
                ((hash[offset + 2].toInt() and 0xff) shl 8) or
                (hash[offset + 3].toInt() and 0xff)
        return String.format("%06d", code % 1_000_000)
    }

    // ── Small bottom toast message ────────────────────────────
    private fun showMsg(text: String) {
        msgJob?.cancel()
        try { msgView?.let { wm.removeView(it) } } catch (_: Exception) {}

        val winType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val tv = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(20.dp, 8.dp, 20.dp, 8.dp)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC1a1a2e"))
                cornerRadius = 20.dp.toFloat()
            }
            elevation = 20f
        }
        val mParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            winType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 120.dp
        }
        msgView = tv
        try { wm.addView(tv, mParams) } catch (_: Exception) {}

        msgJob = scope.launch {
            delay(2500)
            withContext(Dispatchers.Main) {
                try { msgView?.let { wm.removeView(it) }; msgView = null } catch (_: Exception) {}
            }
        }
    }
}

package com.me.island

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.view.Gravity
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

internal enum class Kind { CALL, ONGOING, MEDIA, NOTE, CHARGE }

internal class Live(
    val key: String, val type: Kind, val pkg: String,
    val title: CharSequence, val text: CharSequence, val image: Drawable?,
    val actions: List<Notification.Action> = emptyList(),
    val tap: PendingIntent? = null,
    val chronoBase: Long = 0L, val chronoDown: Boolean = false,
    val progress: Int = -1, val playing: Boolean = false,
    val controller: MediaController? = null,
    var holdOpen: Boolean = false
)

class IslandListener : NotificationListenerService() {

    companion object {
        // ---- tweak for your A56 ----
        const val IDLE_W = 96      // dp, idle pill (covers the camera hole)
        const val IDLE_H = 30      // dp
        const val COMPACT_W = 190  // dp, pill when a live activity is running
        const val OPEN_W = 350     // dp, expanded width
        const val TOP = 10         // dp from top edge
        val GREEN = Color.parseColor("#30D158")
        val RED = Color.parseColor("#FF453A")
        val GRAY = Color.parseColor("#2C2C2E")
        val LIVE_CATS = setOf(
            Notification.CATEGORY_CALL, Notification.CATEGORY_NAVIGATION,
            Notification.CATEGORY_PROGRESS, Notification.CATEGORY_STOPWATCH,
            Notification.CATEGORY_WORKOUT, Notification.CATEGORY_LOCATION_SHARING
        )
        val DANGER = Regex("decline|reject|end|hang|dismiss|cancel|stop|delete|clear", RegexOption.IGNORE_CASE)
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var msm: MediaSessionManager
    private lateinit var root: FrameLayout
    private lateinit var compact: LinearLayout
    private lateinit var expandedL: LinearLayout
    private lateinit var bg: GradientDrawable
    private lateinit var lp: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())
    private val comp by lazy { ComponentName(this, IslandListener::class.java) }

    private val live = LinkedHashMap<String, Live>()
    private val controllers = HashMap<String, Pair<MediaController, MediaController.Callback>>()
    private var transient: Live? = null
    private var shown: Live? = null
    private var expanded = false
    private var added = false
    private var anim: ValueAnimator? = null
    private var mediaBar: ThinBar? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)

    private val collapseRun = Runnable { if (shown?.holdOpen != true) collapse() }

    private val tick = object : Runnable {
        override fun run() {
            val s = shown; val c = s?.controller; val bar = mediaBar
            if (expanded && s?.type == Kind.MEDIA && c != null && bar != null) {
                val ps = c.playbackState
                val dur = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                if (ps != null && dur > 0) {
                    var pos = ps.position
                    if (ps.state == PlaybackState.STATE_PLAYING)
                        pos += ((SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime) * ps.playbackSpeed).toLong()
                    bar.frac = pos.toFloat() / dur
                }
                handler.postDelayed(this, 500)
            }
        }
    }

    private val power = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { showCharge() }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        bindSessions(list ?: emptyList())
    }

    private val gd by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { onTap(); return true }
            override fun onLongPress(e: MotionEvent) {
                if (!expanded) primary()?.let {
                    root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    expand(it, 8000)
                }
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (expanded && vy < -400) { shown?.holdOpen = false; collapse(); return true }
                return false
            }
        })
    }

    // ------------------------------------------------------------ lifecycle

    override fun onListenerConnected() {
        if (added || !Settings.canDrawOverlays(this)) return
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("island", MODE_PRIVATE)
        buildViews()
        wm.addView(root, lp)
        added = true
        registerReceiver(power, IntentFilter(Intent.ACTION_POWER_CONNECTED), Context.RECEIVER_EXPORTED)
        msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        runCatching {
            msm.addOnActiveSessionsChangedListener(sessionsListener, comp)
            bindSessions(msm.getActiveSessions(comp))
        }
        runCatching { activeNotifications?.forEach { handle(it, false) } }
        refresh()
    }

    override fun onListenerDisconnected() {
        if (!added) return
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(power) }
        runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        controllers.values.forEach { it.first.unregisterCallback(it.second) }
        controllers.clear(); live.clear()
        runCatching { wm.removeView(root) }
        added = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handler.post { if (added) handle(sbn, true) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        handler.post { if (added && live.remove(sbn.key) != null) refresh() }
    }

    // ------------------------------------------------------------ views

    private fun buildViews() {
        bg = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = dp(IDLE_H) / 2f }
        compact = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        expandedL = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            visibility = View.GONE
            alpha = 0f
        }
        root = FrameLayout(this).apply {
            background = bg
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            addView(compact, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(expandedL, FrameLayout.LayoutParams(dp(OPEN_W), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            setOnTouchListener { _, e -> gd.onTouchEvent(e) }
            // experimental: hide in fullscreen apps (toggle in the app)
            setOnApplyWindowInsetsListener { v, ins ->
                if (prefs.getBoolean("hideFs", false))
                    v.visibility = if (ins.isVisible(WindowInsets.Type.statusBars())) View.VISIBLE else View.INVISIBLE
                ins
            }
        }
        lp = WindowManager.LayoutParams(
            dp(IDLE_W), dp(IDLE_H),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(TOP) }
    }

    private fun <V : View> V.rounded(rdp: Int): V {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, dp(rdp).toFloat())
            }
        }
        clipToOutline = true
        return this
    }

    private fun tv(t: CharSequence, sp: Float, color: Int, bold: Boolean, lines: Int) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(color); maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun chrono(l: Live, sp: Float) = Chronometer(this).apply {
        base = l.chronoBase
        isCountDown = l.chronoDown
        textSize = sp
        setTextColor(if (l.type == Kind.CALL) GREEN else Color.WHITE)
        start()
    }

    // ------------------------------------------------------------ compact pill

    private fun buildCompact() {
        compact.removeAllViews()
        val p = primary() ?: return
        compact.addView(
            ImageView(this).apply { setImageDrawable(p.image); scaleType = ImageView.ScaleType.CENTER_CROP }.rounded(7),
            lp(dp(22), dp(22))
        )
        compact.addView(View(this), lp(0, 1, 1f)) // spacer sits over the camera hole
        val right: View = when {
            p.type == Kind.MEDIA -> EqView(this).apply { playing = p.playing }
            p.chronoBase != 0L -> chrono(p, 12f)
            p.progress >= 0 -> tv("${p.progress}%", 12f, Color.WHITE, true, 1)
            live.size > 1 -> ImageView(this).apply {
                setImageDrawable(live.values.first { it !== p }.image); scaleType = ImageView.ScaleType.CENTER_CROP
            }.rounded(7)
            else -> View(this)
        }
        val s = if (right is TextView || right is Chronometer) ViewGroup.LayoutParams.WRAP_CONTENT else dp(22)
        compact.addView(right, lp(s, dp(22)))
    }

    // ------------------------------------------------------------ expanded card

    private fun buildExpanded(l: Live) {
        expandedL.removeAllViews()
        mediaBar = null
        val isMedia = l.type == Kind.MEDIA
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val size = if (isMedia) 60 else 44
        header.addView(
            ImageView(this).apply { setImageDrawable(l.image); scaleType = ImageView.ScaleType.CENTER_CROP }
                .rounded(if (isMedia) 12 else 11),
            lp(dp(size), dp(size))
        )
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
        col.addView(tv(l.title, 15f, Color.WHITE, true, 1))
        if (l.text.isNotBlank()) col.addView(tv(l.text, 13f, 0xFFAAAAAA.toInt(), false, if (isMedia) 1 else 2))
        header.addView(col, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (l.chronoBase != 0L) header.addView(chrono(l, 16f))
        expandedL.addView(header)

        if (isMedia) {
            val bar = ThinBar(this)
            mediaBar = bar
            expandedL.addView(bar, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(14) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            fun btn(res: Int, onClick: () -> Unit) = ImageButton(this).apply {
                setImageResource(res); setColorFilter(Color.WHITE); background = null
                setOnClickListener { onClick() }
            }
            val tc = l.controller?.transportControls
            row.addView(btn(android.R.drawable.ic_media_previous) { tc?.skipToPrevious() }, lp(dp(64), dp(48)))
            row.addView(
                btn(if (l.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play) {
                    if (l.playing) tc?.pause() else tc?.play()
                }, lp(dp(64), dp(48))
            )
            row.addView(btn(android.R.drawable.ic_media_next) { tc?.skipToNext() }, lp(dp(64), dp(48)))
            expandedL.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            handler.removeCallbacks(tick); handler.post(tick)
        } else {
            if (l.progress >= 0) {
                val bar = ThinBar(this).apply {
                    frac = l.progress / 100f
                    if (l.type == Kind.CHARGE) fill = GREEN
                }
                expandedL.addView(bar, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(12) })
            }
            val acts = l.actions.filter { it.remoteInputs.isNullOrEmpty() && !it.title.isNullOrBlank() }.take(3)
            if (acts.isNotEmpty()) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                acts.forEach { a ->
                    val danger = DANGER.containsMatchIn(a.title)
                    val color = when { danger -> RED; l.type == Kind.CALL -> GREEN; else -> GRAY }
                    val b = tv(a.title, 14f, Color.WHITE, true, 1).apply {
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(20).toFloat() }
                        setOnClickListener { fire(a.actionIntent); l.holdOpen = false; collapse() }
                    }
                    row.addView(b, lp(0, dp(40), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
                }
                expandedL.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            }
        }
    }

    // ------------------------------------------------------------ state machine

    private fun primary(): Live? = live.values.maxByOrNull {
        when (it.type) { Kind.CALL -> 3; Kind.ONGOING -> 2; else -> 1 }
    }

    private fun expand(l: Live, ms: Long) {
        handler.removeCallbacks(collapseRun)
        shown = l; expanded = true
        buildExpanded(l); buildCompact(); apply()
        if (ms > 0) handler.postDelayed(collapseRun, ms)
    }

    private fun collapse() {
        handler.removeCallbacks(collapseRun); handler.removeCallbacks(tick)
        expanded = false; shown = null; transient = null
        buildCompact(); apply()
    }

    private fun showTransient(l: Live, ms: Long) {
        if (shown?.holdOpen == true) return // never interrupt an incoming call
        transient = l
        expand(l, ms)
    }

    private fun refresh() {
        if (!added) return
        val s = shown
        if (expanded && s != null && s !== transient) {
            val fresh = live[s.key]
            if (fresh == null) { collapse(); return }
            shown = fresh
            buildExpanded(fresh)
        }
        if (!expanded) {
            val p = primary()
            if (p != null && p.holdOpen) { expand(p, 0); return }
        }
        buildCompact()
        apply()
    }

    private fun apply() {
        if (expanded) {
            expandedL.measure(
                View.MeasureSpec.makeMeasureSpec(dp(OPEN_W), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val h = expandedL.measuredHeight
            morph(dp(OPEN_W), h, minOf(dp(34).toFloat(), h / 2f), true)
        } else {
            val w = dp(if (primary() != null) COMPACT_W else IDLE_W)
            morph(w, dp(IDLE_H), dp(IDLE_H) / 2f, false)
        }
    }

    private fun morph(w: Int, h: Int, r: Float, open: Boolean) {
        anim?.cancel()
        val w0 = lp.width; val h0 = lp.height; val r0 = bg.cornerRadius
        val c0 = compact.alpha; val e0 = expandedL.alpha
        compact.visibility = View.VISIBLE
        if (open) expandedL.visibility = View.VISIBLE
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (open) 420 else 320
            interpolator = if (w >= w0 && h >= h0) OvershootInterpolator(1.0f) else DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                val fa = f.coerceIn(0f, 1f)
                lp.width = (w0 + (w - w0) * f).toInt()
                lp.height = (h0 + (h - h0) * f).toInt()
                bg.cornerRadius = r0 + (r - r0) * f
                compact.alpha = c0 + ((if (open) 0f else 1f) - c0) * fa
                expandedL.alpha = e0 + ((if (open) 1f else 0f) - e0) * fa
                root.invalidateOutline()
                if (added) wm.updateViewLayout(root, lp)
            }
            addListener(object : AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) {
                    if (cancelled) return
                    if (open) compact.visibility = View.GONE else expandedL.visibility = View.GONE
                }
            })
            start()
        }
    }

    // ------------------------------------------------------------ actions

    private fun fire(pi: PendingIntent?) {
        if (pi == null) return
        val o = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= 34)
            o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        runCatching { pi.send(this, 0, null, null, null, null, o.toBundle()) }
    }

    private fun openApp(l: Live?) {
        if (l == null) return
        if (l.tap != null) fire(l.tap)
        else packageManager.getLaunchIntentForPackage(l.pkg)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun onTap() {
        if (expanded) {
            openApp(shown)
            shown?.holdOpen = false
            collapse()
        } else openApp(primary())
    }

    // ------------------------------------------------------------ notifications

    private fun handle(sbn: StatusBarNotification, fresh: Boolean) {
        val pkg = sbn.packageName
        if (pkg == packageName || pkg == "android" || pkg == "com.android.systemui") return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val x = n.extras
        if (x.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true) return // media handled via sessions
        val title = x.getCharSequence(Notification.EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: return
        val text = x.getCharSequence(Notification.EXTRA_TEXT) ?: ""
        val chrono = x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val max = x.getInt(Notification.EXTRA_PROGRESS_MAX)
        val prog = x.getInt(Notification.EXTRA_PROGRESS)
        val cat = n.category
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        val isCall = cat == Notification.CATEGORY_CALL
        val isLive = ongoing && (chrono || max > 0 || cat in LIVE_CATS)
        if (ongoing && !isLive && !isCall) return // ignore plain foreground-service noise

        val type = when { isCall -> Kind.CALL; isLive -> Kind.ONGOING; else -> Kind.NOTE }
        val img = n.getLargeIcon()?.loadDrawable(this)
            ?: runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()
        val l = Live(
            key = sbn.key, type = type, pkg = pkg, title = title, text = text, image = img,
            actions = n.actions?.toList() ?: emptyList(), tap = n.contentIntent,
            chronoBase = if (chrono) n.`when` - System.currentTimeMillis() + SystemClock.elapsedRealtime() else 0L,
            chronoDown = x.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
            progress = if (max > 0) prog * 100 / max else -1
        )
        if (type == Kind.CALL) l.holdOpen = !chrono // ringing = stays open until answered/declined

        if (type == Kind.NOTE) { if (fresh) showTransient(l, 4000) }
        else { live[sbn.key] = l; refresh() }
    }

    // ------------------------------------------------------------ media

    private fun bindSessions(list: List<MediaController>) {
        val pkgs = list.map { it.packageName }.toSet()
        controllers.keys.filter { it !in pkgs }.forEach { k ->
            controllers.remove(k)?.let { it.first.unregisterCallback(it.second) }
            live.remove("media:$k")
        }
        list.forEach { c ->
            if (!controllers.containsKey(c.packageName)) {
                val cb = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) = updateMedia(c)
                    override fun onMetadataChanged(metadata: MediaMetadata?) = updateMedia(c)
                    override fun onSessionDestroyed() { live.remove("media:${c.packageName}"); refresh() }
                }
                c.registerCallback(cb)
                controllers[c.packageName] = c to cb
            }
            updateMedia(c)
        }
        refresh()
    }

    private fun updateMedia(c: MediaController) {
        val key = "media:${c.packageName}"
        val st = c.playbackState?.state ?: PlaybackState.STATE_NONE
        val md = c.metadata
        if (md == null || st == PlaybackState.STATE_NONE || st == PlaybackState.STATE_STOPPED) {
            live.remove(key); refresh(); return
        }
        val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val img: Drawable? = bmp?.let { BitmapDrawable(resources, it) }
            ?: runCatching { packageManager.getApplicationIcon(c.packageName) }.getOrNull()
        live[key] = Live(
            key = key, type = Kind.MEDIA, pkg = c.packageName,
            title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown",
            text = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "",
            image = img, tap = c.sessionActivity,
            playing = st == PlaybackState.STATE_PLAYING, controller = c
        )
        refresh()
    }

    // ------------------------------------------------------------ charging

    private fun showCharge() {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        handler.post {
            if (added) showTransient(
                Live("charge", Kind.CHARGE, "", "Charging", "$lvl%",
                    getDrawable(android.R.drawable.ic_lock_idle_charging), progress = lvl),
                3000
            )
        }
    }
}

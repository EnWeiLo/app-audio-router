package com.enweilo.appaudiorouter

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.content.res.ColorStateList
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private class AppItem(val label: String, val pkg: String, val uid: Int, val icon: Drawable?)
    private class DevItem(val id: Int, val typeName: String, val product: String) {
        val emoji: String
            get() = when {
                typeName.contains("喇叭") -> "🔊"
                typeName.contains("聽筒") -> "📱"
                typeName.contains("藍牙") -> "🎧"
                typeName.contains("耳機") -> "🎧"
                typeName.contains("USB") -> "🔌"
                typeName.contains("HDMI") -> "📺"
                typeName.contains("電話") -> "☎️"
                else -> "🔈"
            }
    }

    private class PickRow(
        val title: String,
        val subtitle: String,
        val icon: Drawable?,
        val emoji: String?,
        val payload: Any,
    )

    private var service: IRouterService? = null
    private val bg = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val tone = TonePlayer()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val logBuf = StringBuilder()
    private var logExpanded = false
    private var appsCache: List<AppItem>? = null

    private var selectedApp: AppItem? = null
    private var selectedDev: DevItem? = null

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var appIcon: ImageView
    private lateinit var appValue: TextView
    private lateinit var devEmoji: TextView
    private lateinit var devValue: TextView
    private lateinit var resultText: TextView
    private lateinit var volumeSeek: SeekBar
    private lateinit var volumeValue: TextView
    private lateinit var volumeHint: TextView
    private lateinit var toneBtn: TextView
    private lateinit var logText: TextView
    private lateinit var logToggle: TextView
    private lateinit var focusSwitch: Switch
    private val prefs by lazy { getSharedPreferences("router", MODE_PRIVATE) }

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, RouterService::class.java.name))
            .daemon(true)
            .processNameSuffix("router")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                service = IRouterService.Stub.asInterface(binder)
                log("✅ 已連上分流服務")
                refreshVolume()
            } else {
                log("❌ 服務回傳的 binder 無效")
            }
            updateStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            log("⚠️ 分流服務已斷線")
            updateStatus()
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        main.post {
            updateStatus()
            if (hasShizukuPermission() && service == null) bindRouter()
        }
    }

    private val binderDead = Shizuku.OnBinderDeadListener {
        main.post {
            service = null
            log("⚠️ Shizuku 已停止")
            updateStatus()
        }
    }

    private val permResult = Shizuku.OnRequestPermissionResultListener { _, grant ->
        main.post {
            if (grant == PackageManager.PERMISSION_GRANTED) {
                log("✅ 已取得 Shizuku 授權")
                bindRouter()
            } else {
                log("❌ Shizuku 授權被拒絕")
            }
            updateStatus()
        }
    }

    // ================================================================ 畫面

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindow()
        setContentView(buildUi())

        log("歡迎！先確認 Shizuku 在執行，再點上方狀態列連線。")

        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permResult)
    }

    @Suppress("DEPRECATION")
    private fun setupWindow() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    @Suppress("DEPRECATION")
    private fun buildUi(): View {
        val root = FrameLayout(this)
        root.addView(GlowBackground(this), FrameLayout.LayoutParams(MATCH, MATCH))

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content)
        }
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))

        content.setPadding(dp(20), dp(40), dp(20), dp(24))
        content.setOnApplyWindowInsetsListener { v, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val sb = insets.getInsets(WindowInsets.Type.systemBars())
                top = sb.top; bottom = sb.bottom
            } else {
                top = insets.systemWindowInsetTop; bottom = insets.systemWindowInsetBottom
            }
            v.setPadding(dp(20), top + dp(18), dp(20), bottom + dp(28))
            insets
        }

        // ---- 標題
        content.addView(label("音效分流", 34f, bold = true))
        content.addView(label("讓單一 App 的聲音，走你指定的輸出裝置", 14f, Palette.textDim).apply {
            setPadding(0, dp(4), 0, dp(14))
        })

        // ---- 狀態膠囊
        statusDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.bad) }
        }
        statusText = label("Shizuku：檢查中…", 13f, Palette.text)
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val r = dpf(20)
            background = GlassDrawable(r, Color.argb(26, 255, 255, 255))
            foreground = rippleFor(r)
            setPadding(dp(14), dp(9), dp(16), dp(9))
            addView(statusDot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(10) })
            addView(statusText)
            setOnClickListener { connectShizuku() }
        }
        content.addView(pill, LinearLayout.LayoutParams(WRAP, WRAP))

        // ---- 卡片 1：分流設定
        val routeCard = card()
        routeCard.addView(sectionTitle("分流設定"))

        appIcon = ImageView(this).apply {
            setImageDrawable(placeholderIcon())
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        appValue = label("點這裡選擇", 16f, Palette.text, bold = true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        routeCard.addView(pickerRow("App", appIcon, appValue) { pickApp() })

        devEmoji = label("🔈", 20f).apply {
            gravity = Gravity.CENTER
            background = placeholderIcon()
        }
        devValue = label("點這裡選擇", 16f, Palette.text, bold = true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        routeCard.addView(pickerRow("輸出到", devEmoji, devValue) { pickDevice() }, lpTop(10))

        focusSwitch = Switch(this).apply {
            isChecked = prefs.getBoolean(KEY_FOCUS_DEFAULT, true)
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.WHITE, Color.argb(220, 255, 255, 255))
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Palette.accentA, Color.argb(70, 255, 255, 255))
            )
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(KEY_FOCUS_DEFAULT, c).apply() }
        }
        val focusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val r = dpf(20)
            background = GlassDrawable(r, Color.argb(16, 255, 255, 255), 2f, 28)
            setPadding(dp(16), dp(10), dp(10), dp(10))
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("和音樂同時播放", 15f, Palette.text, bold = true))
                addView(label("不讓這個 App 打斷正在播的音樂", 12f, Palette.textDim))
            }
            addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(focusSwitch)
            setOnClickListener { focusSwitch.toggle() }
        }
        routeCard.addView(focusRow, lpTop(10))

        routeCard.addView(row(
            capsule("套用分流", primary = true) { applyRoute() },
            capsule("全部還原", primary = false) { restoreAll() },
        ), lpTop(16))

        resultText = label("", 13f, Palette.textDim).apply {
            visibility = View.GONE
            setPadding(dp(4), dp(12), dp(4), 0)
        }
        routeCard.addView(resultText)
        addCard(content, routeCard)

        // ---- 卡片 2：喇叭音量
        val volCard = card()
        val volHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        volHeader.addView(sectionTitle("手機喇叭音量").apply { setPadding(dp(4), 0, 0, 0) },
            LinearLayout.LayoutParams(0, WRAP, 1f))
        volumeValue = label("— / —", 14f, Palette.text, bold = true)
        volHeader.addView(volumeValue)
        volCard.addView(volHeader)

        volumeSeek = styledSeekBar()
        val volRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("🔈", 18f))
            addView(volumeSeek, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(label("🔊", 18f))
        }
        volCard.addView(volRow, lpTop(10))
        volumeHint = label("連著藍牙時也能單獨調整。分流到喇叭的 App 會用這個音量。", 12f, Palette.textFaint).apply {
            setPadding(dp(4), dp(6), dp(4), 0)
        }
        volCard.addView(volumeHint)
        addCard(content, volCard)

        // ---- 卡片 3：測試工具
        val toolCard = card()
        toolCard.addView(sectionTitle("測試工具"))
        toneBtn = capsule("▶ 測試音", primary = false) { toggleTone() }
        toolCard.addView(row(
            toneBtn,
            capsule("診斷", primary = false) { runDiagnose() },
        ))
        toolCard.addView(row(
            capsule("權限檢查", primary = false) { runOnService("檢查權限") { it.probe() } },
            capsule("目前設定", primary = false) { runOnService("查詢") { it.activeRoutes() } },
        ), lpTop(10))
        toolCard.addView(label("測試音要先在上面把 App 選成「本 App」再套用分流。", 12f, Palette.textFaint).apply {
            setPadding(dp(4), dp(10), dp(4), 0)
        })
        addCard(content, toolCard)

        // ---- 卡片 4：紀錄
        val logCard = card()
        val logHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        logHeader.addView(sectionTitle("紀錄").apply { setPadding(dp(4), 0, 0, 0) },
            LinearLayout.LayoutParams(0, WRAP, 1f))
        logToggle = smallLink("展開") {
            logExpanded = !logExpanded
            renderLog()
        }
        logHeader.addView(logToggle)
        logHeader.addView(smallLink("複製") { copyLog() })
        logHeader.addView(smallLink("清除") {
            logBuf.setLength(0)
            renderLog()
        })
        logCard.addView(logHeader)
        logText = label("", 11.5f, Palette.textDim).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.15f)
            setPadding(dp(4), dp(10), dp(4), 0)
        }
        logCard.addView(logText)
        addCard(content, logCard)

        updateStatus()
        return root
    }

    // ---------------------------------------------------------------- 元件

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Int) = v * resources.displayMetrics.density

    private fun label(
        text: String,
        size: Float,
        color: Int = Palette.text,
        bold: Boolean = false,
    ) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun sectionTitle(text: String) = label(text, 13f, Palette.textDim, bold = true).apply {
        letterSpacing = 0.06f
        setPadding(dp(4), 0, 0, dp(12))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GlassDrawable(dpf(28))
        setPadding(dp(16), dp(18), dp(16), dp(18))
    }

    private fun addCard(parent: LinearLayout, card: View) {
        parent.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })
    }

    private fun lpTop(top: Int) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) }

    private fun capsule(text: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Palette.text)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(dp(12), dp(14), dp(12), dp(14))
        val r = dpf(24)
        background = if (primary) accentCapsule(r, dp(1)) else GlassDrawable(r, Color.argb(22, 255, 255, 255))
        foreground = rippleFor(r)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun smallLink(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Palette.accentA)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        foreground = rippleFor(dpf(12))
        setOnClickListener { onClick() }
    }

    private fun row(a: View, b: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(5) })
        addView(b, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(5) })
    }

    private fun placeholderIcon(): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.argb(30, 255, 255, 255))
        setStroke(dp(1), Color.argb(60, 255, 255, 255))
    }

    private fun pickerRow(caption: String, icon: View, value: TextView, onClick: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val r = dpf(20)
            background = GlassDrawable(r, Color.argb(16, 255, 255, 255), 2f, 28)
            foreground = rippleFor(r)
            setPadding(dp(12), dp(12), dp(14), dp(12))
            addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)))
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                addView(label(caption, 12f, Palette.textDim))
                addView(value)
            }
            addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(label("›", 26f, Palette.textFaint))
            setOnClickListener { onClick() }
        }

    private fun styledSeekBar() = SeekBar(this).apply {
        max = 25
        val track = GradientDrawable().apply {
            cornerRadius = dpf(5)
            setColor(Color.argb(45, 255, 255, 255))
        }
        val fill = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Palette.accentA, Palette.accentB)
        ).apply { cornerRadius = dpf(5) }
        val layers = LayerDrawable(arrayOf(track, ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL)))
        layers.setId(0, android.R.id.background)
        layers.setId(1, android.R.id.progress)
        progressDrawable = layers
        thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setSize(dp(26), dp(26))
            setStroke(dp(1), Color.argb(50, 0, 0, 0))
        }
        splitTrack = false
        minHeight = dp(8)
        maxHeight = dp(8)
        setPadding(dp(18), dp(14), dp(18), dp(14))
        isEnabled = false
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                volumeValue.text = "$progress / ${sb?.max ?: 25}"
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                setSpeakerVolume(sb?.progress ?: return)
            }
        })
    }

    // ---------------------------------------------------------------- 玻璃選單

    private class RowHolder(val icon: ImageView, val emoji: TextView, val title: TextView, val sub: TextView)

    private fun makeRowView(): View {
        val icon = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val emoji = label("", 22f).apply { gravity = Gravity.CENTER; background = placeholderIcon() }
        val iconBox = FrameLayout(this).apply {
            addView(icon, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(emoji, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        val title = label("", 15f, Palette.text, bold = true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val sub = label("", 12f, Palette.textFaint).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(title)
            addView(sub)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            foreground = rippleFor(dpf(16))
            addView(iconBox, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            tag = RowHolder(icon, emoji, title, sub)
        }
    }

    private fun bindRow(v: View, r: PickRow) {
        val h = v.tag as RowHolder
        h.title.text = r.title
        h.sub.text = r.subtitle
        if (r.icon != null) {
            h.icon.setImageDrawable(r.icon)
            h.icon.visibility = View.VISIBLE
            h.emoji.visibility = View.GONE
        } else {
            h.emoji.text = r.emoji ?: ""
            h.emoji.visibility = View.VISIBLE
            h.icon.visibility = View.GONE
        }
    }

    private inner class RowAdapter(private var rows: List<PickRow>) : BaseAdapter() {
        fun update(newRows: List<PickRow>) {
            rows = newRows
            notifyDataSetChanged()
        }

        override fun getCount() = rows.size
        override fun getItem(position: Int): PickRow = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: makeRowView()
            bindRow(v, rows[position])
            return v
        }
    }

    private fun showSheet(title: String, body: View): Dialog {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassDrawable(dpf(30), Color.argb(165, 20, 22, 32), 2f, 40)
            setPadding(dp(16), dp(20), dp(16), dp(14))
        }
        box.addView(label(title, 20f, bold = true).apply { setPadding(dp(6), 0, 0, dp(12)) })
        box.addView(body)
        box.addView(capsule("取消", primary = false) { d.dismiss() }, lpTop(12))
        d.setContentView(box)
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setDimAmount(0.35f)
            if (Build.VERSION.SDK_INT >= 31) {
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                val attrs = w.attributes
                attrs.blurBehindRadius = dp(28)
                w.attributes = attrs
            }
        }
        d.show()
        d.window?.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        return d
    }

    // ================================================================ Shizuku

    private fun hasShizukuPermission(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    private fun connectShizuku() {
        when {
            !Shizuku.pingBinder() -> log("❌ Shizuku 沒在執行：請先打開 Shizuku App 啟動服務")
            Shizuku.isPreV11() -> log("❌ Shizuku 版本太舊，請更新")
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> bindRouter()
            Shizuku.shouldShowRequestPermissionRationale() ->
                log("❌ 之前選過「拒絕」：請到 Shizuku App 的授權清單手動允許本 App")
            else -> Shizuku.requestPermission(REQ_SHIZUKU)
        }
        updateStatus()
    }

    private fun bindRouter() {
        if (service != null) {
            log("服務已經連上了")
            return
        }
        try {
            log("正在啟動分流服務…")
            Shizuku.bindUserService(serviceArgs, connection)
        } catch (t: Throwable) {
            log("❌ 啟動服務失敗：${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun updateStatus() {
        main.post {
            if (!::statusText.isInitialized) return@post
            val (text, color) = try {
                when {
                    !Shizuku.pingBinder() -> "Shizuku 未執行" to Palette.bad
                    Shizuku.isPreV11() -> "Shizuku 版本太舊" to Palette.bad
                    Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                        "尚未授權 · 點此連線" to Palette.warn
                    service == null -> "已授權 · 點此連線" to Palette.warn
                    else -> "已連線" to Palette.good
                }
            } catch (t: Throwable) {
                "狀態讀取失敗" to Palette.bad
            }
            statusText.text = text
            (statusDot.background as? GradientDrawable)?.setColor(color)
            volumeSeek.isEnabled = service != null
        }
    }

    private fun runOnService(label: String, block: (IRouterService) -> String) {
        val s = service
        if (s == null) {
            log("⚠️ 還沒連上服務，請先點上方狀態列連線")
            return
        }
        log("▶ $label…")
        bg.execute {
            val result = try {
                block(s)
            } catch (t: Throwable) {
                "❌ ${t.javaClass.simpleName}: ${t.message}"
            }
            log(result)
        }
    }

    // ================================================================ 選擇

    private fun pickApp() {
        val cached = appsCache
        if (cached != null) {
            showAppSheet(cached)
            return
        }
        toast("讀取 App 清單…")
        bg.execute {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(intent, 0)
                .distinctBy { it.activityInfo.packageName }
                .filter { it.activityInfo.packageName != packageName }
                .map { ri ->
                    AppItem(
                        ri.loadLabel(pm).toString(),
                        ri.activityInfo.packageName,
                        ri.activityInfo.applicationInfo.uid,
                        try { ri.loadIcon(pm) } catch (t: Throwable) { null },
                    )
                }
                .sortedBy { it.label.lowercase(Locale.ROOT) }
                .toMutableList()
            list.add(0, AppItem("本 App（測試音）", packageName, applicationInfo.uid, applicationInfo.loadIcon(pm)))
            appsCache = list
            main.post { showAppSheet(list) }
        }
    }

    private fun showAppSheet(all: List<AppItem>) {
        fun toRows(items: List<AppItem>) = items.map { PickRow(it.label, it.pkg, it.icon, "📦", it) }

        val adapter = RowAdapter(toRows(all))
        val search = EditText(this).apply {
            hint = "搜尋 App 名稱或套件名"
            setHintTextColor(Palette.textFaint)
            setTextColor(Palette.text)
            textSize = 15f
            isSingleLine = true
            background = GlassDrawable(dpf(16), Color.argb(20, 255, 255, 255), 2f, 20)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val list = ListView(this).apply {
            this.adapter = adapter
            divider = null
            selector = ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(search)
            addView(list, LinearLayout.LayoutParams(MATCH, dp(400)).apply { topMargin = dp(10) })
        }
        val d = showSheet("選擇 App", body)
        list.setOnItemClickListener { _, _, pos, _ ->
            val item = adapter.getItem(pos).payload as AppItem
            selectedApp = item
            appIcon.setImageDrawable(item.icon ?: placeholderIcon())
            appValue.text = item.label
            log("已選 App：${item.label}（UID ${item.uid}）")
            d.dismiss()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase(Locale.ROOT) ?: ""
                adapter.update(toRows(all.filter {
                    q.isEmpty() || it.label.lowercase(Locale.ROOT).contains(q) || it.pkg.contains(q)
                }))
            }
        })
    }

    private fun pickDevice() {
        val s = service
        if (s == null) {
            log("⚠️ 還沒連上服務，請先點上方狀態列連線")
            toast("請先連線 Shizuku")
            return
        }
        bg.execute {
            val raw = try {
                s.listDevices()
            } catch (t: Throwable) {
                log("❌ 讀取輸出裝置失敗：${t.message}")
                return@execute
            }
            val devs = raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
                val p = line.split('\t')
                val id = p[0].toIntOrNull() ?: return@mapNotNull null
                DevItem(id, p.getOrElse(1) { "" }, p.getOrElse(2) { "" }.trim())
            }
            main.post { showDeviceSheet(devs) }
        }
    }

    private fun showDeviceSheet(devs: List<DevItem>) {
        if (devs.isEmpty()) {
            log("❌ 找不到任何輸出裝置")
            return
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(body) }
        lateinit var d: Dialog
        for (dev in devs) {
            val v = makeRowView()
            bindRow(v, PickRow(dev.typeName, "${dev.product}  ·  id ${dev.id}", null, dev.emoji, dev))
            v.setOnClickListener {
                selectedDev = dev
                devEmoji.text = dev.emoji
                devValue.text = dev.typeName
                log("已選輸出裝置：${dev.typeName}（id ${dev.id}）")
                d.dismiss()
            }
            body.addView(v)
        }
        val maxH = (resources.displayMetrics.heightPixels * 0.5f).toInt()
        val wrap = FrameLayout(this).apply {
            addView(scroll)
        }
        scroll.layoutParams = FrameLayout.LayoutParams(MATCH, WRAP)
        body.measure(
            View.MeasureSpec.makeMeasureSpec(resources.displayMetrics.widthPixels, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val h = minOf(body.measuredHeight, maxH)
        wrap.layoutParams = LinearLayout.LayoutParams(MATCH, h)
        d = showSheet("選擇輸出裝置", wrap)
    }

    // ================================================================ 動作

    private fun applyRoute() {
        val app = selectedApp
        val dev = selectedDev
        if (app == null || dev == null) {
            toast("請先選好 App 和輸出裝置")
            return
        }
        val s = service
        if (s == null) {
            toast("請先連線 Shizuku")
            return
        }
        val wantIgnore = focusSwitch.isChecked && app.pkg != packageName
        log("▶ 分流「${app.label}」→ ${dev.typeName}")
        bg.execute {
            val result = try {
                s.route(app.uid, dev.id, true)
            } catch (t: Throwable) {
                "❌ ${t.javaClass.simpleName}: ${t.message}"
            }
            log(result)
            val ok = result.contains("registerAudioPolicy 成功")

            // 音訊焦點：開關開著就設 ignore，關著就還原
            var focusLine = ""
            if (ok && app.pkg != packageName) {
                val fr = try {
                    s.setFocusIgnore(app.pkg, wantIgnore)
                } catch (t: Throwable) {
                    "❌ ${t.javaClass.simpleName}: ${t.message}"
                }
                log(fr)
                val set = HashSet(prefs.getStringSet(KEY_FOCUS_PKGS, emptySet()) ?: emptySet())
                if (wantIgnore && fr.startsWith("✅")) set.add(app.pkg) else if (!wantIgnore) set.remove(app.pkg)
                prefs.edit().putStringSet(KEY_FOCUS_PKGS, set).apply()
                focusLine = when {
                    !fr.startsWith("✅") -> "\n⚠️ 同時播放設定失敗，詳情請看紀錄"
                    wantIgnore -> "\n♪ 不會打斷正在播的音樂"
                    else -> ""
                }
            }
            main.post {
                resultText.visibility = View.VISIBLE
                if (ok) {
                    resultText.setTextColor(Palette.good)
                    resultText.text = "✓ 已分流：${app.label} → ${dev.typeName}$focusLine\n" +
                        "這個 App 正在播放的話，先完全關掉再重開一次。"
                } else {
                    resultText.setTextColor(Palette.bad)
                    resultText.text = "✕ 分流失敗，詳情請看下方紀錄"
                }
            }
        }
    }

    private fun restoreAll() {
        val s = service
        if (s == null) {
            toast("請先連線 Shizuku")
            return
        }
        log("▶ 全部還原…")
        bg.execute {
            val sb = StringBuilder()
            sb.appendLine(try { s.clearAll() } catch (t: Throwable) { "❌ ${t.message}" })
            val pkgs = prefs.getStringSet(KEY_FOCUS_PKGS, emptySet()) ?: emptySet()
            val left = HashSet<String>()
            for (p in pkgs) {
                val r = try { s.setFocusIgnore(p, false) } catch (t: Throwable) { "❌ ${t.message}" }
                sb.appendLine(r)
                if (!r.startsWith("✅")) left.add(p)
            }
            prefs.edit().putStringSet(KEY_FOCUS_PKGS, left).apply()
            log(sb.toString().trimEnd())
            main.post {
                resultText.visibility = View.VISIBLE
                resultText.setTextColor(Palette.textDim)
                resultText.text = "已全部還原：聲音路由和音訊焦點都回到系統預設"
            }
        }
    }

    private fun refreshVolume() {
        val s = service ?: return
        bg.execute {
            val cur = try { s.getSpeakerVolume() } catch (t: Throwable) { -1 }
            val max = try { s.getMaxVolume() } catch (t: Throwable) { 25 }
            main.post {
                volumeSeek.isEnabled = true
                volumeSeek.max = if (max > 0) max else 25
                if (cur >= 0) {
                    volumeSeek.progress = cur
                    volumeValue.text = "$cur / ${volumeSeek.max}"
                } else {
                    volumeValue.text = "讀不到"
                }
            }
        }
    }

    private fun setSpeakerVolume(index: Int) {
        val s = service
        if (s == null) {
            toast("請先連線 Shizuku")
            return
        }
        bg.execute {
            val msg = try {
                s.setSpeakerVolume(index)
            } catch (t: Throwable) {
                "❌ ${t.javaClass.simpleName}: ${t.message}"
            }
            log(msg)
            main.post {
                if (!msg.startsWith("✅")) {
                    volumeHint.setTextColor(Palette.bad)
                    volumeHint.text = if (msg.startsWith("⚠️")) "系統沒有套用這個音量，詳情請看紀錄" else "設定失敗，詳情請看紀錄"
                } else {
                    volumeHint.setTextColor(Palette.textFaint)
                    volumeHint.text = "連著藍牙時也能單獨調整。分流到喇叭的 App 會用這個音量。"
                }
            }
            refreshVolume()
        }
    }

    private fun runDiagnose() {
        val app = selectedApp
        if (app == null) {
            toast("請先選 App")
            return
        }
        val s = service
        if (s == null) {
            toast("請先連線 Shizuku")
            return
        }
        val isSelf = app.pkg == packageName
        if (isSelf && !tone.isPlaying) toggleTone()
        if (!isSelf) log("💡 請確認「${app.label}」現在正在播放聲音")
        log("▶ 診斷中（約 2 秒）…")
        bg.execute {
            Thread.sleep(1500)
            val result = try {
                s.diagnose(app.uid)
            } catch (t: Throwable) {
                "❌ ${t.javaClass.simpleName}: ${t.message}"
            }
            log(result)
            main.post {
                logExpanded = true
                renderLog()
            }
        }
    }

    private fun toggleTone() {
        if (tone.isPlaying) {
            tone.stop()
            toneBtn.text = "▶ 測試音"
        } else {
            tone.start()
            toneBtn.text = "■ 停止"
        }
    }

    // ================================================================ 紀錄

    private fun log(msg: String) {
        main.post {
            logBuf.append("[${timeFmt.format(Date())}] ").append(msg).append("\n\n")
            renderLog()
        }
    }

    private fun renderLog() {
        if (!::logText.isInitialized) return
        if (logExpanded) {
            logText.text = logBuf
            logToggle.text = "收合"
        } else {
            val lines = logBuf.toString().trimEnd().lines()
            logText.text = lines.takeLast(8).joinToString("\n")
            logToggle.text = "展開"
        }
    }

    private fun copyLog() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("AppAudioRouter log", logBuf.toString()))
        toast("已複製紀錄")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        tone.stop()
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permResult)
        // 只解除連線，不關掉服務：關掉 App 後分流設定仍然有效
        try {
            if (service != null) Shizuku.unbindUserService(serviceArgs, connection, false)
        } catch (_: Throwable) {
        }
        bg.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQ_SHIZUKU = 1001
        private const val KEY_FOCUS_PKGS = "focus_ignored_pkgs"
        private const val KEY_FOCUS_DEFAULT = "focus_switch"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

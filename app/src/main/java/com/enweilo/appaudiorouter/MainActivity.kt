package com.enweilo.appaudiorouter

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private data class AppItem(val label: String, val pkg: String, val uid: Int) {
        override fun toString() = "$label\n$pkg"
    }

    private data class DevItem(val id: Int, val name: String)

    private var service: IRouterService? = null
    private val bg = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val tone = TonePlayer()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private var selectedApp: AppItem? = null
    private var selectedDev: DevItem? = null

    private lateinit var statusView: TextView
    private lateinit var appBtn: Button
    private lateinit var devBtn: Button
    private lateinit var toneBtn: Button
    private lateinit var shellBox: CheckBox
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

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
                log("✅ 已連上分流服務（在 Shizuku 裡執行）")
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

    // ---------------------------------------------------------------- 畫面

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        log(
            "使用步驟：\n" +
                "1. 先開 Shizuku App 確認服務在執行，再按「連線 Shizuku」\n" +
                "2. 按「① 檢查權限」看結論\n" +
                "3. 選 App、選輸出裝置，按「② 套用分流」\n" +
                "4. 想用本 App 自我測試：App 選「★ 本 App」，再按「播放測試音」"
        )

        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permResult)
    }

    private fun buildUi(): View {
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            fitsSystemWindows = true
        }

        root.addView(TextView(this).apply {
            text = "App 音效分流測試"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        })
        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(statusView)

        root.addView(row(
            button("連線 Shizuku") { connectShizuku() },
            button("① 檢查權限") { runOnService("檢查權限") { it.probe() } },
        ))

        appBtn = button("選擇 App：（未選）") { pickApp() }
        devBtn = button("選擇輸出裝置：（未選）") { pickDevice() }
        root.addView(appBtn, fullWidth())
        root.addView(devBtn, fullWidth())

        shellBox = CheckBox(this).apply {
            text = "用 com.android.shell 身分註冊（建議勾選）"
            isChecked = true
        }
        root.addView(shellBox)

        root.addView(row(
            button("② 套用分流") { applyRoute() },
            button("全部還原") { runOnService("還原") { it.clearAll() } },
        ))

        toneBtn = button("▶ 播放測試音") { toggleTone() }
        root.addView(row(
            toneBtn,
            button("目前設定") { runOnService("查詢") { it.activeRoutes() } },
        ))

        root.addView(button("③ 診斷：聲音實際走去哪") { runDiagnose() }, fullWidth())

        root.addView(row(
            button("複製紀錄") { copyLog() },
            button("清除紀錄") { logView.text = "" },
        ))

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(8))
        }
        logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        updateStatus()
        return root
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(a: View, b: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- Shizuku

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
            if (!::statusView.isInitialized) return@post
            statusView.text = try {
                when {
                    !Shizuku.pingBinder() -> "Shizuku：❌ 未執行"
                    Shizuku.isPreV11() -> "Shizuku：❌ 版本太舊"
                    Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "Shizuku：⚠️ 尚未授權"
                    service == null -> "Shizuku：✅ 已授權　服務：未連線"
                    else -> "Shizuku：✅ 已授權　服務：✅ 已連線"
                }
            } catch (t: Throwable) {
                "Shizuku：狀態讀取失敗"
            }
        }
    }

    private fun runOnService(label: String, block: (IRouterService) -> String) {
        val s = service
        if (s == null) {
            log("⚠️ 還沒連上服務，請先按「連線 Shizuku」")
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

    // ---------------------------------------------------------------- 選擇

    private fun pickApp() {
        bg.execute {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val items = pm.queryIntentActivities(intent, 0)
                .map { ri ->
                    AppItem(
                        ri.loadLabel(pm).toString(),
                        ri.activityInfo.packageName,
                        ri.activityInfo.applicationInfo.uid,
                    )
                }
                .distinctBy { it.pkg }
                .filter { it.pkg != packageName }
                .sortedBy { it.label.lowercase(Locale.ROOT) }
                .toMutableList()
            items.add(0, AppItem("★ 本 App（用測試音自我測試）", packageName, applicationInfo.uid))
            main.post { showAppDialog(items) }
        }
    }

    private fun showAppDialog(all: List<AppItem>) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, all.toMutableList())
        val search = EditText(this).apply {
            hint = "搜尋 App 名稱或套件名"
            isSingleLine = true
        }
        val list = ListView(this).apply { this.adapter = adapter }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(search)
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420)))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("選擇要分流的 App")
            .setView(box)
            .setNegativeButton("取消", null)
            .create()

        list.setOnItemClickListener { _, _, pos, _ ->
            val item = adapter.getItem(pos) ?: return@setOnItemClickListener
            selectedApp = item
            appBtn.text = "App：${item.label}"
            log("已選 App：${item.label}（${item.pkg}，UID ${item.uid}）")
            dialog.dismiss()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase(Locale.ROOT) ?: ""
                adapter.clear()
                adapter.addAll(all.filter {
                    q.isEmpty() || it.label.lowercase(Locale.ROOT).contains(q) || it.pkg.contains(q)
                })
            }
        })
        dialog.show()
    }

    private fun pickDevice() {
        val s = service
        if (s == null) {
            log("⚠️ 還沒連上服務，請先按「連線 Shizuku」")
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
                DevItem(id, "${p.getOrElse(1) { "" }}　${p.getOrElse(2) { "" }}  [id $id]")
            }
            main.post {
                if (devs.isEmpty()) {
                    log("❌ 找不到任何輸出裝置")
                    return@post
                }
                AlertDialog.Builder(this)
                    .setTitle("選擇輸出裝置")
                    .setItems(devs.map { it.name }.toTypedArray()) { _, i ->
                        selectedDev = devs[i]
                        devBtn.text = "輸出：${devs[i].name}"
                        log("已選輸出裝置：${devs[i].name}")
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private fun applyRoute() {
        val app = selectedApp
        val dev = selectedDev
        if (app == null || dev == null) {
            log("⚠️ 請先選好 App 和輸出裝置")
            return
        }
        val useShell = shellBox.isChecked
        runOnService("把「${app.label}」分流到 ${dev.name}") { it.route(app.uid, dev.id, useShell) }
    }

    private fun runDiagnose() {
        val app = selectedApp
        if (app == null) {
            log("⚠️ 請先選 App（要診斷哪個 App 的聲音）")
            return
        }
        val s = service
        if (s == null) {
            log("⚠️ 還沒連上服務，請先按「連線 Shizuku」")
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
            log("👉 按「複製紀錄」貼給 Claude。測試音還在播，聽完可以按「停止測試音」。")
        }
    }

    // ---------------------------------------------------------------- 其他

    private fun toggleTone() {
        if (tone.isPlaying) {
            tone.stop()
            toneBtn.text = "▶ 播放測試音"
            log("■ 測試音已停止")
        } else {
            tone.start()
            toneBtn.text = "■ 停止測試音"
            log("▶ 播放測試音中（嗶—嗶—）")
        }
    }

    private fun copyLog() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("AppAudioRouter log", logView.text))
        Toast.makeText(this, "已複製紀錄", Toast.LENGTH_SHORT).show()
    }

    private fun log(msg: String) {
        main.post {
            if (!::logView.isInitialized) return@post
            logView.append("[${timeFmt.format(Date())}] $msg\n\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
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
    }
}

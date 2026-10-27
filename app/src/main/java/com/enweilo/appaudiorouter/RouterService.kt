package com.enweilo.appaudiorouter

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Process
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 在 Shizuku 裡（shell 或 root 身分）執行的服務。
 *
 * 做法：對選定 App 的 UID 建一個 AudioMix（RULE_MATCH_UID + ROUTE_FLAG_RENDER），
 * 指定輸出到某個裝置，再用 AudioManager.registerAudioPolicy() 註冊。
 * 這些都是 @SystemApi，需要 MODIFY_AUDIO_ROUTING 權限，所以用反射呼叫。
 */
class RouterService : IRouterService.Stub {

    private class Route(
        val uid: Int,
        val deviceName: String,
        val am: AudioManager,
        val policy: Any,
        val ctxName: String,
    )

    private lateinit var base: Context
    private val routes = LinkedHashMap<Int, Route>()
    private var initNote = ""

    constructor() : super() {
        setup(null)
    }

    constructor(context: Context) : super() {
        setup(context)
    }

    private fun setup(context: Context?) {
        initNote = try {
            if (Build.VERSION.SDK_INT >= 28) HiddenApiBypass.addHiddenApiExemptions("L")
            "隱藏 API 解鎖：OK"
        } catch (t: Throwable) {
            "隱藏 API 解鎖失敗：${t.message}"
        }
        base = context ?: systemContext()
    }

    private fun systemContext(): Context {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null)
            ?: at.getMethod("systemMain").invoke(null)
        return at.getMethod("getSystemContext").invoke(thread) as Context
    }

    private fun shellContext(): Context? = try {
        base.createPackageContext("com.android.shell", 0)
    } catch (t: Throwable) {
        null
    }

    // ---------------------------------------------------------------- 檢查

    override fun probe(): String {
        val sb = StringBuilder()
        val uid = Process.myUid()
        sb.appendLine("【環境】")
        sb.appendLine("Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）")
        sb.appendLine("裝置：${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine(
            "服務身分 UID：$uid " + when (uid) {
                0 -> "（root）"
                2000 -> "（shell，ADB/無線偵錯模式）"
                else -> "（未知）"
            }
        )
        sb.appendLine(initNote)

        sb.appendLine()
        sb.appendLine("【權限】")
        val perm = "android.permission.MODIFY_AUDIO_ROUTING"
        val shellHas = try {
            base.packageManager.checkPermission(perm, "com.android.shell") == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            null
        }
        sb.appendLine(
            "com.android.shell 有 MODIFY_AUDIO_ROUTING：" + when (shellHas) {
                true -> "✅ 有"
                false -> "❌ 沒有"
                null -> "？查詢失敗"
            }
        )
        val selfHas = base.checkPermission(perm, Process.myPid(), uid) == PackageManager.PERMISSION_GRANTED
        sb.appendLine("這個服務程序實際拿得到：${if (selfHas) "✅ 是" else "❌ 否"}")

        sb.appendLine()
        sb.appendLine("【系統 API】")
        for (name in listOf(
            "android.media.audiopolicy.AudioPolicy\$Builder",
            "android.media.audiopolicy.AudioMix\$Builder",
            "android.media.audiopolicy.AudioMixingRule\$Builder",
        )) {
            val ok = try {
                Class.forName(name); true
            } catch (t: Throwable) {
                false
            }
            sb.appendLine("${if (ok) "✅" else "❌"} ${name.substringAfterLast('.')}")
        }
        val regOk = try {
            AudioManager::class.java.getMethod("registerAudioPolicy", Class.forName("android.media.audiopolicy.AudioPolicy"))
            true
        } catch (t: Throwable) {
            false
        }
        sb.appendLine("${if (regOk) "✅" else "❌"} registerAudioPolicy()")
        sb.appendLine("shell context：${if (shellContext() != null) "✅ 可建立" else "❌ 無法建立"}")

        sb.appendLine()
        sb.append(
            if (selfHas && regOk) "👉 結論：條件齊全，可以進行第 ② 步實際分流測試。"
            else "👉 結論：權限或 API 不足，分流大概會失敗（還是可以試，看 log 錯在哪）。"
        )
        return sb.toString()
    }

    override fun listDevices(): String {
        val am = base.getSystemService(AudioManager::class.java)
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString("\n") { d ->
            val product = d.productName?.toString()?.replace('\t', ' ') ?: ""
            val addr = if (d.address.isNullOrBlank()) "" else " ${d.address}"
            "${d.id}\t${deviceTypeName(d.type)}\t$product$addr"
        }
    }

    // ---------------------------------------------------------------- 分流

    /** 以服務自己的身分（shell）執行，避免系統拿呼叫端 App 的 UID 去檢查權限。 */
    private inline fun <T> asSelf(block: () -> T): T {
        val id = Binder.clearCallingIdentity()
        try {
            return block()
        } finally {
            Binder.restoreCallingIdentity(id)
        }
    }

    @Synchronized
    override fun route(uid: Int, deviceId: Int, useShellContext: Boolean): String =
        asSelf { routeInternal(uid, deviceId, useShellContext) }

    private fun routeInternal(uid: Int, deviceId: Int, useShellContext: Boolean): String {
        val log = StringBuilder()
        val start = System.currentTimeMillis()
        try {
            val ctx = if (useShellContext) {
                shellContext() ?: base.also { log.appendLine("⚠️ 建不出 shell context，改用預設") }
            } else base
            val ctxName = ctx.packageName
            log.appendLine("使用身分：$ctxName（UID ${Process.myUid()}）")

            val am = ctx.getSystemService(AudioManager::class.java)
            val dev = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == deviceId }
            if (dev == null) {
                log.appendLine("❌ 找不到輸出裝置 id=$deviceId（可能已拔除或斷線，請重選）")
                return log.toString()
            }
            val devName = "${deviceTypeName(dev.type)} ${dev.productName ?: ""}".trim()

            routes.remove(uid)?.let {
                unregister(it)
                log.appendLine("已先移除這個 App 舊的分流設定")
            }

            val policy = buildPolicy(ctx, uid, dev, log)
            val polCls = Class.forName("android.media.audiopolicy.AudioPolicy")
            val res = AudioManager::class.java.getMethod("registerAudioPolicy", polCls)
                .invoke(am, policy) as Int
            if (res == 0) {
                routes[uid] = Route(uid, devName, am, policy, ctxName)
                log.appendLine("✅ registerAudioPolicy 成功（回傳 0）")
                log.appendLine("UID $uid 的聲音現在應該會走：$devName")
                log.appendLine("💡 如果那個 App 正在播放，先暫停再播放一次，新路由才會生效。")
            } else {
                log.appendLine("❌ registerAudioPolicy 失敗（回傳 $res），看下面 logcat 找原因")
            }
        } catch (t: Throwable) {
            val root = unwrap(t)
            log.appendLine("❌ 例外：${root.javaClass.simpleName}: ${root.message}")
        }
        log.appendLine()
        log.appendLine("--- 系統 log（音訊相關）---")
        log.append(logcatSince(start))
        return log.toString()
    }

    private fun buildPolicy(ctx: Context, uid: Int, dev: AudioDeviceInfo, log: StringBuilder): Any {
        val ruleCls = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val ruleB = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
        val mixCls = Class.forName("android.media.audiopolicy.AudioMix")
        val mixB = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
        val polB = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
        val intType = Int::class.javaPrimitiveType

        val rb = ruleB.getConstructor().newInstance()
        ruleB.getMethod("addMixRule", intType, Any::class.java).invoke(rb, RULE_MATCH_UID, uid)
        val rule = ruleB.getMethod("build").invoke(rb)
        log.appendLine("✔ 規則建立：只比對 UID $uid")

        val format = AudioFormat.Builder()
            .setSampleRate(48000)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val mb = mixB.getConstructor(ruleCls).newInstance(rule)
        mixB.getMethod("setFormat", AudioFormat::class.java).invoke(mb, format)
        mixB.getMethod("setRouteFlags", intType).invoke(mb, ROUTE_FLAG_RENDER)
        mixB.getMethod("setDevice", AudioDeviceInfo::class.java).invoke(mb, dev)
        val mix = mixB.getMethod("build").invoke(mb)
        log.appendLine("✔ AudioMix 建立：輸出到 ${deviceTypeName(dev.type)}")

        val pb = polB.getConstructor(Context::class.java).newInstance(ctx)
        polB.getMethod("addMix", mixCls).invoke(pb, mix)
        val policy = polB.getMethod("build").invoke(pb)!!
        log.appendLine("✔ AudioPolicy 建立完成，準備註冊…")
        return policy
    }

    private fun unregister(r: Route) {
        val polCls = Class.forName("android.media.audiopolicy.AudioPolicy")
        try {
            AudioManager::class.java.getMethod("unregisterAudioPolicy", polCls).invoke(r.am, r.policy)
        } catch (e: NoSuchMethodException) {
            AudioManager::class.java.getMethod("unregisterAudioPolicyAsync", polCls).invoke(r.am, r.policy)
        }
    }

    @Synchronized
    override fun clearAll(): String = asSelf { clearAllInternal() }

    private fun clearAllInternal(): String {
        if (routes.isEmpty()) return "目前沒有任何分流設定"
        val sb = StringBuilder()
        for (r in routes.values) {
            try {
                unregister(r)
                sb.appendLine("✅ 已還原 UID ${r.uid}")
            } catch (t: Throwable) {
                sb.appendLine("❌ UID ${r.uid} 還原失敗：${unwrap(t).message}")
            }
        }
        routes.clear()
        sb.append("全部還原完成，聲音回到系統預設路由")
        return sb.toString()
    }

    @Synchronized
    override fun activeRoutes(): String {
        if (routes.isEmpty()) return "目前沒有任何分流設定"
        return "目前生效中的分流：\n" + routes.values.joinToString("\n") {
            "• UID ${it.uid} → ${it.deviceName}（身分 ${it.ctxName}）"
        }
    }

    // ---------------------------------------------------------------- 喇叭音量

    private fun shellAudio(): AudioManager =
        (shellContext() ?: base).getSystemService(AudioManager::class.java)

    private fun speakerDevice(am: AudioManager): AudioDeviceInfo? =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    private fun deviceAttributes(dev: AudioDeviceInfo): Any =
        Class.forName("android.media.AudioDeviceAttributes")
            .getConstructor(AudioDeviceInfo::class.java)
            .newInstance(dev)

    private fun volumeInfo(am: AudioManager, index: Int?): Any {
        val intType = Int::class.javaPrimitiveType
        val bCls = Class.forName("android.media.VolumeInfo\$Builder")
        val b = bCls.getConstructor(intType).newInstance(AudioManager.STREAM_MUSIC)
        try {
            bCls.getMethod("setMinVolumeIndex", intType).invoke(b, am.getStreamMinVolume(AudioManager.STREAM_MUSIC))
            bCls.getMethod("setMaxVolumeIndex", intType).invoke(b, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        } catch (_: Throwable) {
        }
        if (index != null) bCls.getMethod("setVolumeIndex", intType).invoke(b, index)
        return bCls.getMethod("build").invoke(b)!!
    }

    override fun getMaxVolume(): Int = asSelf {
        shellAudio().getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

    override fun getSpeakerVolume(): Int = asSelf {
        val am = shellAudio()
        val dev = speakerDevice(am)
        // dumpsys 的數字最可靠，優先用；讀不到才試系統 API
        val fromDump = speakerVolumeFromDump()
        if (fromDump >= 0 || dev == null) {
            fromDump
        } else {
            val vi = volumeInfo(am, null)
            val ada = deviceAttributes(dev)
            var result = -1
            for ((_, obj) in volumeTargets(am)) {
                val m = obj.javaClass.methods.firstOrNull { it.name == "getDeviceVolume" && argsFor(it, vi, ada) != null }
                    ?: continue
                try {
                    val r = m.invoke(obj, *argsFor(m, vi, ada)!!)
                    result = Class.forName("android.media.VolumeInfo").getMethod("getVolumeIndex").invoke(r) as Int
                    break
                } catch (_: Throwable) {
                }
            }
            result
        }
    }

    /** 可能有音量方法的物件：AudioManager 本身，以及系統音訊服務 IAudioService */
    private fun volumeTargets(am: AudioManager): List<Pair<String, Any>> {
        val list = mutableListOf<Pair<String, Any>>("AudioManager" to am)
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "audio") as IBinder
            val stub = Class.forName("android.media.IAudioService\$Stub")
            val svc = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            if (svc != null) list.add("IAudioService" to svc)
        } catch (_: Throwable) {
        }
        return list
    }

    /** 依參數型別自動填參數；有不認得的型別就回傳 null（代表不適用） */
    private fun argsFor(m: Method, vi: Any, ada: Any): Array<Any?>? {
        val out = arrayOfNulls<Any>(m.parameterTypes.size)
        for ((i, t) in m.parameterTypes.withIndex()) {
            out[i] = when (t.name) {
                "android.media.VolumeInfo" -> vi
                "android.media.AudioDeviceAttributes" -> ada
                "java.lang.String" -> "com.android.shell"
                else -> return null
            }
        }
        return out
    }

    /** 備用：從 dumpsys audio 的 STREAM_MUSIC 區段讀 "(speaker): N" */
    private fun speakerVolumeFromDump(): Int {
        val lines = runCmd("dumpsys", "audio").lines()
        val i = lines.indexOfFirst { it.contains("STREAM_MUSIC") }
        if (i < 0) return -1
        val cur = lines.drop(i).take(12).firstOrNull { it.contains("Current") } ?: return -1
        return Regex("\\(speaker\\):\\s*(\\d+)").find(cur)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    override fun setSpeakerVolume(index: Int): String = asSelf {
        val am = shellAudio()
        val dev = speakerDevice(am)
        if (dev == null) {
            "❌ 找不到手機喇叭"
        } else {
            setSpeakerVolumeInternal(am, dev, index)
        }
    }

    private fun setSpeakerVolumeInternal(am: AudioManager, dev: AudioDeviceInfo, index: Int): String {
        val errors = mutableListOf<String>()
        val targets = volumeTargets(am)
        val vi = try {
            volumeInfo(am, index)
        } catch (t: Throwable) {
            null
        }
        val ada = deviceAttributes(dev)

        // 做法 1：setDeviceVolume(VolumeInfo, AudioDeviceAttributes[, String])
        if (vi != null) {
            for ((label, obj) in targets) {
                for (m in obj.javaClass.methods.filter { it.name == "setDeviceVolume" }) {
                    val args = argsFor(m, vi, ada) ?: continue
                    try {
                        m.invoke(obj, *args)
                        return "✅ 手機喇叭音量設為 $index（$label.setDeviceVolume）"
                    } catch (t: Throwable) {
                        val r = unwrap(t)
                        errors.add("$label.setDeviceVolume：${r.javaClass.simpleName}: ${r.message}")
                    }
                }
            }
        } else errors.add("VolumeInfo 建立失敗")

        // 做法 2：setStreamVolumeForDevice(stream, index, nativeDevice[, String])
        for ((label, obj) in targets) {
            for (m in obj.javaClass.methods.filter { it.name == "setStreamVolumeForDevice" }) {
                val ints = intArrayOf(AudioManager.STREAM_MUSIC, index, NATIVE_OUT_SPEAKER)
                var k = 0
                val args = arrayOfNulls<Any>(m.parameterTypes.size)
                var ok = true
                for ((i, t) in m.parameterTypes.withIndex()) {
                    args[i] = when {
                        t == Int::class.javaPrimitiveType && k < ints.size -> ints[k++]
                        t == String::class.java -> "com.android.shell"
                        else -> { ok = false; null }
                    }
                }
                if (!ok || k < ints.size) continue
                try {
                    m.invoke(obj, *args)
                    return "✅ 手機喇叭音量設為 $index（$label.setStreamVolumeForDevice）"
                } catch (t: Throwable) {
                    val r = unwrap(t)
                    errors.add("$label.setStreamVolumeForDevice：${r.javaClass.simpleName}: ${r.message}")
                }
            }
        }

        // 全部失敗：列出這台手機有的相關方法，方便下一步判斷
        val sb = StringBuilder("❌ 設定喇叭音量失敗\n")
        if (errors.isEmpty()) sb.appendLine("（找不到可用的方法）") else errors.forEach { sb.appendLine("• $it") }
        sb.appendLine("這台手機上的相關方法：")
        for ((label, obj) in targets) {
            obj.javaClass.methods
                .filter { it.name.contains("Volume") && (it.name.contains("Device") || it.name.contains("ForDevice")) }
                .map { m -> "$label.${m.name}(${m.parameterTypes.joinToString(", ") { it.simpleName }})" }
                .distinct()
                .forEach { sb.appendLine("  $it") }
        }
        return sb.toString().trimEnd()
    }

    // ---------------------------------------------------------------- 診斷

    override fun diagnose(uid: Int): String {
        val sb = StringBuilder()
        val uidWord = Regex("\\b$uid\\b")
        val am = base.getSystemService(AudioManager::class.java)
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        sb.appendLine("【裝置 id 對照】")
        sb.appendLine(outputs.joinToString("、") { "${it.id}=${deviceTypeName(it.type)}" })

        // 1. dumpsys audio：播放紀錄（含 deviceIds）與各裝置的媒體音量
        val audio = runCmd("dumpsys", "audio")
        val audioLines = audio.lines()
        sb.appendLine()
        sb.appendLine("【UID $uid 的播放狀態（deviceIds = 實際輸出裝置）】")
        val plays = audioLines.filter { it.contains("u/pid:$uid/") || (it.contains("piid") && uidWord.containsMatchIn(it)) }
        if (plays.isEmpty()) sb.appendLine("（沒找到，這個 App 現在可能沒在播放）")
        else plays.takeLast(6).forEach { sb.appendLine(it.trim()) }

        sb.appendLine()
        sb.appendLine("【媒體音量（各裝置分開記）】")
        val musicIdx = audioLines.indexOfFirst { it.contains("STREAM_MUSIC") }
        if (musicIdx >= 0) {
            audioLines.drop(musicIdx).take(12)
                .filter { it.contains("Current") || it.contains("Muted") || it.contains("Max") || it.contains("Min") }
                .forEach { sb.appendLine(it.trim()) }
        } else sb.appendLine("（找不到 STREAM_MUSIC 區段）")
        sb.appendLine("目前媒體音量（當下預設裝置）：${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}")

        // 2. dumpsys media.audio_policy：哪個輸出通道上有這個 UID，以及那個通道接到哪個裝置
        val pol = runCmd("dumpsys", "media.audio_policy").lines()
        sb.appendLine()
        sb.appendLine("【音訊策略：UID $uid 所在的輸出通道】")
        var curOutput = ""
        var curDevices = ""
        val shown = HashSet<String>()
        var found = 0
        for (line in pol) {
            val t = line.trim()
            if (Regex("Output \\d+").containsMatchIn(t) && t.contains("dump", ignoreCase = true)) {
                curOutput = t; curDevices = ""
            } else if (t.startsWith("Devices") || t.startsWith("Device:") || t.contains("Devices:")) {
                curDevices = t.take(200)
            }
            if (uidWord.containsMatchIn(t) && (t.contains("uid", true) || t.contains("Client", true))) {
                val key = "$curOutput|$curDevices"
                if (shown.add(key)) {
                    sb.appendLine("▸ $curOutput")
                    if (curDevices.isNotEmpty()) sb.appendLine("  $curDevices")
                }
                sb.appendLine("  ${t.take(220)}")
                if (++found >= 8) break
            }
        }
        if (found == 0) sb.appendLine("（沒找到這個 UID 的播放 client）")

        sb.appendLine()
        sb.appendLine("【已註冊的 Policy Mix】")
        val mixIdx = pol.indexOfFirst { it.contains("Policy Mix", true) }
        if (mixIdx >= 0) pol.drop(mixIdx).take(14).forEach { sb.appendLine(it.trim().take(200)) }
        else sb.appendLine("（dump 裡沒有 Policy Mix 區段）")

        sb.appendLine()
        sb.appendLine("--- 最近幾秒的系統 log ---")
        sb.append(logcatSince(System.currentTimeMillis() - 5000))
        return sb.toString()
    }

    private fun runCmd(vararg cmd: String): String = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (t: Throwable) {
        "（執行 ${cmd.joinToString(" ")} 失敗：${t.message}）"
    }

    override fun destroy() {
        try {
            clearAll()
        } catch (_: Throwable) {
        }
        System.exit(0)
    }

    // ---------------------------------------------------------------- 工具

    private fun unwrap(t: Throwable): Throwable {
        var e: Throwable = t
        while (true) {
            val next = (e as? InvocationTargetException)?.targetException ?: e.cause
            if (next == null || next === e) return e
            e = next
        }
    }

    private fun logcatSince(startMs: Long): String = try {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(startMs - 1000))
        val p = ProcessBuilder("logcat", "-d", "-v", "brief", "-t", ts)
            .redirectErrorStream(true)
            .start()
        val lines = p.inputStream.bufferedReader().readLines()
        p.waitFor()
        val keys = listOf("Audio", "audio", "APM", "olicy")
        val hit = lines.filter { line -> keys.any { line.contains(it) } }.takeLast(40)
        if (hit.isEmpty()) "（沒有相關 log）" else hit.joinToString("\n")
    } catch (t: Throwable) {
        "（讀不到 logcat：${t.message}）"
    }

    companion object {
        private const val RULE_MATCH_UID = 1 shl 2
        private const val ROUTE_FLAG_RENDER = 1
        private const val NATIVE_OUT_SPEAKER = 0x2
    }
}

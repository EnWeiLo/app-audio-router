package com.enweilo.appaudiorouter

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/** 「嗶—嗶—」測試音，用來確認本 App 自己的聲音有沒有被分流到別的裝置。 */
class TonePlayer {
    @Volatile
    private var running = false
    private var thread: Thread? = null

    val isPlaying: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            val rate = 48000
            val minBuf = AudioTrack.getMinBufferSize(
                rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            val buf = ShortArray(rate / 20)
            val step = 2 * PI * 660 / rate
            var phase = 0.0
            var n = 0L
            val cycle = (rate * 0.6).toLong()   // 0.4 秒響、0.2 秒停
            val onLen = (rate * 0.4).toLong()
            track.play()
            while (running) {
                for (i in buf.indices) {
                    val on = (n % cycle) < onLen
                    buf[i] = if (on) (sin(phase) * 0.3 * Short.MAX_VALUE).toInt().toShort() else 0
                    phase += step
                    if (phase > 2 * PI) phase -= 2 * PI
                    n++
                }
                track.write(buf, 0, buf.size)
            }
            track.stop()
            track.release()
        }.also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }
}

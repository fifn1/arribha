package com.abubadr.arribha

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import java.io.File

/**
 * Looks at how strong this TV is and picks settings that keep the game smooth.
 * Tier 2 = strong, 1 = medium (e.g. TCL Smart TV Pro), 0 = weak.
 */
object DeviceCheck {
    class Result(val tier: Int, val hevc: Boolean, val ramMb: Long, val cores: Int, val maxMhz: Int,
                 val benchMs: Long, val decode1080: Boolean)

    fun run(context: Context): Result {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val ramMb = mi.totalMem / 1_048_576
        val cores = Runtime.getRuntime().availableProcessors()
        var maxMhz = 0
        for (i in 0 until cores) {
            val f = try { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toInt() / 1000 } catch (e: Exception) { 0 }
            if (f > maxMhz) maxMhz = f
        }

        // short single-core test (TCL Smart TV Pro: ~690 ms)
        val t0 = System.nanoTime()
        var x = 0.0
        for (i in 1 until 20_000_000) x += Math.sqrt(i.toDouble()) / i
        val benchMs = (System.nanoTime() - t0) / 1_000_000 + (if (x < 0) 1 else 0)

        var hevc1080 = false
        var avc1080 = false
        for (ci in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (ci.isEncoder) continue
            if (Build.VERSION.SDK_INT >= 29 && !ci.isHardwareAccelerated) continue
            for (type in ci.supportedTypes) {
                val vc = try { ci.getCapabilitiesForType(type).videoCapabilities } catch (e: Exception) { null } ?: continue
                val ok = vc.areSizeAndRateSupported(1920, 1080, 60.0)
                if (type == "video/hevc" && ok) hevc1080 = true
                if (type == "video/avc" && ok) avc1080 = true
            }
        }

        val tier = when {
            ramMb >= 3500 && benchMs < 400 && cores >= 4 -> 2
            ramMb >= 1800 && benchMs < 1100 -> 1
            else -> 0
        }
        return Result(tier, hevc1080, ramMb, cores, maxMhz, benchMs, hevc1080 || avc1080)
    }

    /** milliseconds between screen reads for each tier */
    fun readInterval(tier: Int) = when (tier) { 2 -> 500L; 1 -> 1000L; else -> 2000L }
}

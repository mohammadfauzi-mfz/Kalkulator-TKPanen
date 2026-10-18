package id.dsngroup.kalkulatortkpanen

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Application: memasang pencatat crash sejak proses dimulai. */
class TkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        CrashLog.event(this, "Proses aplikasi dimulai")
    }
}

/**
 * Pencatat error & kejadian penting (tracking mulai/berhenti/service terbunuh) ke file di HP,
 * supaya penyebab aplikasi berhenti tiba-tiba di lapangan bisa dilacak.
 */
object CrashLog {
    private const val MAX_EVENT_BYTES = 60_000L
    private const val MAX_CRASH_FILES = 8
    private val lock = Any()

    private fun dir(ctx: Context) = File(ctx.filesDir, "log").apply { mkdirs() }
    private fun eventFile(ctx: Context) = File(dir(ctx), "events.log")
    private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    fun versionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { writeCrash(app, t, e) } catch (x: Throwable) {}
            old?.uncaughtException(t, e)
        }
    }

    private fun writeCrash(ctx: Context, t: Thread, e: Throwable) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val sb = StringBuilder()
        sb.append("Kalkulator TK Panen ").append(versionName(ctx)).append(" - CRASH\n")
        sb.append("Waktu   : ").append(stamp()).append('\n')
        sb.append("Thread  : ").append(t.name).append('\n')
        sb.append("Perangkat: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" (Android ").append(Build.VERSION.RELEASE).append(", SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        val rt = Runtime.getRuntime()
        sb.append("Memori  : ").append((rt.totalMemory() - rt.freeMemory()) / 1048576).append(" MB terpakai dari maks ")
            .append(rt.maxMemory() / 1048576).append(" MB\n")
        sb.append("Sensus aktif: ").append(if (Sensus.aktif(ctx) != null) "ya" else "tidak").append("\n\n")
        sb.append(sw.toString())
        synchronized(lock) {
            val d = dir(ctx)
            File(d, "crash_" + System.currentTimeMillis() + ".txt").writeText(sb.toString())
            val crashes = d.listFiles { f -> f.name.startsWith("crash_") }?.sortedBy { it.name } ?: emptyList()
            if (crashes.size > MAX_CRASH_FILES) crashes.take(crashes.size - MAX_CRASH_FILES).forEach { it.delete() }
        }
        event(ctx, "CRASH: " + e.javaClass.simpleName + ": " + (e.message ?: ""))
    }

    /** Catat kejadian penting (singkat). Dipotong otomatis agar tidak membesar. */
    fun event(ctx: Context, msg: String) {
        try {
            synchronized(lock) {
                val f = eventFile(ctx)
                if (f.exists() && f.length() > MAX_EVENT_BYTES) {
                    val keep = f.readText().takeLast((MAX_EVENT_BYTES / 2).toInt())
                    f.writeText(keep.substringAfter('\n', keep))
                }
                f.appendText(stamp() + "  " + msg + "\n")
            }
        } catch (e: Exception) {
        }
    }

    /** Catat error yang tertangkap (tidak membuat aplikasi berhenti). */
    fun error(ctx: Context, where: String, e: Throwable) {
        event(ctx, "ERROR di $where: " + e.javaClass.simpleName + ": " + (e.message ?: ""))
    }

    private fun lastCrashFile(ctx: Context): File? =
        dir(ctx).listFiles { f -> f.name.startsWith("crash_") }?.maxByOrNull { it.name }

    /** Crash yang belum pernah ditampilkan ke pengguna (untuk dialog "kirim log"). */
    fun unseenCrash(ctx: Context): File? {
        val f = lastCrashFile(ctx) ?: return null
        val prefs = ctx.getSharedPreferences("tkpanen", Context.MODE_PRIVATE)
        return if (prefs.getString("crash_seen", "") == f.name) null else f
    }

    fun markSeen(ctx: Context) {
        val f = lastCrashFile(ctx) ?: return
        ctx.getSharedPreferences("tkpanen", Context.MODE_PRIVATE).edit().putString("crash_seen", f.name).apply()
    }

    fun hasAny(ctx: Context): Boolean = eventFile(ctx).exists() || lastCrashFile(ctx) != null

    /** Gabungkan semua log menjadi satu file teks (mudah dikirim lewat WA). */
    fun bundle(ctx: Context, out: File): Boolean = try {
        val sb = StringBuilder()
        sb.append("=== LOG Kalkulator TK Panen ").append(versionName(ctx)).append(" ===\n")
        sb.append("Dibuat  : ").append(stamp()).append('\n')
        sb.append("Perangkat: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" (Android ").append(Build.VERSION.RELEASE).append(", SDK ").append(Build.VERSION.SDK_INT).append(")\n\n")
        synchronized(lock) {
            val crashes = dir(ctx).listFiles { f -> f.name.startsWith("crash_") }?.sortedBy { it.name } ?: emptyList()
            for (c in crashes) { sb.append("--- ").append(c.name).append(" ---\n").append(c.readText()).append("\n\n") }
            val ev = eventFile(ctx)
            sb.append("--- KEJADIAN ---\n")
            if (ev.exists()) sb.append(ev.readText())
        }
        out.parentFile?.mkdirs()
        out.writeText(sb.toString())
        true
    } catch (e: Exception) { false }

    fun clear(ctx: Context) {
        synchronized(lock) { dir(ctx).listFiles()?.forEach { it.delete() } }
    }
}

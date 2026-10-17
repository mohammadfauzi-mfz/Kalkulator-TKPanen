package id.dsngroup.kalkulatortkpanen

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** brk = true: titik pertama setelah jeda/terputus, tidak disambung garis ke titik sebelumnya. */
data class TPoint(val lat: Double, val lon: Double, val t: Long, val brk: Boolean = false)

data class TrackRecord(
    val id: String,
    val startedAt: Long,
    val endedAt: Long,
    val est: String,
    val afd: String,
    val blok: String,
    val mapId: String,
    val points: List<TPoint>,
    val pausedMs: Long = 0L,      // total waktu jeda (pause) yang tidak dihitung sebagai durasi jalan
) {
    val distance: Double get() = Geo.length(points)
    val durationMs: Long get() = (endedAt - startedAt - pausedMs).coerceAtLeast(0L)
    /** Durasi jalan dalam menit (dibulatkan). */
    val minutes: Long get() = Math.round(durationMs / 60000.0)
}

object Geo {
    fun dist(a: TPoint, b: TPoint): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(h))
    }

    fun length(p: List<TPoint>): Double {
        var d = 0.0
        for (i in 1 until p.size) if (!p[i].brk) d += dist(p[i - 1], p[i])
        return d
    }

    /** Pecah jalur menjadi bagian-bagian menerus (dipisah oleh jeda/terputus). */
    fun segments(p: List<TPoint>): List<List<TPoint>> {
        if (p.isEmpty()) return emptyList()
        val out = ArrayList<List<TPoint>>()
        var cur = ArrayList<TPoint>()
        for (pt in p) {
            if (pt.brk && cur.isNotEmpty()) { out.add(cur); cur = ArrayList() }
            cur.add(pt)
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    fun ptJson(p: TPoint): JSONArray {
        val a = JSONArray().put(p.lat).put(p.lon).put(p.t)
        if (p.brk) a.put(1)
        return a
    }

    fun ptFrom(p: JSONArray): TPoint = TPoint(p.getDouble(0), p.getDouble(1), p.optLong(2), p.optInt(3, 0) == 1)

    /** Luas poligon (jalur ditutup dari titik akhir ke awal), meter persegi, perkiraan datar. */
    fun areaM2(p: List<TPoint>): Double {
        if (p.size < 3) return 0.0
        val k = cos(Math.toRadians(p[0].lat))
        var sum = 0.0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            val ax = (a.lon - p[0].lon) * 111320.0 * k
            val ay = (a.lat - p[0].lat) * 110574.0
            val bx = (b.lon - p[0].lon) * 111320.0 * k
            val by = (b.lat - p[0].lat) * 110574.0
            sum += ax * by - bx * ay
        }
        return Math.abs(sum) / 2.0
    }

    fun luas(m2: Double): String =
        if (m2 >= 10000.0) Fmt.n(m2 / 10000.0, 2) + " Ha (" + Fmt.n(m2, 0) + " m²)"
        else Fmt.n(m2, 1) + " m²"

    fun meter(m: Double): String = if (m >= 1000.0) Fmt.n(m / 1000.0, 2) + " km" else Fmt.n(m, 1) + " m"

    fun km(m: Double): String = Fmt.n(m / 1000.0, 2) + " km"
}

/** Keadaan tracking yang sedang berjalan (dipakai bersama oleh Service dan layar). */
object Tracker {
    @Volatile var active = false
    @Volatile var startedAt = 0L
    @Volatile var gpsError: String? = null
    @Volatile var lastFixAt = 0L
    @Volatile var est = ""
    @Volatile var afd = ""
    @Volatile var blok = ""
    @Volatile var paused = false
    @Volatile private var pausedDone = 0L      // jumlah jeda yang sudah selesai (ms)
    @Volatile private var pauseStart = 0L
    private var breakNext = false              // titik berikutnya dimulai sebagai bagian garis baru
    private val pts = ArrayList<TPoint>()
    private var lastSave = 0L

    private fun file(ctx: Context) = File(ctx.filesDir, "active_track.json")

    fun start(ctx: Context? = null, est: String = "", afd: String = "", blok: String = "") {
        synchronized(pts) { pts.clear() }
        startedAt = System.currentTimeMillis()
        gpsError = null
        lastFixAt = 0L
        this.est = est; this.afd = afd; this.blok = blok
        paused = false; pausedDone = 0L; pauseStart = 0L; breakNext = false
        active = true
        if (ctx != null) save(ctx, true)
    }

    /** Total waktu jeda sampai sekarang (ms), termasuk jeda yang sedang berjalan. */
    fun pausedTotal(): Long = pausedDone + (if (paused && pauseStart > 0L) System.currentTimeMillis() - pauseStart else 0L)

    /** Jeda tracking: titik GPS tidak direkam dan jarak tidak bertambah. */
    fun pause(ctx: Context? = null) {
        if (!active || paused) return
        pauseStart = System.currentTimeMillis()
        paused = true
        if (ctx != null) save(ctx, true)
    }

    /** Lanjutkan setelah jeda; garis baru dimulai dari titik berikutnya (tidak menyambung lompatan). */
    fun unpause(ctx: Context? = null) {
        if (!active || !paused) return
        pausedDone += System.currentTimeMillis() - pauseStart
        pauseStart = 0L
        paused = false
        breakNext = true
        lastFixAt = 0L
        if (ctx != null) save(ctx, true)
    }

    /** Hentikan dan kembalikan titik-titik yang terkumpul. */
    fun stop(ctx: Context? = null): List<TPoint> {
        if (paused) { pausedDone += System.currentTimeMillis() - pauseStart; pauseStart = 0L; paused = false }
        active = false
        if (ctx != null) discard(ctx)
        return snapshot()
    }

    fun snapshot(): List<TPoint> = synchronized(pts) { pts.toList() }

    fun add(p: TPoint) {
        synchronized(pts) {
            val last = pts.lastOrNull()
            if (breakNext) {
                pts.add(if (last != null) p.copy(brk = true) else p)
                breakNext = false
            } else if (last == null || Geo.dist(last, p) >= 3.0) pts.add(p)   // abaikan getaran GPS < 3 m
        }
        lastFixAt = System.currentTimeMillis()
    }

    /** Simpan keadaan tracking ke file agar bisa dipulihkan bila aplikasi/service terbunuh sistem. */
    fun save(ctx: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 15000L) return
        lastSave = now
        try {
            val a = JSONArray()
            for (p in snapshot()) a.put(Geo.ptJson(p))
            val o = JSONObject().put("start", startedAt).put("est", est).put("afd", afd).put("blok", blok).put("pts", a)
                .put("paused", paused).put("pausedDone", pausedDone).put("pauseStart", pauseStart).put("brk", breakNext)
            val tmp = File(ctx.filesDir, "active_track.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file(ctx))
        } catch (e: Exception) {
            CrashLog.error(ctx, "Tracker.save", e)
        }
    }

    fun discard(ctx: Context) {
        try { file(ctx).delete() } catch (e: Exception) {}
    }

    /** Ada tracking yang terputus (aplikasi/service terbunuh) dan belum diselesaikan? */
    fun pendingRecovery(ctx: Context): TrackRecord? {
        if (active) return null
        return readSaved(ctx)
    }

    private fun readSaved(ctx: Context): TrackRecord? {
        val f = file(ctx)
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            val a = o.optJSONArray("pts") ?: JSONArray()
            val list = ArrayList<TPoint>()
            for (i in 0 until a.length()) {
                val p = a.optJSONArray(i) ?: continue
                list.add(Geo.ptFrom(p))
            }
            if (list.isEmpty()) { f.delete(); return null }
            val st = o.optLong("start")
            TrackRecord("recovered", st, list.last().t.coerceAtLeast(st), o.optString("est"), o.optString("afd"), o.optString("blok"), "", list,
                o.optLong("pausedDone"))
        } catch (e: Exception) {
            null
        }
    }

    /** Lanjutkan tracking dari file simpanan (titik lama dipertahankan). */
    fun resume(ctx: Context): Boolean {
        val r = readSaved(ctx) ?: return false
        synchronized(pts) { pts.clear(); pts.addAll(r.points) }
        startedAt = r.startedAt
        est = r.est; afd = r.afd; blok = r.blok
        gpsError = null
        lastFixAt = 0L
        pausedDone = r.pausedMs
        val saved = try { JSONObject(file(ctx).readText()) } catch (e: Exception) { null }
        pauseStart = if (saved?.optBoolean("paused") == true) saved.optLong("pauseStart") else 0L
        paused = pauseStart > 0L
        breakNext = true        // ada selang waktu sejak titik terakhir: jangan sambung garis lurus
        active = true
        return true
    }
}

/** Service latar depan: tracking tetap berjalan walau layar mati / aplikasi dibuka-tutup. */
class TrackingService : Service(), LocationListener {
    private var lm: LocationManager? = null
    private var wake: PowerManager.WakeLock? = null
    private var nm: NotificationManager? = null
    private var lastNotif = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent == null: service dihidupkan ulang oleh sistem setelah terbunuh -> pulihkan dari file
        if (!Tracker.active) {
            if (Tracker.resume(this)) CrashLog.event(this, "Tracking dipulihkan otomatis setelah service terbunuh (${Tracker.snapshot().size} titik)")
            else { stopSelf(); return START_NOT_STICKY }
        }
        if (intent?.action == ACTION_TOGGLE_PAUSE) {
            if (Tracker.paused) Tracker.unpause(this) else Tracker.pause(this)
            CrashLog.event(this, if (Tracker.paused) "Tracking dijeda (dari notifikasi)" else "Tracking dilanjutkan (dari notifikasi)")
        }
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIF_ID, n)
        } catch (e: Exception) {
            CrashLog.error(this, "startForeground", e)
            Tracker.gpsError = "Sistem menolak tracking di latar belakang: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWake()
        if (Tracker.paused) stopUpdates() else startUpdates()      // saat dijeda GPS dimatikan (hemat baterai)
        CrashLog.event(this, "Service tracking berjalan (intent=${if (intent == null) "restart sistem" else "start"})")
        return START_STICKY
    }

    private fun acquireWake() {
        try {
            if (wake?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ckpreport:tracking").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            CrashLog.error(this, "wakelock", e)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        CrashLog.event(this, "Aplikasi digeser dari daftar terbaru (service tetap jalan: ${Tracker.active})")
        super.onTaskRemoved(rootIntent)
    }

    override fun onLowMemory() {
        CrashLog.event(this, "Sistem melaporkan memori rendah")
        super.onLowMemory()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tracking TK Panen", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggle = PendingIntent.getService(
            this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pts = Tracker.snapshot()
        val paused = Tracker.paused
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(if (paused) "Tracking TK Panen dijeda" else "Tracking TK Panen aktif")
            .setContentText("${pts.size} titik • ${Geo.km(Geo.length(pts))}. " +
                if (paused) "GPS dimatikan sementara. Tekan Lanjut untuk meneruskan." else "Buka aplikasi lalu tekan Stop Tracking untuk selesai.")
            .setOnlyAlertOnce(true)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause, if (paused) "Lanjut" else "Jeda", toggle)
            .build()
    }

    private fun stopUpdates() {
        try { lm?.removeUpdates(this) } catch (e: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        val m = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = m
        try {
            if (!m.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                Tracker.gpsError = "GPS HP belum aktif. Aktifkan lokasi lalu mulai lagi."
                return
            }
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, this, Looper.getMainLooper())
        } catch (e: Exception) {
            Tracker.gpsError = "GPS tidak dapat dipakai: ${e.message}"
        }
    }

    override fun onLocationChanged(location: Location) {
        if (!Tracker.active || Tracker.paused) return
        if (location.hasAccuracy() && location.accuracy > 35f) return   // abaikan titik yang kurang akurat
        Tracker.add(TPoint(location.latitude, location.longitude, System.currentTimeMillis()))
        Tracker.save(this)
        val now = System.currentTimeMillis()
        if (now - lastNotif > 30000L) {
            lastNotif = now
            try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification()) } catch (e: Exception) {}
        }
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) CrashLog.event(this, "GPS HP dimatikan saat tracking")
    }
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onDestroy() {
        CrashLog.event(this, "Service tracking berhenti (tracking aktif=${Tracker.active})")
        if (Tracker.active) Tracker.save(this, true)   // dihentikan sistem: simpan agar bisa dipulihkan
        try { lm?.removeUpdates(this) } catch (e: Exception) {}
        try { if (wake?.isHeld == true) wake?.release() } catch (e: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "tracking"
        private const val NOTIF_ID = 77
        const val ACTION_TOGGLE_PAUSE = "id.dsngroup.kalkulatortkpanen.TOGGLE_PAUSE"
    }
}

/** Riwayat tracking di HP (tidak diupload; bisa dibagikan / diimport sebagai cadangan). */
object TrackStore {
    private val lock = Any()
    private fun file(ctx: Context) = File(ctx.filesDir, "tracks.json")

    fun toJson(r: TrackRecord): JSONObject {
        val pts = JSONArray()
        for (p in r.points) pts.put(Geo.ptJson(p))
        return JSONObject()
            .put("id", r.id).put("start", r.startedAt).put("end", r.endedAt)
            .put("est", r.est).put("afd", r.afd).put("blok", r.blok).put("map", r.mapId)
            .put("paused", r.pausedMs)
            .put("pts", pts)
    }

    fun fromJson(o: JSONObject): TrackRecord {
        val a = o.optJSONArray("pts") ?: JSONArray()
        val pts = ArrayList<TPoint>(a.length())
        for (i in 0 until a.length()) {
            val p = a.optJSONArray(i) ?: continue
            pts.add(Geo.ptFrom(p))
        }
        return TrackRecord(
            o.getString("id"), o.optLong("start"), o.optLong("end"),
            o.optString("est"), o.optString("afd"), o.optString("blok"), o.optString("map"), pts,
            o.optLong("paused"),
        )
    }

    private fun read(ctx: Context): MutableList<TrackRecord> {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        return try {
            val a = JSONArray(f.readText())
            MutableList(a.length()) { fromJson(a.getJSONObject(it)) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun write(ctx: Context, list: List<TrackRecord>) {
        val a = JSONArray()
        list.forEach { a.put(toJson(it)) }
        val tmp = File(ctx.filesDir, "tracks.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(file(ctx))
    }

    fun all(ctx: Context): List<TrackRecord> = synchronized(lock) { read(ctx).sortedByDescending { it.startedAt } }

    fun get(ctx: Context, id: String): TrackRecord? = synchronized(lock) { read(ctx).firstOrNull { it.id == id } }

    fun add(ctx: Context, r: TrackRecord) = synchronized(lock) {
        val list = read(ctx)
        list.removeAll { it.id == r.id }
        list.add(r)
        write(ctx, list)
    }

    fun remove(ctx: Context, id: String) = synchronized(lock) {
        val list = read(ctx)
        list.removeAll { it.id == id }
        write(ctx, list)
    }

    fun exportJson(list: List<TrackRecord>): String {
        val a = JSONArray()
        list.forEach { a.put(toJson(it)) }
        return JSONObject().put("app", "ckpreport").put("type", "tracks").put("version", 1).put("tracks", a).toString()
    }

    /** @return jumlah track baru yang ditambahkan, atau -1 jika file tidak valid. */
    fun importJson(ctx: Context, text: String): Int = synchronized(lock) {
        try {
            val o = JSONObject(text)
            if (o.optString("app") != "ckpreport" || o.optString("type") != "tracks") return -1
            val a = o.getJSONArray("tracks")
            val list = read(ctx)
            var added = 0
            for (i in 0 until a.length()) {
                val r = fromJson(a.getJSONObject(i))
                if (list.none { it.id == r.id }) { list.add(r); added++ }
            }
            write(ctx, list)
            added
        } catch (e: Exception) {
            -1
        }
    }
}

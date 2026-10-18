package id.dsngroup.kalkulatortkpanen

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.content.Context
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
    val pokok: List<Pokok> = emptyList(),   // sensus per pokok (kosong untuk jalur lama)
    val petugas: String = "",
) {
    val jumlahPokok: Int get() = pokok.size
    val jumlahJjg: Int get() = pokok.sumOf { it.jjg }
    /** %AKP = janjang matang / jumlah pokok x 100. */
    val akpPersen: Double get() = Sensus.akp(jumlahPokok, jumlahJjg)
    val isSensus: Boolean get() = pokok.isNotEmpty()
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
            .put("paused", r.pausedMs).put("ptg", r.petugas)
            .put("pk", Sensus.pokokJson(r.pokok))
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
            Sensus.pokokFrom(o.optJSONArray("pk")), o.optString("ptg"),
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
        list.filter { it.id == id }.forEach { r ->
            for (p in r.pokok) if (p.foto.isNotBlank()) try { File(Sensus.fotoDir(ctx), p.foto).delete() } catch (e: Exception) {}
        }
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

package id.dsngroup.kalkulatortkpanen

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Satu pokok yang sudah dicatat: koordinat dan lux diambil saat NEXT/FINISH ditekan. */
data class Pokok(
    val no: Int,
    val lat: Double,
    val lon: Double,
    val acc: Float,        // akurasi GPS (meter), 0 = tidak diketahui
    val lux: Double,       // -1 = sensor cahaya tidak tersedia
    val jjg: Int,          // janjang matang
    val foto: String = "", // nama file foto di folder sensus_foto ("" = tanpa foto)
    val ket: String = "",  // keterangan foto
    val t: Long = 0L,
)

/** Sesi sensus yang sedang berjalan (disimpan ke file setiap pokok, agar tahan bila aplikasi tertutup). */
data class SesiAktif(
    val id: String,
    val blok: String,
    val petugas: String,
    val mulai: Long,
    val pokok: List<Pokok>,
)

object Sensus {
    /** %AKP = janjang matang / jumlah pokok x 100. Tanpa pokok = 0. */
    fun akp(pokok: Int, jjg: Int): Double = if (pokok <= 0) 0.0 else jjg * 100.0 / pokok

    fun pokokJson(list: List<Pokok>): JSONArray {
        val a = JSONArray()
        for (p in list) a.put(
            JSONObject().put("no", p.no).put("lat", p.lat).put("lon", p.lon).put("acc", p.acc.toDouble())
                .put("lux", p.lux).put("jjg", p.jjg).put("foto", p.foto).put("ket", p.ket).put("t", p.t)
        )
        return a
    }

    fun pokokFrom(a: JSONArray?): List<Pokok> {
        if (a == null) return emptyList()
        val out = ArrayList<Pokok>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out.add(Pokok(
                o.optInt("no", i + 1), o.optDouble("lat"), o.optDouble("lon"), o.optDouble("acc", 0.0).toFloat(),
                o.optDouble("lux", -1.0), o.optInt("jjg"), o.optString("foto"), o.optString("ket"), o.optLong("t"),
            ))
        }
        return out
    }

    /** Titik peta dari pokok-pokok (urut nomor). */
    fun titik(list: List<Pokok>): List<TPoint> = list.map { TPoint(it.lat, it.lon, it.t) }

    fun luxText(lux: Double): String = if (lux < 0) "-" else Fmt.n(lux, if (lux < 100) 1 else 0) + " lux"

    fun fotoDir(ctx: Context): File = File(ctx.filesDir, "sensus_foto").apply { mkdirs() }

    // ---------- sesi aktif ----------

    private fun file(ctx: Context) = File(ctx.filesDir, "sensus_aktif.json")

    fun simpanAktif(ctx: Context, s: SesiAktif) {
        try {
            val o = JSONObject().put("id", s.id).put("blok", s.blok).put("ptg", s.petugas)
                .put("mulai", s.mulai).put("pk", pokokJson(s.pokok))
            val tmp = File(ctx.filesDir, "sensus_aktif.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file(ctx))
        } catch (e: Exception) {
            CrashLog.error(ctx, "Sensus.simpanAktif", e)
        }
    }

    fun aktif(ctx: Context): SesiAktif? {
        val f = file(ctx)
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            SesiAktif(o.getString("id"), o.optString("blok"), o.optString("ptg"), o.optLong("mulai"), pokokFrom(o.optJSONArray("pk")))
        } catch (e: Exception) {
            null
        }
    }

    fun hapusAktif(ctx: Context) {
        try { file(ctx).delete() } catch (e: Exception) {}
    }

    /** Hapus sesi aktif beserta foto-fotonya (sesi dibatalkan). */
    fun buangAktif(ctx: Context) {
        val s = aktif(ctx)
        if (s != null) for (p in s.pokok) if (p.foto.isNotBlank()) try { File(fotoDir(ctx), p.foto).delete() } catch (e: Exception) {}
        hapusAktif(ctx)
    }

    /** Ubah sesi menjadi catatan permanen (TrackRecord) yang tampil di tab Peta dan bisa dilampirkan ke riwayat. */
    fun selesai(ctx: Context, s: SesiAktif, mapId: String): TrackRecord {
        val now = System.currentTimeMillis()
        val rec = TrackRecord(
            s.id, s.mulai, now, "", "", s.blok, mapId, titik(s.pokok), 0L, s.pokok, s.petugas,
        )
        TrackStore.add(ctx, rec)
        hapusAktif(ctx)
        return rec
    }
}

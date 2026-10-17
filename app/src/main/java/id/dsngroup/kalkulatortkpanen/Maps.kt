package id.dsngroup.kalkulatortkpanen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.Inflater
import kotlin.math.roundToInt

/**
 * Peta offline hasil import GeoPDF (mis. dari Global Mapper): gambar peta + 2 titik registrasi (halaman PDF <-> lon/lat).
 * Koordinat halaman PDF berasal dari kiri-bawah; piksel gambar dari kiri-atas.
 */
data class MapInfo(
    val id: String,
    val name: String,
    val w: Int,
    val h: Int,
    val pageW: Double,
    val pageH: Double,
    val x1: Double, val y1: Double, val lon1: Double, val lat1: Double,
    val x2: Double, val y2: Double, val lon2: Double, val lat2: Double,
    /** Opsional: transformasi affine hasil pencocokan banyak titik kontrol: [lon0, lat0, a, b, c, d, e, f]
     *  dengan x = a*(lon-lon0) + b*(lat-lat0) + c ; y = d*(lon-lon0) + e*(lat-lat0) + f (koordinat halaman PDF). */
    val aff: DoubleArray? = null,
) {
    private fun pagePt(lat: Double, lon: Double): Pair<Double, Double> {
        val f = aff
        if (f != null) {
            val u = lon - f[0]
            val v = lat - f[1]
            return Pair(f[2] * u + f[3] * v + f[4], f[5] * u + f[6] * v + f[7])
        }
        return Pair(
            x1 + (lon - lon1) * (x2 - x1) / (lon2 - lon1),
            y1 + (lat - lat1) * (y2 - y1) / (lat2 - lat1),
        )
    }

    /** Lat/lon -> posisi piksel pada gambar peta. */
    fun px(lat: Double, lon: Double): Pair<Double, Double> {
        val (x, y) = pagePt(lat, lon)
        return Pair(x / pageW * w, (pageH - y) / pageH * h)
    }

    /** Posisi piksel gambar -> lat/lon. */
    fun latLon(px: Double, py: Double): Pair<Double, Double> {
        val x = px / w * pageW
        val y = pageH - py / h * pageH
        val f = aff
        if (f != null) {
            val det = f[2] * f[6] - f[3] * f[5]
            val dx = x - f[4]
            val dy = y - f[7]
            val u = (f[6] * dx - f[3] * dy) / det
            val v = (-f[5] * dx + f[2] * dy) / det
            return Pair(f[1] + v, f[0] + u)
        }
        val lon = lon1 + (x - x1) * (lon2 - lon1) / (x2 - x1)
        val lat = lat1 + (y - y1) * (lat2 - lat1) / (y2 - y1)
        return Pair(lat, lon)
    }

    /** Perkiraan meter per piksel gambar (arah horizontal). */
    val metersPerPixel: Double
        get() {
            if (aff != null) {
                val (la1, lo1) = latLon(h / 2.0, 0.0)
                val (la2, lo2) = latLon(h / 2.0, 100.0)
                val dy = (la2 - la1) * 110574.0
                val dx = (lo2 - lo1) * 111320.0 * Math.cos(Math.toRadians((la1 + la2) / 2))
                return Math.hypot(dx, dy) / 100.0
            }
            val degPerPx = (lon2 - lon1) / (x2 - x1) * pageW / w
            return Math.abs(degPerPx) * 111320.0 * Math.cos(Math.toRadians((lat1 + lat2) / 2))
        }

    fun contains(lat: Double, lon: Double): Boolean {
        val (a, b) = px(lat, lon)
        return a >= 0.0 && a <= w && b >= 0.0 && b <= h
    }
}

/** Render ulang sebagian halaman PDF peta pada resolusi tinggi (dipakai saat zoom dekat). */
class PdfRegion(private val file: File, private val m: MapInfo) {
    private var pfd: ParcelFileDescriptor? = null
    private var r: PdfRenderer? = null

    /** Wilayah (x0,y0)-(x1,y1) dalam piksel peta -> bitmap ow x oh. */
    @Synchronized
    fun render(x0: Float, y0: Float, x1: Float, y1: Float, ow: Int, oh: Int): Bitmap? {
        if (r == null) {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd = fd
            r = PdfRenderer(fd)
        }
        val kx = m.pageW / m.w
        val ky = m.pageH / m.h
        val px0 = x0 * kx; val py0 = y0 * ky; val px1 = x1 * kx; val py1 = y1 * ky
        if (px1 <= px0 || py1 <= py0) return null
        val bmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val sx = (ow / (px1 - px0)).toFloat()
        val sy = (oh / (py1 - py0)).toFloat()
        val mat = android.graphics.Matrix()
        mat.setScale(sx, sy)
        mat.postTranslate((-px0 * sx).toFloat(), (-py0 * sy).toFloat())
        val page = r!!.openPage(0)
        try {
            page.render(bmp, null, mat, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        } finally {
            page.close()
        }
        return bmp
    }

    @Synchronized
    fun close() {
        try { r?.close() } catch (e: Exception) {}
        try { pfd?.close() } catch (e: Exception) {}
        r = null; pfd = null
    }
}

object MapStore {
    private val lock = Any()

    private fun dir(ctx: Context) = File(ctx.filesDir, "maps").apply { mkdirs() }
    private fun index(ctx: Context) = File(dir(ctx), "maps.json")
    fun imageFile(ctx: Context, id: String) = File(dir(ctx), "$id.jpg")
    /** Salinan PDF asli (untuk render ulang detail saat zoom dekat). */
    fun pdfFile(ctx: Context, id: String) = File(dir(ctx), "$id.pdf")

    fun toJson(m: MapInfo): JSONObject = JSONObject()
        .put("id", m.id).put("name", m.name).put("w", m.w).put("h", m.h).put("pw", m.pageW).put("ph", m.pageH)
        .put("x1", m.x1).put("y1", m.y1).put("lon1", m.lon1).put("lat1", m.lat1)
        .put("x2", m.x2).put("y2", m.y2).put("lon2", m.lon2).put("lat2", m.lat2)
        .also { j -> m.aff?.let { f -> j.put("aff", JSONArray().also { a -> f.forEach { v -> a.put(v) } }) } }

    fun fromJson(o: JSONObject) = MapInfo(
        o.getString("id"), o.getString("name"), o.getInt("w"), o.getInt("h"),
        o.getDouble("pw"), o.getDouble("ph"),
        o.getDouble("x1"), o.getDouble("y1"), o.getDouble("lon1"), o.getDouble("lat1"),
        o.getDouble("x2"), o.getDouble("y2"), o.getDouble("lon2"), o.getDouble("lat2"),
        o.optJSONArray("aff")?.let { a -> if (a.length() == 8) DoubleArray(8) { i -> a.getDouble(i) } else null },
    )

    fun all(ctx: Context): List<MapInfo> = synchronized(lock) {
        val f = index(ctx)
        if (!f.exists()) return emptyList()
        try {
            val a = JSONArray(f.readText())
            List(a.length()) { i -> fromJson(a.getJSONObject(i)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(ctx: Context, list: List<MapInfo>) {
        val a = JSONArray()
        for (m in list) a.put(toJson(m))
        index(ctx).writeText(a.toString())
    }

    /** Kemas peta (gambar + data koordinat) menjadi satu file zip untuk dibagikan. */
    fun exportZip(ctx: Context, id: String, out: File): Boolean {
        val m = get(ctx, id) ?: return false
        val img = imageFile(ctx, id)
        if (!img.exists()) return false
        java.util.zip.ZipOutputStream(FileOutputStream(out)).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("meta.json"))
            z.write(toJson(m).toString().toByteArray(Charsets.UTF_8))
            z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("map.jpg"))
            img.inputStream().use { it.copyTo(z) }
            z.closeEntry()
        }
        return true
    }

    /** Import paket peta (zip hasil Share Peta). @return pesan kesalahan, atau null jika berhasil. */
    fun importZip(ctx: Context, bytes: ByteArray): String? {
        var meta: String? = null
        var jpg: ByteArray? = null
        try {
            java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    when (e.name) {
                        "meta.json" -> meta = String(z.readBytes(), Charsets.UTF_8)
                        "map.jpg" -> jpg = z.readBytes()
                    }
                }
            }
            val mj = meta
            val img = jpg
            if (mj == null || img == null) return "File ini bukan paket peta yang valid."
            val old = fromJson(JSONObject(mj))
            val id = UUID.randomUUID().toString()
            imageFile(ctx, id).writeBytes(img)
            synchronized(lock) {
                val same = all(ctx).filter { it.name.equals(old.name, ignoreCase = true) }
                same.forEach { imageFile(ctx, it.id).delete() }
                save(ctx, all(ctx).filter { m -> same.none { it.id == m.id } } + old.copy(id = id))
            }
            return null
        } catch (e: Exception) {
            return "Gagal import paket peta: ${e.message}"
        }
    }

    /** Gabungkan peta dari folder cadangan (maps.json + gambar). @return jumlah peta baru. */
    fun mergeFromDir(ctx: Context, src: File): Int = synchronized(lock) {
        val f = File(src, "maps.json")
        if (!f.exists()) return 0
        val a = try { JSONArray(f.readText()) } catch (e: Exception) { return 0 }
        val list = all(ctx).toMutableList()
        var added = 0
        for (i in 0 until a.length()) {
            val m = try { fromJson(a.getJSONObject(i)) } catch (e: Exception) { continue }
            if (list.any { it.id == m.id || it.name.equals(m.name, ignoreCase = true) }) continue
            val img = File(src, "${m.id}.jpg")
            if (!img.exists()) continue
            img.copyTo(imageFile(ctx, m.id), overwrite = true)
            val pdf = File(src, "${m.id}.pdf")
            if (pdf.exists()) pdf.copyTo(pdfFile(ctx, m.id), overwrite = true)
            list.add(m)
            added++
        }
        save(ctx, list)
        added
    }

    fun get(ctx: Context, id: String): MapInfo? = all(ctx).firstOrNull { it.id == id }

    /** Peta yang memuat titik ini (jika ada). */
    fun pick(ctx: Context, lat: Double, lon: Double): MapInfo? = all(ctx).firstOrNull { it.contains(lat, lon) }

    fun remove(ctx: Context, id: String) = synchronized(lock) {
        val list = all(ctx).filter { it.id != id }
        imageFile(ctx, id).delete()
        pdfFile(ctx, id).delete()
        save(ctx, list)
    }

    class Manual(val latTop: Double, val lonLeft: Double, val latBottom: Double, val lonRight: Double)

    /**
     * Import GeoPDF. Dipanggil dari thread latar belakang.
     * @return pesan kesalahan, null jika berhasil, atau "NOGEO:..." bila PDF tidak punya koordinat yang bisa dibaca
     *         (layar akan menawarkan input koordinat manual lalu memanggil lagi dengan [manual]).
     */
    fun import(ctx: Context, uri: Uri, displayName: String, manual: Manual? = null): String? {
        val tmp = File(ctx.cacheDir, "import_map.pdf")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { ins -> FileOutputStream(tmp).use { ins.copyTo(it) } }
                ?: return "File tidak dapat dibuka"

            var geo: GeoPdf.Result? = null
            if (manual == null) {
                val parsed = GeoPdf.parse(tmp.readBytes())
                geo = parsed.result
                if (geo == null) return "NOGEO:" + (parsed.problem ?: "PDF ini tidak memiliki data georeferensi yang dikenali.")
            } else if (manual.latTop == manual.latBottom || manual.lonLeft == manual.lonRight) {
                return "Koordinat pojok tidak valid (kedua pojok tidak boleh sama)."
            }

            val pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val id = UUID.randomUUID().toString()
            val out = imageFile(ctx, id)
            val rw: Int
            val rh: Int
            renderer.openPage(0).let { pg -> rw = pg.width; rh = pg.height; pg.close() }
            // ukuran halaman: pakai hasil baca file bila cocok dengan renderer (lebih presisi), jika tidak ikuti renderer
            val pageW = geo?.boxW?.takeIf { Math.abs(it - rw) < 2.0 } ?: rw.toDouble()
            val pageH = geo?.boxH?.takeIf { Math.abs(it - rh) < 2.0 } ?: rh.toDouble()
            val scale = Math.min(2600.0 / pageW, 4000.0 / pageH)
            val outW = (pageW * scale).roundToInt().coerceAtLeast(200)
            val outH = (pageH * scale).roundToInt().coerceAtLeast(200)
            try {
                val page = renderer.openPage(0)
                val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                bmp.recycle()
            } finally {
                renderer.close()
                pfd.close()
            }

            val info: MapInfo
            val name = displayName.removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { "Peta" }
            if (manual != null) {
                info = MapInfo(
                    id, name, outW, outH, pageW, pageH,
                    0.0, pageH, manual.lonLeft, manual.latTop, pageW, 0.0, manual.lonRight, manual.latBottom,
                )
            } else {
                val rows = geo!!.ctrl
                val f = if (rows.size >= 3) Affine.fit(rows) else null
                if (f != null) {
                    val tmpInfo = MapInfo(id, name, outW, outH, pageW, pageH, 0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, f)
                    val (laBL, loBL) = tmpInfo.latLon(0.0, outH.toDouble())
                    val (laTR, loTR) = tmpInfo.latLon(outW.toDouble(), 0.0)
                    info = tmpInfo.copy(x1 = 0.0, y1 = 0.0, lon1 = loBL, lat1 = laBL, x2 = pageW, y2 = pageH, lon2 = loTR, lat2 = laTR)
                } else {
                    val c1 = rows[0]
                    val c2 = rows.drop(1).firstOrNull { it[0] != c1[0] && it[1] != c1[1] && it[2] != c1[2] && it[3] != c1[3] }
                        ?: run { imageFile(ctx, id).delete(); return "Titik koordinat peta tidak valid (titik kontrol sejajar / sama)." }
                    info = MapInfo(id, name, outW, outH, pageW, pageH, c1[0], c1[1], c1[3], c1[2], c2[0], c2[1], c2[3], c2[2])
                }
            }
            synchronized(lock) {
                // Nama sama -> ganti
                val old = all(ctx).filter { it.name.equals(name, ignoreCase = true) }
                old.forEach { imageFile(ctx, it.id).delete(); pdfFile(ctx, it.id).delete() }
                save(ctx, all(ctx).filter { m -> old.none { it.id == m.id } } + info)
                // simpan PDF asli agar zoom dekat bisa dirender tajam
                val keep = pdfFile(ctx, id)
                if (!tmp.renameTo(keep)) {
                    try { tmp.copyTo(keep, overwrite = true) } catch (e: Exception) { keep.delete() }
                }
            }
            return null
        } catch (e: OutOfMemoryError) {
            return "Memori HP tidak cukup untuk memproses peta ini."
        } catch (e: Exception) {
            return "Gagal import peta: ${e.message}"
        } finally {
            tmp.delete()
        }
    }
}

/** Pencocokan affine (kuadrat terkecil) dari titik kontrol [x, y, lat, lon] (koordinat halaman PDF). */
object Affine {
    /** True bila pemetaan (lon,lat) -> (x,y halaman) tercermin (determinan negatif); peta utara-atas normal = positif. */
    fun mirrored(rows: List<DoubleArray>): Boolean {
        val f = fit(rows)
        if (f != null) return f[2] * f[6] - f[3] * f[5] < 0
        val a = rows[0]
        val b = rows.drop(1).firstOrNull { it[0] != a[0] && it[1] != a[1] && it[2] != a[2] && it[3] != a[3] } ?: return false
        return (b[0] - a[0]) * (b[1] - a[1]) / ((b[3] - a[3]) * (b[2] - a[2])) < 0
    }

    fun fit(rows: List<DoubleArray>): DoubleArray? {
        val n = rows.size
        if (n < 3) return null
        val lat0 = rows.sumOf { it[2] } / n
        val lon0 = rows.sumOf { it[3] } / n
        val su = Math.sqrt(rows.sumOf { (it[3] - lon0) * (it[3] - lon0) } / n)
        val sv = Math.sqrt(rows.sumOf { (it[2] - lat0) * (it[2] - lat0) } / n)
        if (su < 1e-9 || sv < 1e-9) return null
        var suu = 0.0; var suv = 0.0; var svv = 0.0; var su1 = 0.0; var sv1 = 0.0
        val us = DoubleArray(n); val vs = DoubleArray(n)
        for (i in 0 until n) {
            us[i] = (rows[i][3] - lon0) / su
            vs[i] = (rows[i][2] - lat0) / sv
            suu += us[i] * us[i]; suv += us[i] * vs[i]; svv += vs[i] * vs[i]; su1 += us[i]; sv1 += vs[i]
        }
        val m = arrayOf(
            doubleArrayOf(suu, suv, su1),
            doubleArrayOf(suv, svv, sv1),
            doubleArrayOf(su1, sv1, n.toDouble()),
        )
        val det = det3(m)
        if (Math.abs(det) < 1e-6 * n * n * n) return null     // titik hampir segaris
        fun solve(target: (DoubleArray) -> Double): DoubleArray {
            var r0 = 0.0; var r1 = 0.0; var r2 = 0.0
            for (i in 0 until n) { val t = target(rows[i]); r0 += us[i] * t; r1 += vs[i] * t; r2 += t }
            val rhs = doubleArrayOf(r0, r1, r2)
            return DoubleArray(3) { c ->
                val mm = Array(3) { r -> DoubleArray(3) { k -> if (k == c) rhs[r] else m[r][k] } }
                det3(mm) / det
            }
        }
        val px = solve { it[0] }
        val py = solve { it[1] }
        val f = doubleArrayOf(lon0, lat0, px[0] / su, px[1] / sv, px[2], py[0] / su, py[1] / sv, py[2])
        if (Math.abs(f[2] * f[6] - f[3] * f[5]) < 1e-12) return null
        return f
    }

    private fun det3(m: Array<DoubleArray>): Double =
        m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
            m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
            m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
}

/**
 * Pembaca data georeferensi GeoPDF tanpa library. Format yang dikenali:
 *  1. ISO 32000 / Adobe (Avenza, QGIS, ArcGIS, GDAL, dll): /VP -> /Measure /Subtype /GEO dengan /GPTS + /LPTS (+ /BBox)
 *  2. TerraGo / Global Mapper: /LGIDict -> /Registration (koordinat lat/lon atau UTM)
 *  Peta berproyeksi apa pun pada format 1 didukung (GPTS selalu lat/lon); format 2 mendukung Geographic dan UTM.
 */
object GeoPdf {
    class Result(
        /** Titik kontrol [x, y, lat, lon]; x,y = koordinat halaman PDF relatif terhadap pojok kiri-bawah halaman. */
        val ctrl: List<DoubleArray>,
        val boxW: Double?, val boxH: Double?,
        val kind: String,
    )

    class Parsed(val result: Result?, val problem: String?)

    private val numRe = Regex("-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?")
    private fun nums(t: String): List<Double> = numRe.findAll(t).mapNotNull { it.value.toDoubleOrNull() }.toList()

    private val boxRe = Regex("\\[\\s*(-?[\\d.]+)\\s+(-?[\\d.]+)\\s+(-?[\\d.]+)\\s+(-?[\\d.]+)\\s*\\]")
    private fun boxOf(t: String, key: String): DoubleArray? {
        val k = t.indexOf(key)
        if (k < 0) return null
        val m = boxRe.find(t, k + key.length) ?: return null
        if (m.range.first - (k + key.length) > 6) return null
        val v = m.groupValues.drop(1).map { it.toDoubleOrNull() ?: return null }
        return doubleArrayOf(minOf(v[0], v[2]), minOf(v[1], v[3]), maxOf(v[0], v[2]), maxOf(v[1], v[3]))
    }

    /** Array angka sesudah [key] mulai dari indeks [from]; Pair(angka, posisi key). */
    private fun arrayAfter(t: String, key: String, from: Int): Pair<List<Double>, Int>? {
        val k = t.indexOf(key, from.coerceAtLeast(0))
        if (k < 0) return null
        var i = k + key.length
        while (i < t.length && t[i].isWhitespace()) i++
        if (i >= t.length || t[i] != '[') return null
        val e = t.indexOf(']', i)
        if (e < 0) return null
        return Pair(nums(t.substring(i + 1, e)), k)
    }

    /** Array [key] yang paling dekat dengan posisi [pos] (sebelum atau sesudah). */
    private fun nearestArray(t: String, key: String, pos: Int): List<Double>? {
        val after = arrayAfter(t, key, pos)
        val bi = t.lastIndexOf(key, pos)
        val before = if (bi >= 0) arrayAfter(t, key, bi) else null
        val da = if (after != null) after.second - pos else Int.MAX_VALUE
        val db = if (before != null) pos - before.second else Int.MAX_VALUE
        if (da > 6000 && db > 6000) return null
        return if (da <= db) after?.first else before?.first
    }

    /** BBox viewport (yang berdekatan dengan /Measure) sekitar posisi GPTS. */
    private fun viewportBox(t: String, pos: Int): DoubleArray? {
        var best: DoubleArray? = null
        var bestD = Int.MAX_VALUE
        var from = 0
        while (true) {
            val k = t.indexOf("/BBox", from)
            if (k < 0) break
            from = k + 5
            val win = t.substring(maxOf(0, k - 700), minOf(t.length, k + 700))
            if (!win.contains("/Measure") && !win.contains("/Viewport")) continue
            val m = boxRe.find(t, k + 5) ?: continue
            if (m.range.first - (k + 5) > 6) continue
            val v = m.groupValues.drop(1).mapNotNull { it.toDoubleOrNull() }
            if (v.size != 4) continue
            val d = Math.abs(k - pos)
            if (d < bestD) {
                bestD = d
                best = doubleArrayOf(minOf(v[0], v[2]), minOf(v[1], v[3]), maxOf(v[0], v[2]), maxOf(v[1], v[3]))
            }
        }
        return best
    }

    fun parse(bytes: ByteArray): Parsed {
        val raw = String(bytes, Charsets.ISO_8859_1)
        val blobs = ArrayList<String>()
        blobs.add(raw)
        // Objek PDF 1.5+ sering terkompres (object stream): buka stream Flate bertanda /ObjStm.
        var pos = 0
        while (true) {
            val i = raw.indexOf("stream", pos)
            if (i < 0) break
            pos = i + 6
            if (i >= 3 && raw.startsWith("end", i - 3)) continue
            var s = pos
            if (s < raw.length && raw[s] == '\r') s++
            if (s < raw.length && raw[s] == '\n') s++
            val e = raw.indexOf("endstream", s)
            if (e < 0) break
            val dict = raw.substring(maxOf(0, i - 400), i)
            if (dict.contains("/ObjStm")) {
                val out = inflate(bytes, s, e - s)
                if (out != null && out.isNotEmpty()) blobs.add(String(out, Charsets.ISO_8859_1))
            }
            pos = e + 9
        }
        if (raw.contains("/Encrypt")) {
            // tetap coba: sebagian PDF terenkripsi hanya mengenkripsi isi stream, bukan dictionary halaman
        }

        // Kotak halaman
        var crop: DoubleArray? = null
        var media: DoubleArray? = null
        for (t in blobs) {
            if (crop == null) crop = boxOf(t, "/CropBox")
            if (media == null) media = boxOf(t, "/MediaBox")
        }
        val pb = crop ?: media
        val ox = pb?.get(0) ?: 0.0
        val oy = pb?.get(1) ?: 0.0
        val bw = pb?.let { it[2] - it[0] }
        val bh = pb?.let { it[3] - it[1] }

        var problem: String? = null

        // ---- 1. ISO 32000 (/GPTS) ----
        for (t in blobs) {
            var from = 0
            while (true) {
                val g = arrayAfter(t, "/GPTS", from) ?: break
                from = g.second + 5
                var gp = g.first
                if (gp.size < 4 || gp.size % 2 != 0) continue
                val n = gp.size / 2
                // urutan lat,lon; bila terbalik (lat di luar +-90) tukar
                if (Math.abs(gp[0]) > 90.0 && Math.abs(gp[1]) <= 90.0) {
                    gp = List(gp.size) { i -> if (i % 2 == 0) gp[i + 1] else gp[i - 1] }
                }
                val lat = List(n) { gp[it * 2] }
                val lon = List(n) { gp[it * 2 + 1] }
                if (lat.any { Math.abs(it) > 90.0 } || lon.any { Math.abs(it) > 180.0 }) {
                    problem = "Koordinat pada GeoPDF di luar rentang lat/lon yang wajar."
                    continue
                }
                var lp = nearestArray(t, "/LPTS", g.second)
                if (lp == null || lp.size != gp.size) {
                    lp = if (gp.size == 8) listOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 0.0) else continue
                }
                val vb = viewportBox(t, g.second)
                fun build(flipY: Boolean): List<DoubleArray> {
                    val out = ArrayList<DoubleArray>()
                    for (i in 0 until n) {
                        val lx = lp[i * 2]
                        val ly = if (flipY) 1.0 - lp[i * 2 + 1] else lp[i * 2 + 1]
                        if (vb != null) {
                            out.add(doubleArrayOf(vb[0] + lx * (vb[2] - vb[0]) - ox, vb[1] + ly * (vb[3] - vb[1]) - oy, lat[i], lon[i]))
                        } else if (bw != null && bh != null) {
                            out.add(doubleArrayOf(lx * bw, ly * bh, lat[i], lon[i]))
                        }
                    }
                    return out
                }
                var rows = build(false)
                // Peta tidak mungkin tercermin (mirror). Jika hasilnya tercermin, sumbu-Y LPTS file ini dihitung dari atas -> balik.
                if (rows.size >= 2 && Affine.mirrored(rows)) rows = build(true)
                if (rows.size >= 2) return Parsed(Result(rows, bw, bh, "ISO 32000"), null)
            }
        }

        // ---- 2. TerraGo / Global Mapper (/LGIDict /Registration) ----
        for (t in blobs) {
            val k = t.indexOf("/Registration")
            if (k < 0) continue
            var seg = t.substring(k + 13, minOf(t.length, k + 13 + 1500))
            val cut = seg.indexOfAny(charArrayOf('/', '>'))
            if (cut >= 0) seg = seg.substring(0, cut)
            val n = nums(seg)
            if (n.size < 8) continue
            val reg = (0 until n.size / 4).map { doubleArrayOf(n[it * 4], n[it * 4 + 1], n[it * 4 + 2], n[it * 4 + 3]) }
            val win = t.substring(maxOf(0, k - 3000), minOf(t.length, k + 3000))
            val ptype = Regex("/ProjectionType\\s*/(\\w+)").find(win)?.groupValues?.get(1)?.uppercase()
            val rows = ArrayList<DoubleArray>()
            if (ptype == "UT" || ptype == "UTM") {
                val zone = Regex("/Zone\\s+(\\d+)").find(win)?.groupValues?.get(1)?.toIntOrNull()
                if (zone == null || zone !in 1..60) { problem = "Zona UTM pada peta tidak terbaca."; continue }
                val south = Regex("/Hemisphere\\s*[/(]\\s*([NSns])").find(win)?.groupValues?.get(1)?.uppercase() == "S"
                val r1 = reg[0]
                val r2 = reg.drop(1).firstOrNull { it[0] != r1[0] && it[1] != r1[1] && it[2] != r1[2] && it[3] != r1[3] } ?: continue
                // halaman -> (E,N) linier, lalu grid 3x3 dikonversi ke lat/lon
                val x0 = ox
                val y0 = oy
                val w = bw ?: continue
                val h = bh ?: continue
                for (gi in 0..2) for (gj in 0..2) {
                    val ax = x0 + w * gi / 2.0
                    val ay = y0 + h * gj / 2.0
                    val e = r1[2] + (ax - r1[0]) * (r2[2] - r1[2]) / (r2[0] - r1[0])
                    val nn = r1[3] + (ay - r1[1]) * (r2[3] - r1[3]) / (r2[1] - r1[1])
                    val (la, lo) = utmToLatLon(e, nn, zone, south)
                    rows.add(doubleArrayOf(ax - ox, ay - oy, la, lo))
                }
                return Parsed(Result(rows, bw, bh, "Global Mapper UTM"), null)
            }
            // geografis
            if (reg.any { Math.abs(it[2]) > 180.0 || Math.abs(it[3]) > 90.0 }) {
                problem = "Proyeksi peta (${ptype ?: "bukan lat/lon"}) belum didukung."
                continue
            }
            for (r in reg) rows.add(doubleArrayOf(r[0] - ox, r[1] - oy, r[3], r[2]))
            return Parsed(Result(rows, bw, bh, "Global Mapper"), null)
        }
        return Parsed(null, problem ?: "PDF ini tidak memiliki data georeferensi yang dikenali (bukan GeoPDF).")
    }

    /** UTM (WGS84) -> lat/lon derajat. */
    fun utmToLatLon(e: Double, n: Double, zone: Int, south: Boolean): Pair<Double, Double> {
        val a = 6378137.0
        val f = 1 / 298.257223563
        val k0 = 0.9996
        val nn = f / (2 - f)
        val a1 = a / (1 + nn) * (1 + nn * nn / 4 + Math.pow(nn, 4.0) / 64)
        val b = doubleArrayOf(
            nn / 2 - 2 * nn * nn / 3 + 37 * Math.pow(nn, 3.0) / 96 - Math.pow(nn, 4.0) / 360,
            nn * nn / 48 + Math.pow(nn, 3.0) / 15 - 437 * Math.pow(nn, 4.0) / 1440,
            17 * Math.pow(nn, 3.0) / 480 - 37 * Math.pow(nn, 4.0) / 840,
            4397 * Math.pow(nn, 4.0) / 161280,
        )
        val d = doubleArrayOf(
            2 * nn - 2 * nn * nn / 3 - 2 * Math.pow(nn, 3.0),
            7 * nn * nn / 3 - 8 * Math.pow(nn, 3.0) / 5,
            56 * Math.pow(nn, 3.0) / 15,
        )
        val x = e - 500000.0
        val y = if (south) n - 10000000.0 else n
        val xi = y / (k0 * a1)
        val eta = x / (k0 * a1)
        var xi2 = xi
        var eta2 = eta
        for (j in 1..4) {
            xi2 -= b[j - 1] * Math.sin(2 * j * xi) * Math.cosh(2 * j * eta)
            eta2 -= b[j - 1] * Math.cos(2 * j * xi) * Math.sinh(2 * j * eta)
        }
        val chi = Math.asin(Math.sin(xi2) / Math.cosh(eta2))
        var lat = chi
        for (j in 1..3) lat += d[j - 1] * Math.sin(2 * j * chi)
        val lon0 = (zone - 1) * 6 - 180 + 3
        val lon = lon0 + Math.toDegrees(Math.atan2(Math.sinh(eta2), Math.cos(xi2)))
        return Pair(Math.toDegrees(lat), lon)
    }

    private fun inflate(b: ByteArray, off: Int, len: Int): ByteArray? {
        if (len <= 0) return null
        val inf = Inflater()
        return try {
            inf.setInput(b, off, len)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(65536)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > 40_000_000) break
            }
            out.toByteArray()
        } catch (e: Exception) {
            null
        } finally {
            inf.end()
        }
    }
}

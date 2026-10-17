package id.dsngroup.kalkulatortkpanen

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Ekspor tracking ke format GIS: KML, GPX, GeoJSON, Shapefile (cocok untuk QGIS, ArcGIS, Google Earth, Avenza). */
object Gis {
    enum class Format(val label: String, val ext: String, val mime: String) {
        KML("KML (Google Earth, QGIS, ArcGIS)", "kml", "application/vnd.google-earth.kml+xml"),
        SHP("Shapefile .zip (ArcGIS, QGIS)", "zip", "application/zip"),
        GEOJSON("GeoJSON (QGIS, ArcGIS Online)", "geojson", "application/geo+json"),
        GPX("GPX (GPS, Avenza, QGIS)", "gpx", "application/gpx+xml"),
    }

    private fun d6(v: Double) = String.format(Locale.US, "%.7f", v)

    private fun iso(t: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun trackName(r: TrackRecord) =
        "Tracking ${if (r.blok.isNotBlank()) "Blok " + r.blok else ""} ${SimpleDateFormat("dd MMM yyyy HH:mm", Locale("in", "ID")).format(Date(r.startedAt))}".replace("  ", " ").trim()

    // ---------------- KML ----------------

    fun kml(tracks: List<TrackRecord>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n<name>Kalkulator TK Panen</name>\n")
        sb.append("<Style id=\"trk\"><LineStyle><color>ff0000ff</color><width>3</width></LineStyle></Style>\n")
        if (tracks.isNotEmpty()) {
            sb.append("<Folder><name>Tracking</name>\n")
            for (r in tracks) {
                if (r.points.size < 2) continue
                sb.append("<Placemark><name>${xml(trackName(r))}</name>")
                sb.append("<description>${xml("Jarak ${Geo.km(r.distance)}; ${r.points.size} titik; Est ${r.est}; Afd ${r.afd}")}</description>")
                sb.append("<TimeSpan><begin>${iso(r.startedAt)}</begin><end>${iso(r.endedAt)}</end></TimeSpan><styleUrl>#trk</styleUrl>")
                val segs = Geo.segments(r.points).filter { it.size >= 2 }
                if (segs.size > 1) sb.append("<MultiGeometry>")
                for (sg in segs) {
                    sb.append("<LineString><tessellate>1</tessellate><coordinates>")
                    sb.append(sg.joinToString(" ") { "${d6(it.lon)},${d6(it.lat)},0" })
                    sb.append("</coordinates></LineString>")
                }
                if (segs.size > 1) sb.append("</MultiGeometry>")
                sb.append("</Placemark>\n")
            }
            sb.append("</Folder>\n")
        }
        sb.append("</Document>\n</kml>\n")
        return sb.toString()
    }

    // ---------------- GPX ----------------

    fun gpx(tracks: List<TrackRecord>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Kalkulator TK Panen\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        for (r in tracks) {
            if (r.points.size < 2) continue
            sb.append("<trk><name>${xml(trackName(r))}</name><trkseg>\n")
            for ((i, p) in r.points.withIndex()) {
                if (p.brk && i > 0) sb.append("</trkseg>\n<trkseg>\n")      // jeda: segmen baru
                sb.append("<trkpt lat=\"${d6(p.lat)}\" lon=\"${d6(p.lon)}\"><time>${iso(if (p.t > 0) p.t else r.startedAt)}</time></trkpt>\n")
            }
            sb.append("</trkseg></trk>\n")
        }
        sb.append("</gpx>\n")
        return sb.toString()
    }

    // ---------------- GeoJSON ----------------

    private fun js(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append(String.format("\\u%04x", c.code))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }

    fun geojson(tracks: List<TrackRecord>): String {
        val feats = ArrayList<String>()
        for (r in tracks) {
            if (r.points.size < 2) continue
            val segs = Geo.segments(r.points).filter { it.size >= 2 }
            val geom = if (segs.size > 1)
                "{\"type\":\"MultiLineString\",\"coordinates\":[" +
                    segs.joinToString(",") { sg -> "[" + sg.joinToString(",") { "[${d6(it.lon)},${d6(it.lat)}]" } + "]" } + "]}"
            else
                "{\"type\":\"LineString\",\"coordinates\":[" + r.points.joinToString(",") { "[${d6(it.lon)},${d6(it.lat)}]" } + "]}"
            feats.add(
                "{\"type\":\"Feature\",\"geometry\":" + geom + "," +
                    "\"properties\":{\"jenis\":\"tracking\",\"nama\":${js(trackName(r))},\"est\":${js(r.est)},\"afd\":${js(r.afd)},\"blok\":${js(r.blok)}," +
                    "\"mulai\":${js(iso(r.startedAt))},\"selesai\":${js(iso(r.endedAt))},\"jarak_m\":${d6(r.distance)}}}"
            )
        }
        return "{\"type\":\"FeatureCollection\",\"features\":[\n" + feats.joinToString(",\n") + "\n]}\n"
    }

    // ---------------- Shapefile ----------------

    private const val PRJ = "GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\",6378137.0,298.257223563]]," +
        "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]"

    private class Field(val name: String, val type: Char, val len: Int, val dec: Int = 0)

    private fun fit(s: String, maxBytes: Int): ByteArray {
        var t = s
        var b = t.toByteArray(Charsets.UTF_8)
        while (b.size > maxBytes && t.isNotEmpty()) { t = t.dropLast(1); b = t.toByteArray(Charsets.UTF_8) }
        return b
    }

    private fun dbf(fields: List<Field>, rows: List<List<String>>): ByteArray {
        val out = ByteArrayOutputStream()
        val recLen = 1 + fields.sumOf { it.len }
        val hdrLen = 32 + 32 * fields.size + 1
        val h = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        val cal = java.util.Calendar.getInstance()
        h.put(3).put((cal.get(java.util.Calendar.YEAR) - 1900).toByte()).put((cal.get(java.util.Calendar.MONTH) + 1).toByte())
            .put(cal.get(java.util.Calendar.DAY_OF_MONTH).toByte())
        h.putInt(rows.size).putShort(hdrLen.toShort()).putShort(recLen.toShort())
        out.write(h.array())
        for (f in fields) {
            val fd = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
            val nb = f.name.toByteArray(Charsets.US_ASCII)
            fd.put(nb, 0, minOf(10, nb.size))
            fd.position(11)
            fd.put(f.type.code.toByte())
            fd.position(16)
            fd.put(f.len.toByte()).put(f.dec.toByte())
            out.write(fd.array())
        }
        out.write(0x0D)
        for (r in rows) {
            out.write(' '.code)
            for ((i, f) in fields.withIndex()) {
                val raw = r[i].replace('\n', ' ').replace('\r', ' ')
                val bytes = fit(raw, f.len)
                if (f.type == 'N') {
                    repeat(f.len - bytes.size) { out.write(' '.code) }
                    out.write(bytes)
                } else {
                    out.write(bytes)
                    repeat(f.len - bytes.size) { out.write(' '.code) }
                }
            }
        }
        out.write(0x1A)
        return out.toByteArray()
    }

    private fun header(buf: ByteBuffer, fileWords: Int, type: Int, x0: Double, y0: Double, x1: Double, y1: Double) {
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.putInt(9994)
        repeat(5) { buf.putInt(0) }
        buf.putInt(fileWords)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(1000).putInt(type)
        buf.putDouble(x0).putDouble(y0).putDouble(x1).putDouble(y1)
        buf.putDouble(0.0).putDouble(0.0).putDouble(0.0).putDouble(0.0)
    }

    /** Hasil: (shp, shx). Tiap bentuk = daftar bagian, tiap bagian = daftar (lon,lat). Titik: 1 bagian 1 titik. */
    private fun shp(isPoint: Boolean, shapes: List<List<DoubleArray>>): Pair<ByteArray, ByteArray> {
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (s in shapes) for (p in s) { minX = minOf(minX, p[0]); maxX = maxOf(maxX, p[0]); minY = minOf(minY, p[1]); maxY = maxOf(maxY, p[1]) }
        val recBytes = shapes.map { if (isPoint) 20 else 44 + 4 + 16 * it.size }   // isi rekaman (tanpa header 8 byte)
        val shpLen = 100 + recBytes.sumOf { it + 8 }
        val shxLen = 100 + 8 * shapes.size
        val a = ByteBuffer.allocate(shpLen)
        val x = ByteBuffer.allocate(shxLen)
        val type = if (isPoint) 1 else 3
        header(a, shpLen / 2, type, minX, minY, maxX, maxY)
        header(x, shxLen / 2, type, minX, minY, maxX, maxY)
        var offset = 100
        for ((i, s) in shapes.withIndex()) {
            val cl = recBytes[i]
            a.order(ByteOrder.BIG_ENDIAN); a.putInt(i + 1); a.putInt(cl / 2)
            x.order(ByteOrder.BIG_ENDIAN); x.putInt(offset / 2); x.putInt(cl / 2)
            a.order(ByteOrder.LITTLE_ENDIAN)
            a.putInt(type)
            if (isPoint) {
                a.putDouble(s[0][0]).putDouble(s[0][1])
            } else {
                var bx0 = Double.MAX_VALUE; var by0 = Double.MAX_VALUE; var bx1 = -Double.MAX_VALUE; var by1 = -Double.MAX_VALUE
                for (p in s) { bx0 = minOf(bx0, p[0]); bx1 = maxOf(bx1, p[0]); by0 = minOf(by0, p[1]); by1 = maxOf(by1, p[1]) }
                a.putDouble(bx0).putDouble(by0).putDouble(bx1).putDouble(by1)
                a.putInt(1).putInt(s.size).putInt(0)
                for (p in s) { a.putDouble(p[0]); a.putDouble(p[1]) }
            }
            offset += cl + 8
        }
        return Pair(a.array(), x.array())
    }

    private fun put(z: ZipOutputStream, name: String, bytes: ByteArray) {
        z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
    }

    fun shapefileZip(tracks: List<TrackRecord>, out: File) {
        ZipOutputStream(FileOutputStream(out)).use { z ->
            val tr = tracks.filter { it.points.size >= 2 }
            if (tr.isNotEmpty()) {
                val parts = tr.flatMap { r -> Geo.segments(r.points).filter { it.size >= 2 }.map { sg -> Pair(r, sg) } }
                val (s, x) = shp(false, parts.map { (_, sg) -> sg.map { doubleArrayOf(it.lon, it.lat) } })
                put(z, "tkpanen_tracking.shp", s); put(z, "tkpanen_tracking.shx", x)
                val f = listOf(
                    Field("NAMA", 'C', 100), Field("EST", 'C', 30), Field("AFD", 'C', 30), Field("BLOK", 'C', 20),
                    Field("MULAI", 'C', 20), Field("SELESAI", 'C', 20), Field("JARAK_M", 'N', 14, 1), Field("TITIK", 'N', 8, 0),
                )
                // satu rekaman per bagian garis (tracking yang dijeda menjadi beberapa bagian)
                put(z, "tkpanen_tracking.dbf", dbf(f, parts.map { (it, sg) ->
                    listOf(trackName(it), it.est, it.afd, it.blok, iso(it.startedAt), iso(it.endedAt),
                        String.format(Locale.US, "%.1f", Geo.length(sg)), sg.size.toString())
                }))
                put(z, "tkpanen_tracking.prj", PRJ.toByteArray()); put(z, "tkpanen_tracking.cpg", "UTF-8".toByteArray())
            }
        }
    }

    // ---------------- Ekspor ----------------

    /** Tulis file ekspor. @return file hasil atau null bila tidak ada data. */
    fun export(format: Format, tracks: List<TrackRecord>, dir: File): File? {
        val tr = tracks.filter { it.points.size >= 2 }
        if (tr.isEmpty()) return null
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val f = File(dir, "tkpanen_tracking_$stamp.${format.ext}")
        when (format) {
            Format.KML -> f.writeText(kml(tr), Charsets.UTF_8)
            Format.GPX -> f.writeText(gpx(tr), Charsets.UTF_8)
            Format.GEOJSON -> f.writeText(geojson(tr), Charsets.UTF_8)
            Format.SHP -> shapefileZip(tr, f)
        }
        return f
    }
}

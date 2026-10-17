package id.dsngroup.kalkulatortkpanen

import java.util.Locale

/** Konversi & pembacaan koordinat: desimal, derajat-menit-detik (DMS), dan UTM (WGS84). */
object Coord {
    data class Utm(val zone: Int, val south: Boolean, val e: Double, val n: Double) {
        override fun toString(): String =
            String.format(Locale.US, "%d%s %.0f %.0f", zone, if (south) "S" else "N", e, n)
    }

    data class Parsed(val lat: Double, val lon: Double, val kind: String)

    private const val A = 6378137.0
    private const val F = 1 / 298.257223563
    private const val K0 = 0.9996

    private fun atanh(x: Double) = 0.5 * Math.log((1 + x) / (1 - x))

    /** Lat/lon derajat -> UTM (rumus Krüger, galat < 1 mm di dalam zona). */
    fun toUtm(lat: Double, lon: Double, forceZone: Int? = null): Utm {
        val zone = forceZone ?: (Math.floor((lon + 180.0) / 6.0).toInt() + 1).coerceIn(1, 60)
        val lon0 = Math.toRadians((zone - 1) * 6.0 - 180.0 + 3.0)
        val n = F / (2 - F)
        val a1 = A / (1 + n) * (1 + n * n / 4 + Math.pow(n, 4.0) / 64)
        val al = doubleArrayOf(
            n / 2 - 2 * n * n / 3 + 5 * Math.pow(n, 3.0) / 16 + 41 * Math.pow(n, 4.0) / 180,
            13 * n * n / 48 - 3 * Math.pow(n, 3.0) / 5 + 557 * Math.pow(n, 4.0) / 1440,
            61 * Math.pow(n, 3.0) / 240 - 103 * Math.pow(n, 4.0) / 140,
            49561 * Math.pow(n, 4.0) / 161280,
        )
        val e = Math.sqrt(F * (2 - F))
        val phi = Math.toRadians(lat)
        val dl = Math.toRadians(lon) - lon0
        val t = Math.sinh(atanh(Math.sin(phi)) - e * atanh(e * Math.sin(phi)))
        val xi0 = Math.atan2(t, Math.cos(dl))
        val eta0 = atanh(Math.sin(dl) / Math.sqrt(1 + t * t))
        var xi = xi0
        var eta = eta0
        for (j in 1..4) {
            xi += al[j - 1] * Math.sin(2 * j * xi0) * Math.cosh(2 * j * eta0)
            eta += al[j - 1] * Math.cos(2 * j * xi0) * Math.sinh(2 * j * eta0)
        }
        val east = 500000.0 + K0 * a1 * eta
        var north = K0 * a1 * xi
        val south = lat < 0
        if (south) north += 10000000.0
        return Utm(zone, south, east, north)
    }

    fun utmText(lat: Double, lon: Double): String = toUtm(lat, lon).toString()

    private const val BANDS = "CDEFGHJKLMNPQRSTUVWX"

    /** Baca UTM seperti "50N 512345 82345", "50 S 490000 9990000", "50M 490000 9990000". */
    private fun parseUtm(s: String): Parsed? {
        val re = Regex("^\\s*(\\d{1,2})\\s*([A-Za-z])?\\s+(\\d+(?:[.,]\\d+)?)\\s*(?:m|mE|E)?[\\s,;]+(\\d+(?:[.,]\\d+)?)\\s*(?:m|mN|N)?\\s*$")
        val m = re.find(s) ?: return null
        val zone = m.groupValues[1].toInt()
        if (zone !in 1..60) return null
        val e = parseNum(m.groupValues[3]) ?: return null
        val n = parseNum(m.groupValues[4]) ?: return null
        val letter = m.groupValues[2].uppercase()
        // N / S dibaca sebagai belahan bumi (utara / selatan), seperti di aplikasi GPS lapangan.
        // Huruf lain dibaca sebagai band lintang UTM (C-M selatan, P-X utara).
        val south = when {
            letter == "S" -> true
            letter == "N" -> false
            letter.isNotEmpty() && BANDS.indexOf(letter[0]) >= 0 -> BANDS.indexOf(letter[0]) < BANDS.indexOf('N')
            letter.isNotEmpty() -> return null
            else -> n > 1_100_000.0      // tanpa huruf: northing besar = belahan selatan (wilayah Indonesia)
        }
        if (e < 100000.0 || e > 900000.0 || n < 0 || n > 10_000_000.0) return null
        val (lat, lon) = GeoPdf.utmToLatLon(e, n, zone, south)
        if (lat.isNaN() || lon.isNaN()) return null
        return Parsed(lat, lon, "UTM")
    }

    private fun parseDms(s0: String): Parsed? {
        if (!s0.contains('°') && !s0.contains('º') && !Regex("\\d\\s*d\\s*\\d", RegexOption.IGNORE_CASE).containsMatchIn(s0)) return null
        var s = s0.uppercase(Locale.US)
        s = s.replace("LU", "N").replace("LS", "S").replace("BT", "E").replace("BB", "W")
        s = s.replace('º', '°').replace('′', '\'').replace('’', '\'').replace('″', '"').replace("''", "\"")
        val re = Regex("([NSEW])?\\s*(-?\\d{1,3})\\s*[°D]\\s*(\\d{1,2}(?:[.,]\\d+)?)?\\s*'?\\s*(?:(\\d{1,2}(?:[.,]\\d+)?)\\s*\"?)?\\s*([NSEW])?")
        val vals = ArrayList<Pair<Double, Char?>>()
        for (m in re.findAll(s)) {
            val deg = m.groupValues[2].toDoubleOrNull() ?: continue
            val min = parseNum(m.groupValues[3]) ?: 0.0
            val sec = parseNum(m.groupValues[4]) ?: 0.0
            if (min >= 60.0 || sec >= 60.0) return null
            val hemi = (m.groupValues[1] + m.groupValues[5]).firstOrNull()
            var v = Math.abs(deg) + min / 60.0 + sec / 3600.0
            if (deg < 0) v = -v
            if (hemi == 'S' || hemi == 'W') v = -Math.abs(v)
            vals.add(Pair(v, hemi))
        }
        if (vals.size != 2) return null
        var (a, b) = Pair(vals[0], vals[1])
        // E/W di depan -> urutan lon,lat
        if (a.second == 'E' || a.second == 'W' || b.second == 'N' || b.second == 'S') { val t = a; a = b; b = t }
        if (Math.abs(a.first) > 90 || Math.abs(b.first) > 180) return null
        return Parsed(a.first, b.first, "DMS")
    }

    private fun parseDecimal(s: String): Parsed? {
        val t = s.trim()
        val byWs = t.split(Regex("[;\\s]+")).map { it.trimEnd(',', ';') }.filter { it.isNotEmpty() }
        val toks = when {
            byWs.size == 2 -> byWs
            else -> t.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
        if (toks.size != 2) return null
        val lat = parseNum(toks[0]) ?: return null
        val lon = parseNum(toks[1]) ?: return null
        if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return null
        return Parsed(lat, lon, "Desimal")
    }

    /** Baca koordinat dari teks bebas: desimal, DMS (LU/LS/BT/BB atau N/S/E/W), atau UTM. null bila tidak dikenali. */
    fun parse(text: String): Parsed? {
        val s = text.trim()
        if (s.isEmpty()) return null
        return parseDms(s) ?: parseUtm(s) ?: parseDecimal(s)
    }
}

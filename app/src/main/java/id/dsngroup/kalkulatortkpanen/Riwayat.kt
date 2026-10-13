package id.dsngroup.kalkulatortkpanen

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/** Satu catatan riwayat: tanggal (dd/MM/yyyy, diketik manual) + semua isian kalkulator. */
data class Entri(val id: Long, val tanggal: String, val input: Input)

object Fmt {
    private val idn = Locale("in", "ID")

    fun n(v: Double, digits: Int, minDigits: Int = digits): String {
        if (v.isNaN() || v.isInfinite()) return "#DIV/0!"
        val nf = NumberFormat.getNumberInstance(idn)
        nf.minimumFractionDigits = minDigits
        nf.maximumFractionDigits = digits
        return nf.format(v)
    }

    fun orang(v: Double): String =
        if (v.isNaN() || v.isInfinite()) "#DIV/0!"
        else "${n(v, 2)} Orang" + if (v > 0) "  (≈ ${ceil(v - 1e-9).toLong()})" else ""

    fun hariIni(): String = SimpleDateFormat("dd/MM/yyyy", idn).format(Date())

    /** true jika berformat dd/MM/yyyy dan tanggalnya nyata. */
    fun tanggalValid(s: String): Boolean = try {
        val f = SimpleDateFormat("dd/MM/yyyy", idn)
        f.isLenient = false
        f.parse(s.trim()) != null && Regex("""\d{1,2}/\d{1,2}/\d{4}""").matches(s.trim())
    } catch (e: Exception) { false }

    /** Teks laporan untuk WhatsApp. */
    fun pesan(e: Entri): String {
        val i = e.input
        val h = Calculator.hitung(i)
        return buildString {
            append("*KALKULATOR TK PANEN*\n")
            append("Tanggal : ${e.tanggal}\n\n")
            append("*Data Panen*\n")
            append("%AKP : ${n(i.akp * 100, 2, 0)}%\n")
            append("Target Luas : ${n(i.luas, 2, 0)} Ha\n")
            append("Rata-Rata SPH : ${n(i.sph, 2, 0)}\n")
            append("BJR : ${n(i.bjr, 2, 0)}\n")
            append("Janjang : ${n(h.janjang, 0)} janjang\n")
            append("Tonase : ${n(h.tonase, 2)} ton\n\n")
            append("*Kemampuan TK Panen*\n")
            append("Ha/HK : ${n(i.haPerHk, 2, 0)}\n")
            append("Ton/HK : ${n(i.tonPerHk, 2, 0)}\n\n")
            append("*Kebutuhan TK Panen*\n")
            append("By Luasan : ${orang(h.kebPanenLuasan)}\n")
            append("By Produksi : ${orang(h.kebPanenProduksi)}\n\n")
            append("*TK Tersedia* : ${n(i.tkTersedia, 2, 0)} Orang\n\n")
            append("*Kemampuan TK Gandeng*\n")
            append("Ha/HK : ${n(i.gandengHaPerHk, 2, 0)}\n")
            append("Ton/HK : ${n(i.gandengTonPerHk, 2, 0)}\n\n")
            append("*Kebutuhan TK Gandeng*\n")
            append("By Luasan : ${orang(h.kebGandengLuasan)}\n")
            append("By Produksi : ${orang(h.kebGandengProduksi)}")
        }
    }

    /** Kirim teks ke WhatsApp (WA biasa -> WA Business -> pilihan aplikasi). */
    fun bagikan(ctx: Context, teks: String) {
        fun intent(pkg: String?) = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, teks)
            if (pkg != null) setPackage(pkg)
        }
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try { ctx.startActivity(intent(pkg)); return } catch (e: ActivityNotFoundException) {}
        }
        ctx.startActivity(Intent.createChooser(intent(null), "Bagikan lewat"))
    }
}

/** Penyimpanan riwayat di HP (SharedPreferences, JSON). */
object RiwayatStore {
    private const val KEY = "riwayat"
    private fun prefs(c: Context) = c.getSharedPreferences("tkpanen", Context.MODE_PRIVATE)

    fun all(c: Context): List<Entri> {
        val arr = try { JSONArray(prefs(c).getString(KEY, "[]")) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<Entri>()
        for (k in 0 until arr.length()) {
            val o = arr.optJSONObject(k) ?: continue
            val d = Input()
            out.add(Entri(
                o.optLong("id"), o.optString("tgl"),
                Input(
                    akp = o.optDouble("akp", d.akp), luas = o.optDouble("luas", d.luas),
                    sph = o.optDouble("sph", d.sph), bjr = o.optDouble("bjr", d.bjr),
                    haPerHk = o.optDouble("haHk", d.haPerHk), tonPerHk = o.optDouble("tonHk", d.tonPerHk),
                    tkTersedia = o.optDouble("tk", d.tkTersedia),
                    gandengHaPerHk = o.optDouble("gHaHk", d.gandengHaPerHk),
                    gandengTonPerHk = o.optDouble("gTonHk", d.gandengTonPerHk),
                )
            ))
        }
        return out
    }

    private fun save(c: Context, list: List<Entri>) {
        val arr = JSONArray()
        for (e in list) arr.put(JSONObject()
            .put("id", e.id).put("tgl", e.tanggal)
            .put("akp", e.input.akp).put("luas", e.input.luas).put("sph", e.input.sph).put("bjr", e.input.bjr)
            .put("haHk", e.input.haPerHk).put("tonHk", e.input.tonPerHk).put("tk", e.input.tkTersedia)
            .put("gHaHk", e.input.gandengHaPerHk).put("gTonHk", e.input.gandengTonPerHk))
        prefs(c).edit().putString(KEY, arr.toString()).apply()
    }

    /** Entri terbaru di paling atas. */
    fun add(c: Context, e: Entri) = save(c, listOf(e) + all(c))

    fun delete(c: Context, id: Long) = save(c, all(c).filter { it.id != id })
}

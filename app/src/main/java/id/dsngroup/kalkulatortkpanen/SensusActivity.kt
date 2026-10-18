package id.dsngroup.kalkulatortkpanen

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.ExifInterface
import android.os.Bundle
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.io.FileOutputStream

/**
 * Sensus AKP per pokok. Koordinat dan lux terus diperbarui di layar; nilai yang dicatat
 * adalah nilai saat NEXT / FINISH ditekan. Hasil akhir: jumlah pokok, jumlah janjang, %AKP.
 */
class SensusActivity : AppCompatActivity(), LocationListener, SensorEventListener {

    private lateinit var sesi: SesiAktif
    private val pokok = ArrayList<Pokok>()
    private var noSekarang = 1

    // lokasi & lux
    private var lastLoc: Location? = null
    private var lastGpsAt = 0L
    private var lm: LocationManager? = null
    private var sm: SensorManager? = null
    private val luxSamples = ArrayList<Float>()
    private var luxRaw = -1f
    private var hasLight = false
    private val luxFactor: Float get() = getSharedPreferences("tkpanen", MODE_PRIVATE).getFloat("lux_factor", 1f)

    // tampilan
    private lateinit var tvNo: TextView
    private lateinit var tvKoord: TextView
    private lateinit var tvLux: TextView
    private lateinit var tvCount: TextView
    private lateinit var etJjg: TextInputEditText
    private lateinit var etKet: TextInputEditText
    private lateinit var ivFoto: ImageView
    private lateinit var btnFoto: MaterialButton
    private var fotoSekarang = ""
    private var hasilDitampilkan = false
    private var selesaiRec: TrackRecord? = null

    private val kamera = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) prosesFoto() else File(Sensus.fotoDir(this), TMP).delete()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val ada = Sensus.aktif(this)
        sesi = ada ?: SesiAktif(
            java.util.UUID.randomUUID().toString(),
            intent.getStringExtra("blok").orEmpty(), intent.getStringExtra("petugas").orEmpty(),
            System.currentTimeMillis(), emptyList(),
        )
        pokok.addAll(sesi.pokok)
        noSekarang = (pokok.maxOfOrNull { it.no } ?: 0) + 1
        if (ada == null) Sensus.simpanAktif(this, sesi)

        bangunLayar()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { keluar() }
        })
    }

    // ---------------------------------------------------------------- tampilan

    private fun header(sub: String): View {
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF2E7D32.toInt())
            setPadding(dp(8), dp(14), dp(16), dp(12))
        }
        h.addView(TextView(this).apply {
            text = "←"; textSize = 24f; setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(12), dp(4), dp(16), dp(4))
            contentDescription = "Kembali"
            setOnClickListener { keluar() }
        })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = "Sensus AKP"; textSize = 20f; setTypeface(null, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
        })
        col.addView(TextView(this).apply { text = sub; textSize = 13f; setTextColor(0xFFE8F5E9.toInt()) })
        h.addView(col)
        return h
    }

    private fun subJudul(): String {
        val b = if (sesi.blok.isNotBlank()) "Blok ${sesi.blok}" else "Tanpa blok"
        return if (sesi.petugas.isNotBlank()) "$b · ${sesi.petugas}" else b
    }

    private fun kartu(): Pair<MaterialCardView, LinearLayout> {
        val c = MaterialCardView(this).apply {
            radius = dp(16).toFloat(); cardElevation = dp(2).toFloat()
            setCardBackgroundColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(12) }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        c.addView(col)
        return Pair(c, col)
    }

    private fun bangunLayar() {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFFF1F3EF.toInt()) }
        page.addView(header(subJudul()))

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }

        // --- kartu pokok ---
        val (c1, col1) = kartu()
        tvNo = TextView(this).apply { textSize = 28f; setTypeface(null, Typeface.BOLD); setTextColor(0xFF111111.toInt()) }
        tvKoord = TextView(this).apply { textSize = 14f; setTextColor(0xFF444444.toInt()); setPadding(0, dp(4), 0, 0) }
        tvLux = TextView(this).apply { textSize = 14f; setTextColor(0xFF444444.toInt()) }
        tvCount = TextView(this).apply { textSize = 13f; setTypeface(null, Typeface.BOLD); setTextColor(0xFF1B5E20.toInt()); setPadding(0, dp(6), 0, 0) }
        col1.addView(tvNo)
        col1.addView(tvKoord)
        col1.addView(tvLux)
        col1.addView(TextView(this).apply {
            text = "Koordinat & lux terus diperbarui. Nilai akhir diambil saat menekan NEXT / FINISH."
            textSize = 13f; setTextColor(0xFF555555.toInt()); setPadding(0, dp(8), 0, dp(8))
        })
        col1.addView(tvCount)
        col1.addView(TextView(this).apply { text = "Janjang Matang (Jjg)"; textSize = 14f; setTextColor(0xFF444444.toInt()); setPadding(0, dp(10), 0, dp(4)) })
        val (til, et) = inputField(this, "Janjang matang", InputType.TYPE_CLASS_NUMBER, "0")
        etJjg = et
        et.setSelectAllOnFocus(true)
        val rowJ = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun pm(label: String, d: Int) = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label; textSize = 20f; insetTop = 0; insetBottom = 0
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { leftMargin = dp(6); bottomMargin = dp(8) }
            setPadding(0, 0, 0, 0)
            setOnClickListener { etJjg.setText(((etJjg.text.toString().trim().toIntOrNull() ?: 0) + d).coerceAtLeast(0).toString()) }
        }
        til.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { bottomMargin = dp(8) }
        rowJ.addView(til); rowJ.addView(pm("−", -1)); rowJ.addView(pm("+", 1))
        col1.addView(rowJ)
        body.addView(c1)

        // --- kartu foto ---
        val (c2, col2) = kartu()
        col2.addView(TextView(this).apply { text = "Foto Pokok"; textSize = 17f; setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, dp(8)) })
        btnFoto = MaterialButton(this).apply {
            text = "📷 Ambil Foto"; textSize = 16f
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2E7D32.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            setOnClickListener { ambilFoto() }
        }
        col2.addView(btnFoto)
        ivFoto = ImageView(this).apply {
            adjustViewBounds = true; scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180)).apply { topMargin = dp(8) }
        }
        col2.addView(ivFoto)
        val (til2, et2) = inputField(this, "Keterangan foto (opsional)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        etKet = et2
        til2.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        col2.addView(til2)
        body.addView(c2)

        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // --- tombol bawah ---
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(14))
        }
        bar.addView(MaterialButton(this).apply {
            text = "FINISH"; textSize = 16f
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFC62828.toInt())
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply { rightMargin = dp(6) }
            setOnClickListener { konfirmasiFinish() }
        })
        bar.addView(MaterialButton(this).apply {
            text = "NEXT ▶"; textSize = 16f
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2E7D32.toInt())
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply { leftMargin = dp(6) }
            setOnClickListener { next() }
        })
        page.addView(bar)
        setContentView(page)
        refreshTampilan()
    }

    private fun refreshTampilan() {
        tvNo.text = "Pokok No. $noSekarang"
        val l = lastLoc
        tvKoord.text = if (l == null) "📍 Mencari sinyal GPS…"
        else "📍 " + Fmt.koordinat(l.latitude, l.longitude).replace(",", ", ") +
            (if (l.hasAccuracy()) " (±${Fmt.n(l.accuracy.toDouble(), 1)} m)" else "")
        tvLux.text = "☀ " + when {
            !hasLight -> "sensor cahaya tidak tersedia"
            luxRaw < 0 -> "membaca…"
            else -> Sensus.luxText(luxKalibrasi())
        }
        val jjg = pokok.sumOf { it.jjg }
        tvCount.text = if (pokok.isEmpty()) "Belum ada pokok tersimpan"
        else "Tersimpan: ${pokok.size} pokok • $jjg janjang • %AKP ${Fmt.n(Sensus.akp(pokok.size, jjg), 2)}%"
    }

    private fun luxKalibrasi(): Double = (luxRaw * luxFactor).toDouble()

    // ---------------------------------------------------------------- GPS & lux

    @SuppressLint("MissingPermission")
    override fun onStart() {
        super.onStart()
        if (hasilDitampilkan) return
        val m = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = m
        var ada = false
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (m.isProviderEnabled(p)) { m.requestLocationUpdates(p, 1000L, 0f, this, Looper.getMainLooper()); ada = true }
            } catch (e: Exception) {}
        }
        if (!ada) Toast.makeText(this, "GPS HP belum aktif. Aktifkan lokasi agar koordinat terbaca.", Toast.LENGTH_LONG).show()
        val s = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm = s
        val light = s.getDefaultSensor(Sensor.TYPE_LIGHT)
        hasLight = light != null
        if (light != null) s.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL)
        refreshTampilan()
    }

    override fun onStop() {
        super.onStop()
        try { lm?.removeUpdates(this) } catch (e: Exception) {}
        try { sm?.unregisterListener(this) } catch (e: Exception) {}
    }

    override fun onLocationChanged(location: Location) {
        val now = System.currentTimeMillis()
        if (location.provider == LocationManager.GPS_PROVIDER) { lastLoc = location; lastGpsAt = now }
        else if (lastLoc == null || now - lastGpsAt > 10000L) lastLoc = location
        refreshTampilan()
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onSensorChanged(e: SensorEvent) {
        luxSamples.add(e.values[0])
        if (luxSamples.size > 5) luxSamples.removeAt(0)
        luxRaw = luxSamples.average().toFloat()
        refreshTampilan()
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}

    // ---------------------------------------------------------------- foto

    private fun ambilFoto() {
        try {
            val f = File(Sensus.fotoDir(this), TMP)
            if (!f.exists()) f.createNewFile()
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            kamera.launch(uri)
        } catch (e: Exception) {
            CrashLog.error(this, "ambilFoto", e)
            Toast.makeText(this, "Kamera tidak dapat dibuka: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Perkecil foto (sisi terpanjang 1280 px) dan putar sesuai EXIF, simpan sebagai foto pokok sekarang. */
    private fun prosesFoto() {
        val tmp = File(Sensus.fotoDir(this), TMP)
        try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, o)
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 1280) sample *= 2
            val opt = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = BitmapFactory.decodeFile(tmp.path, opt) ?: throw IllegalStateException("foto tidak terbaca")
            val rot = when (ExifInterface(tmp.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (rot != 0f) {
                val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
                if (r !== bmp) { bmp.recycle(); bmp = r }
            }
            val nama = "${sesi.id.take(8)}_$noSekarang.jpg"
            FileOutputStream(File(Sensus.fotoDir(this), nama)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            tmp.delete()
            fotoSekarang = nama
            ivFoto.setImageBitmap(bmp)
            ivFoto.visibility = View.VISIBLE
            btnFoto.text = "📷 Ganti Foto"
        } catch (e: Throwable) {
            CrashLog.error(this, "prosesFoto", if (e is Exception) e else RuntimeException(e.toString()))
            Toast.makeText(this, "Foto gagal diproses: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- next / finish

    /** Catat pokok yang sedang tampil dengan koordinat & lux saat ini. false jika GPS belum tersedia. */
    private fun catatSekarang(): Boolean {
        val l = lastLoc
        if (l == null) {
            Toast.makeText(this, "Koordinat belum didapat. Tunggu sinyal GPS dulu.", Toast.LENGTH_LONG).show()
            return false
        }
        val jjg = etJjg.text.toString().trim().toIntOrNull() ?: 0
        pokok.add(Pokok(
            noSekarang, l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else 0f,
            if (hasLight && luxRaw >= 0) luxKalibrasi() else -1.0,
            jjg, fotoSekarang, etKet.text.toString().trim(), System.currentTimeMillis(),
        ))
        Sensus.simpanAktif(this, sesi.copy(pokok = pokok.toList()))
        return true
    }

    private fun kosongkanIsian() {
        etJjg.setText("0")
        etKet.setText("")
        fotoSekarang = ""
        ivFoto.setImageDrawable(null)
        ivFoto.visibility = View.GONE
        btnFoto.text = "📷 Ambil Foto"
    }

    private fun next() {
        if (!catatSekarang()) return
        Toast.makeText(this, "Pokok No. $noSekarang tersimpan", Toast.LENGTH_SHORT).show()
        noSekarang++
        kosongkanIsian()
        refreshTampilan()
    }

    private fun konfirmasiFinish() {
        val d = MaterialAlertDialogBuilder(this)
            .setMessage("Selesaikan sensus? Pokok No. $noSekarang sedang tampil.")
            .setNeutralButton("Cancel", null)
            .setPositiveButton("Ya, simpan juga yang sedang tampil") { _, _ -> finishSensus(true) }
            .setNegativeButton("Ya, jangan simpan yang sedang tampil") { _, _ -> finishSensus(false) }
            .show()
        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setTextColor(0xFF2E7D32.toInt())
        d.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setTextColor(0xFFC62828.toInt())
    }

    private fun finishSensus(simpanSekarang: Boolean) {
        if (simpanSekarang) {
            if (!catatSekarang()) return
        } else if (fotoSekarang.isNotBlank()) {
            try { File(Sensus.fotoDir(this), fotoSekarang).delete() } catch (e: Exception) {}
            fotoSekarang = ""
        }
        if (pokok.isEmpty()) {
            Toast.makeText(this, "Belum ada pokok yang tersimpan. Tekan NEXT untuk menyimpan pokok.", Toast.LENGTH_LONG).show()
            return
        }
        val mid = pokok[pokok.size / 2]
        val mapId = try { MapStore.pick(this, mid.lat, mid.lon)?.id ?: "" } catch (e: Exception) { "" }
        val rec = Sensus.selesai(this, sesi.copy(pokok = pokok.toList()), mapId)
        selesaiRec = rec
        setResult(RESULT_OK, Intent().putExtra(EXTRA_TRACK_ID, rec.id))
        try { lm?.removeUpdates(this) } catch (e: Exception) {}
        try { sm?.unregisterListener(this) } catch (e: Exception) {}
        hasilDitampilkan = true
        tampilkanHasil(rec)
    }

    private fun keluar() {
        if (hasilDitampilkan) { finish(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Keluar dari sensus?")
            .setMessage(
                (if (pokok.isEmpty()) "" else "${pokok.size} pokok yang sudah tersimpan tetap aman dan bisa dilanjutkan nanti dari tombol \"Lanjutkan Sensus\". ") +
                "Isian pokok No. $noSekarang yang belum NEXT tidak ikut tersimpan."
            )
            .setPositiveButton("Keluar") { _, _ -> finish() }
            .setNeutralButton("Batalkan sensus") { _, _ ->
                MaterialAlertDialogBuilder(this).setTitle("Batalkan sensus?")
                    .setMessage("Semua ${pokok.size} pokok (dan fotonya) akan dibuang.")
                    .setPositiveButton("Buang") { _, _ -> Sensus.buangAktif(this); finish() }
                    .setNegativeButton("Kembali", null).show()
            }
            .setNegativeButton("Lanjut sensus", null)
            .show()
    }

    // ---------------------------------------------------------------- layar hasil

    private fun tampilkanHasil(rec: TrackRecord) {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFFF1F3EF.toInt()) }
        page.addView(header(subJudul()))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }

        body.addView(TextView(this).apply {
            text = "✓ Sensus selesai"; textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(0xFF1B5E20.toInt())
            setPadding(dp(4), dp(4), 0, dp(10))
        })

        fun stat(label: String, value: String, besar: Boolean = false, warna: Int = 0xFF111111.toInt()): MaterialCardView {
            val (c, col) = kartu()
            col.addView(TextView(this).apply { text = label; textSize = 14f; setTextColor(0xFF555555.toInt()) })
            col.addView(TextView(this).apply {
                text = value; textSize = if (besar) 40f else 30f; setTypeface(null, Typeface.BOLD); setTextColor(warna)
            })
            return c
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val a = stat("Jumlah Pokok", rec.jumlahPokok.toString())
        val b = stat("Jumlah Janjang", rec.jumlahJjg.toString())
        a.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6); bottomMargin = dp(12) }
        b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6); bottomMargin = dp(12) }
        row.addView(a); row.addView(b)
        body.addView(row)
        body.addView(stat("%AKP", Fmt.n(rec.akpPersen, 2) + "%", true, 0xFF2E7D32.toInt()))
        body.addView(TextView(this).apply {
            text = "%AKP = jumlah janjang matang ÷ jumlah pokok × 100.\nNilai ini otomatis diisi ke kolom %AKP di kalkulator."
            textSize = 13f; setTextColor(0xFF555555.toInt()); setPadding(dp(4), 0, dp(4), dp(12))
        })

        val (c3, col3) = kartu()
        val menit = Math.round((rec.endedAt - rec.startedAt) / 60000.0)
        val acc = rec.pokok.filter { it.acc > 0 }.map { it.acc.toDouble() }
        val luxList = rec.pokok.filter { it.lux >= 0 }.map { it.lux }
        col3.addView(TextView(this).apply {
            text = "Mulai : ${Fmt.tgljam(rec.startedAt)}\nDurasi : $menit menit\nJarak antar pokok : ${Geo.km(rec.distance)}" +
                (if (acc.isNotEmpty()) "\nRata-rata akurasi GPS : ±${Fmt.n(acc.average(), 1)} m" else "") +
                (if (luxList.isNotEmpty()) "\nRata-rata lux : ${Sensus.luxText(luxList.average())}" else "") +
                "\nPokok berfoto : ${rec.pokok.count { it.foto.isNotBlank() }}"
            textSize = 14f
        })
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFDDE8DD.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)).apply { topMargin = dp(10) }
        }
        col3.addView(iv)
        body.addView(c3)
        val app = applicationContext
        Thread {
            val bmp = try { TrackRender.render(app, MapStore.get(app, rec.mapId), rec.points, 900, 560, null, true) } catch (x: Throwable) { null }
            iv.post { if (bmp != null) iv.setImageBitmap(bmp) }
        }.start()

        val (c4, col4) = kartu()
        col4.addView(TextView(this).apply { text = "Daftar Pokok"; textSize = 16f; setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, dp(6)) })
        col4.addView(TextView(this).apply {
            text = rec.pokok.joinToString("\n") { p ->
                "No ${p.no} • ${p.jjg} jjg • ${Fmt.koordinat(p.lat, p.lon)} • ${Sensus.luxText(p.lux)}" + if (p.foto.isNotBlank()) " 📷" else ""
            }
            textSize = 12f
            typeface = Typeface.MONOSPACE
        })
        body.addView(c4)

        val scroll = ScrollView(this).apply { addView(body) }
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val bar = LinearLayout(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt()); setPadding(dp(12), dp(10), dp(12), dp(14))
        }
        bar.addView(MaterialButton(this).apply {
            text = "SELESAI · ISI %AKP KE KALKULATOR"; textSize = 15f
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2E7D32.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
            setOnClickListener { finish() }
        })
        page.addView(bar)
        setContentView(page)
    }

    companion object {
        const val EXTRA_TRACK_ID = "trackId"
        private const val TMP = "tmp_kamera.jpg"
    }
}

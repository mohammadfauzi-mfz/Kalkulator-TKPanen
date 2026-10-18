package id.dsngroup.kalkulatortkpanen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val fields = LinkedHashMap<String, TextInputEditText>()
    private lateinit var etTanggal: TextInputEditText
    private lateinit var tvJanjang: TextView
    private lateinit var tvTonase: TextView
    private lateinit var tvPanenLuasan: TextView
    private lateinit var tvPanenProduksi: TextView
    private lateinit var tvGandengLuasan: TextView
    private lateinit var tvGandengProduksi: TextView
    private lateinit var tabs: TabLayout
    private lateinit var calcScroll: ScrollView
    private lateinit var histScroll: ScrollView
    private lateinit var histList: LinearLayout

    // ---- bukti tracking & peta ----
    private lateinit var peta: PetaView
    private lateinit var etBlok: TextInputEditText
    private lateinit var btnTrack: MaterialButton
    private lateinit var etPetugas: TextInputEditText
    private lateinit var trackBox: LinearLayout
    private lateinit var tvTrackInfo: TextView
    private lateinit var ivTrack: ImageView
    private lateinit var cbTrack: MaterialCheckBox
    private var lastTrack: TrackRecord? = null
    private var rendering = false
    private val prefs by lazy { getSharedPreferences("tkpanen", MODE_PRIVATE) }

    private val permReq = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (::tabs.isInitialized && tabs.selectedTabPosition == 1) peta.onShown()
    }
    private val sensusReq = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val id = r.data?.getStringExtra(SensusActivity.EXTRA_TRACK_ID)
        if (r.resultCode == RESULT_OK && id != null) hasilSensus(id) else updateTrackUi()
    }
    private val importMap = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) doImportMap(uri)
    }
    private val importData = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) doImportData(uri)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = Input()

        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Header: judul + ikon informasi (kanan atas)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF2E7D32.toInt())
            setPadding(dp(16), dp(16), dp(8), dp(12))
        }
        header.addView(TextView(this).apply {
            text = "KALKULATOR TK PANEN"; textSize = 20f
            setTypeface(null, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = "⚙"; textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
            contentDescription = "Pengaturan peta"
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { showSettings() }
        })
        header.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_info)
            contentDescription = "Informasi"
            setBackgroundColor(0x00000000)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { startActivity(Intent(this@MainActivity, AboutActivity::class.java)) }
        })
        page.addView(header)

        tabs = TabLayout(this)
        tabs.addTab(tabs.newTab().setText("Kalkulator"))
        tabs.addTab(tabs.newTab().setText("Peta"))
        tabs.addTab(tabs.newTab().setText("Riwayat"))
        page.addView(tabs)

        // ---------- Tab Kalkulator ----------
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }

        root.addView(section("Tanggal"))
        val tgl = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "Tanggal (dd/MM/yyyy)"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        etTanggal = TextInputEditText(tgl.context).apply {
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE
            setText(Fmt.hariIni())
        }
        tgl.addView(etTanggal)
        root.addView(tgl)

        root.addView(section("Sensus %AKP per Pokok"))
        val (tBlok, eBlok) = inputField(this, "Blok / lokasi sensus (opsional)",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        etBlok = eBlok
        root.addView(tBlok)
        val (tPtg, ePtg) = inputField(this, "Nama petugas sensus (opsional)",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        etPetugas = ePtg
        root.addView(tPtg)
        btnTrack = MaterialButton(this).apply {
            text = "Mulai Sensus AKP"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            backgroundTintList = ColorStateList.valueOf(0xFFD32F2F.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
                .apply { topMargin = dp(4); bottomMargin = dp(4) }
            setOnClickListener { mulaiSensus() }
        }
        root.addView(btnTrack)
        trackBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        tvTrackInfo = TextView(this).apply { textSize = 13f; setPadding(0, dp(2), 0, dp(4)) }
        trackBox.addView(tvTrackInfo)
        ivTrack = ImageView(this).apply {
            adjustViewBounds = true
            setBackgroundColor(0xFFDDE8DD.toInt())
            minimumHeight = dp(160)
        }
        trackBox.addView(ivTrack, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        cbTrack = MaterialCheckBox(this).apply {
            text = "Lampirkan bukti sensus saat Simpan / Share WA"
            textSize = 14f
            isChecked = true
            visibility = View.GONE
        }
        trackBox.addView(cbTrack)
        root.addView(trackBox)

        root.addView(section("Data Panen"))
        root.addView(field("akp", "%AKP", d.akp * 100))
        root.addView(field("luas", "Target Luas (Ha)", d.luas))
        root.addView(field("sph", "Rata-Rata SPH", d.sph))
        root.addView(field("bjr", "BJR", d.bjr))
        tvJanjang = resultRow(root, "Janjang")
        tvTonase = resultRow(root, "Tonase")

        root.addView(section("Kemampuan TK Panen"))
        root.addView(field("haHk", "Ha/HK", d.haPerHk))
        root.addView(field("tonHk", "Ton/HK", d.tonPerHk))
        root.addView(section("Kebutuhan TK Panen"))
        tvPanenLuasan = resultRow(root, "By Luasan")
        tvPanenProduksi = resultRow(root, "By Produksi")

        root.addView(section("TK Tersedia"))
        root.addView(field("tk", "TK Tersedia (Orang)", d.tkTersedia))

        root.addView(section("Kemampuan TK Gandeng"))
        root.addView(field("gHaHk", "Ha/HK", d.gandengHaPerHk))
        root.addView(field("gTonHk", "Ton/HK", d.gandengTonPerHk))
        root.addView(section("Kebutuhan TK Gandeng"))
        tvGandengLuasan = resultRow(root, "By Luasan")
        tvGandengProduksi = resultRow(root, "By Produksi")

        val aksi = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(20), 0, 0)
        }
        aksi.addView(MaterialButton(this).apply {
            text = "Simpan ke Riwayat"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) }
            setOnClickListener { simpan() }
        })
        aksi.addView(MaterialButton(this).apply {
            text = "Share ke WA"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) }
            setOnClickListener { entriSaatIni()?.let { bagikanEntri(it) } }
        })
        root.addView(aksi)

        calcScroll = ScrollView(this).apply { isFillViewport = true; addView(root) }

        // ---------- Tab Riwayat ----------
        histList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }
        histScroll = ScrollView(this).apply { addView(histList); visibility = View.GONE }

        peta = PetaView(this, object : PetaHost {
            override fun startSensus() { this@MainActivity.mulaiSensus() }
            override fun hasLocationPermission() = this@MainActivity.hasLocationPermission()
            override fun requestLocationPermission() { requestGps() }
            override fun importMap() { this@MainActivity.importMap.launch(arrayOf("application/pdf", "*/*")) }
            override fun importData() { this@MainActivity.importData.launch(arrayOf("*/*")) }
        })
        peta.visibility = View.GONE

        val frame = FrameLayout(this)
        frame.addView(calcScroll)
        frame.addView(peta, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(histScroll)
        page.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val p = tab.position
                calcScroll.visibility = if (p == 0) View.VISIBLE else View.GONE
                peta.visibility = if (p == 1) View.VISIBLE else View.GONE
                histScroll.visibility = if (p == 2) View.VISIBLE else View.GONE
                if (p == 1) peta.onShown() else peta.onHidden()
                if (p == 2) renderRiwayat()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        setContentView(page)

        fields.values.forEach { it.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refresh()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }) }
        refresh()

        updateTrackUi()
        window.decorView.post { checkRecoveryAndCrash() }
    }

    override fun onStart() {
        super.onStart()
        if (::tabs.isInitialized && tabs.selectedTabPosition == 1) peta.onShown()
    }

    override fun onStop() {
        super.onStop()
        if (::tabs.isInitialized && tabs.selectedTabPosition == 1) peta.onHidden()
    }

    override fun onResume() {
        super.onResume()
        if (::btnTrack.isInitialized) updateTrackUi()
    }

    // ---------- komponen ----------

    private fun section(title: String) = TextView(this).apply {
        text = title
        textSize = 16f
        setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun field(key: String, label: String, value: Double): TextInputLayout {
        val til = TextInputLayout(this, null,
            com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = label
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val et = TextInputEditText(til.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(teksAngka(value))
        }
        til.addView(et)
        fields[key] = et
        return til
    }

    private fun teksAngka(value: Double) =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    private fun resultRow(parent: LinearLayout, label: String): TextView {
        val card = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(0xFFE8F5E9.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        row.addView(TextView(this).apply {
            text = label; textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val value = TextView(this).apply {
            textSize = 18f; setTypeface(null, Typeface.BOLD); gravity = Gravity.END
        }
        row.addView(value)
        card.addView(row)
        parent.addView(card)
        return value
    }

    // ---------- hitung ----------

    private fun num(key: String): Double =
        fields[key]!!.text.toString().trim().replace(',', '.').toDoubleOrNull() ?: 0.0

    private fun inputSaatIni() = Input(
        akp = num("akp") / 100.0, luas = num("luas"), sph = num("sph"), bjr = num("bjr"),
        haPerHk = num("haHk"), tonPerHk = num("tonHk"), tkTersedia = num("tk"),
        gandengHaPerHk = num("gHaHk"), gandengTonPerHk = num("gTonHk"),
    )

    private fun refresh() {
        val h = Calculator.hitung(inputSaatIni())
        tvJanjang.text = Fmt.n(h.janjang, 0) + " janjang"
        tvTonase.text = Fmt.n(h.tonase, 2) + " ton"
        tvPanenLuasan.text = Fmt.orang(h.kebPanenLuasan)
        tvPanenProduksi.text = Fmt.orang(h.kebPanenProduksi)
        tvGandengLuasan.text = Fmt.orang(h.kebGandengLuasan)
        tvGandengProduksi.text = Fmt.orang(h.kebGandengProduksi)
    }

    // ---------- tanggal, simpan, share ----------

    /** Entri dari isian saat ini; null (dengan pesan) jika tanggal tidak valid. */
    private fun entriSaatIni(): Entri? {
        val t = etTanggal.text.toString().trim()
        if (!Fmt.tanggalValid(t)) {
            Toast.makeText(this, "Tanggal tidak valid. Gunakan format dd/MM/yyyy, mis. 13/10/2026", Toast.LENGTH_LONG).show()
            return null
        }
        if (Sensus.aktif(this) != null) {
            Toast.makeText(this, "Sensus AKP belum selesai. Lanjutkan sensus lalu tekan FINISH.", Toast.LENGTH_LONG).show()
            return null
        }
        val rec = lastTrack
        val lampir = rec != null && cbTrack.visibility == View.VISIBLE && cbTrack.isChecked
        return Entri(System.currentTimeMillis(), t, inputSaatIni(), if (lampir) rec!!.id else "")
    }

    /** Share teks WA; bila ada bukti tracking, gambar jalur di peta ikut terkirim. */
    private fun bagikanEntri(e: Entri) {
        val tr = if (e.trackId.isNotBlank()) TrackStore.get(this, e.trackId) else null
        val teks = Fmt.pesan(e, tr)
        if (tr == null || tr.points.size < 2) { Fmt.bagikan(this, teks); return }
        Toast.makeText(this, "Menyiapkan gambar tracking...", Toast.LENGTH_SHORT).show()
        val app = applicationContext
        Thread {
            val f = try {
                val map = MapStore.get(app, tr.mapId) ?: tr.points[tr.points.size / 2].let { MapStore.pick(app, it.lat, it.lon) }
                val title = "AKP ${Fmt.n(e.input.akp * 100, 2, 0)}% • " + (if (tr.blok.isNotBlank()) "Blok ${tr.blok} • " else "") +
                    (if (tr.isSensus) "${tr.jumlahPokok} pokok • ${tr.jumlahJjg} jjg" else Geo.km(tr.distance)) + " • ${e.tanggal}"
                val bmp = TrackRender.render(app, map, tr.points, 1280, 900, title, tr.isSensus)
                val out = File(File(app.cacheDir, "exports").apply { mkdirs() }, "akp_tracking_${e.id}.jpg")
                FileOutputStream(out).use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
                out
            } catch (x: Throwable) { null }
            runOnUiThread {
                if (f != null) Share.textWithPhoto(this, teks, f) else Fmt.bagikan(this, teks)
            }
        }.start()
    }

    private fun simpan() {
        val e = entriSaatIni() ?: return
        RiwayatStore.add(this, e)
        Toast.makeText(this, "Tersimpan di Riwayat", Toast.LENGTH_SHORT).show()
    }

    // ---------- riwayat ----------

    private fun renderRiwayat() {
        histList.removeAllViews()
        val list = RiwayatStore.all(this)
        if (list.isEmpty()) {
            histList.addView(TextView(this).apply {
                text = "Belum ada riwayat. Isi kalkulator lalu tekan \"Simpan ke Riwayat\"."
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(48), 0, 0)
            })
            return
        }
        for (e in list) histList.addView(kartuRiwayat(e))
    }

    private fun kartuRiwayat(e: Entri): View {
        val i = e.input
        val h = Calculator.hitung(i)
        val card = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(0xFFE8F5E9.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        col.addView(TextView(this).apply {
            text = e.tanggal; textSize = 17f; setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF1B5E20.toInt())
        })
        col.addView(TextView(this).apply {
            text = "AKP ${Fmt.n(i.akp * 100, 2, 0)}% • Luas ${Fmt.n(i.luas, 2, 0)} Ha • SPH ${Fmt.n(i.sph, 2, 0)} • BJR ${Fmt.n(i.bjr, 2, 0)}\n" +
                "Janjang ${Fmt.n(h.janjang, 0)} • Tonase ${Fmt.n(h.tonase, 2)} ton\n" +
                "TK Panen: luasan ${Fmt.orang(h.kebPanenLuasan)}\n" +
                "TK Panen: produksi ${Fmt.orang(h.kebPanenProduksi)}\n" +
                "TK Tersedia ${Fmt.n(i.tkTersedia, 2, 0)} Orang"
            textSize = 13f
            setPadding(0, dp(4), 0, dp(4))
        })
        val tr = if (e.trackId.isNotBlank()) TrackStore.get(this, e.trackId) else null
        if (e.trackId.isNotBlank()) {
            col.addView(TextView(this).apply {
                text = if (tr == null) "Bukti sensus sudah dihapus dari HP"
                else if (tr.isSensus) "Bukti sensus: " + (if (tr.blok.isNotBlank()) "Blok ${tr.blok} • " else "") +
                    "${tr.jumlahPokok} pokok • ${tr.jumlahJjg} janjang • %AKP ${Fmt.n(tr.akpPersen, 2)}%"
                else "Bukti tracking: " + (if (tr.blok.isNotBlank()) "Blok ${tr.blok} • " else "") +
                    "${Geo.km(tr.distance)} • ${tr.minutes} menit • ${tr.points.size} titik"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(0xFF1B5E20.toInt())
                setPadding(0, dp(2), 0, dp(4))
            })
            if (tr != null) {
                val iv = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(0xFFDDE8DD.toInt())
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(140))
                        .apply { bottomMargin = dp(6) }
                    tag = e.id
                }
                col.addView(iv)
                val app = applicationContext
                Thread {
                    val bmp = try {
                        TrackRender.render(app, MapStore.get(app, tr.mapId), tr.points, 640, 300, null, tr.isSensus)
                    } catch (x: Throwable) { null }
                    iv.post { if (iv.tag == e.id && bmp != null) iv.setImageBitmap(bmp) }
                }.start()
            }
        }
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tombol(label: String, onClick: () -> Unit) = MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = label; textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(2), 0, dp(2), 0) }
            setOnClickListener { onClick() }
        }
        btns.addView(tombol("Buka") { muat(e) })
        btns.addView(tombol("Share WA") { bagikanEntri(e) })
        btns.addView(tombol("Hapus") {
            MaterialAlertDialogBuilder(this)
                .setTitle("Hapus riwayat?")
                .setMessage("Catatan tanggal ${e.tanggal} akan dihapus.")
                .setPositiveButton("Hapus") { _, _ -> RiwayatStore.delete(this, e.id); renderRiwayat() }
                .setNegativeButton("Batal", null)
                .show()
        })
        col.addView(btns)
        card.addView(col)
        return card
    }

    /** Muat catatan riwayat kembali ke kalkulator. */
    private fun muat(e: Entri) {
        val i = e.input
        etTanggal.setText(e.tanggal)
        fields["akp"]!!.setText(teksAngka(i.akp * 100))
        fields["luas"]!!.setText(teksAngka(i.luas))
        fields["sph"]!!.setText(teksAngka(i.sph))
        fields["bjr"]!!.setText(teksAngka(i.bjr))
        fields["haHk"]!!.setText(teksAngka(i.haPerHk))
        fields["tonHk"]!!.setText(teksAngka(i.tonPerHk))
        fields["tk"]!!.setText(teksAngka(i.tkTersedia))
        fields["gHaHk"]!!.setText(teksAngka(i.gandengHaPerHk))
        fields["gTonHk"]!!.setText(teksAngka(i.gandengTonPerHk))
        run {
            val rec = if (e.trackId.isNotBlank()) TrackStore.get(this, e.trackId) else null
            lastTrack = rec
            if (rec != null) {
                etBlok.setText(rec.blok)
                etPetugas.setText(rec.petugas)
                trackBox.visibility = View.VISIBLE
                cbTrack.isChecked = true
                updateTrackUi()
                renderPreview(rec)
            } else {
                trackBox.visibility = View.GONE
                updateTrackUi()
            }
        }
        tabs.getTabAt(0)?.select()
    }

    // ---------- Izin lokasi ----------

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun requestGps() {
        permReq.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    // ---------- Sensus AKP per pokok ----------

    private fun mulaiSensus() {
        if (!hasLocationPermission()) {
            toast("Izinkan akses lokasi dulu, lalu tekan tombol sensus lagi.")
            requestGps()
            return
        }
        val aktif = Sensus.aktif(this)
        if (aktif != null) {
            val jjg = aktif.pokok.sumOf { it.jjg }
            MaterialAlertDialogBuilder(this).setTitle("Sensus belum selesai")
                .setMessage("Blok ${aktif.blok.ifBlank { "-" }}: ${aktif.pokok.size} pokok, $jjg janjang sudah tersimpan.\n\nLanjutkan sensus ini, atau buang dan mulai sensus baru?")
                .setPositiveButton("Lanjutkan") { _, _ -> bukaSensus() }
                .setNeutralButton("Buang & mulai baru") { _, _ -> Sensus.buangAktif(this); bukaSensus() }
                .setNegativeButton("Batal", null).show()
            return
        }
        if (MapStore.all(this).isEmpty()) toast("Peta offline belum diimport (ikon ⚙). Titik pokok tetap tersimpan di latar polos.")
        bukaSensus()
    }

    private fun bukaSensus() {
        sensusReq.launch(Intent(this, SensusActivity::class.java)
            .putExtra("blok", etBlok.text.toString().trim())
            .putExtra("petugas", etPetugas.text.toString().trim()))
    }

    /** Sensus selesai: %AKP otomatis masuk ke kolom %AKP kalkulator. */
    private fun hasilSensus(trackId: String) {
        val rec = TrackStore.get(this, trackId) ?: return
        lastTrack = rec
        etBlok.setText(rec.blok)
        etPetugas.setText(rec.petugas)
        val akp = Math.round(rec.akpPersen * 100.0) / 100.0
        fields["akp"]!!.setText(teksAngka(akp))
        trackBox.visibility = View.VISIBLE
        cbTrack.isChecked = true
        updateTrackUi()
        renderPreview(rec)
        peta.refresh()
        toast("%AKP ${Fmt.n(rec.akpPersen, 2)}% diisi otomatis dari sensus (${rec.jumlahPokok} pokok, ${rec.jumlahJjg} janjang).")
        calcScroll.post { calcScroll.smoothScrollTo(0, 0) }
    }

    /** Dipanggil saat aplikasi dibuka: kirim log crash bila ada. */
    private fun checkRecoveryAndCrash() {
        if (CrashLog.unseenCrash(this) == null) return
        CrashLog.markSeen(this)
        MaterialAlertDialogBuilder(this).setTitle("Aplikasi sempat berhenti")
            .setMessage("Kalkulator TK Panen sebelumnya berhenti tiba-tiba. Kirim log error ke pengembang agar bisa diperbaiki?")
            .setPositiveButton("Kirim log") { _, _ -> shareLog() }
            .setNegativeButton("Nanti", null).show()
    }

    private fun shareLog() {
        val f = File(File(cacheDir, "exports").apply { mkdirs() }, "TKPanen_Log_" +
            java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date()) + ".txt")
        if (!CrashLog.bundle(this, f)) { toast("Log tidak dapat dibuat."); return }
        Share.file(this, f, "text/plain", "Kirim log error")
    }

    private fun updateTrackUi() {
        val aktif = Sensus.aktif(this)
        btnTrack.text = if (aktif != null) "Lanjutkan Sensus (${aktif.pokok.size} pokok)" else "Mulai Sensus AKP"
        val rec = lastTrack
        cbTrack.visibility = if (rec != null) View.VISIBLE else View.GONE
        if (rec != null) {
            tvTrackInfo.text = if (rec.isSensus)
                "Sensus selesai • ${rec.jumlahPokok} pokok • ${rec.jumlahJjg} janjang • %AKP ${Fmt.n(rec.akpPersen, 2)}% • tersimpan di tab Peta"
            else "Tracking selesai • ${rec.points.size} titik • ${Geo.km(rec.distance)} • tersimpan di tab Peta"
        }
    }

    private fun renderPreview(rec: TrackRecord) {
        if (rendering || rec.points.isEmpty()) return
        rendering = true
        val app = applicationContext
        Thread {
            val bmp = try {
                TrackRender.render(app, MapStore.get(app, rec.mapId), rec.points, 900, 560, null, rec.isSensus)
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            runOnUiThread {
                rendering = false
                if (bmp != null) ivTrack.setImageBitmap(bmp)
            }
        }.start()
    }

    // ---------- Import peta & data ----------

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) return c.getString(i) ?: "Peta"
            }
        } catch (e: Exception) {}
        return "Peta"
    }

    private fun doImportMap(uri: Uri, manual: MapStore.Manual? = null) {
        val name = displayName(uri)
        val app = applicationContext
        toast("Mengimport peta... (bisa sampai 30 detik)")
        Thread {
            val err = MapStore.import(app, uri, name, manual)
            runOnUiThread {
                when {
                    err == null -> { toast("Peta berhasil diimport: ${name.removeSuffix(".pdf")}"); peta.refresh() }
                    err.startsWith("NOGEO:") -> askManualCalibration(uri, err.removePrefix("NOGEO:"))
                    else -> MaterialAlertDialogBuilder(this).setTitle("Import peta gagal").setMessage(err)
                        .setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }

    /** PDF tanpa koordinat yang bisa dibaca: tawarkan input koordinat 2 pojok secara manual (peta harus menghadap utara). */
    private fun askManualCalibration(uri: Uri, detail: String) {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(8), dp(20), 0)
        col.addView(TextView(this).apply {
            text = "$detail\n\nJika Anda tahu koordinat dua pojok peta (peta harus menghadap utara, tidak miring), isi di bawah. Format: lat,lon (mis. 0.746073,117.415342)."
            textSize = 13f
            setPadding(0, 0, 0, dp(8))
        })
        val (t1, e1) = inputField(this, "Pojok KIRI-ATAS (lat,lon)", InputType.TYPE_CLASS_TEXT)
        val (t2, e2) = inputField(this, "Pojok KANAN-BAWAH (lat,lon)", InputType.TYPE_CLASS_TEXT)
        col.addView(t1); col.addView(t2)
        val sv = ScrollView(this)
        sv.addView(col)
        fun pair(s: String): Pair<Double, Double>? {
            val p = s.trim().split(Regex("[,;\\s]+")).filter { it.isNotBlank() }
            if (p.size != 2) return null
            val a = parseNum(p[0]) ?: return null
            val b = parseNum(p[1]) ?: return null
            return if (Math.abs(a) <= 90 && Math.abs(b) <= 180) Pair(a, b) else null
        }
        MaterialAlertDialogBuilder(this).setTitle("Peta tidak berkoordinat").setView(sv)
            .setPositiveButton("Pakai koordinat ini") { _, _ ->
                val tl = pair(e1.text.toString())
                val br = pair(e2.text.toString())
                if (tl == null || br == null) toast("Format koordinat salah. Contoh: 0.746073,117.415342")
                else doImportMap(uri, MapStore.Manual(tl.first, tl.second, br.first, br.second))
            }
            .setNegativeButton("Batal", null).show()
    }

    /** Import file: GeoPDF (peta), paket peta (.zip hasil Share File Peta), atau database jalur (.json). */
    private fun doImportData(uri: Uri) {
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) { null }
        if (bytes == null) { toast("File tidak dapat dibuka."); return }
        if (bytes.size > 4 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'D'.code.toByte()) {
            doImportMap(uri)
            return
        }
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            val app = applicationContext
            toast("Mengimport file peta...")
            Thread {
                val err = MapStore.importZip(app, bytes)
                runOnUiThread {
                    if (err == null) toast("Peta berhasil diimport.") else toast(err)
                    peta.refresh()
                }
            }.start()
            return
        }
        val n = TrackStore.importJson(this, bytes.toString(Charsets.UTF_8))
        toast(when {
            n < 0 -> "File bukan data tracking yang valid."
            n == 0 -> "Tidak ada tracking baru (semua sudah ada)."
            else -> "Import: $n tracking baru."
        })
        peta.refresh()
    }

    // ---------- Pengaturan peta & tracking ----------

    private fun showSettings() {
        val maps = MapStore.all(this)
        val info = if (maps.isEmpty()) "Belum ada peta offline." else "Peta offline: " + maps.joinToString(", ") { it.name }
        val items = arrayOf("📥 Import peta GeoPDF", "🗑 Hapus peta…", "📨 Kirim Log Error")
        MaterialAlertDialogBuilder(this).setTitle("Peta & Sensus")
            .setMessage(info)
            .setItems(items) { _, i ->
                when (i) {
                    0 -> importMap.launch(arrayOf("application/pdf", "*/*"))
                    1 -> confirmDeleteMap()
                    2 -> shareLog()
                }
            }
            .setNegativeButton("Tutup", null).show()
    }

    private fun confirmDeleteMap() {
        val maps = MapStore.all(this)
        if (maps.isEmpty()) { toast("Belum ada peta untuk dihapus"); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Hapus peta mana?")
            .setItems(maps.map { it.name }.toTypedArray()) { _, which ->
                MapStore.remove(this, maps[which].id)
                peta.refresh()
                toast("Peta dihapus")
            }
            .setNegativeButton("Batal", null)
            .show()
    }
}

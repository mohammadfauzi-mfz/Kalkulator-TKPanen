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
    private lateinit var btnPause: MaterialButton
    private lateinit var trackBox: LinearLayout
    private lateinit var tvTrackInfo: TextView
    private lateinit var ivTrack: ImageView
    private lateinit var cbTrack: MaterialCheckBox
    private var lastTrack: TrackRecord? = null
    private var rendering = false
    private val trackHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("tkpanen", MODE_PRIVATE) }

    private val permReq = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (::tabs.isInitialized && tabs.selectedTabPosition == 1) peta.onShown()
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
            contentDescription = "Pengaturan peta & tracking"
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

        root.addView(section("Bukti Tracking Pengambilan %AKP"))
        val (tBlok, eBlok) = inputField(this, "Blok / lokasi pengambilan AKP (opsional)",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        etBlok = eBlok
        root.addView(tBlok)
        btnTrack = MaterialButton(this).apply {
            text = "Start Tracking"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            backgroundTintList = ColorStateList.valueOf(0xFFD32F2F.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
                .apply { topMargin = dp(4); bottomMargin = dp(4) }
            setOnClickListener { toggleTracking() }
        }
        root.addView(btnTrack)
        btnPause = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "⏸ Pause Tracking"
            textSize = 14f
            isAllCaps = false
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
                .apply { bottomMargin = dp(4) }
            setOnClickListener { togglePause() }
        }
        root.addView(btnPause)
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
            text = "Lampirkan bukti tracking saat Simpan / Share WA"
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
            override fun toggleTracking() { this@MainActivity.toggleTracking() }
            override fun togglePause() { this@MainActivity.togglePause() }
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

        // tracking yang masih berjalan (aplikasi dibuka kembali) / terputus / log crash
        if (Tracker.active) {
            trackBox.visibility = View.VISIBLE
            updateTrackUi()
            trackHandler.post(trackTick)
        }
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

    override fun onDestroy() {
        trackHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
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
        if (Tracker.active) {
            Toast.makeText(this, "Tracking masih berjalan. Tekan Stop Tracking dulu.", Toast.LENGTH_LONG).show()
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
                    "${Geo.km(tr.distance)} • ${e.tanggal}"
                val bmp = TrackRender.render(app, map, tr.points, 1280, 900, title)
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
                text = if (tr != null) "Bukti tracking: " + (if (tr.blok.isNotBlank()) "Blok ${tr.blok} • " else "") +
                    "${Geo.km(tr.distance)} • ${tr.minutes} menit • ${tr.points.size} titik"
                else "Bukti tracking sudah dihapus dari HP"
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
                        TrackRender.render(app, MapStore.get(app, tr.mapId), tr.points, 640, 300)
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
        if (!Tracker.active) {
            val rec = if (e.trackId.isNotBlank()) TrackStore.get(this, e.trackId) else null
            lastTrack = rec
            if (rec != null) {
                etBlok.setText(rec.blok)
                trackBox.visibility = View.VISIBLE
                cbTrack.isChecked = true
                updateTrackUi()
                renderPreview(rec.points, rec.mapId)
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
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permReq.launch(perms.toTypedArray())
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    // ---------- Tracking (sama dengan CKP Report) ----------

    private val trackTick = object : Runnable {
        override fun run() {
            if (!Tracker.active) return
            val err = Tracker.gpsError
            if (err != null) {
                toast(err)
                cancelTracking()
                return
            }
            val pts = Tracker.snapshot()
            val gap = if (Tracker.lastFixAt > 0) (System.currentTimeMillis() - Tracker.lastFixAt) / 1000 else -1L
            val warn = if (gap > 60 && !Tracker.paused) "\n⚠ Tidak ada titik GPS baru ${gap} dtk (sinyal lemah / GPS mati?)" else ""
            tvTrackInfo.text = (if (Tracker.paused) "⏸ Tracking DIJEDA (GPS mati sementara) • " else "● Tracking aktif • ") +
                "${pts.size} titik • ${Geo.km(Geo.length(pts))}$warn"
            updateTrackUi()
            renderPreview(pts, null)
            trackHandler.postDelayed(this, 2000)
        }
    }

    private fun toggleTracking() {
        if (Tracker.active) stopTracking() else startTracking()
    }

    /** Jeda / lanjutkan tracking (dipakai tombol di tab Kalkulator dan tab Peta). */
    private fun togglePause() {
        if (!Tracker.active) return
        if (Tracker.paused) Tracker.unpause(this) else Tracker.pause(this)
        CrashLog.event(this, if (Tracker.paused) "Tracking dijeda pengguna" else "Tracking dilanjutkan pengguna")
        try {
            // service menyesuaikan GPS (mati saat jeda, hidup saat lanjut) dan memperbarui notifikasi
            ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java))
        } catch (e: Exception) {
            CrashLog.error(this, "togglePause", e)
            toast("Gagal mengubah status tracking: ${e.message}")
        }
        updateTrackUi()
        toast(if (Tracker.paused) "Tracking dijeda. GPS dimatikan sementara." else "Tracking dilanjutkan.")
    }

    private fun startTracking() {
        if (!hasLocationPermission()) {
            toast("Izinkan akses lokasi dulu, lalu tekan Start Tracking lagi.")
            requestGps()
            return
        }
        Tracker.start(this, "", "", etBlok.text.toString().trim())
        try {
            ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java))
        } catch (e: Exception) {
            Tracker.stop(this)
            CrashLog.error(this, "startTracking", e)
            toast("Tracking tidak dapat dimulai: ${e.message}")
            return
        }
        CrashLog.event(this, "Tracking dimulai oleh pengguna")
        askNotifPermissionOnce()
        if (!prefs.getBoolean("batt_guide", false)) {
            prefs.edit().putBoolean("batt_guide", true).apply()
            showBatteryGuide()
        }
        lastTrack = null
        cbTrack.visibility = View.GONE
        trackBox.visibility = View.VISIBLE
        tvTrackInfo.text = "● Tracking aktif • menunggu sinyal GPS..."
        ivTrack.setImageDrawable(null)
        updateTrackUi()
        if (MapStore.all(this).isEmpty()) toast("Peta offline belum diimport (ikon ⚙). Jalur tetap direkam di latar polos.")
        trackHandler.removeCallbacks(trackTick)
        trackHandler.post(trackTick)
    }

    private fun cancelTracking() {
        Tracker.stop(this)
        stopService(Intent(this, TrackingService::class.java))
        trackHandler.removeCallbacks(trackTick)
        if (lastTrack == null) trackBox.visibility = View.GONE
        updateTrackUi()
    }

    private fun stopTracking() {
        val pts = Tracker.stop(this)
        CrashLog.event(this, "Tracking dihentikan pengguna (${pts.size} titik)")
        stopService(Intent(this, TrackingService::class.java))
        trackHandler.removeCallbacks(trackTick)
        if (pts.size < 2) {
            toast("Titik tracking terlalu sedikit (belum ada pergerakan). Tracking dibatalkan.")
            trackBox.visibility = View.GONE
            lastTrack = null
            updateTrackUi()
            return
        }
        val mid = pts[pts.size / 2]
        val mapId = MapStore.pick(this, mid.lat, mid.lon)?.id ?: ""
        val rec = TrackRecord(
            UUID.randomUUID().toString(), Tracker.startedAt, System.currentTimeMillis(),
            "", "", etBlok.text.toString().trim(), mapId, pts,
            Tracker.pausedTotal(),
        )
        TrackStore.add(this, rec)
        lastTrack = rec
        cbTrack.isChecked = true
        updateTrackUi()
        renderPreview(pts, mapId)
        peta.refresh()
        toast(
            if (mapId.isEmpty()) "Tracking tersimpan (lokasi di luar peta offline / peta belum diimport). Tekan Simpan ke Riwayat atau Share WA untuk melampirkannya."
            else "Tracking tersimpan. Tekan Simpan ke Riwayat atau Share WA untuk melampirkannya sebagai bukti."
        )
    }

    /** Android 13+: tanpa izin notifikasi, penanda tracking di status bar tidak tampil (service lebih mudah dimatikan). */
    private fun askNotifPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        if (prefs.getBoolean("notif_asked", false)) return
        prefs.edit().putBoolean("notif_asked", true).apply()
        permReq.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }

    private fun showBatteryGuide() {
        val brand = Build.MANUFACTURER.lowercase()
        val khusus = when {
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ->
                "Xiaomi/Redmi/POCO: Info Aplikasi Kalkulator TK Panen → Hemat baterai → pilih \"Tanpa batasan\", lalu aktifkan \"Mulai otomatis\". Di layar Recent, kunci aplikasi (tahan kartu → ikon gembok)."
            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") ->
                "Oppo/Realme/OnePlus: Info Aplikasi → Penggunaan baterai → izinkan \"Aktivitas latar belakang\" dan \"Mulai otomatis\"; kunci aplikasi di layar Recent."
            brand.contains("vivo") || brand.contains("iqoo") ->
                "Vivo/iQOO: Pengaturan → Baterai → Konsumsi daya latar belakang → Kalkulator TK Panen → \"Izinkan aktivitas latar belakang\"; aktifkan juga Mulai otomatis."
            brand.contains("samsung") ->
                "Samsung: Pengaturan → Baterai → Batas penggunaan di latar belakang → keluarkan Kalkulator TK Panen dari \"Aplikasi tidur\" dan \"Aplikasi tidur lanjutan\"."
            brand.contains("huawei") || brand.contains("honor") ->
                "Huawei/Honor: Pengaturan → Baterai → Peluncuran aplikasi → Kalkulator TK Panen → atur manual, aktifkan semua (Mulai otomatis, Aktivitas latar belakang)."
            else -> "Cari pengaturan baterai/aplikasi HP Anda lalu set Kalkulator TK Panen ke \"Tanpa batasan\" / \"Izinkan aktivitas latar belakang\"."
        }
        MaterialAlertDialogBuilder(this).setTitle("Agar tracking tidak terhenti")
            .setMessage(
                "Banyak HP mematikan aplikasi di latar belakang untuk hemat baterai, sehingga jalur terputus.\n\n" +
                "1. Jangan tutup aplikasi dari daftar terbaru (kecuali sudah Stop Tracking).\n" +
                "2. Izinkan aplikasi berjalan tanpa pembatasan baterai.\n" +
                "3. $khusus\n\n" +
                "Bila tetap terputus, saat aplikasi dibuka lagi Anda akan ditawari melanjutkan atau menyimpan jalur yang sudah terekam."
            )
            .setPositiveButton("Buka pengaturan baterai") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                catch (e: Exception) {
                    try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (x: Exception) {}
                }
            }
            .setNeutralButton("Pengaturan aplikasi") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (x: Exception) {}
            }
            .setNegativeButton("Mengerti", null).show()
    }

    /** Dipanggil saat aplikasi dibuka: tawarkan pemulihan tracking yang terputus & kirim log crash. */
    private fun checkRecoveryAndCrash() {
        val rec = Tracker.pendingRecovery(this)
        if (rec != null) {
            val jam = java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale("id", "ID")).format(java.util.Date(rec.startedAt))
            MaterialAlertDialogBuilder(this).setTitle("Tracking terputus")
                .setMessage(
                    "Ada tracking yang terhenti sebelum diselesaikan (aplikasi/service dimatikan sistem).\n\n" +
                    "Mulai: $jam\nTitik: ${rec.points.size}\nJarak: ${Geo.km(rec.distance)}\nBlok: ${rec.blok.ifBlank { "-" }}\n\n" +
                    "Lanjutkan merekam, simpan jalur yang sudah ada, atau buang?"
                )
                .setCancelable(false)
                .setPositiveButton("Lanjutkan") { _, _ -> resumeTracking() }
                .setNeutralButton("Simpan jalur") { _, _ -> saveRecovered(rec) }
                .setNegativeButton("Buang") { _, _ -> Tracker.discard(this) }
                .show()
            return
        }
        if (CrashLog.unseenCrash(this) == null) return
        CrashLog.markSeen(this)
        MaterialAlertDialogBuilder(this).setTitle("Aplikasi sempat berhenti")
            .setMessage("Kalkulator TK Panen sebelumnya berhenti tiba-tiba. Kirim log error ke pengembang agar bisa diperbaiki?")
            .setPositiveButton("Kirim log") { _, _ -> shareLog() }
            .setNegativeButton("Nanti", null).show()
    }

    private fun resumeTracking() {
        if (!hasLocationPermission()) { toast("Izinkan akses lokasi dulu."); requestGps(); return }
        if (!Tracker.resume(this)) { toast("Data tracking tidak dapat dibaca."); return }
        try {
            ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java))
        } catch (e: Exception) {
            Tracker.stop(this)
            CrashLog.error(this, "resumeTracking", e)
            toast("Tracking tidak dapat dilanjutkan: ${e.message}")
            return
        }
        CrashLog.event(this, "Tracking dilanjutkan pengguna setelah terputus")
        lastTrack = null
        cbTrack.visibility = View.GONE
        trackBox.visibility = View.VISIBLE
        updateTrackUi()
        trackHandler.removeCallbacks(trackTick)
        trackHandler.post(trackTick)
    }

    private fun saveRecovered(rec: TrackRecord) {
        if (rec.points.size < 2) { toast("Titik terlalu sedikit, jalur dibuang."); Tracker.discard(this); return }
        val mid = rec.points[rec.points.size / 2]
        val mapId = MapStore.pick(this, mid.lat, mid.lon)?.id ?: ""
        val saved = rec.copy(id = UUID.randomUUID().toString(), mapId = mapId)
        TrackStore.add(this, saved)
        Tracker.discard(this)
        lastTrack = saved
        trackBox.visibility = View.VISIBLE
        cbTrack.isChecked = true
        updateTrackUi()
        renderPreview(saved.points, saved.mapId)
        peta.refresh()
        toast("Jalur tersimpan (${saved.points.size} titik, ${Geo.km(saved.distance)}).")
    }

    private fun shareLog() {
        val f = File(File(cacheDir, "exports").apply { mkdirs() }, "TKPanen_Log_" +
            java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date()) + ".txt")
        if (!CrashLog.bundle(this, f)) { toast("Log tidak dapat dibuat."); return }
        Share.file(this, f, "text/plain", "Kirim log error")
    }

    private fun updateTrackUi() {
        val active = Tracker.active
        btnTrack.text = if (active) "Stop Tracking" else "Start Tracking"
        btnPause.visibility = if (active) View.VISIBLE else View.GONE
        btnPause.text = if (Tracker.paused) "▶ Lanjutkan Tracking" else "⏸ Pause Tracking"
        btnTrack.backgroundTintList = ColorStateList.valueOf(if (active) 0xFF7F0000.toInt() else 0xFFD32F2F.toInt())
        val rec = lastTrack
        cbTrack.visibility = if (!active && rec != null) View.VISIBLE else View.GONE
        if (!active && rec != null) {
            tvTrackInfo.text = "Tracking selesai • ${rec.points.size} titik • ${Geo.km(rec.distance)} • tersimpan di tab Peta"
        }
    }

    private fun renderPreview(pts: List<TPoint>, mapId: String?) {
        if (rendering || pts.isEmpty()) return
        rendering = true
        val app = applicationContext
        Thread {
            val bmp = try {
                val map = if (mapId != null) MapStore.get(app, mapId)
                else pts.last().let { MapStore.pick(app, it.lat, it.lon) }
                TrackRender.render(app, map, pts, 900, 560)
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
        val items = arrayOf("📥 Import peta GeoPDF", "🗑 Hapus peta…", "🔋 Panduan Tracking", "📨 Kirim Log Error")
        MaterialAlertDialogBuilder(this).setTitle("Peta & Tracking")
            .setMessage(info)
            .setItems(items) { _, i ->
                when (i) {
                    0 -> importMap.launch(arrayOf("application/pdf", "*/*"))
                    1 -> confirmDeleteMap()
                    2 -> showBatteryGuide()
                    3 -> shareLog()
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

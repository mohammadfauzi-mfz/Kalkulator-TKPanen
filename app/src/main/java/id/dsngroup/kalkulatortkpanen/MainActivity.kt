package id.dsngroup.kalkulatortkpanen

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

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
            setOnClickListener { entriSaatIni()?.let { Fmt.bagikan(this@MainActivity, Fmt.pesan(it)) } }
        })
        root.addView(aksi)

        calcScroll = ScrollView(this).apply { isFillViewport = true; addView(root) }

        // ---------- Tab Riwayat ----------
        histList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }
        histScroll = ScrollView(this).apply { addView(histList); visibility = View.GONE }

        val frame = FrameLayout(this)
        frame.addView(calcScroll)
        frame.addView(histScroll)
        page.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val first = tab.position == 0
                calcScroll.visibility = if (first) View.VISIBLE else View.GONE
                histScroll.visibility = if (first) View.GONE else View.VISIBLE
                if (!first) renderRiwayat()
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
        return Entri(System.currentTimeMillis(), t, inputSaatIni())
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
        btns.addView(tombol("Share WA") { Fmt.bagikan(this, Fmt.pesan(e)) })
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
        tabs.getTabAt(0)?.select()
    }
}

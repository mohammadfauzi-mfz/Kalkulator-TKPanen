package id.dsngroup.kalkulatortkpanen

import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.ceil

class MainActivity : AppCompatActivity() {

    private val id = Locale("in", "ID")
    private val fields = LinkedHashMap<String, TextInputEditText>()
    private lateinit var tvJanjang: TextView
    private lateinit var tvTonase: TextView
    private lateinit var tvPanenLuasan: TextView
    private lateinit var tvPanenProduksi: TextView
    private lateinit var tvGandengLuasan: TextView
    private lateinit var tvGandengProduksi: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = Input()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        root.addView(TextView(this).apply {
            text = "KALKULATOR TK PANEN"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF1B5E20.toInt())
            setPadding(0, dp(24), 0, dp(8))
        })

        root.addView(section("Data Panen"))
        root.addView(field("akp", "%AKP", d.akp * 100))
        root.addView(field("luas", "Target Luas (Ha)", d.luas))
        root.addView(field("sph", "Rata-Rata SPH", d.sph))
        root.addView(field("bjr", "BJR", d.bjr))
        tvJanjang = resultRow(root, "Janjang", "janjang")
        tvTonase = resultRow(root, "Tonase", "ton")

        root.addView(section("Kemampuan TK Panen"))
        root.addView(field("haHk", "Ha/HK", d.haPerHk))
        root.addView(field("tonHk", "Ton/HK", d.tonPerHk))
        root.addView(section("Kebutuhan TK Panen"))
        tvPanenLuasan = resultRow(root, "By Luasan", "orang")
        tvPanenProduksi = resultRow(root, "By Produksi", "orang")

        root.addView(section("TK Tersedia"))
        root.addView(field("tk", "TK Tersedia (Orang)", d.tkTersedia))

        root.addView(section("Kemampuan TK Gandeng"))
        root.addView(field("gHaHk", "Ha/HK", d.gandengHaPerHk))
        root.addView(field("gTonHk", "Ton/HK", d.gandengTonPerHk))
        root.addView(section("Kebutuhan TK Gandeng"))
        tvGandengLuasan = resultRow(root, "By Luasan", "orang")
        tvGandengProduksi = resultRow(root, "By Produksi", "orang")

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })

        fields.values.forEach { it.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refresh()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }) }
        refresh()
    }

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
            setText(if (value % 1.0 == 0.0) value.toLong().toString() else value.toString())
        }
        til.addView(et)
        fields[key] = et
        return til
    }

    private fun resultRow(parent: LinearLayout, label: String, unit: String): TextView {
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
            tag = unit
        }
        row.addView(value)
        card.addView(row)
        parent.addView(card)
        return value
    }

    private fun num(key: String): Double =
        fields[key]!!.text.toString().trim().replace(',', '.').toDoubleOrNull() ?: 0.0

    private fun fmt(v: Double, digits: Int): String {
        if (v.isNaN() || v.isInfinite()) return "#DIV/0!"
        val nf = NumberFormat.getNumberInstance(id)
        nf.minimumFractionDigits = digits
        nf.maximumFractionDigits = digits
        return nf.format(v)
    }

    private fun orang(v: Double): String =
        if (v.isNaN() || v.isInfinite()) "#DIV/0!"
        else "${fmt(v, 2)} Orang" + if (v > 0) "  (≈ ${ceil(v - 1e-9).toLong()})" else ""

    private fun refresh() {
        val h = Calculator.hitung(Input(
            akp = num("akp") / 100.0, luas = num("luas"), sph = num("sph"), bjr = num("bjr"),
            haPerHk = num("haHk"), tonPerHk = num("tonHk"), tkTersedia = num("tk"),
            gandengHaPerHk = num("gHaHk"), gandengTonPerHk = num("gTonHk"),
        ))
        tvJanjang.text = fmt(h.janjang, 0) + " janjang"
        tvTonase.text = fmt(h.tonase, 2) + " ton"
        tvPanenLuasan.text = orang(h.kebPanenLuasan)
        tvPanenProduksi.text = orang(h.kebPanenProduksi)
        tvGandengLuasan.text = orang(h.kebGandengLuasan)
        tvGandengProduksi.text = orang(h.kebGandengProduksi)
    }
}

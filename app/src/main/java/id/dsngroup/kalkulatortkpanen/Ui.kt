package id.dsngroup.kalkulatortkpanen

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

const val NUM_TYPE = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

fun View.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** Angka dari teks (koma atau titik desimal); null jika kosong/tidak valid. */
fun parseNum(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

/** Kolom isian (outlined). */
fun inputField(ctx: Context, hint: String, type: Int, value: String = ""): Pair<TextInputLayout, TextInputEditText> {
    val til = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle)
    til.hint = hint
    til.layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = til.dp(8) }
    val et = TextInputEditText(til.context)
    et.inputType = type
    et.setText(value)
    til.addView(et)
    return Pair(til, et)
}

/** Angka dari isian; kosong = 0. */
fun EditText.num(): Double = parseNum(text.toString()) ?: 0.0

fun EditText.watch(onChange: () -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) = onChange()
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })
}

/** Baris hasil hitung otomatis (hijau muda): label kiri, nilai kanan. */
fun resultRow(ctx: Context, label: String): Pair<MaterialCardView, TextView> {
    val card = MaterialCardView(ctx)
    card.radius = card.dp(10).toFloat()
    card.cardElevation = 0f
    card.setCardBackgroundColor(0xFFE8F5E9.toInt())
    card.layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = card.dp(8) }
    val row = LinearLayout(ctx)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    row.setPadding(card.dp(14), card.dp(10), card.dp(14), card.dp(10))
    val l = TextView(ctx)
    l.text = label
    l.textSize = 14f
    l.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    val v = TextView(ctx)
    v.textSize = 16f
    v.setTypeface(null, Typeface.BOLD)
    v.gravity = Gravity.END
    row.addView(l)
    row.addView(v)
    card.addView(row)
    return Pair(card, v)
}

package id.dsngroup.kalkulatortkpanen

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class AboutActivity : AppCompatActivity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF2E7D32.toInt())
            setPadding(dp(8), dp(14), dp(16), dp(12))
        }
        header.addView(TextView(this).apply {
            text = "‹"; textSize = 30f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(12), 0, dp(16), dp(4))
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "About Application"; textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
        })
        root.addView(header)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        body.addView(TextView(this).apply {
            text = "Create By Mohammad Fauzi"; textSize = 20f
            setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
        })
        body.addView(TextView(this).apply {
            text = "Contact Me for Suggestion & Feedback"; textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(20))
        })
        body.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_whatsapp)
            contentDescription = "WhatsApp"
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
            setOnClickListener { openWhatsApp() }
        })
        root.addView(body)

        root.addView(TextView(this).apply {
            text = "Versi Aplikasi 1.2"; textSize = 13f
            setTextColor(0xFF666666.toInt()); gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(20))
        })
        setContentView(root)
    }

    private fun openWhatsApp() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/6285252390543")))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "WhatsApp / browser tidak ditemukan", Toast.LENGTH_LONG).show()
        }
    }
}

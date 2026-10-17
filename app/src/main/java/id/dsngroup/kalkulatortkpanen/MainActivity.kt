package id.dsngroup.kalkulatortkpanen

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var etAkp: EditText
    private lateinit var etBlok: EditText
    private lateinit var btnStartTracking: Button

    // Launcher untuk menerima hasil nilai %AKP dari SensusActivity
    private val sensusLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val percentAkp = result.data?.getDoubleExtra("RESULT_PERCENT_AKP", 0.0) ?: 0.0
            val totalPokok = result.data?.getIntExtra("TOTAL_POKOK", 0) ?: 0
            val totalJanjang = result.data?.getIntExtra("TOTAL_JANJANG", 0) ?: 0

            // Otomatis isi kolom %AKP pada kalkulator
            etAkp.setText(String.format("%.2f", percentAkp))

            Toast.makeText(
                this,
                "Sensus selesai! Total $totalPokok pokok, $totalJanjang janjang matang. %AKP: ${String.format("%.2f", percentAkp)}%",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etAkp = findViewById(R.id.etAkp)
        etBlok = findViewById(R.id.etBlok)
        btnStartTracking = findViewById(R.id.btnStartTracking)

        btnStartTracking.setOnClickListener {
            val namaBlok = etBlok.text.toString().ifEmpty { "H34" }
            val intent = Intent(this, SensusActivity::class.java).apply {
                putExtra("BLOK_NAME", namaBlok)
            }
            sensusLauncher.launch(intent)
        }
    }
}

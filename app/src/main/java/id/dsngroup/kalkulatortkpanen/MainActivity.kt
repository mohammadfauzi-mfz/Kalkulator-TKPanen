package id.dsngroup.kalkulatortkpanen

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import id.dsngroup.kalkulatortkpanen.R

class MainActivity : AppCompatActivity() {

    private val sensusLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val percentAkp = result.data?.getDoubleExtra("RESULT_PERCENT_AKP", 0.0) ?: 0.0
            val totalPokok = result.data?.getIntExtra("TOTAL_POKOK", 0) ?: 0
            val totalJanjang = result.data?.getIntExtra("TOTAL_JANJANG", 0) ?: 0

            val etAkp = findViewById<EditText>(R.id.etAkp)
            etAkp?.setText(String.format("%.2f", percentAkp))

            Toast.makeText(
                this,
                "Sensus selesai! Total $totalPokok pokok, $totalJanjang janjang. %AKP: ${String.format("%.2f", percentAkp)}%",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnStartTracking = findViewById<Button>(R.id.btnStartTracking)
        btnStartTracking?.setOnClickListener {
            val intent = Intent(this, SensusActivity::class.java)
            sensusLauncher.launch(intent)
        }
    }
}

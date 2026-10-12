package id.dsngroup.kalkulatortkpanen

/** Input kalkulator. akp dalam pecahan (24% = 0.24), sama seperti sel C4 di Excel. */
data class Input(
    val akp: Double = 0.24,        // C4  %AKP
    val luas: Double = 120.0,      // C5  Target Luas (Ha)
    val sph: Double = 125.0,       // C6  Rata-rata SPH
    val bjr: Double = 14.0,        // C7  BJR
    val haPerHk: Double = 2.8,     // C13 Kemampuan TK Panen Ha/HK
    val tonPerHk: Double = 1.1,    // C14 Kemampuan TK Panen Ton/HK
    val tkTersedia: Double = 35.0, // C20 TK Tersedia
    val gandengHaPerHk: Double = 2.0,  // C24 Kemampuan TK Gandeng Ha/HK
    val gandengTonPerHk: Double = 0.5, // C25 Kemampuan TK Gandeng Ton/HK
)

data class Hasil(
    val janjang: Double,        // C9
    val tonase: Double,         // C10
    val kebPanenLuasan: Double, // C17
    val kebPanenProduksi: Double, // C18
    val kebGandengLuasan: Double, // C28
    val kebGandengProduksi: Double, // C29
)

/** Rumus identik dengan Excel. Pembagian nol menghasilkan Infinity/NaN (di Excel: #DIV/0!). */
object Calculator {
    fun hitung(i: Input): Hasil {
        val janjang = i.akp * i.luas * i.sph                         // =C4*C5*C6
        val tonase = janjang * i.bjr / 1000                          // =C9*C7/1000
        val panenLuasan = i.luas / i.haPerHk                         // =C5/C13
        val panenProduksi = tonase / i.tonPerHk                      // =C10/C14
        val gandengLuasan =
            ((i.luas / i.haPerHk) - i.tkTersedia) * i.haPerHk / i.gandengHaPerHk       // =((C5/C13)-C20)*C13/C24
        val gandengProduksi =
            ((tonase / i.tonPerHk) - i.tkTersedia) * i.tonPerHk / i.gandengTonPerHk    // =((C10/C14)-C20)*C14/C25
        return Hasil(janjang, tonase, panenLuasan, panenProduksi, gandengLuasan, gandengProduksi)
    }
}

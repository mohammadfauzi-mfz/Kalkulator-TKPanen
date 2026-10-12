# Kalkulator TK Panen (Android)
Buka folder ini di Android Studio (Koala atau lebih baru) -> Sync -> Build > Build APK(s).
Atau: `gradle assembleDebug` -> app/build/outputs/apk/debug/app-debug.apk
Uji rumus: `gradle test`

## Tanpa Android Studio: GitHub Actions (gratis)
1. Buat repo GitHub baru, upload seluruh isi folder ini (termasuk folder .github).
2. Tab Actions > "Build APK" > Run workflow.
3. Setelah hijau, unduh APK di bagian Artifacts.

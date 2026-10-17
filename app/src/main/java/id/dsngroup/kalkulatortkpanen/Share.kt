package id.dsngroup.kalkulatortkpanen

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object Share {
    /** Bagikan teks + gambar (bila ada) ke WhatsApp (WA biasa -> WA Business -> pilihan aplikasi). */
    fun textWithPhoto(ctx: Context, teks: String, photo: File?) {
        if (photo == null || !photo.exists()) { text(ctx, teks); return }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", photo)
        fun build(pkg: String?) = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, teks)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (pkg != null) setPackage(pkg)
        }
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try { ctx.startActivity(build(pkg)); return } catch (e: ActivityNotFoundException) {}
        }
        ctx.startActivity(Intent.createChooser(build(null), "Bagikan"))
    }

    /** Bagikan teks ke WhatsApp (WA biasa -> WA Business -> pilihan aplikasi). */
    fun text(ctx: Context, teks: String) {
        fun build(pkg: String?) = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, teks)
            if (pkg != null) setPackage(pkg)
        }
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try { ctx.startActivity(build(pkg)); return } catch (e: ActivityNotFoundException) {}
        }
        ctx.startActivity(Intent.createChooser(build(null), "Bagikan"))
    }

    /** Bagikan sebuah file (data tracking, peta, gambar) lewat pilihan aplikasi. */
    fun file(ctx: Context, f: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, title))
    }
}

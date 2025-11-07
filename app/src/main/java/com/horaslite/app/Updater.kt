package com.horaslite.app

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import com.horaslite.app.BuildConfig
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.URL

object Updater {

    private const val UPDATE_INFO_URL =
        "https://cristianghcode.github.io/HorasLite/horaslite-update.json"

    init {
        Log.d("Updater", "Intentando conectar a: $UPDATE_INFO_URL")
    }

    fun checkForUpdate(context: Context) {
        Thread {
            try {
                val text = URL(UPDATE_INFO_URL).readText()
                val json = JSONObject(text)
                val remoteCode = json.getInt("versionCode")
                val remoteName = json.getString("versionName")
                val apkUrl = json.getString("apkUrl")

                val localCode = BuildConfig.VERSION_CODE

                if (remoteCode > localCode) {
                    (context as AppCompatActivity).runOnUiThread {
                        AlertDialog.Builder(context)
                            .setTitle("Nueva versión disponible")
                            .setMessage("Hay una actualización ($remoteName). ¿Quieres instalarla?")
                            .setPositiveButton("Sí") { _, _ ->
                                downloadAndInstall(context, apkUrl, remoteName)
                            }
                            .setNegativeButton("No", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                Log.e("Updater", "No se pudo verificar actualización: ${e.message}")
            }
        }.start()
    }

    private fun downloadAndInstall(context: Context, apkUrl: String, versionName: String) {
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Horas Lite $versionName")
            .setDescription("Descargando actualización...")
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "HorasLite-$versionName.apk")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setMimeType("application/vnd.android.package-archive")

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)

        AlertDialog.Builder(context)
            .setTitle("Descarga iniciada")
            .setMessage("Cuando termine, toca el archivo para instalar la nueva versión.")
            .setPositiveButton("Entendido", null)
            .show()
    }
}

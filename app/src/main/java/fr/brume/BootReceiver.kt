package fr.brume

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Relance l'exploration après un redémarrage du téléphone ou une mise à jour de Brume. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val a = intent.action
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = ctx.getSharedPreferences("brume", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("tracking", false)) return
        // Démarrer le GPS sans que l'app soit ouverte exige « Toujours autoriser »
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try { TrackingService.start(ctx) } catch (e: Exception) { e.printStackTrace() }
    }
}

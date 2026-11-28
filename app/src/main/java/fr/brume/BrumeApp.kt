package fr.brume

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal des plantages : toute erreur non gérée est écrite dans un fichier avant que l'app ne
 * s'arrête ; Android 11+ fournit en plus la raison des arrêts forcés (gel, manque de mémoire…).
 * Le rapport est consultable et copiable dans Réglages → Diagnostic.
 */
class BrumeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val head = "Plantage du ${stamp(System.currentTimeMillis())} · fil « ${t.name} » · Brume ${version(this)} · Android ${Build.VERSION.RELEASE} (${Build.MODEL})\n"
                File(filesDir, CRASH_FILE).writeText(head + sw.toString().take(20_000))
            } catch (x: Throwable) { }
            previous?.uncaughtException(t, e)
        }
    }

    companion object {
        const val CRASH_FILE = "dernier_plantage.txt"

        fun stamp(ms: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE).format(Date(ms))

        fun version(ctx: Context): String = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }

        /** Rapport lisible : dernier plantage enregistré + derniers arrêts anormaux signalés par Android. */
        fun report(ctx: Context): String {
            val sb = StringBuilder()
            val f = File(ctx.filesDir, CRASH_FILE)
            if (f.exists()) sb.append(f.readText()).append("\n\n")
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    val am = ctx.getSystemService(ActivityManager::class.java)
                    val exits = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 8)
                    val bad = exits.filter { it.reason in setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                        ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE) }
                    if (bad.isNotEmpty()) sb.append("Arrêts anormaux signalés par Android :\n")
                    for (e in bad) {
                        val why = when (e.reason) {
                            ApplicationExitInfo.REASON_CRASH -> "plantage"
                            ApplicationExitInfo.REASON_CRASH_NATIVE -> "plantage natif"
                            ApplicationExitInfo.REASON_ANR -> "app figée (ANR)"
                            ApplicationExitInfo.REASON_LOW_MEMORY -> "mémoire insuffisante"
                            else -> "ressources excessives"
                        }
                        sb.append("• ${stamp(e.timestamp)} : $why${if (e.description != null) " — " + e.description else ""}\n")
                        if (e.reason == ApplicationExitInfo.REASON_ANR) {
                            try {
                                e.traceInputStream?.bufferedReader()?.use { r ->
                                    // Seulement le fil principal : c'est lui qui était bloqué
                                    val all = r.readText()
                                    val i = all.indexOf("\"main\"")
                                    if (i >= 0) sb.append(all.substring(i, minOf(all.length, i + 3000))).append("\n")
                                }
                            } catch (x: Exception) { }
                        }
                    }
                } catch (x: Exception) { }
            }
            return sb.toString().trim()
        }

        /** Vrai s'il y a un plantage plus récent que le dernier rapport consulté. */
        fun hasNewCrash(ctx: Context): Boolean {
            val seen = ctx.getSharedPreferences("brume", Context.MODE_PRIVATE).getLong("crashSeen", 0L)
            val f = File(ctx.filesDir, CRASH_FILE)
            var last = if (f.exists()) f.lastModified() else 0L
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    val am = ctx.getSystemService(ActivityManager::class.java)
                    for (e in am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5))
                        if (e.reason == ApplicationExitInfo.REASON_CRASH || e.reason == ApplicationExitInfo.REASON_ANR) last = maxOf(last, e.timestamp)
                } catch (x: Exception) { }
            }
            return last > seen
        }

        fun markSeen(ctx: Context) {
            ctx.getSharedPreferences("brume", Context.MODE_PRIVATE).edit().putLong("crashSeen", System.currentTimeMillis()).apply()
        }
    }
}

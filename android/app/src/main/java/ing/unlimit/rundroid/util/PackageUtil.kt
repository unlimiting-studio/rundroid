package ing.unlimit.rundroid.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import ing.unlimit.rundroid.model.AppInfo
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request

object PackageUtil {
    fun getInstalledApps(context: Context): List<AppInfo> {
        val packageManager = context.packageManager
        val applications = getInstalledApplications(packageManager)

        return applications.map { appInfo ->
            val packageInfo = getPackageInfo(packageManager, appInfo.packageName)
            AppInfo(
                packageName = appInfo.packageName,
                appName = appInfo.loadLabel(packageManager).toString(),
                versionName = packageInfo?.versionName,
                versionCode = packageInfo?.let { getLongVersionCode(it) } ?: 0L,
                isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        }.sortedBy { it.packageName }
    }

    fun launchApp(context: Context, packageName: String): Boolean {
        val packageManager = context.packageManager
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun stopApp(context: Context, packageName: String): Boolean {
        return try {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            activityManager.killBackgroundProcesses(packageName)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun installApkFromUrl(context: Context, url: String): Boolean {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            Log.e("PackageUtil", "Blocked install from unsupported URL scheme: $scheme")
            return false
        }

        val client = OkHttpClient()
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false

                val body = response.body ?: return false
                val tempFile = File.createTempFile("remote-install-", ".apk", context.cacheDir)
                try {
                    tempFile.outputStream().use { outputStream ->
                        body.byteStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    val packageInstaller = context.packageManager.packageInstaller
                    val params = PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL
                    )
                    val sessionId = packageInstaller.createSession(params)
                    packageInstaller.openSession(sessionId).use { session ->
                        session.openWrite("package", 0, tempFile.length()).use { sessionStream ->
                            tempFile.inputStream().use { fileStream ->
                                fileStream.copyTo(sessionStream)
                            }
                            session.fsync(sessionStream)
                        }

                        val pendingIntent = PendingIntent.getBroadcast(
                            context,
                            sessionId,
                            Intent("ing.unlimit.rundroid.INSTALL_COMPLETE")
                                .setPackage(context.packageName),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        session.commit(pendingIntent.intentSender)
                    }
                    true
                } finally {
                    tempFile.delete()
                }
            }
        } catch (e: Exception) {
            Log.e("PackageUtil", "Failed to install APK from URL", e)
            false
        }
    }

    fun uninstallApp(context: Context, packageName: String): Boolean {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(intent)
        }
        return true
    }

    private fun getInstalledApplications(packageManager: PackageManager): List<ApplicationInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        }
    }

    private fun getPackageInfo(packageManager: PackageManager, packageName: String): PackageInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun getLongVersionCode(packageInfo: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    }
}

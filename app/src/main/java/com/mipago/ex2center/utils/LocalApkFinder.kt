package com.mipago.ex2center.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Utility to discover installable packages (.apk and split bundles .xapk/.apks) on local storage
 * (Internal storage, Downloads, and mounted USB drives).
 */
object LocalApkFinder {

    private const val TAG = "LocalApkFinder"

    private fun isPackageFile(file: File): Boolean {
        val n = file.name.lowercase()
        return file.isFile && (n.endsWith(".apk") || n.endsWith(".xapk") || n.endsWith(".apks"))
    }

    /**
     * Gathers all candidate root directories where APK files might be placed by the driver.
     */
    fun getCandidateDirectories(): List<File> {
        val dirs = LinkedHashSet<File>()

        try {
            // 1. Standard Downloads folder
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloads != null && downloads.exists() && downloads.isDirectory) {
                dirs.add(downloads)
            }

            // 2. External Storage Root (/sdcard or /storage/emulated/0)
            val extRoot = Environment.getExternalStorageDirectory()
            if (extRoot != null && extRoot.exists() && extRoot.isDirectory) {
                dirs.add(extRoot)
                val extDownloads = File(extRoot, "Download")
                if (extDownloads.exists() && extDownloads.isDirectory) dirs.add(extDownloads)
                val extApk = File(extRoot, "apk")
                if (extApk.exists() && extApk.isDirectory) dirs.add(extApk)
            }

            // 3. USB Drives & Removable Media under /storage
            val storageRoot = File("/storage")
            if (storageRoot.exists() && storageRoot.isDirectory) {
                storageRoot.listFiles()?.forEach { sub ->
                    if (sub.isDirectory && sub.canRead() && sub.name != "self" && sub.name != "emulated") {
                        dirs.add(sub)
                        // Also check common subfolders on USB
                        val subDownload = File(sub, "Download")
                        if (subDownload.exists() && subDownload.isDirectory) dirs.add(subDownload)
                        val subApk = File(sub, "apk")
                        if (subApk.exists() && subApk.isDirectory) dirs.add(subApk)
                    }
                }
            }

            // 4. USB Mounts under /mnt/media_rw (Common in Android Car head units)
            val mediaRw = File("/mnt/media_rw")
            if (mediaRw.exists() && mediaRw.isDirectory) {
                mediaRw.listFiles()?.forEach { sub ->
                    if (sub.isDirectory && sub.canRead()) {
                        dirs.add(sub)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error resolving candidate directories: ${t.message}")
        }

        return dirs.toList()
    }

    /**
     * Scans for all valid APK files in candidate storage directories.
     */
    fun scanAllApks(context: Context): List<File> {
        val foundApks = LinkedHashSet<File>()

        for (dir in getCandidateDirectories()) {
            try {
                val files = dir.listFiles { file -> isPackageFile(file) } ?: continue

                for (f in files) {
                    if (PackageInstallerHelper.inspect(context, f) != null) foundApks.add(f)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Error listing files in ${dir.absolutePath}: ${t.message}")
            }
        }

        return foundApks.toList()
    }

    /**
     * Looks for an APK matching the specific package name or keywords.
     */
    fun findApkForPackage(context: Context, packageName: String, keywords: List<String> = emptyList()): File? {
        val pm = context.packageManager
        val searchKeywords = (keywords + packageName).map { it.lowercase() }

        for (dir in getCandidateDirectories()) {
            try {
                val files = dir.listFiles { file -> isPackageFile(file) } ?: continue

                // Pass 1: package name read from the APK manifest / XAPK manifest.json
                for (f in files) {
                    if (PackageInstallerHelper.inspect(context, f)?.packageName == packageName) {
                        Log.i(TAG, "Found exact package match for $packageName at ${f.absolutePath}")
                        return f
                    }
                }

                // Pass 2: Keyword match in filename (plain APKs only)
                for (f in files.filter { it.name.endsWith(".apk", ignoreCase = true) }) {
                    val nameLower = f.name.lowercase()
                    if (searchKeywords.any { term -> nameLower.contains(term) }) {
                        try {
                            val info = pm.getPackageArchiveInfo(f.absolutePath, 0)
                            if (info != null) {
                                Log.i(TAG, "Found keyword match for $packageName: ${f.name} at ${f.absolutePath}")
                                return f
                            }
                        } catch (ignored: Throwable) {}
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Error searching in ${dir.absolutePath}: ${t.message}")
            }
        }

        return null
    }
}

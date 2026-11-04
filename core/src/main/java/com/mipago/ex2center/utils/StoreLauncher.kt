package com.mipago.ex2center.utils

import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Opens EX2 Store (com.mipago.ex2store), which installs and updates MyEX2Center and the other
 * catalog apps. While EX2 Store is not installed yet, MyEX2Center falls back to its built-in
 * catalog screen (kept until the migration to the standalone store is complete).
 */
object StoreLauncher {

    const val STORE_PACKAGE = "com.mipago.ex2store"

    fun isStoreInstalled(context: Context): Boolean =
        PackageInstallerHelper.isPackageInstalled(context, STORE_PACKAGE)

    fun open(context: Context) {
        if (context.packageName != STORE_PACKAGE) {
            val launch = context.packageManager.getLaunchIntentForPackage(STORE_PACKAGE)
            if (launch != null) {
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            Toast.makeText(context, "Chưa có EX2 Store — đang mở Kho tích hợp", Toast.LENGTH_SHORT).show()
        }
        context.startActivity(
            Intent(context, com.mipago.ex2center.ui.AppCatalogActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

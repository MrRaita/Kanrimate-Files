/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.packageManager
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.startActivitySafe

object Terminal {
    private const val TERMUX_PACKAGE_NAME = "com.termux"
    private const val TERMUX_RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
    private const val TERMUX_BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"

    fun open(path: String, context: Context) {
        if (openInTermux(path, context)) {
            return
        }
        val componentName =
            packageManager.queryIntentActivities(Intent(Intent.ACTION_SEND).setType("*/*"), 0)
                .firstOrNull { it.activityInfo.name.endsWith(".TermHere") }?.activityInfo
                ?.let { ComponentName(it.packageName, it.name) }
                ?: ComponentName("jackpal.androidterm", "jackpal.androidterm.TermHere")
        val intent = Intent()
            .setComponent(componentName)
            .setAction(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_STREAM, Uri.parse(path))
        context.startActivitySafe(intent)
    }

    /**
     * Opens a session in Termux with [path] as the working directory.
     *
     * Termux only accepts this from other apps if the user has granted our app the "Run commands in
     * Termux environment" permission and set `allow-external-apps = true` in
     * `~/.termux/termux.properties`, so we explain that when it is not set up.
     *
     * @return whether Termux is installed (and so the request was handled here)
     */
    private fun openInTermux(path: String, context: Context): Boolean {
        val isInstalled = try {
            packageManager.getPackageInfo(TERMUX_PACKAGE_NAME, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        if (!isInstalled) {
            return false
        }
        if (ContextCompat.checkSelfPermission(context, TERMUX_RUN_COMMAND_PERMISSION) !=
            PackageManager.PERMISSION_GRANTED) {
            context.showToast(R.string.terminal_termux_setup_required)
            return true
        }
        val intent = Intent(TERMUX_RUN_COMMAND_ACTION)
            .setClassName(TERMUX_PACKAGE_NAME, TERMUX_RUN_COMMAND_SERVICE)
            .putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH_PATH)
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-l"))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", path)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            // 0: Switch to the new session and open the Termux activity.
            .putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            e.printStackTrace()
            context.showToast(R.string.terminal_termux_setup_required)
        }
        return true
    }
}

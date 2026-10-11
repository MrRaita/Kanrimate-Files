/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.saveas

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asMimeTypeOrNull
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.util.saveAsPath
import me.zhanghai.android.files.util.saveAsPaths
import me.zhanghai.android.files.util.showToast

class SaveAsActivity : AppActivity() {
    private val createFileLauncher =
        registerForActivityResult(FileListActivity.CreateFileContract(), ::onCreateFileResult)

    private val openDirectoryLauncher =
        registerForActivityResult(
            FileListActivity.OpenDirectoryContract(), ::onOpenDirectoryResult
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Without this, the launcher would be started again after a configuration change.
        if (savedInstanceState != null) {
            return
        }
        val intent = intent
        val initialPath =
            Paths.get(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
            )
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            if (intent.saveAsPaths.isEmpty()) {
                showToast(R.string.save_as_error)
                finish()
                return
            }
            openDirectoryLauncher.launch(initialPath)
            return
        }
        val mimeType = intent.type?.asMimeTypeOrNull() ?: MimeType.ANY
        val path = intent.saveAsPath
        if (path == null) {
            showToast(R.string.save_as_error)
            finish()
            return
        }
        val title = path.fileName.toString()
        createFileLauncher.launch(Triple(mimeType, title, initialPath))
    }

    private fun onCreateFileResult(result: Path?) {
        if (result == null) {
            finish()
            return
        }
        FileJobService.save(intent.saveAsPath!!, result, this)
        finish()
    }

    private fun onOpenDirectoryResult(result: Path?) {
        if (result == null) {
            finish()
            return
        }
        for (source in intent.saveAsPaths) {
            val name = source.fileName?.toString() ?: continue
            FileJobService.save(source, result.resolve(name), this)
        }
        finish()
    }
}

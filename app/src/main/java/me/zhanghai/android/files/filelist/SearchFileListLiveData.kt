/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import me.zhanghai.android.files.util.AppExecutors
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.fileindex.FileIndex
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.provider.common.search
import me.zhanghai.android.files.util.CloseableLiveData
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.valueCompat
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

class SearchFileListLiveData(
    private val path: Path,
    private val query: String,
    private val useIndex: Boolean = true
) : CloseableLiveData<Stateful<List<FileItem>>>() {
    private var future: Future<Unit>? = null

    /** Whether the current results come from the file name index rather than a folder walk. */
    @Volatile
    var fromIndex = false
        private set

    init {
        loadValue()
    }

    fun loadValue() {
        future?.cancel(true)
        value = Loading(emptyList())
        fromIndex = false
        future = AppExecutors.io.submit<Unit> {
            val fileList = mutableListOf<FileItem>()
            try {
                val indexResult =
                    if (useIndex && path.isLinuxPath) {
                        FileIndex.search(path.toFile().path, query)
                    } else {
                        null
                    }
                val indexedPaths = indexResult?.paths
                if (indexedPaths != null) {
                    fromIndex = true
                    for ((index, indexedPath) in indexedPaths.withIndex()) {
                        if (Thread.currentThread().isInterrupted) {
                            return@submit
                        }
                        val fileItem = try {
                            Paths.get(indexedPath).loadFileItem()
                        } catch (e: IOException) {
                            // The file is gone since the index was built; skip it.
                            continue
                        }
                        fileList.add(fileItem)
                        if ((index + 1) % INDEXED_CHUNK_SIZE == 0) {
                            postValue(Loading(fileList.toList()))
                        }
                    }
                    postValue(Success(fileList))
                    return@submit
                }
                path.search(query, INTERVAL_MILLIS) { paths: List<Path> ->
                    for (path in paths) {
                        val fileItem = try {
                            path.loadFileItem()
                        } catch (e: IOException) {
                            e.printStackTrace()
                            // TODO: Support file without information.
                            continue
                        }
                        fileList.add(fileItem)
                    }
                    postValue(Loading(fileList.toList()))
                }
                postValue(Success(fileList))
            } catch (e: Exception) {
                // TODO: Retrieval of previous value is racy.
                postValue(Failure(valueCompat.value, e))
            }
        }
    }

    override fun close() {
        future?.cancel(true)
    }

    companion object {
        private const val INTERVAL_MILLIS = 500L
        private const val INDEXED_CHUNK_SIZE = 200
    }
}

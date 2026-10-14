/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.os.Environment
import android.text.TextUtils
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.compat.EnvironmentCompat2
import me.zhanghai.android.files.filelist.FileSortOptions
import me.zhanghai.android.files.filelist.FileViewType
import me.zhanghai.android.files.filelist.OpenApkDefaultAction
import me.zhanghai.android.files.navigation.BookmarkDirectory
import me.zhanghai.android.files.navigation.StandardDirectorySettings
import me.zhanghai.android.files.provider.root.RootStrategy
import me.zhanghai.android.files.storage.FileSystemRoot
import me.zhanghai.android.files.storage.PrimaryStorageVolume
import me.zhanghai.android.files.storage.Storage
import me.zhanghai.android.files.theme.custom.ThemeColor
import me.zhanghai.android.files.theme.night.NightMode
import java.io.File

object Settings {
    val STORAGES: SettingLiveData<List<Storage>> =
        ParcelValueSettingLiveData(
            R.string.pref_key_storages,
            listOf(FileSystemRoot(null, true), PrimaryStorageVolume(null, true))
        )

    val FILE_LIST_DEFAULT_DIRECTORY: SettingLiveData<Path> =
        ParcelValueSettingLiveData(
            R.string.pref_key_file_list_default_directory,
            @Suppress("DEPRECATION")
            Paths.get(Environment.getExternalStorageDirectory().absolutePath)
        )

    val FILE_LIST_PERSISTENT_DRAWER_OPEN: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_file_list_persistent_drawer_open,
            R.bool.pref_default_value_file_list_persistent_drawer_open
        )

    val FILE_INDEX_ENABLED: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_file_index_enabled, R.bool.pref_default_value_file_index_enabled
        )

    val FILE_INDEX_PARTIAL_SEARCH: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_file_index_partial_search,
            R.bool.pref_default_value_file_index_partial_search
        )

    val FILE_INDEX_APP_DATA: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_file_index_app_data, R.bool.pref_default_value_file_index_app_data
        )

    /** Bit mask of FileIndex.ROOT_DIRS, ROOT_USER_DATA and ROOT_ALL; 0 means root is off. */
    val FILE_INDEX_ROOT_MASK: SettingLiveData<Int> =
        IntegerSettingLiveData(
            R.string.pref_key_file_index_root_mask,
            R.integer.pref_default_value_file_index_root_mask
        )

    val FILE_INDEX_AUTO_OPEN: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_file_index_auto_open, R.bool.pref_default_value_file_index_auto_open
        )

    /** Hours between automatic refreshes as a string ("0" is off). */
    val FILE_INDEX_AUTO_INTERVAL: SettingLiveData<String> =
        StringSettingLiveData(
            R.string.pref_key_file_index_auto_interval,
            R.string.pref_default_value_file_index_auto_interval
        )

    val FILE_INDEX_AUTO_CHARGING: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_file_index_auto_charging,
            R.bool.pref_default_value_file_index_auto_charging
        )

    val FILE_INDEX_PROMPT_SHOWN: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_file_index_prompt_shown,
            R.bool.pref_default_value_file_index_prompt_shown
        )

    val SEARCH_RETURN_PREVIOUS: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_search_return_previous,
            R.bool.pref_default_value_search_return_previous
        )

    val SEARCH_RETURN_NEXT: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_search_return_next, R.bool.pref_default_value_search_return_next
        )

    val SEARCH_RETURN_MAX_CHANGES: SettingLiveData<Int> =
        IntegerSettingLiveData(
            R.string.pref_key_search_return_max_changes,
            R.integer.pref_default_value_search_return_max_changes
        )

    val FILE_SIZE_BINARY: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_file_size_binary, R.bool.pref_default_value_file_size_binary
        )

    val FILE_LIST_GRID_COLUMNS: SettingLiveData<String> =
        StringSettingLiveData(
            R.string.pref_key_file_list_grid_columns,
            R.string.pref_default_value_file_list_grid_columns
        )

    val ARCHIVE_INVALID_CHAR_MODE: SettingLiveData<String> =
        StringSettingLiveData(
            R.string.pref_key_archive_invalid_char_mode,
            R.string.pref_default_value_archive_invalid_char_mode
        )

    val FILE_LIST_DENSITY: SettingLiveData<String> =
        StringSettingLiveData(
            R.string.pref_key_file_list_density, R.string.pref_default_value_file_list_density
        )

    val FILE_LIST_SHOW_HIDDEN_FILES: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_file_list_show_hidden_files,
            R.bool.pref_default_value_file_list_show_hidden_files
        )

    val FILE_LIST_VIEW_TYPE: SettingLiveData<FileViewType> =
        EnumSettingLiveData(
            R.string.pref_key_file_list_view_type, R.string.pref_default_value_file_list_view_type,
            FileViewType::class.java
        )

    val FILE_LIST_SORT_OPTIONS: SettingLiveData<FileSortOptions> =
        ParcelValueSettingLiveData(
            R.string.pref_key_file_list_sort_options,
            FileSortOptions(FileSortOptions.By.NAME, FileSortOptions.Order.ASCENDING, true)
        )

    val CREATE_ARCHIVE_TYPE: SettingLiveData<Int> =
        ResourceIdSettingLiveData(R.string.pref_key_create_archive_type, R.id.zipRadio)

    val FTP_SERVER_ANONYMOUS_LOGIN: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_ftp_server_anonymous_login,
            R.bool.pref_default_value_ftp_server_anonymous_login
        )

    val FTP_SERVER_USERNAME: SettingLiveData<String> =
        StringSettingLiveData(
            R.string.pref_key_ftp_server_username, R.string.pref_default_value_ftp_server_username
        )

    val FTP_SERVER_PASSWORD: SettingLiveData<String> =
        StringSettingLiveData(
            R.string.pref_key_ftp_server_password, R.string.pref_default_value_empty
        )

    val FTP_SERVER_PORT: SettingLiveData<Int> =
        IntegerSettingLiveData(
            R.string.pref_key_ftp_server_port, R.integer.pref_default_value_ftp_server_port
        )

    val FTP_SERVER_HOME_DIRECTORY: SettingLiveData<Path> =
        ParcelValueSettingLiveData(
            R.string.pref_key_ftp_server_home_directory,
            @Suppress("DEPRECATION")
            Paths.get(Environment.getExternalStorageDirectory().absolutePath)
        )

    val FTP_SERVER_WRITABLE: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_ftp_server_writable, R.bool.pref_default_value_ftp_server_writable
        )

    val THEME_COLOR: SettingLiveData<ThemeColor> =
        EnumSettingLiveData(
            R.string.pref_key_theme_color, R.string.pref_default_value_theme_color,
            ThemeColor::class.java
        )

    val MATERIAL_DESIGN_3: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_material_design_3, R.bool.pref_default_value_material_design_3
        )

    val NIGHT_MODE: SettingLiveData<NightMode> =
        EnumSettingLiveData(
            R.string.pref_key_night_mode, R.string.pref_default_value_night_mode,
            NightMode::class.java
        )

    val BLACK_NIGHT_MODE: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_black_night_mode, R.bool.pref_default_value_black_night_mode
        )

    val FILE_LIST_ANIMATION: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_file_list_animation, R.bool.pref_default_value_file_list_animation
        )

    val FILE_NAME_ELLIPSIZE: SettingLiveData<TextUtils.TruncateAt> =
        EnumSettingLiveData(
            R.string.pref_key_file_name_ellipsize, R.string.pref_default_value_file_name_ellipsize,
            TextUtils.TruncateAt::class.java
        )

    val STANDARD_DIRECTORY_SETTINGS: SettingLiveData<List<StandardDirectorySettings>> =
        ParcelValueSettingLiveData(R.string.pref_key_standard_directory_settings, emptyList())

    val BOOKMARK_DIRECTORIES: SettingLiveData<List<BookmarkDirectory>> =
        ParcelValueSettingLiveData(
            R.string.pref_key_bookmark_directories, listOf(
                BookmarkDirectory(
                    application.getString(R.string.settings_bookmark_directory_screenshots),
                    Paths.get(
                        File(
                            @Suppress("DEPRECATION")
                            Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_PICTURES
                            ), EnvironmentCompat2.DIRECTORY_SCREENSHOTS
                        ).absolutePath
                    )
                )
            )
        )

    val ROOT_STRATEGY: SettingLiveData<RootStrategy> =
        EnumSettingLiveData(
            R.string.pref_key_root_strategy, R.string.pref_default_value_root_strategy,
            RootStrategy::class.java
        )

    val ARCHIVE_FILE_NAME_ENCODING: SettingLiveData<String> =
        StringSettingLiveData(
            R.string.pref_key_archive_file_name_encoding,
            R.string.pref_default_value_archive_file_name_encoding
        )

    val OPEN_APK_DEFAULT_ACTION: SettingLiveData<OpenApkDefaultAction> =
        EnumSettingLiveData(
            R.string.pref_key_open_apk_default_action,
            R.string.pref_default_value_open_apk_default_action,
            OpenApkDefaultAction::class.java
        )

    val SHOW_PDF_THUMBNAIL_PRE_28: SettingLiveData<Boolean> = BooleanSettingLiveData(
        R.string.pref_key_show_pdf_thumbnail_pre_28,
        R.bool.pref_default_value_show_pdf_thumbnail_pre_28
    )

    val READ_REMOTE_FILES_FOR_THUMBNAIL: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_read_remote_files_for_thumbnail,
            R.bool.pref_default_value_read_remote_files_for_thumbnail
        )
}

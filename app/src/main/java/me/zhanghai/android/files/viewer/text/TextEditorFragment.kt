/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.text

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.SubMenu
import android.view.View
import android.view.ViewGroup
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.view.children
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import java8.nio.file.Path
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.TextEditorFragmentBinding
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.provider.common.readAllBytes

import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.settings.CodeEditorSettingsActivity
import me.zhanghai.android.files.settings.TextEditorSettingsActivity
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.ActionState
import me.zhanghai.android.files.util.DataState
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.addOnBackPressedCallback
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.fadeInUnsafe
import me.zhanghai.android.files.util.fadeOutUnsafe
import me.zhanghai.android.files.util.isReady
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.viewModels
import java.nio.charset.Charset
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.subscribeAlways
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.EditorSearcher.SearchOptions
import java.util.regex.PatternSyntaxException
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import org.eclipse.tm4e.core.registry.IThemeSource
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel

class TextEditorFragment : Fragment(), ConfirmReloadDialogFragment.Listener,
    ConfirmCloseDialogFragment.Listener {
    private val args by args<Args>()
    private lateinit var argsFile: Path
    private val isPlainTextMode: Boolean by lazy { args.intent.getBooleanExtra(TextEditorActivity.EXTRA_PLAIN_TEXT_MODE, false) }

    private lateinit var binding: TextEditorFragmentBinding

    private lateinit var menuBinding: MenuBinding

    private val viewModel by viewModels { { TextEditorViewModel(argsFile) } }

    private lateinit var onBackPressedCallback: OnBackPressedCallback

    private val compareFileLauncher =
        registerForActivityResult(FileListActivity.OpenFileContract()) { path ->
            if (path != null) compareWith(path)
        }

    private var isSettingText = false
    private var textMateReady = false
    private var lastAppliedThemeName: String? = null
    private val preferences: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(requireContext())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setHasOptionsMenu(true)

        lifecycleScope.launchWhenStarted {
            onBackPressedCallback = object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() {
                    ConfirmCloseDialogFragment.show(this@TextEditorFragment)
                }
            }
            launch {
                viewModel.isTextChanged.collect {
                    onBackPressedCallback.isEnabled = viewModel.isTextChanged.value
                }
            }
            addOnBackPressedCallback(onBackPressedCallback)

            launch { viewModel.encoding.collect { onEncodingChanged(it) } }
            launch { viewModel.textState.collect { onTextStateChanged(it) } }
            launch { viewModel.isTextChanged.collect { onIsTextChangedChanged(it) } }
            launch { viewModel.writeFileState.collect { onWriteFileStateChanged(it) } }
            launch { viewModel.isDecodedBinary.collect { onDecodedBinaryChanged(it) } }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        TextEditorFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val argsFile = args.intent.extraPath
        if (argsFile == null) {
            // TODO: Show a toast.
            finish()
            return
        }
        this.argsFile = argsFile

        val activity = requireActivity() as AppCompatActivity
        activity.lifecycleScope.launchWhenCreated {
            activity.setSupportActionBar(binding.toolbar)
            activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        }

        setupEditor()

        // TODO: Request storage permission if not granted.
    }

    override fun onResume() {
        super.onResume()
        if (this::binding.isInitialized) {
            // setupEditor() (called from onViewCreated) already performed a full TextMate
            // grammar/theme setup once. Redoing that unconditionally on every single resume
            // (including the very first one right after creation, and any trivial resume
            // unrelated to settings, e.g. switching apps and coming back) is expensive and
            // causes visible stutter. Only redo the heavy TextMate/theme rebuild when the
            // user's saved theme has actually changed since we last applied it; otherwise
            // just re-apply the lightweight font/appearance/toolbar preferences so real
            // settings changes still always show up when returning to this screen.
            if (!isPlainTextMode && currentThemeSignature() != lastAppliedThemeName) {
                textMateReady = setupTextMate()
            }
            applyEditorPreferences()
            if (viewModel.textState.value is DataState.Success) {
                applyLanguage()
                updatePageGuideVisibility()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

    }

    override fun onDestroyView() {
        if (this::binding.isInitialized) {
            searchHandler.removeCallbacksAndMessages(null)
            binding.textEdit.release()
        }
        super.onDestroyView()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)

        menuBinding = MenuBinding.inflate(menu, inflater)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)

        updateSaveMenuItem()
        updateEncodingMenuItems()
        updateUndoRedoMenuItems()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_save -> {
                save()
                true
            }
            R.id.action_reload -> {
                onReload()
                true
            }
            R.id.action_search -> {
                toggleSearchBar()
                true
            }
            R.id.action_find_replace -> {
                showSearchDialog()
                true
            }
            R.id.action_undo -> {
                if (binding.textEdit.canUndo()) binding.textEdit.undo()
                updateUndoRedoMenuItems()
                true
            }
            R.id.action_redo -> {
                if (binding.textEdit.canRedo()) binding.textEdit.redo()
                updateUndoRedoMenuItems()
                true
            }
            R.id.action_settings -> {
                openEditorSettings()
                true
            }
            R.id.action_compare -> {
                compareFileLauncher.launch(listOf(MimeType.ANY))
                true
            }
            R.id.action_save_as -> {
                saveDecodedAs()
                true
            }
            Menu.FIRST -> {
                viewModel.encoding.value = Charset.forName(item.titleCondensed!!.toString())
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    fun switchToFile(path: Path): Boolean {
        if (viewModel.isTextChanged.value) {
            showToast(R.string.text_editor_unsaved_changes)
            return false
        }
        argsFile = path
        viewModel.switchFile(path)
        return true
    }

    fun onSupportNavigateUp(): Boolean {
        if (onBackPressedCallback.isEnabled) {
            onBackPressedCallback.handleOnBackPressed()
            return true
        }
        return false
    }

    override fun finish() {
        requireActivity().finish()
    }

    private fun setupEditor() {
        migrateLegacyEditorPreferences()
        textMateReady = !isPlainTextMode && setupTextMate()
        buildToolbarActions()
        binding.editorToolbar.symbolsToggle.setOnClickListener {
            setSymbolsExpanded(binding.editorToolbar.symbolRow.visibility != View.VISIBLE, true)
        }
        binding.textEdit.setOnScrollChangeListener { _, _, _, _, _ -> binding.pageGuide.invalidate() }
        binding.pageGuide.attachTo(binding.textEdit)
        populateSymbols()
        setupSearchBar()

        binding.textEdit.apply {
            props.stickyScroll = !isPlainTextMode
            setWordwrap(isPlainTextMode)
            setLineSpacing(if (isPlainTextMode) 5f else 2f, if (isPlainTextMode) 1.0f else 1.05f)
            setHighlightCurrentLine(!isPlainTextMode)
            setLigatureEnabled(preferences.getBoolean(prefKey(PREF_FONT_LIGATURES), !isPlainTextMode))
            if (isPlainTextMode) {
                setDividerWidth(0f)
                setDividerMargin(0f)
            }
            subscribeAlways<ContentChangeEvent> {
                if (!isSettingText && viewModel.textState.value is DataState.Success) {
                    viewModel.isTextChanged.value = true
                }
                updateUndoRedoMenuItems()
            }
        }
        applyEditorPreferences()
    }

    private fun applyEditorPreferences() {
        val wordWrap = if (isPlainTextMode) preferences.getBoolean(PREF_TEXT_WORD_WRAP, true)
            else preferences.getBoolean(PREF_CODE_WORD_WRAP, false)
        binding.textEdit.setWordwrap(wordWrap)

        val lineNumbers = if (isPlainTextMode) preferences.getBoolean(PREF_TEXT_LINE_NUMBERS, false)
            else when (preferences.getString(PREF_CODE_LINE_NUMBERS_MODE, MODE_AUTO)) {
            MODE_ON -> true
            MODE_OFF -> false
            else -> shouldShowLineNumbersByDefault()
        }
        binding.textEdit.setLineNumberEnabled(lineNumbers)
        binding.textEdit.props.stickyScroll = if (isPlainTextMode) false
            else preferences.getBoolean(PREF_CODE_STICKY_SCROLL, true)
        val fontKey = if (isPlainTextMode) PREF_TEXT_FONT_SIZE else PREF_CODE_FONT_SIZE
        val fontSize = try {
            preferences.getString(fontKey, "16")?.toFloatOrNull()
        } catch (_: ClassCastException) {
            preferences.getFloat(fontKey, if (isPlainTextMode) 16f else 14f)
        } ?: if (isPlainTextMode) 16f else 14f
        binding.textEdit.setTextSize(fontSize)
        applyEditorAppearance()
        val symbolBarEnabled = preferences.getBoolean(prefKey(PREF_SYMBOL_BAR_ENABLED), true)
        binding.editorToolbar.symbolsToggle.visibility = if (symbolBarEnabled) View.VISIBLE else View.GONE
        if (symbolBarEnabled) setSymbolsExpanded(preferences.getBoolean(prefKey(PREF_SYMBOLS_OPEN), false), false) else binding.editorToolbar.symbolRow.visibility = View.GONE
        updatePageGuideVisibility()
        updateToolbarActions()
    }

    private fun updateUndoRedoMenuItems() {
        if (!this::menuBinding.isInitialized || !this::binding.isInitialized) return
        menuBinding.undoItem.isEnabled = binding.textEdit.canUndo()
        menuBinding.redoItem.isEnabled = binding.textEdit.canRedo()
    }

    // region Inline search bar (live search with previous/next arrows and a match counter)

    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var searchBackCallback: OnBackPressedCallback? = null
    private var searchQueryDispatched: String? = null
    private val liveSearchRunnable = Runnable { runSearch() }

    private val isSearchBarVisible: Boolean
        get() = this::binding.isInitialized && binding.searchBar.root.visibility == View.VISIBLE

    private fun currentSearchQuery(): String =
        binding.searchBar.searchBarInput.text?.toString().orEmpty()

    private fun setupSearchBar() {
        val bar = binding.searchBar
        bar.searchBarInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                searchHandler.removeCallbacks(liveSearchRunnable)
                searchHandler.postDelayed(liveSearchRunnable, 120)
            }
        })
        bar.searchBarInput.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                event.action == android.view.KeyEvent.ACTION_DOWN
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || isEnter) {
                jumpToSearchMatch(true)
                true
            } else {
                false
            }
        }
        bar.searchBarNext.setOnClickListener { jumpToSearchMatch(true) }
        bar.searchBarPrevious.setOnClickListener { jumpToSearchMatch(false) }
        bar.searchBarClose.setOnClickListener { closeSearchBar() }
    }

    private fun toggleSearchBar() {
        if (isSearchBarVisible) closeSearchBar() else openSearchBar()
    }

    private fun openSearchBar() {
        if (!this::binding.isInitialized) return
        binding.searchBar.root.visibility = View.VISIBLE
        val input = binding.searchBar.searchBarInput
        input.requestFocus()
        input.setSelection(input.text?.length ?: 0)
        input.post {
            requireContext().getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        // Back closes the search bar first (registered last, so it has priority over other callbacks).
        searchBackCallback?.remove()
        searchBackCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                closeSearchBar()
            }
        }.also { requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, it) }
        if (currentSearchQuery().isNotEmpty()) runSearch() else updateSearchCount()
    }

    private fun closeSearchBar() {
        if (!this::binding.isInitialized) return
        searchHandler.removeCallbacksAndMessages(null)
        searchBackCallback?.remove()
        searchBackCallback = null
        if (binding.searchBar.root.visibility == View.VISIBLE) {
            binding.searchBar.root.visibility = View.GONE
            binding.textEdit.searcher.stopSearch()
            searchQueryDispatched = null
            requireContext().getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(binding.searchBar.searchBarInput.windowToken, 0)
        }
    }

    // Live search: as the user types, matches are highlighted and counted, but the editor's
    // cursor/scroll position is never touched here. Jumping to a match only ever happens in
    // jumpToSearchMatch(), triggered explicitly by Enter or the next/previous arrows. This also
    // means the search bar is never dismissed or disrupted while there happen to be zero matches
    // for whatever has been typed so far — it just keeps showing "0" until the text changes.
    private fun runSearch() {
        if (!isSearchBarVisible) return
        val query = currentSearchQuery()
        val searcher = binding.textEdit.searcher
        if (query.isEmpty()) {
            searcher.stopSearch()
            searchQueryDispatched = null
            updateSearchCount()
            return
        }
        try {
            searcher.search(query, SearchOptions(SearchOptions.TYPE_NORMAL, true, null))
            searchQueryDispatched = query
        } catch (_: Exception) {
        }
        updateSearchCount()
        // The match count becomes available only after Sora finishes computing it on a
        // background thread, so refresh the label a bit later too (no cursor movement involved).
        searchHandler.postDelayed({ if (currentSearchQuery() == query) updateSearchCount() }, 150)
    }

    // Jumps to the next/previous match. Only ever called from an explicit user action (Enter,
    // the IME search action, or the arrow buttons) — never automatically while typing.
    private fun jumpToSearchMatch(next: Boolean) {
        if (!isSearchBarVisible) return
        val query = currentSearchQuery()
        if (query.isEmpty()) return
        if (searchQueryDispatched != query) {
            // The debounced live search has not caught up with the latest text yet (e.g. Enter
            // was pressed faster than the debounce delay); dispatch it right now before jumping.
            try {
                binding.textEdit.searcher.search(query, SearchOptions(SearchOptions.TYPE_NORMAL, true, null))
                searchQueryDispatched = query
            } catch (_: Exception) {
                return
            }
        }
        pollAndJump(query, next, 0)
    }

    // Keeps retrying gotoNext()/gotoPrevious() until it actually moves the cursor (proof the
    // search finished), the match count getter (if available on this library version) confirms
    // zero results, or a generous ceiling is hit.
    private fun pollAndJump(query: String, next: Boolean, attempt: Int) {
        if (!isSearchBarVisible || searchQueryDispatched != query || currentSearchQuery() != query) return
        updateSearchCount()
        val count = searcherInt("getMatchedPositionCount")
        if (count == 0) {
            // Confirmed no matches; nothing to jump to, stop retrying.
            return
        }
        val cursor = binding.textEdit.cursor
        val beforeLine = cursor.leftLine
        val beforeColumn = cursor.leftColumn
        try {
            val searcher = binding.textEdit.searcher
            if (next) searcher.gotoNext() else searcher.gotoPrevious()
        } catch (_: Exception) {
        }
        val moved = cursor.leftLine != beforeLine || cursor.leftColumn != beforeColumn
        if (moved) {
            updateSearchCount()
            return
        }
        // Not ready yet (or it genuinely could not move because there are 0 matches and the
        // count getter is unavailable on this library version). Keep trying for up to ~3s.
        if (attempt < 40) {
            searchHandler.postDelayed({ pollAndJump(query, next, attempt + 1) }, 75)
        } else {
            updateSearchCount()
        }
    }

    private fun updateSearchCount() {
        if (!this::binding.isInitialized) return
        val label = binding.searchBar.searchBarCount
        val query = currentSearchQuery()
        if (query.isEmpty()) {
            label.text = ""
            return
        }
        // The library's own counter was not reliable (it is read reflectively and may be empty
        // while its background search runs), so the matches are counted here directly, with the
        // same case-insensitive rule the search uses. A snapshot keeps the editor untouched.
        val text = binding.textEdit.text.toString()
        val cursor = binding.textEdit.cursor
        // Character offset of the cursor, worked out from its line and column.
        var cursorLeft = 0
        repeat(cursor.leftLine) {
            val newline = text.indexOf('\n', cursorLeft)
            cursorLeft = if (newline < 0) text.length else newline + 1
        }
        cursorLeft += cursor.leftColumn
        Thread {
            var total = 0
            var current = 0
            var from = 0
            while (true) {
                val found = text.indexOf(query, from, ignoreCase = true)
                if (found < 0) break
                total++
                if (found <= cursorLeft) current = total
                from = found + query.length.coerceAtLeast(1)
            }
            label.post {
                if (this::binding.isInitialized && currentSearchQuery() == query) {
                    label.text = "$current/$total"
                }
            }
        }.start()
    }

    // The counter accessors are read reflectively so a different Sora version can never break the build.
    private fun searcherInt(methodName: String): Int? = try {
        val searcher = binding.textEdit.searcher
        searcher.javaClass.getMethod(methodName).invoke(searcher) as? Int
    } catch (_: Throwable) {
        null
    }

    // endregion

    private fun showSearchDialog() {
        closeSearchBar()
        val dialogBinding = me.zhanghai.android.files.databinding.TextEditorSearchDialogBinding.inflate(layoutInflater)
        val searcher = binding.textEdit.searcher
        dialogBinding.searchQuery.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = commitSearch(dialogBinding)
        })
        dialogBinding.searchNext.setOnClickListener { searcher.gotoNext() }
        dialogBinding.searchPrevious.setOnClickListener { searcher.gotoPrevious() }
        dialogBinding.searchReplace.setOnClickListener {
            commitSearch(dialogBinding)
            searcher.replaceCurrentMatch(dialogBinding.searchReplacement.text.toString())
            updateUndoRedoMenuItems()
        }
        dialogBinding.searchReplaceAll.setOnClickListener {
            commitSearch(dialogBinding)
            searcher.replaceAll(dialogBinding.searchReplacement.text.toString()) {
                requireActivity().runOnUiThread { updateUndoRedoMenuItems() }
            }
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.text_editor_search)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        val optionListener = View.OnClickListener { commitSearch(dialogBinding) }
        dialogBinding.searchMatchCase.setOnClickListener(optionListener)
        dialogBinding.searchWholeWord.setOnClickListener {
            if (dialogBinding.searchWholeWord.isChecked) dialogBinding.searchRegex.isChecked = false
            commitSearch(dialogBinding)
        }
        dialogBinding.searchRegex.setOnClickListener {
            if (dialogBinding.searchRegex.isChecked) dialogBinding.searchWholeWord.isChecked = false
            commitSearch(dialogBinding)
        }
        dialog.show()
    }

    private fun commitSearch(
        dialogBinding: me.zhanghai.android.files.databinding.TextEditorSearchDialogBinding
    ) {
        val type = when {
            dialogBinding.searchRegex.isChecked -> SearchOptions.TYPE_REGULAR_EXPRESSION
            dialogBinding.searchWholeWord.isChecked -> SearchOptions.TYPE_WHOLE_WORD
            else -> SearchOptions.TYPE_NORMAL
        }
        val newOptions = SearchOptions(type, !dialogBinding.searchMatchCase.isChecked, null)
        try {
            val query = dialogBinding.searchQuery.text.toString()
            if (query.isEmpty()) binding.textEdit.searcher.stopSearch()
            else binding.textEdit.searcher.search(query, newOptions)
        } catch (_: PatternSyntaxException) {
            showToast(R.string.text_editor_search_error)
        }
    }

    private fun setupTextMate(): Boolean {
        if (isPlainTextMode) return false
        return try {
            val provider = AssetsFileResolver(requireContext().applicationContext.assets)
            FileProviderRegistry.getInstance().addFileProvider(provider)
            synchronized(TEXT_MATE_LOCK) {
                if (!grammarsLoaded) {
                    GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
                    grammarsLoaded = true
                }
            }
            val themeName = preferences.getString(prefKey(PREF_EDITOR_THEME), "github_dark") ?: "github_dark"
            val palette = EditorThemeFactory.paletteWithOverrides(themeName, currentColorOverrides())
            // The tm4e ThemeRegistry keeps the FIRST model registered under a given name, so the
            // registry name must change whenever the effective colors change; otherwise edited
            // custom colors (same base theme) would silently keep using the stale model.
            val model = EditorThemeFactory.themeModel(themeSignature(themeName, palette), palette)
            val themeRegistry = ThemeRegistry.getInstance()
            themeRegistry.loadTheme(model)
            themeRegistry.setTheme(model.name)
            binding.textEdit.colorScheme = TextMateColorScheme.create(themeRegistry)
            lastAppliedThemeName = themeSignature(themeName, palette)
            textMateReady = true
            true
        } catch (e: Throwable) {
            e.printStackTrace()
            textMateReady = false
            false
        }
    }

    private fun currentColorOverrides(): Map<String, String> = listOf(
        "key_editor_color_background", "key_editor_color_text", "key_editor_color_comment",
        "key_editor_color_keyword", "key_editor_color_string", "key_editor_color_number",
        "key_editor_color_type", "key_editor_color_function", "key_editor_color_variable",
        "key_editor_color_constant", "key_editor_color_operator", "key_editor_color_tag",
        "key_editor_color_attribute", "key_editor_color_punctuation"
    ).associateWith { preferences.getString(prefKey(it), "") ?: "" }

    private fun themeSignature(themeName: String, palette: EditorThemeFactory.Palette): String =
        "$themeName-${palette.hashCode().toString(16)}"

    private fun currentThemeSignature(): String {
        val themeName = preferences.getString(prefKey(PREF_EDITOR_THEME), "github_dark") ?: "github_dark"
        return themeSignature(themeName, EditorThemeFactory.paletteWithOverrides(themeName, currentColorOverrides()))
    }

    private fun applyEditorAppearance() {
        val themeName = preferences.getString(prefKey(PREF_EDITOR_THEME), "github_dark") ?: "github_dark"
        val palette = EditorThemeFactory.palette(themeName)
        if (isPlainTextMode || !textMateReady) {
            val scheme = EditorColorScheme()
            scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, parseColor(preferences.getString(prefKey("key_editor_color_background"), palette.background), Color.parseColor(palette.background)))
            scheme.setColor(EditorColorScheme.TEXT_NORMAL, parseColor(preferences.getString(prefKey("key_editor_color_text"), palette.foreground), Color.parseColor(palette.foreground)))
            binding.textEdit.colorScheme = scheme
        }
        val scheme = binding.textEdit.colorScheme
        scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, parseColor(preferences.getString(prefKey("key_editor_color_background"), palette.background), Color.parseColor(palette.background)))
        scheme.setColor(EditorColorScheme.TEXT_NORMAL, parseColor(preferences.getString(prefKey("key_editor_color_text"), palette.foreground), Color.parseColor(palette.foreground)))
        scheme.setColor(EditorColorScheme.CURRENT_LINE, parseColor(preferences.getString(prefKey("key_editor_color_current_line"), ""), Color.parseColor(palette.background)))
        scheme.setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, parseColor(preferences.getString(prefKey("key_editor_color_selection"), ""), blend(Color.parseColor(palette.background), Color.parseColor(palette.keyword), 0.35f)))
        scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, parseColor(preferences.getString(prefKey("key_editor_color_gutter_background"), ""), Color.parseColor(palette.background)))
        scheme.setColor(EditorColorScheme.LINE_NUMBER, parseColor(preferences.getString(prefKey("key_editor_color_gutter_text"), ""), Color.parseColor(palette.comment)))
        scheme.setColor(EditorColorScheme.LINE_NUMBER_CURRENT, parseColor(preferences.getString(prefKey("key_editor_color_gutter_text"), ""), Color.parseColor(palette.keyword)))
        scheme.setColor(EditorColorScheme.SELECTION_INSERT, parseColor(preferences.getString(prefKey("key_editor_color_cursor"), ""), Color.parseColor(palette.foreground)))
        applyToolbarColors()
                binding.textEdit.setBackgroundColor(parseColor(preferences.getString(prefKey("key_editor_color_background"), palette.background), Color.parseColor(palette.background)))
        binding.textEdit.typefaceText = createEditorTypeface(
            preferences.getString(prefKey(PREF_FONT_FAMILY), if (isPlainTextMode) "sans-serif" else "monospace") ?: "monospace",
            preferences.getString(prefKey(PREF_FONT_WEIGHT), "normal") ?: "normal"
        )
        binding.textEdit.setLigatureEnabled(preferences.getBoolean(prefKey(PREF_FONT_LIGATURES), !isPlainTextMode))
    }

    private fun createEditorTypeface(family: String, weight: String): Typeface {
        val style = if (weight == "bold") Typeface.BOLD else Typeface.NORMAL
        return when (family) {
            "jetbrains_mono" -> loadBundledTypeface("fonts/JetBrainsMono-${if (style == Typeface.BOLD) "Bold" else "Regular"}.ttf", style)
            "fira_code" -> loadBundledTypeface("fonts/FiraCode-${if (style == Typeface.BOLD) "Bold" else "Regular"}.ttf", style)
            "source_code_pro" -> loadBundledTypeface("fonts/SourceCodePro-${if (style == Typeface.BOLD) "Bold" else "Regular"}.ttf", style)
            "cascadia_code" -> loadBundledTypeface("fonts/CascadiaCode-Regular.ttf", style)
            else -> Typeface.create(family, style)
        }
    }

    private fun loadBundledTypeface(asset: String, style: Int): Typeface = try {
        Typeface.createFromAsset(requireContext().assets, asset).let { Typeface.create(it, style) }
    } catch (_: Exception) {
        Typeface.create("monospace", style)
    }

    private fun migrateLegacyEditorPreferences() {
        val legacyKeys = listOf(
            "key_editor_theme", "key_editor_font_family", "key_editor_font_weight",
            "key_editor_font_ligatures", "key_editor_color_background", "key_editor_color_text",
            "key_editor_color_comment", "key_editor_color_keyword", "key_editor_color_string",
            "key_editor_color_number", "key_editor_color_type", "key_editor_color_function",
            "key_editor_color_variable", "key_editor_color_constant", "key_editor_color_operator",
            "key_editor_color_tag", "key_editor_color_attribute", "key_editor_color_punctuation",
            "key_editor_color_current_line", "key_editor_color_selection",
            "key_editor_color_gutter_background", "key_editor_color_gutter_text",
            "key_editor_color_cursor", "key_editor_toolbar_background", "key_editor_toolbar_icon",
            "key_editor_toolbar_actions", "key_editor_toolbar_enabled", "key_text_editor_symbols_open"
        )
        val editor = preferences.edit()
        var changed = false
        for (legacy in legacyKeys) {
            val value = preferences.all[legacy] ?: continue
            if (legacy == "key_text_editor_symbols_open") {
                if (!preferences.contains("key_code_editor_symbols_open")) { putPreferenceValue(editor, "key_code_editor_symbols_open", value); changed = true }
                if (!preferences.contains("key_text_editor_symbols_open")) { putPreferenceValue(editor, "key_text_editor_symbols_open", value); changed = true }
                continue
            }
            val codeKey = "key_code_editor_${legacy.removePrefix("key_editor_")}"
            val textKey = "key_text_editor_${legacy.removePrefix("key_editor_")}"
            if (!preferences.contains(codeKey)) { putPreferenceValue(editor, codeKey, value); changed = true }
            if (!preferences.contains(textKey)) { putPreferenceValue(editor, textKey, value); changed = true }
        }
        if (changed) editor.apply()
    }

    private fun putPreferenceValue(editor: SharedPreferences.Editor, key: String, value: Any) {
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
        }
    }

    private fun prefKey(base: String): String = if (isPlainTextMode) "key_text_editor_${base.removePrefix("key_editor_")}" else "key_code_editor_${base.removePrefix("key_editor_")}"

    private fun toolbarPrefKey(base: String): String = if (isPlainTextMode) "key_text_editor_${base.removePrefix("key_editor_")}" else "key_code_editor_${base.removePrefix("key_editor_")}"

    private fun parseColor(value: String?, fallback: Int): Int = try {
        if (value.isNullOrBlank()) fallback else Color.parseColor(value)
    } catch (_: IllegalArgumentException) { fallback }

    private fun blend(a: Int, b: Int, ratio: Float): Int {
        val r = (Color.red(a) * (1 - ratio) + Color.red(b) * ratio).toInt()
        val g = (Color.green(a) * (1 - ratio) + Color.green(b) * ratio).toInt()
        val bl = (Color.blue(a) * (1 - ratio) + Color.blue(b) * ratio).toInt()
        return Color.rgb(r, g, bl)
    }

    private fun applyLanguage() {
        if (isPlainTextMode || !preferences.getBoolean(PREF_CODE_SYNTAX_HIGHLIGHTING, true) || !textMateReady) {
            binding.textEdit.setEditorLanguage(EmptyLanguage())
            return
        }

        // Large documents are intentionally kept as plain text unless the user explicitly
        // re-enables syntax highlighting after reducing the document size. This avoids parser
        // work blocking navigation when many files are opened in succession.
        if (binding.textEdit.text.length > MAX_SYNTAX_HIGHLIGHT_CHARS) {
            binding.textEdit.setEditorLanguage(EmptyLanguage())
            return
        }

        val name = viewModel.file.value.fileName.toString().lowercase()
        val scope = when {
            name.endsWith(".java") -> "source.java"
            name.endsWith(".gradle") || name.endsWith(".gradle.kts") -> "source.gradle"
            name.endsWith(".kt") || name.endsWith(".kts") -> "source.kotlin"
            name.endsWith(".py") -> "source.python"
            name.endsWith(".xml") || name.endsWith(".axml") -> "text.xml"
            name.endsWith(".html") || name.endsWith(".htm") -> "text.html.basic"
            name.endsWith(".js") || name.endsWith(".mjs") || name.endsWith(".cjs") -> "source.js"
            // The bundled grammar is JavaScript, not a TypeScript grammar. Open TS safely as
            // plain text instead of feeding it to the wrong parser.
            name.endsWith(".ts") || name.endsWith(".tsx") -> null
            name.endsWith(".md") || name.endsWith(".markdown") -> "text.html.markdown"
            name.endsWith(".sh") || name.endsWith(".bash") || name.endsWith(".zsh") || name.endsWith(".fish") -> "source.shell"
            name.endsWith(".toml") -> "source.toml"
            name.endsWith(".css") -> "source.css"
            name.endsWith(".sql") -> "source.sql"
            name.endsWith(".properties") || name.endsWith(".ini") || name.endsWith(".conf") -> "source.properties"
            name.endsWith(".json") -> "source.json"
            name.endsWith(".smali") -> "source.smali"
            else -> null
        }

        try {
            binding.textEdit.setEditorLanguage(
                if (scope != null) TextMateLanguage.create(scope, true) else EmptyLanguage()
            )
        } catch (e: Throwable) {
            // A broken or incompatible grammar must never take the whole file viewer down.
            e.printStackTrace()
            binding.textEdit.setEditorLanguage(EmptyLanguage())
        }
    }

    private fun shouldShowLineNumbersByDefault(): Boolean {
        val name = viewModel.file.value.fileName.toString().lowercase()
        val extension = name.substringAfterLast('.', "")
        return extension in setOf(
            "java", "kt", "kts", "gradle", "py", "js", "mjs", "cjs", "ts", "tsx",
            "c", "cc", "cpp", "h", "hpp", "cs", "go", "rs", "php", "swift", "dart",
            "sh", "bash", "zsh", "fish", "smali", "xml", "axml", "json", "yaml", "yml",
            "toml", "properties", "html", "htm", "css", "sql", "md", "markdown"
        )
    }

    private fun openEditorSettings() {
        // Code and plain-text editors have fully separate settings (key_code_editor_* vs
        // key_text_editor_*). Always open the settings screen that belongs to the mode this
        // editor is currently running in, otherwise the user edits keys this editor never reads.
        val intent = if (isPlainTextMode) TextEditorSettingsActivity::class.createIntent()
            else CodeEditorSettingsActivity::class.createIntent()
        startActivitySafe(intent)
    }

    private fun onEncodingChanged(encoding: Charset) {
        updateEncodingMenuItems()
    }

    private fun updateEncodingMenuItems() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        val charsetName = viewModel.encoding.value.name()
        menuBinding.encodingSubMenu.children
            .find { it.titleCondensed == charsetName }
            ?.isChecked = true
    }

    private fun onTextStateChanged(state: DataState<String>) {
        updateTitle()
        when (state) {
            is DataState.Loading -> {
                binding.progress.fadeInUnsafe()
                binding.errorText.fadeOutUnsafe()
                binding.textEdit.fadeOutUnsafe()
            }
            is DataState.Success -> {
                binding.progress.fadeOutUnsafe()
                binding.errorText.fadeOutUnsafe()
                binding.textEdit.fadeInUnsafe()
                if (!viewModel.isTextChanged.value) {
                    setText(state.data)
                }
            }
            is DataState.Error -> {
                state.throwable.printStackTrace()
                binding.progress.fadeOutUnsafe()
                binding.errorText.fadeInUnsafe()
                binding.errorText.text = state.throwable.toString()
                binding.textEdit.fadeOutUnsafe()
            }
        }
    }

    private fun setText(text: String?) {
        isSettingText = true
        binding.textEdit.setText(text ?: "")
        isSettingText = false
        applyLanguage()
        updatePageGuideVisibility()
        viewModel.isTextChanged.value = false
    }

    private fun onIsTextChangedChanged(changed: Boolean) {
        updateTitle()
    }

    private fun onDecodedBinaryChanged(decoded: Boolean) {
        if (this::binding.isInitialized) {
            binding.textEdit.setEditable(!decoded)
        }
        updateSaveMenuItem()
    }

    private fun compareWith(path: Path) {
        if (path == argsFile) return
        lifecycleScope.launch {
            try {
                val reference = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { path.readAllBytes().toString(Charsets.UTF_8) }
                val target = binding.textEdit.text.toString()
                val refName = argsFile.fileName.toString().lowercase()
                val tgtName = path.fileName.toString().lowercase()
                val result = if (refName.endsWith(".xml") && tgtName.endsWith(".xml")) {
                    TextCompareSynchronizer.synchronize(reference, target)
                } else if (refName.endsWith(".json") && tgtName.endsWith(".json")) {
                    JsonCompareSynchronizer.synchronize(reference, target)
                } else null
                if (result == null) {
                    showToast(R.string.text_editor_compare_no_sync)
                    return@launch
                }
                val summary = buildString {
                    append(getString(R.string.text_editor_compare_summary, result.missingKeys.size, if (result.reordered) 1 else 0))
                    if (result.repairedKeys.isNotEmpty()) {
                        append("\n")
                        append(getString(R.string.text_editor_compare_repaired, result.repairedKeys.size))
                    }
                    if (result.missingKeys.isNotEmpty()) {
                        append("\n\n")
                        result.missingKeys.take(20).forEach { append("• ").append(it).append('\n') }
                        if (result.missingKeys.size > 20) append("…")
                    }
                }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.text_editor_compare)
                    .setMessage(summary)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.text_editor_compare_apply) { _, _ ->
                        isSettingText = true
                        binding.textEdit.setText(result.text)
                        isSettingText = false
                        applyLanguage()
                        updatePageGuideVisibility()
                        viewModel.isTextChanged.value = result.changed
                        updateUndoRedoMenuItems()
                    }.show()
            } catch (e: Exception) {
                showToast(e.toString())
            }
        }
    }

    private fun saveDecodedAs() {
        val text = binding.textEdit.text.toString()
        val title = viewModel.file.value.fileName.toString() + ".txt"
        val intent = FileListActivity.CreateFileContract().createIntent(requireContext(), Triple(MimeType.TEXT_PLAIN, title, null))
        startActivityForResult(intent, REQUEST_SAVE_DECODED)
        pendingDecodedText = text
    }

    private fun indentSelection(outdent: Boolean) {
        val cursor = binding.textEdit.text.cursor
        val startLine = cursor.getLeftLine()
        val endLine = cursor.getRightLine()
        for (line in endLine downTo startLine) {
            val content = binding.textEdit.text.getLine(line).toString()
            if (outdent) {
                val remove = content.takeWhile { it == ' ' || it == '\t' }.take(4).length
                if (remove > 0) binding.textEdit.text.delete(line, 0, line, remove)
            } else {
                binding.textEdit.text.insert(line, 0, "    ")
            }
        }
    }

    private fun toggleComment() {
        val name = viewModel.file.value.fileName.toString().lowercase()
        val hashComment = name.endsWith(".py") || name.endsWith(".sh") || name.endsWith(".bash") || name.endsWith(".zsh") || name.endsWith(".fish") || name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".toml")
        val xmlComment = name.endsWith(".xml") || name.endsWith(".html") || name.endsWith(".htm")
        val prefix = if (hashComment) "# " else "// "
        val cursor = binding.textEdit.text.cursor
        val lines = cursor.getLeftLine()..cursor.getRightLine()
        for (line in lines.reversed()) {
            val text = binding.textEdit.text.getLine(line).toString()
            val first = text.indexOfFirst { !it.isWhitespace() }
            if (first >= 0) {
                if (xmlComment) {
                    val trimmed = text.substring(first)
                    if (trimmed.startsWith("<!-- ") && trimmed.endsWith(" -->")) {
                        binding.textEdit.text.replace(line, first, line, text.length, trimmed.removePrefix("<!-- ").removeSuffix(" -->"))
                    } else {
                        binding.textEdit.text.replace(line, first, line, text.length, "<!-- ${trimmed} -->")
                    }
                } else if (text.substring(first).startsWith(prefix.trim())) {
                    binding.textEdit.text.delete(line, first, line, first + prefix.length)
                } else {
                    binding.textEdit.text.insert(line, first, prefix)
                }
            }
        }
    }

    private data class ToolbarAction(
        val id: String,
        val labelRes: Int,
        val iconRes: Int,
        val action: () -> Unit
    )

    private fun toolbarActions(): List<ToolbarAction> = listOf(
        ToolbarAction("undo", R.string.text_editor_undo, R.drawable.google_undo_24dp) { if (binding.textEdit.canUndo()) binding.textEdit.undo() },
        ToolbarAction("redo", R.string.text_editor_redo, R.drawable.google_redo_24dp) { if (binding.textEdit.canRedo()) binding.textEdit.redo() },
        ToolbarAction("cut", R.string.cut, R.drawable.google_content_cut_24dp) { binding.textEdit.cutText() },
        ToolbarAction("copy", R.string.copy, R.drawable.google_content_copy_24dp) { binding.textEdit.copyText(false) },
        ToolbarAction("paste", R.string.paste, R.drawable.google_content_paste_24dp) { binding.textEdit.pasteText() },
        ToolbarAction("select_all", R.string.select_all, R.drawable.google_select_all_24dp) { binding.textEdit.selectAll() },
        ToolbarAction("indent", R.string.text_editor_indent, R.drawable.google_format_indent_increase_24dp) { indentSelection(false) },
        ToolbarAction("outdent", R.string.text_editor_outdent, R.drawable.google_format_indent_decrease_24dp) { indentSelection(true) },
        ToolbarAction("comment", R.string.text_editor_comment, R.drawable.google_comment_24dp) { toggleComment() },
        ToolbarAction("search", R.string.text_editor_find, R.drawable.google_search_24dp) { toggleSearchBar() },
        ToolbarAction("find_replace", R.string.text_editor_search, R.drawable.google_find_replace_24dp) { showSearchDialog() }
    )

    /**
     * Colors the editor toolbar (action icons, symbol toggle and symbol keys) from the selected editor
     * theme. Unless the user set an explicit icon color, the icon/text color is the theme's foreground,
     * falling back to black or white whenever that would not contrast enough with the toolbar background.
     */
    private fun applyToolbarColors() {
        if (!this::binding.isInitialized) return
        val themeName = preferences.getString(prefKey(PREF_EDITOR_THEME), "github_dark") ?: "github_dark"
        val palette = EditorThemeFactory.palette(themeName)
        val toolbarBackground = parseColor(preferences.getString(prefKey("key_editor_toolbar_background"), ""), Color.parseColor(palette.background))
        val explicitIcon = preferences.getString(prefKey("key_editor_toolbar_icon"), "")
        val themeForeground = Color.parseColor(palette.foreground)
        val toolbarIcon = if (!explicitIcon.isNullOrBlank()) {
            parseColor(explicitIcon, themeForeground)
        } else if (androidx.core.graphics.ColorUtils.calculateContrast(themeForeground, toolbarBackground) >= 4.5) {
            themeForeground
        } else if (androidx.core.graphics.ColorUtils.calculateLuminance(toolbarBackground) > 0.5) {
            Color.BLACK
        } else {
            Color.WHITE
        }
        val tint = android.content.res.ColorStateList.valueOf(toolbarIcon)
        binding.editorToolbar.root.setBackgroundColor(toolbarBackground)
        binding.editorToolbar.symbolRow.setBackgroundColor(toolbarBackground)
        binding.editorToolbar.toolbarActions.children.forEach { (it as? android.widget.ImageButton)?.imageTintList = tint }
        binding.editorToolbar.symbolsToggle.imageTintList = tint
        binding.editorToolbar.symbolContainer.children.forEach { (it as? android.widget.TextView)?.setTextColor(toolbarIcon) }
    }

    private fun buildToolbarActions() {
        binding.editorToolbar.toolbarActions.removeAllViews()
        val actions = toolbarActions().associateBy { it.id }
        val order = getToolbarOrder()
        order.forEach { id ->
            val action = actions[id] ?: return@forEach
            val button = android.widget.ImageButton(requireContext()).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(48.dp(), 48.dp())
                setBackgroundResource(R.drawable.text_editor_toolbar_button_background)
                setImageResource(action.iconRes)
                contentDescription = getString(action.labelRes)
                setOnClickListener { action.action.invoke(); updateUndoRedoMenuItems() }
                scaleType = android.widget.ImageView.ScaleType.CENTER
                setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            }
            binding.editorToolbar.toolbarActions.addView(button)
        }
        applyToolbarColors()
    }

    private fun updateToolbarActions() {
        if (!this::binding.isInitialized) return
        buildToolbarActions()
        val symbolBarEnabled = preferences.getBoolean(prefKey(PREF_SYMBOL_BAR_ENABLED), true)
        val hasToolbarActions = binding.editorToolbar.toolbarActions.childCount > 0
        binding.editorToolbar.root.visibility =
            if (!symbolBarEnabled && !hasToolbarActions) View.GONE else View.VISIBLE
        binding.editorToolbar.toolbarBar.visibility =
            if (!symbolBarEnabled && !hasToolbarActions) View.GONE else View.VISIBLE
    }

    private fun getToolbarOrder(): List<String> {
        val defaults = listOf("undo", "redo", "cut", "copy", "paste", "select_all", "indent", "outdent", "comment", "search", "find_replace")
        val stored = preferences.getString(toolbarPrefKey(PREF_TOOLBAR_ACTIONS), null)?.split(',')?.filter { it.isNotBlank() }
        // Actions that were added after the user last saved the toolbar layout are enabled by default.
        val newIds = defaults.filter { stored != null && it !in stored }
        val enabled = preferences.getString(toolbarPrefKey(PREF_TOOLBAR_ENABLED), null)?.split(',')?.filter { it.isNotBlank() }?.toSet()?.plus(newIds) ?: defaults.toSet()
        val order = if (stored.isNullOrEmpty()) defaults else stored.filter { it in defaults } + defaults.filter { it !in stored }
        return order.filter { it in enabled }
    }

    private fun populateSymbols() {
        binding.editorToolbar.symbolContainer.removeAllViews()
        symbolSetForFile().forEach { symbol ->
            val text = android.widget.TextView(requireContext()).apply {
                text = symbol.label
                gravity = android.view.Gravity.CENTER
                minWidth = 48.dp()
                minHeight = 40.dp()
                setPadding(12.dp(), 0, 12.dp(), 0)
                setTextColor(resolveThemeTextColor(android.R.attr.textColorPrimary))
                background = androidx.appcompat.content.res.AppCompatResources.getDrawable(requireContext(), R.drawable.text_editor_symbol_background)
                contentDescription = symbol.label
                setOnClickListener { binding.textEdit.insertText(symbol.insert, symbol.cursorOffset) }
            }
            binding.editorToolbar.symbolContainer.addView(text)
        }
        applyToolbarColors()
    }

    private fun resolveThemeTextColor(attr: Int): android.content.res.ColorStateList {
        val value = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) {
            androidx.core.content.ContextCompat.getColorStateList(requireContext(), value.resourceId)
                ?: android.content.res.ColorStateList.valueOf(value.data)
        } else {
            android.content.res.ColorStateList.valueOf(value.data)
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private data class Symbol(
        val label: String,
        val insert: String = label,
        val cursorOffset: Int = insert.length
    )

    private fun symbolSetForFile(): List<Symbol> {
        val name = viewModel.file.value.fileName.toString().lowercase()
        return when {
            name.endsWith(".sh") || name.endsWith(".bash") ||
                name.endsWith(".zsh") || name.endsWith(".fish") -> listOf(
                Symbol("$"),
                Symbol("${'$'}{", "${'$'}{}", 2),
                Symbol("|"),
                Symbol("||"),
                Symbol("&&"),
                Symbol(">"),
                Symbol(">>"),
                Symbol("2>"),
                Symbol(";"),
                Symbol("$()", "${'$'}()", 2)
            )

            name.endsWith(".xml") || name.endsWith(".html") || name.endsWith(".htm") -> listOf(
                Symbol("<>"),
                Symbol("</>"),
                Symbol("=", "=\"", 1),
                Symbol("\"", "\"", 1),
                Symbol("<!-- -->", "<!--  -->", 5),
                Symbol("&amp;")
            )

            name.endsWith(".json") -> listOf(
                Symbol("{}", "{}", 1),
                Symbol("[]", "[]", 1),
                Symbol("()", "()", 1),
                Symbol(":"),
                Symbol(","),
                Symbol("\"", "\"", 1)
            )

            name.endsWith(".md") || name.endsWith(".markdown") -> listOf(
                Symbol("# "),
                Symbol("## "),
                Symbol("- "),
                Symbol("* "),
                Symbol("`"),
                Symbol("```\\n```", "```\\n```", 4),
                Symbol("[]", "[]", 1),
                Symbol("()", "()", 1)
            )

            else -> listOf(
                Symbol("{}", "{}", 1),
                Symbol("()", "()", 1),
                Symbol("[]", "[]", 1),
                Symbol("<>"),
                Symbol("= "),
                Symbol("->"),
                Symbol("::"),
                Symbol(";"),
                Symbol(":")
            )
        }
    }

    private fun setSymbolsExpanded(expanded: Boolean, persist: Boolean) {
        binding.editorToolbar.symbolRow.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.editorToolbar.symbolsToggle.setImageResource(
            if (expanded) R.drawable.google_keyboard_arrow_down_24dp
            else R.drawable.google_keyboard_arrow_up_24dp
        )
        binding.editorToolbar.symbolsToggle.contentDescription = getString(
            if (expanded) R.string.text_editor_symbols_hide else R.string.text_editor_symbols_show
        )
        if (persist) preferences.edit().putBoolean(prefKey(PREF_SYMBOLS_OPEN), expanded).apply()
    }

    private fun updatePageGuideVisibility() {
        val name = viewModel.file.value.fileName.toString().lowercase()
        val plainText = name.endsWith(".txt") || name.endsWith(".log") || name.endsWith(".csv") || name.endsWith(".tsv")
        binding.pageGuide.visibility = if (plainText && preferences.getBoolean(PREF_TEXT_PAGE_GUIDE, true)) View.VISIBLE else View.GONE
    }

    private fun updateTitle() {
        val fileName = viewModel.file.value.fileName.toString()
        val changed = viewModel.isTextChanged.value
        requireActivity().title = getString(
            if (changed) {
                R.string.text_editor_title_changed_format
            } else {
                R.string.text_editor_title_format
            }, fileName
        )
    }

    private fun onReload() {
        if (viewModel.isTextChanged.value) {
            ConfirmReloadDialogFragment.show(this)
        } else {
            reload()
        }
    }

    override fun reload() {
        viewModel.isTextChanged.value = false
        viewModel.reload()
    }

    private fun save() {
        if (viewModel.isDecodedBinary.value) return
        val text = binding.textEdit.text.toString()
        viewModel.writeFile(argsFile, text, requireContext())
    }

    private fun onWriteFileStateChanged(state: ActionState<Pair<Path, String>, Unit>) {
        when (state) {
            is ActionState.Ready, is ActionState.Running -> updateSaveMenuItem()
            is ActionState.Success -> {
                showToast(R.string.text_editor_save_success)
                viewModel.finishWritingFile()
                viewModel.isTextChanged.value = false
            }
            // The error will be toasted by service so we should never show it in UI.
            is ActionState.Error -> viewModel.finishWritingFile()
        }
    }

    private fun updateSaveMenuItem() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        menuBinding.saveItem.isEnabled =
            !viewModel.isDecodedBinary.value && viewModel.writeFileState.value.isReady
        menuBinding.saveAsItem.isVisible = viewModel.isDecodedBinary.value
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SAVE_DECODED && resultCode == AppCompatActivity.RESULT_OK) {
            val path = data?.extraPath
            val text = pendingDecodedText
            if (path != null && text != null) FileJobService.write(path, text.toByteArray(viewModel.encoding.value), requireContext()) { }
            pendingDecodedText = null
        }
    }

    private var pendingDecodedText: String? = null

    companion object {
        private const val REQUEST_SAVE_DECODED = 9041
        private const val MODE_AUTO = "auto"
        private const val MODE_ON = "on"
        private const val MODE_OFF = "off"
        private const val PREF_CODE_SYNTAX_HIGHLIGHTING = "key_code_editor_syntax_highlighting"
        private const val PREF_CODE_WORD_WRAP = "key_code_editor_word_wrap"
        private const val PREF_CODE_LINE_NUMBERS_MODE = "key_code_editor_line_numbers"
        private const val PREF_CODE_STICKY_SCROLL = "key_code_editor_sticky_scroll"
        private const val PREF_CODE_FONT_SIZE = "key_code_editor_font_size"
        private const val PREF_TEXT_WORD_WRAP = "key_text_editor_word_wrap"
        private const val PREF_TEXT_LINE_NUMBERS = "key_text_editor_line_numbers"
        private const val PREF_TEXT_FONT_SIZE = "key_text_editor_font_size"
        private const val PREF_TEXT_PAGE_GUIDE = "key_text_editor_page_guide"
        private const val PREF_SYMBOLS_OPEN = "key_editor_symbols_open"
        private const val PREF_SYMBOL_BAR_ENABLED = "key_editor_symbol_bar"
        private const val PREF_TOOLBAR_ACTIONS = "key_editor_toolbar_actions"
        private const val PREF_TOOLBAR_ENABLED = "key_editor_toolbar_enabled"
        private const val PREF_EDITOR_THEME = "key_editor_theme"
        private const val PREF_FONT_FAMILY = "key_editor_font_family"
        private const val PREF_FONT_WEIGHT = "key_editor_font_weight"
        private const val PREF_FONT_LIGATURES = "key_editor_font_ligatures"
        private const val MAX_SYNTAX_HIGHLIGHT_CHARS = 300_000
        private val TEXT_MATE_LOCK = Any()
        private var grammarsLoaded = false
    }

    @Parcelize
    class Args(val intent: Intent) : ParcelableArgs

    private class MenuBinding private constructor(
        val menu: Menu,
        val saveItem: MenuItem,
        val saveAsItem: MenuItem,
        val undoItem: MenuItem,
        val redoItem: MenuItem,
        val encodingSubMenu: SubMenu
    ) {
        companion object {
            fun inflate(menu: Menu, inflater: MenuInflater): MenuBinding {
                inflater.inflate(R.menu.text_editor, menu)
                val encodingSubMenu = menu.findItem(R.id.action_encoding).subMenu!!
                for ((charsetName, charset) in Charset.availableCharsets()) {
                    // HACK: Use titleCondensed to store charset name.
                    encodingSubMenu.add(Menu.NONE, Menu.FIRST, Menu.NONE, charset.displayName())
                        .titleCondensed = charsetName
                }
                encodingSubMenu.setGroupCheckable(Menu.NONE, true, true)
                return MenuBinding(
                    menu,
                    menu.findItem(R.id.action_save),
                    menu.findItem(R.id.action_save_as),
                    menu.findItem(R.id.action_undo),
                    menu.findItem(R.id.action_redo),
                    encodingSubMenu
                )
            }
        }
    }
}

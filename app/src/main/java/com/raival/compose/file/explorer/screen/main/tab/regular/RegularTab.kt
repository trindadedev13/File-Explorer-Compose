package com.raival.compose.file.explorer.screen.main.tab.regular

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider.getUriForFile
import com.raival.compose.file.explorer.App.Companion.globalClass
import com.raival.compose.file.explorer.R
import com.raival.compose.file.explorer.common.extension.addIfAbsent
import com.raival.compose.file.explorer.common.extension.emptyString
import com.raival.compose.file.explorer.common.extension.getIndexIf
import com.raival.compose.file.explorer.common.extension.isNot
import com.raival.compose.file.explorer.common.extension.orIf
import com.raival.compose.file.explorer.common.extension.removeIf
import com.raival.compose.file.explorer.screen.main.MainActivity
import com.raival.compose.file.explorer.screen.main.tab.Tab
import com.raival.compose.file.explorer.screen.main.tab.regular.misc.FileMimeType.anyFileType
import com.raival.compose.file.explorer.screen.main.tab.regular.modal.DocumentHolder
import com.raival.compose.file.explorer.screen.main.tab.regular.provider.FileProvider
import com.raival.compose.file.explorer.screen.main.tab.regular.task.RegularTabCompressTask
import com.raival.compose.file.explorer.screen.main.tab.regular.task.RegularTabDeleteTask
import com.raival.compose.file.explorer.screen.main.tab.regular.task.RegularTabTask
import com.raival.compose.file.explorer.screen.main.tab.regular.task.RegularTabTaskCallback
import com.raival.compose.file.explorer.screen.main.tab.regular.task.RegularTabTaskDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RegularTab(initFile: DocumentHolder, context: Context? = null) : Tab() {

    override val id = globalClass.generateUid()

    companion object {
        fun isValidPath(path: String) = DocumentHolder.fromFullPath(path) isNot null
    }

    val search = Search
    val taskDialog = TaskDialog
    val apkDialog = ApkDialog
    val compressDialog = CompressDialog
    val renameDialog = RenameDialog
    val fileOptionsDialog = FileOptionsDialog
    val openWithDialog = OpenWithDialog

    val homeDir: DocumentHolder = if (initFile.isFolder()) initFile else initFile.getParent()
        ?: FileProvider.getPrimaryInternalStorage(globalClass).documentHolder
    var activeFolder: DocumentHolder = homeDir

    val activeFolderContent = mutableStateListOf<DocumentHolder>()
    val contentListStates = hashMapOf<String, LazyGridState>()
    var activeListState by mutableStateOf(LazyGridState())

    val currentPathSegments = mutableStateListOf<DocumentHolder>()
    val currentPathSegmentsListState = LazyListState()

    val selectedFiles = linkedMapOf<String, DocumentHolder>()
    var lastSelectedFileIndex = -1

    var highlightedFiles = arrayListOf<String>()

    var showSortingMenu by mutableStateOf(false)
    var showCreateNewFileDialog by mutableStateOf(false)
    var showConfirmDeleteDialog by mutableStateOf(false)
    var showFileProperties by mutableStateOf(false)
    var showTasksPanel by mutableStateOf(false)
    var showSearchPenal by mutableStateOf(false)
    var showBookmarkDialog by mutableStateOf(false)
    var showMoreOptionsButton by mutableStateOf(false)
    var showEmptyRecycleBin by mutableStateOf(false)
    var handleBackGesture by mutableStateOf(activeFolder.canAccessParent() || selectedFiles.isNotEmpty())
    var tabViewLabel by mutableStateOf(emptyString)

    var isLoading by mutableStateOf(false)

    var foldersCount = 0
    var filesCount = 0

    override var onTabClicked = { openFolder(item = activeFolder, rememberSelectedFiles = true) }
    override var onTabStarted = { }
    override var onTabResumed = { openFolder(item = activeFolder, rememberSelectedFiles = true) }
    override var onTabStopped = { }

    override val title: String
        get() = createTitle()

    override val subtitle: String
        get() = createSubtitle()

    init {
        if (initFile.isFile()) {
            val parent = initFile.getParent()
            if (parent != null && parent.exists()) {
                openFolder(parent) {
                    CoroutineScope(Dispatchers.Main).launch {
                        getFileListState().scrollToItem(
                            activeFolderContent.getIndexIf { getPath() == initFile.getPath() },
                            0
                        )
                    }
                }
            } else {
                openFolder(homeDir)
            }
            highlightedFiles.apply {
                clear()
                add(initFile.getPath())
            }
            context?.let { openFile(context, initFile) }
        } else {
            openFolder(homeDir)
        }
    }

    private fun createSubtitle(): String {
        var selectedFolders = 0
        var selectedFiles = 0

        this.selectedFiles.values.forEach {
            if (it.isFolder()) selectedFolders++
            else selectedFiles++
        }

        return buildString {
            if (foldersCount + filesCount == 0) {
                append(globalClass.getString(R.string.empty_folder))
                return@buildString
            }

            if (foldersCount > 0) {
                append(activeFolder.getFormattedFileCount(0, foldersCount))
                if (selectedFolders > 0) {
                    append(globalClass.getString(R.string.files_selected).format(selectedFolders))
                }
            }

            if (filesCount > 0 && foldersCount > 0) append(" | ")

            if (filesCount > 0) {
                append(activeFolder.getFormattedFileCount(filesCount, 0))
                if (selectedFiles > 0) {
                    append(globalClass.getString(R.string.files_selected).format(selectedFiles))
                }
            }
        }
    }

    private fun createTitle() = globalClass.getString(R.string.regular_tab_title)


    fun onBackPressed() {
        if (!unselectAnySelectedFiles()) {
            highlightedFiles.apply {
                clear()
                add(activeFolder.getPath())
            }
            openFolder(activeFolder.getParent()!!)
        }
    }

    /**
     * Unselects all the selected files.
     * returns true if any files were selected
     */
    fun unselectAnySelectedFiles(): Boolean {
        if (selectedFiles.isNotEmpty()) {
            unselectAllFiles()
            return true
        }
        return false
    }

    fun openFile(context: Context, item: DocumentHolder) {
        if (item.isApk()) {
            ApkDialog.show(item)
        } else {
            item.openFile(context, anonymous = false, skipSupportedExtensions = false)
        }
    }

    fun openFolder(
        item: DocumentHolder,
        rememberListState: Boolean = true,
        rememberSelectedFiles: Boolean = false,
        postEvent: () -> Unit = {}
    ) {
        if (isLoading) return

        if (!rememberSelectedFiles) {
            selectedFiles.clear()
            lastSelectedFileIndex = -1
        } else {
            selectedFiles.removeIf { key, value -> !value.exists() }
            if (selectedFiles.isEmpty()) lastSelectedFileIndex = -1
        }

        activeFolder = item

        showEmptyRecycleBin = activeFolder.hasParent(globalClass.recycleBinDir)
                || activeFolder.getPath() == globalClass.recycleBinDir.getPath()

        handleBackGesture = activeFolder.canAccessParent() || selectedFiles.isNotEmpty()

        updatePathList()

        listFiles { newContent ->
            requestHomeToolbarUpdate()

            activeFolderContent.clear()
            activeFolderContent.addAll(newContent)

            if (!rememberListState) {
                contentListStates[item.getPath()] = LazyGridState(0, 0)
            }

            activeListState = contentListStates[item.getPath()] ?: LazyGridState()
                .also { contentListStates[item.getPath()] = it }

            postEvent()
        }
    }

    fun quickReloadFiles() {
        if (isLoading) return

        val temp = arrayListOf<DocumentHolder>().apply { addAll(activeFolderContent) }

        activeFolderContent.clear()
        activeFolderContent.addAll(temp)

        handleBackGesture = activeFolder.canAccessParent() || selectedFiles.isNotEmpty()

        requestHomeToolbarUpdate()

        showMoreOptionsButton = selectedFiles.size > 0

        showEmptyRecycleBin = activeFolder.hasParent(globalClass.recycleBinDir)
                || activeFolder.getPath() == globalClass.recycleBinDir.getPath()
    }

    fun reloadFiles(postEvent: () -> Unit = {}) {
        openFolder(activeFolder) { postEvent() }
    }

    private fun listFiles(onReady: (ArrayList<DocumentHolder>) -> Unit) {
        CoroutineScope(Dispatchers.Default).launch {
            isLoading = true

            foldersCount = 0
            filesCount = 0

            val result = activeFolder.listContent(
                sortingPrefs = globalClass.preferencesManager.filesSortingPrefs.getSortingPrefsFor(
                    activeFolder
                )
            ) {
                if (it.isFile()) filesCount++
                else foldersCount++
            }.apply {
                if (!globalClass.preferencesManager.displayPrefs.showHiddenFiles) {
                    removeIf { it.getFileName().startsWith(".") }
                }
            }

            withContext(Dispatchers.Main) {
                onReady(result)
                isLoading = false
            }
        }
    }

    fun unselectAllFiles(quickReload: Boolean = true) {
        selectedFiles.clear()
        lastSelectedFileIndex = -1
        if (quickReload) quickReloadFiles()
    }

    fun onNewFileCreated(fileName: String, openFolder: Boolean = false) {
        val newFile = activeFolder.findFile(fileName)

        newFile?.let {
            highlightedFiles.apply {
                clear()
                add(it.getPath())
            }

            reloadFiles {
                CoroutineScope(Dispatchers.Main).launch {
                    val newItemIndex =
                        activeFolderContent.getIndexIf { getPath() == newFile.getPath() }
                    if (newItemIndex > -1) {
                        getFileListState().scrollToItem(newItemIndex, 0)
                    }

                    if (openFolder) {
                        openFolder(newFile)
                    }
                }
            }
        }
    }

    fun getFileListState() = contentListStates[activeFolder.getPath()] ?: LazyGridState().also {
        contentListStates[activeFolder.getPath()] = it
    }

    private fun updateTabViewLabel() {
        val fullName =
            activeFolder.getFileName().orIf(globalClass.getString(R.string.internal_storage)) {
                activeFolder.getPath() == Environment.getExternalStorageDirectory().absolutePath
            }
        tabViewLabel = if (fullName.length > 18) fullName.substring(0, 15) + "..." else fullName
    }

    private fun updatePathList() {
        currentPathSegments.apply {
            clear()
            add(activeFolder)
            if (activeFolder.canAccessParent()) {
                var parentDir = activeFolder.getParent()!!
                add(parentDir)
                while (parentDir.canAccessParent()) {
                    parentDir = parentDir.getParent()!!
                    add(parentDir)
                }
            }
            reverse()
        }.also { updateTabViewLabel() }
    }

    fun requestNewTab(tab: Tab) {
        globalClass.mainActivityManager.addTabAndSelect(tab)
    }

    fun requestHomeToolbarUpdate() {
        CoroutineScope(Dispatchers.Main).launch {
            globalClass.mainActivityManager.title = title
            globalClass.mainActivityManager.subtitle = subtitle
        }
    }

    fun addNewTask(task: RegularTabTask) {
        globalClass.regularTabManager.regularTabTasks.add(task)
        globalClass.showMsg(R.string.new_task_has_been_added)
    }

    fun hideDocumentOptionsMenu() {
        FileOptionsDialog.hide()
    }

    fun deleteFiles(
        targetFiles: List<DocumentHolder>,
        taskCallback: RegularTabTaskCallback,
        moveToRecycleBin: Boolean = true
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            RegularTabDeleteTask(targetFiles, moveToRecycleBin).execute(activeFolder, taskCallback)
        }
    }

    fun share(
        context: Context,
        targetDocumentHolder: DocumentHolder
    ) {
        val uris = arrayListOf<Uri>()
        selectedFiles.forEach {
            val file = it.component2().toFile()
            if (file != null) {
                uris.add(
                    getUriForFile(
                        context,
                        globalClass.packageName + ".provider",
                        file
                    )
                )
            } else {
                uris.add(it.component2().getUri())
            }
        }

        val builder = ShareCompat.IntentBuilder(globalClass)
            .setType(if (uris.size == 1) targetDocumentHolder.getMimeType() else anyFileType)
        uris.forEach {
            builder.addStream(it)
        }

        context.startActivity(
            builder.intent.apply {
                if (uris.size > 1) action = Intent.ACTION_SEND_MULTIPLE

                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        )
    }

    fun addToHomeScreen(context: Context, file: DocumentHolder) {
        val shortcutManager = context.getSystemService(ShortcutManager::class.java)
        val pinShortcutInfo = ShortcutInfo
            .Builder(context, file.getPath())
            .setIntent(
                Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    putExtra("filePath", file.getPath())
                    flags = Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
                }
            )
            .setIcon(
                android.graphics.drawable.Icon.createWithResource(
                    context,
                    if (file.isFile()) R.mipmap.default_shortcut_icon else R.mipmap.folder_shortcut_icon
                )
            )
            .setShortLabel(file.getFileName())
            .build()
        val pinnedShortcutCallbackIntent =
            shortcutManager.createShortcutResultIntent(pinShortcutInfo)
        shortcutManager.requestPinShortcut(
            pinShortcutInfo,
            PendingIntent.getBroadcast(
                context,
                0,
                pinnedShortcutCallbackIntent,
                PendingIntent.FLAG_IMMUTABLE
            ).intentSender
        )
    }

    object TaskDialog {
        var showTaskDialog by mutableStateOf(false)
        var taskDialogTitle by mutableStateOf(emptyString)
        var taskDialogSubtitle by mutableStateOf(emptyString)
        var taskDialogInfo by mutableStateOf(emptyString)
        var showTaskDialogProgressbar by mutableStateOf(true)
        var taskDialogProgress by mutableFloatStateOf(-1f)
    }

    object ApkDialog {
        var showApkDialog by mutableStateOf(false)
            private set
        var apkFile: DocumentHolder? = null
            private set

        fun show(file: DocumentHolder) {
            apkFile = file
            showApkDialog = true
        }

        fun hide() {
            showApkDialog = false
            apkFile = null
        }
    }

    object CompressDialog {
        var showCompressDialog by mutableStateOf(false)
            private set
        var task: RegularTabCompressTask? = null
            private set

        fun show(task: RegularTabCompressTask) {
            CompressDialog.task = task
            showCompressDialog = true
        }

        fun hide() {
            showCompressDialog = false
            task = null
        }
    }

    object RenameDialog {
        var showRenameFileDialog by mutableStateOf(false)
            private set
        var targetFile: DocumentHolder? = null
            private set

        fun show(file: DocumentHolder) {
            targetFile = file
            showRenameFileDialog = true
        }

        fun hide() {
            showRenameFileDialog = false
            targetFile = null
        }
    }

    object FileOptionsDialog {
        var showFileOptionsDialog by mutableStateOf(false)
            private set
        var targetFile: DocumentHolder? = null
            private set

        fun show(file: DocumentHolder) {
            targetFile = file
            showFileOptionsDialog = true
        }

        fun hide() {
            showFileOptionsDialog = false
            targetFile = null
        }
    }

    object OpenWithDialog {
        var showOpenWithDialog by mutableStateOf(false)
            private set
        var targetFile: DocumentHolder? = null
            private set

        fun show(file: DocumentHolder) {
            targetFile = file
            showOpenWithDialog = true
        }

        fun hide() {
            showOpenWithDialog = false
            targetFile = null
        }
    }

    object Search {
        var searchQuery by mutableStateOf(emptyString)
        var searchResults = mutableStateListOf<DocumentHolder>()
    }

    val taskCallback = object : RegularTabTaskCallback(CoroutineScope(Dispatchers.IO)) {
        override fun onPrepare(details: RegularTabTaskDetails) {
            taskDialog.apply {
                showTaskDialog = true
                taskDialogTitle = details.title
                taskDialogSubtitle = details.subtitle
                showTaskDialogProgressbar = true
                taskDialogProgress = details.progress
                taskDialogInfo = details.info
            }
        }

        override fun onReport(details: RegularTabTaskDetails) {
            taskDialog.apply {
                taskDialogTitle = details.title
                taskDialogSubtitle = details.subtitle
                taskDialogProgress = details.progress
                taskDialogInfo = details.info
            }
        }

        override fun onComplete(details: RegularTabTaskDetails) {
            highlightedFiles.clear()

            details.task.getSourceFiles().forEach {
                activeFolder.findFile(it.getFileName())?.let { file ->
                    highlightedFiles.addIfAbsent(file.getPath())
                }
            }

            globalClass.showMsg(buildString {
                append(details.subtitle)
            })

            TaskDialog.showTaskDialog = false
            showTasksPanel = false

            reloadFiles()

            globalClass.regularTabManager.regularTabTasks.removeIf { it.id == details.task.id }
        }

        override fun onFailed(details: RegularTabTaskDetails) {
            globalClass.showMsg(details.subtitle)
            TaskDialog.showTaskDialog = false
            showTasksPanel = false
            reloadFiles()
        }
    }
}
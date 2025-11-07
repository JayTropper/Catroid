/*
 * Catroid: An on-device visual programming system for Android devices
 * Copyright (C) 2010-2025 The Catrobat Team
 * (<http://developer.catrobat.org/credits>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * An additional term exception under section 7 of the GNU Affero
 * General Public License, version 3, is available at
 * http://developer.catrobat.org/license_additional_term
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.catrobat.catroid.ui.recyclerview.fragment

import android.Manifest.permission
import android.annotation.SuppressLint
import android.app.Activity.RESULT_OK
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.preference.PreferenceManager
import android.provider.DocumentsContract
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.PluralsRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.documentfile.provider.DocumentFile
import org.catrobat.catroid.ProjectManager
import org.catrobat.catroid.R
import org.catrobat.catroid.common.Constants
import org.catrobat.catroid.common.FlavoredConstants
import org.catrobat.catroid.common.ProjectData
import org.catrobat.catroid.common.ProjectType
import org.catrobat.catroid.common.SharedPreferenceKeys
import org.catrobat.catroid.content.backwardcompatibility.ProjectMetaDataParser
import org.catrobat.catroid.exceptions.LoadingProjectException
import org.catrobat.catroid.io.StorageOperations
import org.catrobat.catroid.io.XstreamSerializer
import org.catrobat.catroid.io.asynctask.ProjectCopier
import org.catrobat.catroid.io.asynctask.ProjectLoader
import org.catrobat.catroid.io.asynctask.ProjectLoader.ProjectLoadListener
import org.catrobat.catroid.io.asynctask.ProjectRenamer
import org.catrobat.catroid.io.asynctask.ProjectUnZipperAndImporter
import org.catrobat.catroid.stage.godot.RunGodotGameActivity
import org.catrobat.catroid.ui.BottomBar
import org.catrobat.catroid.ui.ProjectActivity
import org.catrobat.catroid.ui.ProjectListActivity
import org.catrobat.catroid.ui.UiUtils
import org.catrobat.catroid.ui.filepicker.FilePickerActivity
import org.catrobat.catroid.ui.fragment.ProjectOptionsFragment
import org.catrobat.catroid.ui.recyclerview.adapter.ProjectAdapter
import org.catrobat.catroid.ui.recyclerview.adapter.RVAdapter
import org.catrobat.catroid.ui.recyclerview.adapter.multiselection.MultiSelectionManager
import org.catrobat.catroid.ui.recyclerview.viewholder.CheckableViewHolder
import org.catrobat.catroid.ui.runtimepermissions.RequiresPermissionTask
import org.catrobat.catroid.utils.ToastUtil
import org.koin.android.ext.android.inject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.locks.ReentrantLock

@SuppressLint("NotifyDataSetChanged")
class ProjectListFragment : RecyclerViewFragment<ProjectData?>(), ProjectLoadListener {
    private var items: MutableList<ProjectData> = ArrayList()

    private var filesForUnzipAndImportTask: ArrayList<File>? = null
    private var hasUnzipAndImportTaskFinished = false

    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    private var filesForImportTask: ArrayList<File>? = null

    private val projectManager: ProjectManager by inject()

    private val lock = ReentrantLock()

    override fun onActivityCreated(savedInstance: Bundle?) {
        super.onActivityCreated(savedInstance)
        filesForUnzipAndImportTask = ArrayList()
        filesForImportTask = ArrayList()
        hasUnzipAndImportTaskFinished = true
        if (arguments != null) {
            importProject(requireArguments().getParcelable("intent"), ProjectType.CATROBAT)
        }
        if (requireActivity().intent?.hasExtra(ProjectListActivity.IMPORT_LOCAL_INTENT) == true) {
            adapter.showSettings = false
            actionModeType = IMPORT_LOCAL
        }
    }

    private fun onImportProjectFinished(success: Boolean) {
        if (!success) {
            ToastUtil.showError(requireContext(), R.string.error_import_project)
        } else {
            ToastUtil.showSuccess(
                requireContext(),
                resources.getQuantityString(
                    R.plurals.imported_projects,
                    filesForUnzipAndImportTask?.size?.plus(filesForImportTask?.size!!) ?: 0,
                    filesForUnzipAndImportTask?.size?.plus(filesForImportTask?.size!!) ?: 0
                )
            )
        }

        getLocalProjectListAsync(object: LoadProjectsListener {
            override fun onProjectsLoaded() {
                setAdapterItems(adapter.projectsSorted)
                filesForUnzipAndImportTask?.clear()
                filesForImportTask?.clear()
                setShowProgressBar(false)
            }
        })
    }

    private fun onRenameFinished(success: Boolean) {
        if (success) {
            if (hasUnzipAndImportTaskFinished) {
                ToastUtil.showSuccess(
                    requireContext(),
                    getString(R.string.renamed_project)
                )
                filesForUnzipAndImportTask?.clear()
            }

            getLocalProjectListAsync(object: LoadProjectsListener {
                override fun onProjectsLoaded() {
                    setAdapterItems(adapter.projectsSorted)
                    setShowProgressBar(false)
                }
            })
        } else {
            ToastUtil.showError(requireContext(), R.string.error_rename_incompatible_project)
        }
    }

    override fun onResume() {
        if (actionModeType != IMPORT_LOCAL) {
            projectManager.currentProject = null
        }

        if (adapter != null) {
            setAdapterItems(adapter.projectsSorted)
            checkForEmptyList()
        }

        getLocalProjectListAsync(object: LoadProjectsListener {
            override fun onProjectsLoaded() {
                if (adapter != null) {
                    setAdapterItems(adapter.projectsSorted)
                    checkForEmptyList()
                    setShowProgressBar(false)
                }
            }
        })

        BottomBar.showBottomBar(requireActivity())
        super.onResume()
    }

    override fun initializeAdapter() {
        getLocalProjectListAsync(object: LoadProjectsListener {
            override fun onProjectsLoaded() {
                sharedPreferenceDetailsKey = SharedPreferenceKeys.SHOW_DETAILS_PROJECTS_PREFERENCE_KEY
                adapter = ProjectAdapter(items)
                onAdapterReady()
            }
        })
    }

    private fun getSortedItemList(): MutableList<ProjectData> {
        val sortedItems = items.toMutableList()
        sortedItems.sortWith(Comparator { project1: ProjectData, project2: ProjectData ->
            project1.name.compareTo(
                project2.name
            )
        })
        return sortedItems
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.import_catrobat_project -> showCatroidImportChooser()
            R.id.import_godot_project -> showGodotImportChooser()
            R.id.sort_projects -> sortProjects()
            R.id.start_godot_game -> startGodotGame()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun sortProjects() {
        if (adapter != null) {
            adapter.projectsSorted = !adapter.projectsSorted
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit()
                .putBoolean(
                    SharedPreferenceKeys.SORT_PROJECTS_PREFERENCE_KEY,
                    adapter.projectsSorted
                )
                .apply()
            setAdapterItems(adapter.projectsSorted)
        }
    }

    private fun showCatroidImportChooser() {
        setShowProgressBar(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            importUsingSystemFilePicker()
        } else {
            importUsingFilePickerActivity()
        }
    }

    private fun startGodotGame() {
//        val intent = Intent(this.context, GodotStageActivity::class.java)
//        startActivity(intent)

        val godotActivity = RunGodotGameActivity()
        this.context?.let {
            godotActivity.onNewGodotInstanceRequested(
                it, args = arrayOf(
                    "--path",
                    "/storage/emulated/0/Android/data/org.catrobat" +
                        ".catroid/files/godot_minimal_project"
                    /*"/storage/emulated/0/Documents/godot_minimal_project",
                    "--editor-pid",
                    "23565",
                    "--position",
                    "0,0",
                    "res://main.tscn"*/
                )
            )
        }
    }

    private fun startGodotGame(godotDirectory: File) {
        val godotActivity = RunGodotGameActivity()
        val path = godotDirectory.path

        if (!godotDirectory.exists()) {
            Log.d(TAG, "The passed Godot directory does not exist.")
            return
        }

        this.context?.let {
            godotActivity.onNewGodotInstanceRequested(it, args = arrayOf("--path", path))
        }
    }

    private val importCatrobatLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                importProject(result.data, ProjectType.CATROBAT)
            }
        }

    private fun importUsingSystemFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, Environment.DIRECTORY_DOWNLOADS)
        }
        val title = requireContext().resources.getString(R.string.import_project)
        importCatrobatLauncher.launch(Intent.createChooser(intent, title))
    }

    private fun importUsingFilePickerActivity() {
        object : RequiresPermissionTask(
            PERMISSIONS_REQUEST_IMPORT_FROM_EXTERNAL_STORAGE,
            listOf(permission.READ_EXTERNAL_STORAGE),
            R.string.runtime_permission_general
        ) {
            override fun task() {
                importCatrobatLauncher.launch(Intent(requireContext(), FilePickerActivity::class.java))
            }
        }.execute(requireActivity())
    }

    private fun importProject(data: Intent?, projectType: ProjectType) {
        if (data == null) {
            onImportError()
            return
        }
        var uris: ArrayList<Uri> = ArrayList()
        if (data.data == null && !data.hasExtra(Intent.EXTRA_STREAM) && data.clipData == null) {
            onImportError()
            return
        }
        if (data.hasExtra(Intent.EXTRA_STREAM)) {
            uris = data.extras?.get(Intent.EXTRA_STREAM) as ArrayList<Uri>
        } else {
            extractAllUris(data, uris)
        }
        try {
            if (projectType == ProjectType.CATROBAT) {
                importCatrobatProjectUris(uris)
            } else if (projectType == ProjectType.GODOT) {
                importGodotProjectUris(uris)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Cannot resolve project to import.", e)
        }
    }

    private fun onImportError() {
        setShowProgressBar(false)
        ToastUtil.showError(requireContext(), R.string.error_import_project)
    }

    private fun extractAllUris(data: Intent, uris: ArrayList<Uri>) {
        val singleUri = data.data
        if (singleUri != null) {
            uris.add(singleUri)
        } else {
            val clipData = data.clipData
            if (clipData != null) {
                val itemCount = clipData.itemCount
                for (idx in 0 until itemCount) {
                    uris.add(clipData.getItemAt(idx).uri)
                }
            }
        }
    }

    private fun importCatrobatProjectUris(uris: ArrayList<Uri>) {
        prepareCatrobatFilesForImport(uris)
        filesForUnzipAndImportTask?.apply {
            if (isNotEmpty()) {
                setShowProgressBar(true)
                val filesToUnzipAndImport = filesForUnzipAndImportTask?.toTypedArray() ?: arrayOf()
                ProjectUnZipperAndImporter({ success: Boolean -> onImportProjectFinished(success) })
                    .unZipAndImportAsync(filesToUnzipAndImport)
            }
        }
    }

    private fun prepareCatrobatFilesForImport(urisToImport: ArrayList<Uri>) {
        for (uri in urisToImport) {
            val contentResolver = requireActivity().contentResolver
            var fileName = StorageOperations.resolveFileName(contentResolver, uri)
            if (!fileName.contains(Constants.CATROBAT_EXTENSION)) {
                ToastUtil.showError(requireContext(), R.string.only_select_catrobat_files)
                continue
            }
            fileName = fileName.replace(Constants.CATROBAT_EXTENSION, Constants.ZIP_EXTENSION)
            copyCatrobatFileContentToCacheFile(uri, fileName)
        }
    }

    private fun copyCatrobatFileContentToCacheFile(uri: Uri, fileName: String) {
        val projectFile = StorageOperations.copyUriToDir(
            requireActivity().contentResolver, uri,
            Constants.CACHE_DIRECTORY, fileName
        )
        filesForUnzipAndImportTask?.add(projectFile)
        hasUnzipAndImportTaskFinished = false
    }

    override fun prepareActionMode(@ActionModeType type: Int) {
        if (type == COPY) {
            adapter.selectionMode = RVAdapter.MULTIPLE
        } else if (type == MERGE) {
            adapter.selectionMode = RVAdapter.PAIRS
        }
        super.prepareActionMode(type)
    }

    override fun packItems(selectedItems: MutableList<ProjectData?>?) {
        throw IllegalStateException("$TAG: Projects cannot be backpacked")
    }

    override fun isBackpackEmpty(): Boolean = true

    override fun switchToBackpack() {
        throw IllegalStateException("$TAG: Projects cannot be backpacked")
    }

    override fun copyItems(selectedItems: MutableList<ProjectData?>?) {
        finishActionMode()
        setShowProgressBar(true)
        selectedItems ?: return
        val usedProjectNames = ArrayList(adapter.items)
        for (projectData in selectedItems) {
            projectData ?: continue
            val name = uniqueNameProvider.getUniqueNameInNameables(projectData.name, usedProjectNames)
            usedProjectNames.add(ProjectData(name, null, 0.0, false, ProjectType.CATROBAT))
            val projectCopier = ProjectCopier(projectData.directory, name)
            projectCopier.copyProjectAsync({ success: Boolean -> onCopyProjectComplete(success) })
        }
    }

    @PluralsRes
    override fun getDeleteAlertTitleId(): Int = R.plurals.delete_projects

    override fun deleteItems(selectedItems: MutableList<ProjectData?>?) {
        setShowProgressBar(true)
        var deletedItemCount = 0
        selectedItems ?: return
        for (item in selectedItems) {
            item ?: continue
            try {
                projectManager.deleteDownloadedProjectInformation(item.name)
                StorageOperations.deleteDir(item.directory)
                items.remove(item)
            } catch (e: IOException) {
                Log.e(TAG, Log.getStackTraceString(e))
                items.remove(item)
            }
            adapter.remove(item)
            deletedItemCount++
        }
        ToastUtil.showSuccess(
            requireContext(), resources.getQuantityString(
                R.plurals.deleted_projects,
                deletedItemCount,
                deletedItemCount
            )
        )
        finishActionMode()
        setAdapterItems(adapter.projectsSorted)
        checkForEmptyList()
    }

    private fun checkForEmptyList() {
        if (adapter.items.isEmpty()) {
            setShowProgressBar(true)
            if (projectManager.initializeDefaultProject()) {
                setAdapterItems(adapter.projectsSorted)
                setShowProgressBar(false)
            } else {
                ToastUtil.showError(requireContext(), R.string.wtf_error)
                requireActivity().finish()
            }
        }
    }

    override fun getRenameDialogTitle(): Int = R.string.rename_project

    override fun getRenameDialogHint(): Int = R.string.project_name_label

    override fun renameItem(item: ProjectData?, name: String?) {
        finishActionMode()
        item ?: return
        name ?: return
        if (name != item.name) {
            setShowProgressBar(true)
            ProjectRenamer(item.directory, name)
                .renameProjectAsync({ success: Boolean -> onRenameFinished(success) })
        }
    }

    override fun onLoadFinished(success: Boolean) {
        if (success) {
            val intent = Intent(requireContext(), ProjectActivity::class.java)
            intent.putExtra(
                ProjectActivity.EXTRA_FRAGMENT_POSITION,
                ProjectActivity.FRAGMENT_SCENES
            )
            startActivity(intent)
        } else {
            setShowProgressBar(false)
            ToastUtil.showError(requireContext(), R.string.error_load_project)
        }
    }

    private fun onCopyProjectComplete(success: Boolean) {
        if (success) {
            getLocalProjectListAsync(object: LoadProjectsListener {
                override fun onProjectsLoaded() {
                    setAdapterItems(adapter.projectsSorted)
                    setShowProgressBar(false)
                }
            })
        } else {
            ToastUtil.showError(requireContext(), R.string.error_copy_project)
        }
    }

    override fun onItemClick(item: ProjectData?, selectionManager: MultiSelectionManager?) {
        when (actionModeType) {
            RENAME -> {
                super.onItemClick(item, null)
                return
            }
            NONE -> {
                setShowProgressBar(true)
                val directoryFile = item?.directory ?: return
                if (item.projectType.equals(ProjectType.CATROBAT)) {
                    ProjectLoader(directoryFile, requireContext()).setListener(this).loadProjectAsync()
                } else if (item.projectType.equals(ProjectType.GODOT)) {
                    startGodotGame(item.directory)
                }
            }
            IMPORT_LOCAL -> {
                val intent = Intent()
                intent.putExtra(
                    ProjectListActivity.IMPORT_LOCAL_INTENT,
                    item?.directory?.absoluteFile?.absolutePath
                )
                requireActivity().setResult(RESULT_OK, intent)
                requireActivity().finish()
            }
            else -> super.onItemClick(item, selectionManager)
        }
    }

    override fun onItemLongClick(item: ProjectData?, holder: CheckableViewHolder?) {
        onItemClick(item, null)
    }

    override fun onSettingsClick(item: ProjectData?, view: View?) {
        val itemList: MutableList<ProjectData?> = ArrayList()
        itemList.add(item)
        val hiddenMenuOptionIds = intArrayOf(
            R.id.new_group, R.id.new_scene, R.id.show_details,
            R.id.from_local, R.id.edit
        )
        val popupMenu = UiUtils.createSettingsPopUpMenu(
            view, requireContext(),
            R.menu.menu_project_activity, hiddenMenuOptionIds
        )
        popupMenu.setOnMenuItemClickListener { menuItem: MenuItem ->
            when (menuItem.itemId) {
                R.id.copy -> copyItems(itemList)
                R.id.rename -> showRenameDialog(item)
                R.id.delete -> deleteItems(itemList)
                R.id.project_options -> showProjectOptionsFragment(item)
            }
            true
        }
        popupMenu.show()
    }

    private fun showProjectOptionsFragment(item: ProjectData?) {
        item ?: return
        try {
            val project = XstreamSerializer.getInstance().loadProject(
                item.directory,
                requireContext()
            )
            projectManager.currentProject = project
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(
                    R.id.fragment_container, ProjectOptionsFragment(), ProjectOptionsFragment.TAG
                )
                .addToBackStack(ProjectOptionsFragment.TAG)
                .commit()
        } catch (exception: IOException) {
            ToastUtil.showError(requireContext(), R.string.error_load_project)
            Log.e(TAG, Log.getStackTraceString(exception))
        } catch (exception: LoadingProjectException) {
            ToastUtil.showError(requireContext(), R.string.error_load_project)
            Log.e(TAG, Log.getStackTraceString(exception))
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        if (adapter != null) {
            adapter.projectsSorted = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getBoolean(SharedPreferenceKeys.SORT_PROJECTS_PREFERENCE_KEY, false)
            menu.findItem(R.id.sort_projects)
                .setTitle(
                    if (adapter.projectsSorted) {
                        R.string.unsort_projects
                    } else {
                        R.string.sort_projects
                    }
                )
        }

    }

    private fun setAdapterItems(sortProjects: Boolean) {
        if (sortProjects) {
            adapter.setItems(getSortedItemList().toList())
        } else {
            adapter.setItems(items.toList())
        }
        adapter.notifyDataSetChanged()
    }

    /**
     * Godot import methods
     */
    private fun showGodotImportChooser() {
        setShowProgressBar(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            importUsingSystemFolderPicker()
        } else {
            ToastUtil.showError(requireContext(), R.string.outdated_android_version_godot)
        }
    }

    private fun importUsingSystemFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, Environment.DIRECTORY_DOWNLOADS)
        }
        importGodotLauncher.launch(intent)
    }

    private val importGodotLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                importProject(result.data, ProjectType.GODOT)
            }
        }

    private fun importGodotProjectUris(uris: ArrayList<Uri>) {
        if (!prepareGodotFilesForImport(uris)) {
            return
        }
        filesForImportTask?.apply {
            if (isNotEmpty()) {
                val filesToImport = filesForImportTask?.toTypedArray() ?: arrayOf()
                ProjectUnZipperAndImporter({ success: Boolean -> onImportProjectFinished(success) })
                    .importAsync(filesToImport)
            }
        }
    }

    private fun prepareGodotFilesForImport(urisToImport: ArrayList<Uri>): Boolean {
        for (uri in urisToImport) {
            val projectDirectory = requireContext().let { DocumentFile.fromTreeUri(it, uri) }
            val projectFile = projectDirectory?.findFile(Constants.GODOT_PROJECT_FILE_NAME)

            if (projectFile == null || !projectFile.isFile) {
                ToastUtil.showError(requireContext(), R.string.only_select_godot_projects)
                continue
            }
            if (!projectDirectory.name?.let { copyGodotFileContentToCacheFile(uri, it) }!!) {
                return false
            }
        }
        return true
    }

    private fun copyGodotFileContentToCacheFile(uri: Uri, projectName: String): Boolean {
        val folder = context?.let { DocumentFile.fromTreeUri(it, uri) } ?: return false
        // TODO: take care of same sames as well
        val projectDirectory = File(Constants.CACHE_DIRECTORY.path + "/" + projectName)
        val projectFile = File(projectDirectory.path + "/" + Constants.GODOT_PROJECT_FILE_NAME)
        filesForImportTask?.add(projectFile)
        return copyProjectRecursively(folder, projectDirectory)
    }

    private fun copyProjectRecursively(sourceFolder: DocumentFile, destFolder: File): Boolean {
        if (!destFolder.exists() && !destFolder.mkdirs()) {
            return false
        }

        for (file in sourceFolder.listFiles()) {
            val target = File(destFolder, file.name ?: continue)

            if (file.isDirectory && !copyProjectRecursively(file, target)) return false
            else if (file.isFile) {
                try {
                    requireContext().contentResolver.openInputStream(file.uri).use { input ->
                        target.outputStream().use { output ->
                            if (input != null) {
                                copyStream(input, output)
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    return false
                }
            }
        }
        return true
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(4096)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
        }
    }

    private fun getLocalProjectListAsync(callback: LoadProjectsListener) {
        coroutineScope.launch {
            val newItems: MutableList<ProjectData> = ArrayList()

            lock.lock()
            getLocalProjectList(newItems)
            newItems.sortWith(Comparator { project1: ProjectData, project2: ProjectData -> project2.lastUsed.compareTo(project1.lastUsed) })
            items = newItems
            lock.unlock()

            withContext(Dispatchers.Main) {
                callback.onProjectsLoaded()
            }
        }
    }

    interface ProjectImportFinishedListener {
        fun notifyActivityFinished(success: Boolean)
    }

    interface LoadProjectsListener {
        fun onProjectsLoaded()
    }

    companion object {
        @JvmStatic
        val TAG: String = ProjectListFragment::class.java.simpleName
        private const val PERMISSIONS_REQUEST_IMPORT_FROM_EXTERNAL_STORAGE = 801

        @JvmStatic
        fun getLocalProjectList(items: MutableList<ProjectData>) {
            FlavoredConstants.DEFAULT_ROOT_DIRECTORY.listFiles()?.forEach { projectDir ->
                val xmlFile = File(projectDir, Constants.CODE_XML_FILE_NAME)
                val godotFile = File(projectDir, Constants.GODOT_PROJECT_FILE_NAME)

                if (!xmlFile.exists() && !godotFile.exists()) {
                    return@forEach
                }

                val metaDataParser = if (godotFile.exists()) {
                    ProjectMetaDataParser(godotFile, ProjectType.GODOT)
                } else {
                    ProjectMetaDataParser(xmlFile, ProjectType.CATROBAT)
                }
                try {
                    items.add(metaDataParser.projectMetaData)
                } catch (exception: IOException) {
                    Log.e(TAG, "Could no parse local project.", exception)
                }
            }
        }
    }
}

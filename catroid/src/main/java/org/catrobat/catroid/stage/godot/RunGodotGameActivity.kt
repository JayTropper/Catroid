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

package org.catrobat.catroid.stage.godot

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.util.Log
import androidx.annotation.CallSuper
import org.godotengine.godot.GodotActivity
import org.godotengine.godot.utils.PermissionsUtil
import org.godotengine.godot.utils.ProcessPhoenix
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.window.layout.WindowMetricsCalculator
import org.catrobat.catroid.ui.ProjectListActivity
import org.godotengine.godot.BuildConfig
import org.godotengine.godot.Godot
import org.godotengine.godot.GodotLib
import java.util.ArrayList
import kotlin.math.min

open class RunGodotGameActivity : GodotActivity() {

    companion object {
        private val TAG = RunGodotGameActivity::class.java.simpleName

        private const val WAIT_FOR_DEBUGGER = false

        private const val EXTRA_COMMAND_LINE_PARAMS = "command_line_params"

        // Info for the various classes used by the editor
        internal val EDITOR_MAIN_INFO = EditorWindowInfo(RunGodotGameActivity::class.java, 777, "")
        internal val RUN_GAME_INFO = EditorWindowInfo(GodotGame::class.java, 667, ":GodotGame", LaunchAdjacentPolicy.AUTO)
    }

    private val commandLineParams = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        // We exclude certain permissions from the set we request at startup, as they'll be
        // requested on demand based on use-cases.
        PermissionsUtil.requestManifestPermissions(this, setOf(Manifest.permission.RECORD_AUDIO))

        val params = intent.getStringArrayExtra(EXTRA_COMMAND_LINE_PARAMS)
        Log.d(TAG, "Starting intent $intent with parameters ${params.contentToString()}")
        updateCommandLineParams(params?.asList() ?: emptyList())

        if (BuildConfig.BUILD_TYPE == "dev" && WAIT_FOR_DEBUGGER) {
            Debug.waitForDebugger()
        }

        super.onCreate(savedInstanceState)
    }

    override fun onGodotSetupCompleted() {
        super.onGodotSetupCompleted()
        val longPressEnabled = enableLongPressGestures()
        val panScaleEnabled = enablePanAndScaleGestures()

        checkForProjectPermissionsToEnable()

        runOnUiThread {
            // Enable long press, panning and scaling gestures
            godotFragment?.godot?.renderView?.inputHandler?.apply {
                enableLongPress(longPressEnabled)
                enablePanningAndScalingGestures(panScaleEnabled)
            }
        }
    }

    fun onNewGodotInstanceRequested(context: Context, args: Array<String>) {
        val windowClassName = "org.godotengine.editor.GodotGame"
        // Launch a new activity
        val newInstance = Intent(context, this::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_COMMAND_LINE_PARAMS, args)

        if (windowClassName == javaClass.name) {
            Log.d(TAG, "Restarting ${windowClassName} with parameters ${args.contentToString()}")
            val godot = godot
            if (godot != null) {
                godot.destroyAndKillProcess {
                    ProcessPhoenix.triggerRebirth(this, newInstance)
                }
            } else {
                ProcessPhoenix.triggerRebirth(this, newInstance)
            }
        } else {
            Log.d(TAG, "Starting ${windowClassName} with parameters ${args.contentToString()}")
            newInstance.putExtra(EXTRA_NEW_LAUNCH, true)
            context.startActivity(newInstance)
        }
    }

    override fun onGodotForceQuit(instance: Godot) {
        super.onGodotForceQuit(instance)
        val intent = Intent(applicationContext, ProjectListActivity::class.java)
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        val intent = Intent(applicationContext, ProjectListActivity::class.java)
        startActivity(intent)
    }

    final override fun onGodotForceQuit(godotInstanceId: Int): Boolean {
        val editorWindowInfo = getEditorWindowInfoForInstanceId(godotInstanceId) ?: return super
            .onGodotForceQuit(godotInstanceId)

        if (editorWindowInfo.windowClassName == javaClass.name) {
            Log.d(TAG, "Force quitting ${editorWindowInfo.windowClassName}")
            ProcessPhoenix.forceQuit(this)

            val intent = Intent(baseContext, ProjectListActivity::class.java)
            startActivity(intent)

            return true
        }

        val processName = packageName + editorWindowInfo.processNameSuffix
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningProcesses = activityManager.runningAppProcesses
        for (runningProcess in runningProcesses) {
            if (runningProcess.processName == processName) {
                // Killing process directly
                Log.v(TAG, "Killing Godot process ${runningProcess.processName}")
                Process.killProcess(runningProcess.pid)
                return true
            }
        }

        val intent = Intent(baseContext, ProjectListActivity::class.java)
        startActivity(intent)

        return super.onGodotForceQuit(godotInstanceId)
    }

    // Get the screen's density scale
    private val isLargeScreen: Boolean
        // Get the minimum window size // Correspond to the EXPANDED window size class.
        get() {
            val metrics = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(this)

            // Get the screen's density scale
            val scale = resources.displayMetrics.density

            // Get the minimum window size
            val minSize = min(metrics.bounds.width(), metrics.bounds.height()).toFloat()
            val minSizeDp = minSize / scale
            return minSizeDp >= 840f // Correspond to the EXPANDED window size class.
        }

    override fun setRequestedOrientation(requestedOrientation: Int) {
        if (!overrideOrientationRequest()) {
            super.setRequestedOrientation(requestedOrientation)
        }
    }

    /**
     * The Godot Android Editor sets its own orientation via its AndroidManifest
     */
    protected open fun overrideOrientationRequest() = true

    /**
     * Check for project permissions to enable
     */
    protected open fun checkForProjectPermissionsToEnable() {
        // Check for RECORD_AUDIO permission
        val audioInputEnabled = java.lang.Boolean.parseBoolean(GodotLib.getGlobal("audio/driver/enable_input"))
        if (audioInputEnabled) {
            PermissionsUtil.requestPermission(Manifest.permission.RECORD_AUDIO, this)
        }
    }

    @CallSuper
    protected open fun updateCommandLineParams(args: List<String>) {
        // Update the list of command line params with the new args
        commandLineParams.clear()
        if (args.isNotEmpty()) {
            commandLineParams.addAll(args)
        }
        if (BuildConfig.BUILD_TYPE == "dev") {
            commandLineParams.add("--benchmark")
        }
    }

    protected open fun getEditorWindowInfoForInstanceId(instanceId: Int): EditorWindowInfo? {
        return when (instanceId) {
            RUN_GAME_INFO.windowId -> RUN_GAME_INFO
            EDITOR_MAIN_INFO.windowId -> EDITOR_MAIN_INFO
            else -> null
        }
    }

    /**
     * Enable long press gestures for the Godot Android editor.
     */
    protected open fun enableLongPressGestures() =
        java.lang.Boolean.parseBoolean(GodotLib.getEditorSetting("interface/touchscreen/enable_long_press_as_right_click"))

    /**
     * Enable pan and scale gestures for the Godot Android editor.
     */
    protected open fun enablePanAndScaleGestures() =
        java.lang.Boolean.parseBoolean(GodotLib.getEditorSetting("interface/touchscreen/enable_pan_and_scale_gestures"))
}
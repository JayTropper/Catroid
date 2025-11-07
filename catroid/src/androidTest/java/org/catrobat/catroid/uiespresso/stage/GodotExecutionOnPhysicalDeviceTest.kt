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

package org.catrobat.catroid.uiespresso.stage

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.catrobat.catroid.common.Constants
import org.catrobat.catroid.stage.godot.RunGodotGameActivity
import org.catrobat.catroid.testsuites.annotations.Cat.AppUi
import org.catrobat.catroid.testsuites.annotations.Level.Smoke
import org.catrobat.catroid.ui.recyclerview.fragment.ProjectListFragment.Companion.TAG
import org.catrobat.catroid.uiespresso.util.rules.BaseActivityTestRule
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File

/**
 * Since Godot only works on a physical device due to Vulkan, this test also only works on
 * on a physical device. To run this test successfully, connect your phone to your computer and
 * run this test on it.
  */
@Category(AppUi::class, Smoke::class)
class GodotExecutionOnPhysicalDeviceTest {

    @Rule
    @JvmField
    var baseActivityTestRule: BaseActivityTestRule<RunGodotGameActivity> = BaseActivityTestRule(
        RunGodotGameActivity::class.java, true, false
    )

    @Test
    fun testGodotLaunch() {
        launchActivity()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        // Wait a bit for the Godot game to render
        Thread.sleep(1000)

        val screenshotName = "test_screenshot.png"
        val screenshotFile = File(Constants.CACHE_DIRECTORY.absolutePath, screenshotName)
        screenshotFile.delete()
        val success = device.takeScreenshot(screenshotFile)
        require(success)

        val bitmap = BitmapFactory.decodeFile(screenshotFile.absolutePath)
            ?: error("Failed to decode screenshot bitmap")
        // Check the center pixel
        val centerX = bitmap.width / 2
        val centerY = bitmap.height / 2
        val pixel = bitmap.getPixel(centerX, centerY)

        val red = (pixel shr 16) and 0xFF
        val green = (pixel shr 8) and 0xFF
        val blue = pixel and 0xFF

        assert(red == 0 && green == 255 && blue == 0) {
            "Expected green but got R:$red G:$green B:$blue"
        }
    }

    private fun launchActivity() {
        baseActivityTestRule.launchActivity(null)
        val context = InstrumentationRegistry.getInstrumentation().context
        val godotDirectory: File = copyGodotDirToCache(context)

        if (!godotDirectory.exists()) {
            Log.d(TAG, "The passed Godot directory does not exist.")
            return
        }

        context?.let {
            baseActivityTestRule.activity.onNewGodotInstanceRequested(
                it, args = arrayOf("--path", godotDirectory.path)
            )
        }
    }

    private fun copyGodotDirToCache(context: Context): File {
        val assetDir = "GodotMinimalProject"
        val cacheDir = File(context.cacheDir, assetDir)
        cacheDir.mkdirs()

        val assetManager = context.assets
        val files = assetManager.list(assetDir) ?: return cacheDir

        for (file in files) {
            val inStream = assetManager.open("$assetDir/$file")
            val outFile = File(cacheDir, file)
            inStream.use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        return cacheDir
    }
}
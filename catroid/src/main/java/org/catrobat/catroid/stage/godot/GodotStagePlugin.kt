/*
 * Catroid: An on-device visual programming system for Android devices
 * Copyright (C) 2010-2024 The Catrobat Team
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

import android.util.Log
import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.plugin.SignalInfo

/**
 * This class is the required connection from pocket code to godot.
 * It enables the sending of signals to the running godot game, but not vice versa.
 */
class GodotStagePlugin(godot: Godot) : GodotPlugin(godot) {

    companion object {
        val LOAD_PROJECT = SignalInfo("load_project", String::class.java)
    }

    private val signals: MutableList<SignalInfo> = mutableListOf()

    override fun getPluginName() = "AppPlugin"

    override fun getPluginSignals(): Set<SignalInfo> {
        return signals.toSet()
    }

    fun addSignal(name: String, vararg argTypes: Class<*>) {
        signals.add(SignalInfo(name, *argTypes))
    }

    /**
     * This method triggers the desired signal identified by signalName with the passed
     * arguments in the executed Godot project. If the signal is not in the signals list,
     * it is added to it.
     */
    fun emitGodotSignal(signalName: String, signalArgs: Any) {
        if (signals.none { it.name == signalName}) {
            Log.e(GodotStagePlugin::class.simpleName, "The signal cannot be emitted since it was " +
                "never added to the signal list. Please add your signal using the " +
                "GodotStagePlugin::addSignal() method first.")
            return
        }
        emitSignal(signalName, signalArgs)
    }

    /**
     * Example method to demonstrate how a static signal could be applied
     */
    fun loadGodotProject(pathToProject: String) {
        emitSignal(LOAD_PROJECT.name, pathToProject)
    }
}
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

import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.plugin.SignalInfo

/**
 * This class is the required connection from pocket code to godot.
 * It enables the sending of signals to the running godot game, but not vice versa.
 */
class GodotStagePlugin(godot: Godot) : GodotPlugin(godot) {

    companion object {
        val SIGNALS: MutableSet<SignalInfo> = mutableSetOf(SignalInfo("default", String::class.java))
    }

    override fun getPluginName() = "AppPlugin"

    override fun getPluginSignals(): Set<SignalInfo> {
        return SIGNALS
    }

    fun emitGodotSignal(signalName: String, signalArgs: Any) {
        emitSignal(signalName, signalArgs)
    }
}
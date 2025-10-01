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

package org.catrobat.catroid.content.backwardcompatibility;

import android.util.Log;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.annotations.XStreamAlias;

import org.catrobat.catroid.common.ProjectData;
import org.catrobat.catroid.common.ProjectType;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;

import kotlin.text.Regex;

public class ProjectMetaDataParser {

	private final File projectFile;
	private final ProjectType projectType;

	public ProjectMetaDataParser(File projectFile, ProjectType projectType) {
		this.projectFile = projectFile;
		this.projectType = projectType;
	}

	public ProjectData getProjectMetaData() throws IOException {
		if (projectType.equals(ProjectType.CATROBAT)) {
			return getCatrobatMetaData();
		} else if (projectType.equals(ProjectType.GODOT)) {
			return getGodotMetaData();
		}
		return null;
	}

	private ProjectData getCatrobatMetaData() throws IOException {
		if (!projectFile.exists()) {
			throw new FileNotFoundException(projectFile.getAbsolutePath() + " does not exist.");
		}
		XStream xstream = new XStream();
		xstream.allowTypesByWildcard(new String[] {"org.catrobat.catroid.**"});
		xstream.processAnnotations(ProjectMetaData.class);
		xstream.ignoreUnknownElements();
		ProjectMetaData metaData;
		try {
			metaData = (ProjectMetaData) xstream.fromXML(projectFile);
		} catch (Exception e) {
			throw new IOException("Project metadata invalid", e);
		}
		metaData.setFile(projectFile);
		return new ProjectData(metaData.getName(),
				metaData.getDirectory(),
				metaData.getLanguageVersion(),
				metaData.hasScenes(),
				ProjectType.CATROBAT);
	}

	private ProjectData getGodotMetaData() {
		String projectName = "";
		String projectDirectoryString = projectFile.getPath().replaceAll("/project(#\\d+)?\\.godot$", "");
		double languageVersion = 0.0;
		boolean hasScenes = false;
		
		try {
			List<String> lines = Files.readAllLines(projectFile.toPath());
			for (String line : lines) {
				line = line.trim();
				if (line.startsWith("config/name=")) {
					String value = line.substring("config/name=".length()).trim();
					projectName = value.replaceAll("^\"|\"$", ""); // remove surrounding quotes
				} else if (line.startsWith("run/main_scene=")) {
					hasScenes = true;
				} else if (line.startsWith("config/features=")) {
					String versionArray = line.substring("config/features=".length()).trim();
					int versionStart = versionArray.indexOf('"');
					int versionEnd = versionArray.indexOf('"', versionStart);
					String version = versionArray.substring(versionStart, versionEnd);
					languageVersion = Double.parseDouble(version);
				}
			}
		} catch (IOException e) {
			Log.e(this.getClass().getName(), Objects.requireNonNull(e.getMessage()));
		} catch (NumberFormatException e) {
			Log.e(this.getClass().getName(), "Converting the version from the project.godot file "
					+ "failed");
		}

		File projectDirectory = new File(projectDirectoryString);

		return new ProjectData(projectName,
				projectDirectory,
				languageVersion,
				hasScenes,
				ProjectType.GODOT);
	}

	@XStreamAlias("program")
	private static class ProjectMetaData implements Serializable {

		private static final long serialVersionUID = 1L;

		private XmlHeaderMetaData header;
		private File xmlFile;

		public String getName() {
			return header.programName;
		}

		public void setFile(File xmlFile) {
			this.xmlFile = xmlFile;
		}

		public File getDirectory() {
			return xmlFile.getParentFile();
		}

		public double getLanguageVersion() {
			return header.catrobatLanguageVersion;
		}

		public boolean hasScenes() {
			return header.scenesEnabled;
		}

		private static final class XmlHeaderMetaData implements Serializable {

			private static final long serialVersionUID = 1L;

			private String programName;
			private double catrobatLanguageVersion;
			private boolean scenesEnabled = false;
		}
	}
}

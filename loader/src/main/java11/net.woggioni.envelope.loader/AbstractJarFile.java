/*
 * Copyright 2012-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.woggioni.envelope.loader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.Permission;
import java.util.Objects;
import java.util.zip.ZipFile;
import java.util.stream.Stream;
import java.util.jar.JarEntry;

/**
 * Base class for extended variants of {@link java.util.jar.JarFile}.
 *
 * @author Phillip Webb
 */
abstract class AbstractJarFile extends java.util.jar.JarFile {

	private static final String META_INF_VERSION_PREFIX = "META-INF/versions/";

	private final Runtime.Version version;  // current version
	private final int versionFeature;       // version.feature()

	private String getBasename(String name) {
		if (name.startsWith(META_INF_VERSION_PREFIX)) {
			int off = META_INF_VERSION_PREFIX.length();
			int index = name.indexOf('/', off);
			try {
				// filter out dir META-INF/versions/ and META-INF/versions/*/
				// and any entry with version > 'version'
				if (index == -1 || index == (name.length() - 1) ||
						Integer.parseInt(name, off, index, 10) > versionFeature) {
					return null;
				}
			} catch (NumberFormatException x) {
				return null; // remove malformed entries silently
			}
			// map to its base name
			return name.substring(index + 1);
		}
		return name;
	}

	@Override
	public Stream<JarEntry> versionedStream() {
		return stream()
				.map(JarEntry::getName)
				.map(this::getBasename)
				.filter(Objects::nonNull)
				.distinct()
				.map(this::getJarEntry)
				.filter(Objects::nonNull);
	}

	/**
	 * Create a new {@link AbstractJarFile}.
	 * @param file the root jar file.
	 * @throws IOException on IO error
	 */
	AbstractJarFile(File file, boolean verify, int mode) throws IOException {
		super(file, verify, mode);
		this.version = Runtime.version();
		this.versionFeature = this.version.feature();
	}

	/**
	 * Return a URL that can be used to access this JAR file. NOTE: the specified URL
	 * cannot be serialized and or cloned.
	 * @return the URL
	 * @throws MalformedURLException if the URL is malformed
	 */
	abstract URL getUrl() throws MalformedURLException;

	/**
	 * Return the {@link JarFileType} of this instance.
	 * @return the jar file type
	 */
	abstract JarFileType getType();

	/**
	 * Return the security permission for this JAR.
	 * @return the security permission.
	 */
	abstract Permission getPermission();

	/**
	 * Return an {@link InputStream} for the entire jar contents.
	 * @return the contents input stream
	 * @throws IOException on IO error
	 */
	abstract InputStream getInputStream() throws IOException;

	/**
	 * The type of a {@link JarFile}.
	 */
	enum JarFileType {

		DIRECT, NESTED_DIRECTORY, NESTED_JAR

	}

}

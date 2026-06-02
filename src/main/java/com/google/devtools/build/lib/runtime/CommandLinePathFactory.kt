// Copyright 2022 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.analysis.BlazeDirectories

/**
 * Factory for creating [PathFragment]s from command-line options.
 * 
 * 
 * The difference between this and using [PathFragment.create] directly is that
 * this factory replaces values starting with `%<name>%` with the corresponding (named) roots
 * (e.g., `%workspace%/foo` becomes `</path/to/workspace>/foo`).
 */
class CommandLinePathFactory @com.google.common.annotations.VisibleForTesting constructor(
    fileSystem: com.google.devtools.build.lib.vfs.FileSystem?,
    roots: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.vfs.Path?>?
) {
    /** An exception thrown while attempting to resolve a path.  */
    class CommandLinePathFactoryException(message: String?) : IOException(message)

    private val fileSystem: com.google.devtools.build.lib.vfs.FileSystem
    private val roots: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.vfs.Path?>

    init {
        this.fileSystem =
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.FileSystem>(fileSystem)
        this.roots =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.vfs.Path?>>(
                roots
            )
    }

    /** Creates a [Path].  */
    @Throws(IOException::class)
    fun create(env: MutableMap<String?, String?>?, value: String?): com.google.devtools.build.lib.vfs.Path? {
        com.google.common.base.Preconditions.checkNotNull<MutableMap<String?, String?>?>(env)
        com.google.common.base.Preconditions.checkNotNull<String?>(value)

        val matcher: java.util.regex.Matcher = REPLACEMENT_PATTERN.matcher(value)
        com.google.common.base.Preconditions.checkArgument(matcher.matches())

        val rootName: String? = matcher.group(2)
        val path: PathFragment = PathFragment.create(matcher.group(3))
        if (path.containsUplevelReferences()) {
            throw CommandLinePathFactoryException(
                String.format(
                    Locale.US, "Path '%s' must not contain any uplevel references ('..')", value
                )
            )
        }

        // Case 1: `path` is relative to a well-known root.
        if (!com.google.common.base.Strings.isNullOrEmpty(rootName)) {
            val root: com.google.devtools.build.lib.vfs.Path? = roots.get(rootName)
            if (root == null) {
                throw CommandLinePathFactoryException(
                    String.format(Locale.US, "Unknown root %s", rootName)
                )
            }
            return root.getRelative(path)
        }

        // Case 2: `value` is an absolute path.
        if (path.isAbsolute()) {
            return fileSystem.getPath(path)
        }

        // Case 3: `value` is a relative path.
        //
        // Since relative paths from the command-line are ambiguous to where they are relative to (i.e.,
        // relative to the workspace?, the directory Bazel is running in? relative to the `.bazelrc` the
        // flag is from?), we only allow relative paths with a single segment (i.e., no `/`) and treat
        // it as relative to the user's `PATH`.
        if (path.segmentCount() > 1) {
            throw CommandLinePathFactoryException(
                String.format(
                    Locale.US,
                    "Path '%s' must either be absolute or not contain any path separators",
                    value
                )
            )
        }

        val pathVariable = env!!.getOrDefault("PATH", "")
        if (!com.google.common.base.Strings.isNullOrEmpty(pathVariable)) {
            for (lookupPath in PATH_SPLITTER.split(pathVariable)) {
                val lookupPathFragment: PathFragment = PathFragment.create(lookupPath)
                if (lookupPathFragment.isEmpty() || !lookupPathFragment.isAbsolute()) {
                    // Ignore empty or relative path components. These are uncommon and may be confusing if
                    // bazel is running in a different directory than the user's current directory.
                    continue
                }

                val maybePath: com.google.devtools.build.lib.vfs.Path =
                    fileSystem.getPath(lookupPathFragment).getRelative(path)
                if (maybePath.exists(Symlinks.FOLLOW)
                    && maybePath.isFile(Symlinks.FOLLOW)
                    && maybePath.isExecutable()
                ) {
                    return maybePath
                }
            }
        }

        throw FileNotFoundException(
            String.format(
                Locale.US, "Could not find file with name '%s' on PATH '%s'", path, pathVariable
            )
        )
    }

    companion object {
        private val REPLACEMENT_PATTERN: java.util.regex.Pattern =
            java.util.regex.Pattern.compile("^(%([a-z_]+)%/+)?([^%].*)$")

        private val PATH_SPLITTER: com.google.common.base.Splitter =
            com.google.common.base.Splitter.on(java.io.File.pathSeparator)

        fun create(
            fileSystem: com.google.devtools.build.lib.vfs.FileSystem?,
            directories: BlazeDirectories?
        ): CommandLinePathFactory {
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.FileSystem?>(fileSystem)
            com.google.common.base.Preconditions.checkNotNull<Any?>(directories)

            val wellKnownRoots: com.google.common.collect.ImmutableMap.Builder<String?, com.google.devtools.build.lib.vfs.Path?> =
                com.google.common.collect.ImmutableMap.builder<String?, com.google.devtools.build.lib.vfs.Path?>()

            // This is necessary because some tests don't have a workspace set.
            val workspace: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                directories.getWorkspace()
            if (workspace != null) {
                wellKnownRoots.put("workspace", workspace)
            }

            val installBase: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                directories.getInstallBase()
            if (installBase != null) {
                wellKnownRoots.put("install_base", installBase)
            }

            return CommandLinePathFactory(fileSystem, wellKnownRoots.buildOrThrow())
        }
    }
}

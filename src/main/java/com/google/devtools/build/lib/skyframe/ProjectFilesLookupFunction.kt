// Copyright 2024 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.FileValue

/** [SkyFunction] for [ProjectFilesLookupValue].  */
class ProjectFilesLookupFunction : SkyFunction {
    private class State : SkyKeyComputeState {
        /** Which directory up the package path are we currently examining?  */
        private var currentDir: PackageIdentifier? = null

        /** Which project files have we discovered so far?  */
        private val projectFiles: java.util.ArrayList<Label?> = java.util.ArrayList<Label?>()
    }

    @Throws(java.lang.InterruptedException::class, ProjectFilesLookupException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val state: State =
            env.getState<State>(java.util.function.Supplier { com.google.devtools.build.lib.skyframe.ProjectFilesLookupFunction.State() })
        // Tracks the current directory we're checking for BUILD and PROJECT.scl files. According to the
        // ProjectFilesLookupValue API contract, the original value from the skykey should be a package
        // (i.e. has a BUILD file). But this code doesn't require that: if the directory doesn't have a
        // BUILD file the code still gracefully finds the nearest enclosing package. This is especially
        // important when walking up the directory path, since the parent directory of a package isn't
        // necessarily another package.
        if (state.currentDir == null) {
            state.currentDir = skyKey.argument() as PackageIdentifier?
        }
        while (true) {
            val innermostPkgLookupValue: ContainingPackageLookupValue? =
                env.getValue(ContainingPackageLookupValue.key(state.currentDir)) as ContainingPackageLookupValue?
            if (innermostPkgLookupValue == null) {
                return null
            }
            if (!innermostPkgLookupValue.hasContainingPackage()) {
                // We've reached the root directory: nothing left. This list may be empty but that's okay.
                // That just means the input package has no associated project files.
                return ProjectFilesLookupValue.Companion.of(state.projectFiles)
            }

            // Now that we've found a BUILD file, determine the project file path to look for.
            val innermostPkgId: PackageIdentifier = innermostPkgLookupValue.containingPackageName
            val projectFileLabel: Label?
            try {
                projectFileLabel = Label.create(innermostPkgId, PROJECT_FILE_NAME)
            } catch (e: LabelSyntaxException) {
                throw java.lang.IllegalStateException("Unexpected failure parsing " + PROJECT_FILE_NAME, e)
            }

            // Lookup the project file.
            val projectFilePath: PathFragment =
                innermostPkgId.getPackageFragment().getRelative(PROJECT_FILE_NAME)
            val fileSkyKey: SkyKey? =
                FileValue.key(
                    RootedPath.toRootedPath(
                        innermostPkgLookupValue.containingPackageRoot, projectFilePath
                    )
                )
            val fileValue: FileValue?
            try {
                fileValue = env.getValueOrThrow<IOException?>(fileSkyKey, IOException::class.java) as FileValue?
            } catch (e: IOException) {
                throw ProjectFilesLookupException(e)
            }
            if (fileValue == null) {
                return null
            }

            if (fileValue.isFile()) {
                state.projectFiles.add(projectFileLabel)
            } else if (fileValue.exists()) {
                throw ProjectFilesLookupException(
                    UnexpectedProjectFileTypeException(
                        projectFilePath.getPathString() + " isn't a file"
                    )
                )
            }

            val parentDir: PathFragment? = innermostPkgId.getPackageFragment().getParentDirectory()
            if (parentDir == null) {
                // Hit the root directory. Returns the results we've collected.
                return ProjectFilesLookupValue.Companion.of(state.projectFiles)
            }
            state.currentDir = PackageIdentifier.create(state.currentDir.getRepository(), parentDir)
        }
    }

    /** A project file exists but isn't a file.  */
    private class UnexpectedProjectFileTypeException(msg: String?) : java.lang.Exception(msg)

    /** Exception thrown by [ProjectFilesLookupFunction].  */
    class ProjectFilesLookupException : SkyFunctionException {
        internal constructor(e: IOException?) : super(e, Transience.PERSISTENT)

        internal constructor(e: UnexpectedProjectFileTypeException?) : super(e, Transience.PERSISTENT)
    }

    companion object {
        /** Name of project metadata files. See [com.google.devtools.build.lib.analysis.Project].  */
        @com.google.common.annotations.VisibleForTesting
        const val PROJECT_FILE_NAME: String = "PROJECT.scl"
    }
}

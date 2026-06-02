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

import com.google.devtools.build.lib.cmdline.Label

/**
 * [SkyValue] for finding the PROJECT files associated with a package.
 * 
 * 
 * See [com.google.devtools.build.lib.analysis.Project].
 */
class ProjectFilesLookupValue private constructor(projectFiles: com.google.common.collect.ImmutableList<Label?>?) :
    SkyValue {
    private val projectFiles: com.google.common.collect.ImmutableList<Label?>?

    /**
     * Returns the [com.google.devtools.build.lib.analysis.Project] files associated with the
     * corresponding [Key]'s package.
     * 
     * 
     * Given `a/b/c/d`, project resolution walks up the package path (i.e. walks up the
     * directory tree from `d` back to `a`, only counting directories with BUILD files).
     * Each directory with both a BUILD file and project file has a label reference to the project
     * file here.
     * 
     * 
     * Order is innermost to outermost: if both `a/PROJECT.scl` and `a/b/c/PROJECT.scl`
     * are included, `a/b/c/PROJECT.scl` appears first.
     */
    fun getProjectFiles(): com.google.common.collect.ImmutableList<Label?>? {
        return projectFiles
    }

    init {
        this.projectFiles = projectFiles
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o !is ProjectFilesLookupValue) {
            return false
        }
        return com.google.common.base.Objects.equal(projectFiles, o.projectFiles)
    }

    override fun hashCode(): Int {
        return java.util.Objects.hashCode(projectFiles)
    }

    /** [SkyKey] for `ProjectFilesLookupValue`.  */
    @AutoCodec
    class Key private constructor(arg: PackageIdentifier?) : AbstractSkyKey<PackageIdentifier?>(arg) {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.PROJECT_FILES_LOOKUP
        }

        val skyKeyInterner: SkyKeyInterner<Key?>
            get() = com.google.devtools.build.lib.skyframe.ProjectFilesLookupValue.Key.Companion.interner

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            private fun create(arg: PackageIdentifier?): Key {
                return com.google.devtools.build.lib.skyframe.ProjectFilesLookupValue.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.skyframe.ProjectFilesLookupValue.Key(
                        arg
                    )
                )
            }

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Interner
            fun intern(key: Key?): Key {
                return com.google.devtools.build.lib.skyframe.ProjectFilesLookupValue.Key.Companion.interner.intern(key)
            }
        }
    }

    companion object {
        /**
         * Lookup key.
         * 
         * @param id the package for which to find enclosing [     ] files
         */
        fun key(id: PackageIdentifier): Key {
            com.google.common.base.Preconditions.checkArgument(!id.getPackageFragment().isAbsolute(), id)
            return com.google.devtools.build.lib.skyframe.ProjectFilesLookupValue.Key.Companion.create(id)
        }

        private val NO_PROJECT_FILES = ProjectFilesLookupValue(com.google.common.collect.ImmutableList.of<Label?>())

        fun of(projectFiles: MutableCollection<Label?>): ProjectFilesLookupValue? {
            return if (projectFiles.isEmpty())
                NO_PROJECT_FILES
            else
                ProjectFilesLookupValue(com.google.common.collect.ImmutableList.copyOf<Label?>(projectFiles))
        }
    }
}

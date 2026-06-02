// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/**
 * A descriptor for a glob request, used as the [SkyKey] for [GlobFunction].
 * 
 * 
 * `subdir` must be empty or point to an existing directory.
 * 
 * 
 * `pattern` must be valid, as indicated by `UnixGlob#checkPatternForError`.
 */
@AutoCodec
@ThreadSafe
class GlobDescriptor private constructor(
    packageId: PackageIdentifier?,
    packageRoot: Root?,
    subdir: PathFragment?,
    pattern: String,
    globberOperation: Globber.Operation
) : SkyKey {
    private val packageId: PackageIdentifier
    private val packageRoot: Root
    private val subdir: PathFragment

    /**
     * Returns the glob pattern under consideration. May contain wildcards.
     * 
     * 
     * As the glob evaluator traverses deeper into the file tree, components are added at the
     * beginning of `subdir` and removed from the beginning of `pattern`.
     */
    @kotlin.jvm.JvmField
    val pattern: String
    private val globberOperation: Globber.Operation

    init {
        this.packageId = com.google.common.base.Preconditions.checkNotNull<PackageIdentifier>(packageId)
        this.packageRoot = com.google.common.base.Preconditions.checkNotNull<Root>(packageRoot)
        this.subdir = com.google.common.base.Preconditions.checkNotNull<PathFragment>(subdir)
        this.pattern = com.google.common.base.Preconditions.checkNotNull<String>(pattern.intern())
        this.globberOperation = globberOperation
    }

    override fun toString(): String {
        return java.lang.String.format(
            "<GlobDescriptor packageName=%s packageRoot=%s subdir=%s pattern=%s globberOperation=%s>",
            packageId, packageRoot, subdir, pattern, globberOperation.name()
        )
    }

    /**
     * Returns the package that "owns" this glob.
     * 
     * 
     * The glob evaluation code ensures that the boundaries of this package are not crossed.
     */
    fun getPackageId(): PackageIdentifier {
        return packageId
    }

    /** Returns the package root of `getPackageId()`.  */
    fun getPackageRoot(): Root {
        return packageRoot
    }

    /**
     * Returns the subdirectory of the package under consideration.
     */
    fun getSubdir(): PathFragment {
        return subdir
    }

    /** Returns the type of Globber operation that produced the results.  */
    fun globberOperation(): Globber.Operation {
        return globberOperation
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj !is GlobDescriptor) {
            return false
        }
        return packageId.equals(obj.packageId)
                && packageRoot == obj.packageRoot
                && subdir == obj.subdir
                && pattern == obj.pattern
                && globberOperation === obj.globberOperation
    }

    override fun hashCode(): Int {
        // Generated instead of Objects.hashCode to avoid intermediate array required for latter.
        val prime = 31
        var result = 1
        result = prime * result + globberOperation.hashCode()
        result = prime * result + packageId.hashCode()
        result = prime * result + packageRoot.hashCode()
        result = prime * result + pattern.hashCode()
        result = prime * result + subdir.hashCode()
        return result
    }

    override fun functionName(): SkyFunctionName {
        return SkyFunctions.GLOB
    }

    val skyKeyInterner: SkyKeyInterner<GlobDescriptor?>
        get() = interner

    companion object {
        private val interner: SkyKeyInterner<GlobDescriptor?> = SkyKey.newInterner<GlobDescriptor?>()

        /**
         * Returns interned instance based on the parameters.
         * 
         * @param packageId the name of the owner package (must be an existing package)
         * @param packageRoot the package root of `packageId`
         * @param subdir the subdirectory being looked at (must exist and must be a directory. It's
         * assumed that there are no other packages between `packageName` and `subdir`.
         * @param pattern a valid glob pattern
         * @param globberOperation type of Globber operation being tracked.
         */
        fun create(
            packageId: PackageIdentifier?,
            packageRoot: Root?,
            subdir: PathFragment?,
            pattern: String,
            globberOperation: Globber.Operation
        ): GlobDescriptor {
            return interner.intern(
                GlobDescriptor(packageId, packageRoot, subdir, pattern, globberOperation)
            )
        }

        @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
        @AutoCodec.Interner
        fun intern(globDescriptor: GlobDescriptor?): GlobDescriptor {
            return interner.intern(globDescriptor)
        }
    }
}

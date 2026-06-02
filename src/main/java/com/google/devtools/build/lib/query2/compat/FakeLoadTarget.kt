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
package com.google.devtools.build.lib.query2.compat

import com.google.common.base.Preconditions
import com.google.devtools.build.lib.cmdline.Label
import java.util.*

/**
 * A fake Target - Use only so that "blaze query" can report Load files as Targets.
 */
class FakeLoadTarget(label: Label?, packageoidOfBuildFile: Packageoid) : Target {
    private val label: Label
    private val pkg: Packageoid

    /**
     * @param packageoidOfBuildFile the [Packageoid] owning the package's BUILD file: in other
     * words, either a monolithic [Package] (under eager symbolic macro expansion), or a
     * [PackagePiece.ForBuildFile] (under lazy symbolic macro expansion).
     */
    init {
        this.label = Preconditions.checkNotNull<Label>(label)
        Preconditions.checkNotNull<Any?>(packageoidOfBuildFile)
        Preconditions.checkArgument(
            (packageoidOfBuildFile is Package && packageoidOfBuildFile.getBuildFile()
                .getPackageoid() === packageoidOfBuildFile)
                    || packageoidOfBuildFile is ForBuildFile,
            "%s must be either a monolithic package or a top-level package piece",
            packageoidOfBuildFile
        )
        this.pkg = packageoidOfBuildFile
    }

    public override fun getLabel(): Label {
        return label
    }

    val packageoid: Packageoid
        get() = pkg

    val packageMetadata: Package.Metadata
        get() = pkg.getMetadata()

    val packageDeclarations: Package.Declarations
        get() = pkg.getDeclarations()

    val targetKind: String
        get() = targetKind()

    val associatedRule: Rule?
        get() = null

    val license: License?
        get() {
            throw UnsupportedOperationException()
        }

    val location: Location
        get() = this.packageMetadata.getBuildFileLocation()

    val rawVisibility: RuleVisibility
        get() = RuleVisibility.PUBLIC

    val isConfigurable: Boolean
        get() = true

    override fun toString(): String {
        return label.toString()
    }

    override fun hashCode(): Int {
        return Objects.hash(label, pkg)
    }

    override fun equals(obj: Any?): Boolean {
        if (obj !is FakeLoadTarget) {
            return false
        }
        return label.equals(obj.label) && pkg.equals(obj.pkg)
    }

    public override fun reduceForSerialization(): TargetData? {
        throw UnsupportedOperationException()
    }

    companion object {
        /** Returns the target kind for all fake sub-include targets.  */
        fun targetKind(): String {
            return "source file"
        }
    }
}

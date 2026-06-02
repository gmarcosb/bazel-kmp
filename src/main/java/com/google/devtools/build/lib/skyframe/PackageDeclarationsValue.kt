// Copyright 2025 The Bazel Authors. All rights reserved.
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
 * A Skyframe value representing [Package.Declarations] and associated data (e.g. package
 * metadata, Starlark builtins, etc.) shared by all package pieces of a package.
 * 
 * 
 * The corresponding [SkyKey] is [PackageDeclarationsValue.Key].
 * 
 * 
 * The purpose of this value is to allow change-pruning on the transitive dependency from a
 * [PackagePieceValue.ForMacro] to its [PackagePieceValue.ForBuildFile] - since package
 * pieces are not comparable.
 */
@AutoCodec
class PackageDeclarationsValue(
    metadata: Package.Metadata?,
    declarations: Package.Declarations?,
    starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?,
    mainRepositoryMapping: RepositoryMapping?
) : SkyValue {
    /** The [SkyKey] for a [PackageDeclarationsValue].  */
    @AutoCodec
    class Key(packageId: PackageIdentifier?) : SkyKey {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.PACKAGE_DECLARATIONS
        }

        val packageId: PackageIdentifier?

        init {
            this.packageId = packageId
            com.google.common.base.Preconditions.checkNotNull<Any?>(packageId)
        }
    }

    val metadata: Package.Metadata?
    val declarations: Package.Declarations?
    val starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?
    val mainRepositoryMapping: RepositoryMapping?

    init {
        this.mainRepositoryMapping = mainRepositoryMapping
        this.starlarkSemantics = starlarkSemantics
        this.declarations = declarations
        this.metadata = metadata
        com.google.common.base.Preconditions.checkNotNull<Any?>(metadata)
        com.google.common.base.Preconditions.checkNotNull<Any?>(declarations)
        com.google.common.base.Preconditions.checkNotNull<net.starlark.java.eval.StarlarkSemantics?>(starlarkSemantics)
        com.google.common.base.Preconditions.checkNotNull<Any?>(mainRepositoryMapping)
    }
}

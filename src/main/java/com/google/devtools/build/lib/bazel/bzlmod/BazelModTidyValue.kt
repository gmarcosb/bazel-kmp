// Copyright 2022 The Bazel Authors. All rights reserved.
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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.bazel.bzlmod.ExternalDepsException
import com.google.devtools.build.lib.bazel.bzlmod.RootModuleFileFixup
import com.google.devtools.build.lib.skyframe.SkyFunctions
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant
import com.google.devtools.build.lib.vfs.PathFragment
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue

/**
 * All Skyframe information required for the `bazel mod tidy` command.
 * 
 * @param fixups Buildozer fixups for incorrect use_repo declarations by the root module.
 * @param buildozer The path of the buildozer binary provided by the "buildozer" module.
 * @param moduleFilePaths The set of paths to the root MODULE.bazel file and all its includes.
 * @param errors Errors encountered while evaluating prerequisites for `bazel mod tidy`.
 */
class BazelModTidyValue(
    fixups: com.google.common.collect.ImmutableList<RootModuleFileFixup?>?,
    buildozer: com.google.devtools.build.lib.vfs.Path?,
    moduleFilePaths: com.google.common.collect.ImmutableSet<PathFragment?>?,
    errors: com.google.common.collect.ImmutableList<ExternalDepsException?>?
) : SkyValue {
    val fixups: com.google.common.collect.ImmutableList<RootModuleFileFixup?>?
    val buildozer: com.google.devtools.build.lib.vfs.Path?
    val moduleFilePaths: com.google.common.collect.ImmutableSet<PathFragment?>?
    val errors: com.google.common.collect.ImmutableList<ExternalDepsException?>?

    init {
        this.errors = errors
        this.moduleFilePaths = moduleFilePaths
        this.buildozer = buildozer
        this.fixups = fixups
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableList<RootModuleFileFixup?>?>(
            fixups,
            "fixups"
        )
        java.util.Objects.requireNonNull<com.google.devtools.build.lib.vfs.Path?>(buildozer, "buildozer")
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableSet<PathFragment?>?>(
            moduleFilePaths,
            "moduleFilePaths"
        )
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableList<ExternalDepsException?>?>(
            errors,
            "errors"
        )
    }

    companion object {
        @SerializationConstant
        val KEY: SkyKey = SkyKey { SkyFunctions.BAZEL_MOD_TIDY }

        fun create(
            fixups: MutableList<RootModuleFileFixup?>,
            buildozer: com.google.devtools.build.lib.vfs.Path?,
            moduleFilePaths: com.google.common.collect.ImmutableSet<PathFragment?>?,
            errors: com.google.common.collect.ImmutableList<ExternalDepsException?>?
        ): BazelModTidyValue {
            return BazelModTidyValue(
                com.google.common.collect.ImmutableList.copyOf<RootModuleFileFixup?>(fixups),
                buildozer,
                moduleFilePaths,
                errors
            )
        }
    }
}

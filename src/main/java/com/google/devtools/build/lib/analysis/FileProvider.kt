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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.actions.Artifact

/**
 * A representation of the concept "this transitive info provider builds these files".
 * 
 * 
 * Every transitive info collection contains at least this provider.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class FileProvider private constructor(filesToBuild: NestedSet<Artifact?>) :
    com.google.devtools.build.lib.analysis.TransitiveInfoProvider, FileProviderApi {
    private val filesToBuild: NestedSet<Artifact?>

    init {
        this.filesToBuild = filesToBuild
    }

    override fun isImmutable(): Boolean {
        return true // immutable and Starlark-hashable
    }

    override fun  /*<Artifact>*/getFilesToBuildForStarlark(): Depset? {
        return Depset.of<Artifact?>(Artifact::class.java, filesToBuild)
    }

    override fun debugPrint(printer: net.starlark.java.eval.Printer, thread: net.starlark.java.eval.StarlarkThread?) {
        printer.append("FileProvider(files_to_build = ")
        printer.debugPrint(getFilesToBuildForStarlark(), thread)
        printer.append(")")
    }

    fun getFilesToBuild(): NestedSet<Artifact?> {
        return filesToBuild
    }

    companion object {
        @kotlin.jvm.JvmField
        val EMPTY: FileProvider =
            FileProvider(NestedSetBuilder.emptySet<Artifact?>(com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER))

        fun of(filesToBuild: NestedSet<Artifact?>): FileProvider? {
            return if (filesToBuild.isEmpty()) EMPTY else FileProvider(filesToBuild)
        }
    }
}

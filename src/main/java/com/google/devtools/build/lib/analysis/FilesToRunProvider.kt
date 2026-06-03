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

/** Returns information about executables produced by a target and the files needed to run it.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
open class FilesToRunProvider private constructor(filesToRun: NestedSet<Artifact?>?) : TransitiveInfoProvider,
    FilesToRunProviderApi<Artifact?> {
    private val filesToRun: NestedSet<Artifact?>?

    init {
        this.filesToRun = filesToRun
    }

    public override fun isImmutable(): Boolean {
        return true // immutable and Starlark-hashable
    }

    /**
     * Returns artifacts needed to run the executable for this target.
     * 
     * 
     * This method should not be used because its semantics are complicated and confusing. Instead,
     * either use [.getExecutable] or [.getRunfilesSupport] if you know what you are
     * doing and it's something very arcane.
     */
    @Deprecated("")
    fun getFilesToRun(): NestedSet<Artifact?>? {
        return filesToRun
    }

    public override fun getExecutable(): Artifact? {
        return null
    }

    /**
     * Returns the [RunfilesSupport] object associated with the target or null if it does not
     * exist.
     */
    open fun getRunfilesSupport(): RunfilesSupport? {
        return null
    }

    public override fun getRunfilesManifest(): Artifact? {
        val runfilesSupport: RunfilesSupport? = getRunfilesSupport()
        return if (runfilesSupport != null) runfilesSupport.getRunfilesManifest() else null
    }

    public override fun getRepoMappingManifest(): Artifact? {
        val runfilesSupport: RunfilesSupport? = getRunfilesSupport()
        return if (runfilesSupport != null) runfilesSupport.getRepoMappingManifest() else null
    }

    public override fun debugPrint(printer: net.starlark.java.eval.Printer, thread: StarlarkThread?) {
        printer.append("FilesToRunProvider(executable = ")
        printer.debugPrint(getExecutable(), thread)
        printer.append(", repo_mapping_manifest = ")
        printer.debugPrint(getRepoMappingManifest(), thread)
        printer.append(", runfiles_manifest = ")
        printer.debugPrint(getRunfilesManifest(), thread)
        printer.append(")")
    }

    /** A single executable.  */
    private class SingleExecutableFilesToRunProvider(filesToRun: NestedSet<Artifact?>?) :
        FilesToRunProvider(filesToRun) {
        override fun getExecutable(): Artifact {
            return getFilesToRun().getSingleton()
        }
    }

    /** A [FilesToRunProvider] possible with [RunfilesSupport] and/or an executable.  */
    private class FullFilesToRunProvider(
        filesToRun: NestedSet<Artifact?>?,
        runfilesSupport: RunfilesSupport?,
        executable: Artifact?
    ) : FilesToRunProvider(filesToRun) {
        private val runfilesSupport: RunfilesSupport?
        private val executable: Artifact?

        init {
            this.runfilesSupport = runfilesSupport
            this.executable = executable
        }

        override fun getRunfilesSupport(): RunfilesSupport? {
            return runfilesSupport
        }

        override fun getExecutable(): Artifact? {
            return executable
        }
    }

    companion object {
        /** The name of the field in Starlark used to access a [FilesToRunProvider].  */
        const val STARLARK_NAME: String = "files_to_run"

        val EMPTY: FilesToRunProvider = FilesToRunProvider(NestedSetBuilder.emptySet(Order.STABLE_ORDER))

        fun create(
            filesToRun: NestedSet<Artifact?>,
            runfilesSupport: RunfilesSupport?,
            executable: Artifact?
        ): FilesToRunProvider? {
            if (filesToRun.isEmpty()) {
                com.google.common.base.Preconditions.checkArgument(
                    runfilesSupport == null,
                    "No files to run with runfiles: %s",
                    runfilesSupport
                )
                com.google.common.base.Preconditions.checkArgument(
                    executable == null,
                    "No files to run with executable: %s",
                    executable
                )
                return EMPTY
            }
            if (runfilesSupport == null && executable == null) {
                return FilesToRunProvider(filesToRun)
            }
            if (filesToRun.isSingleton()
                && runfilesSupport == null && filesToRun.getSingleton().equals(executable)
            ) {
                return SingleExecutableFilesToRunProvider(filesToRun)
            }
            return FullFilesToRunProvider(filesToRun, runfilesSupport, executable)
        }
    }
}

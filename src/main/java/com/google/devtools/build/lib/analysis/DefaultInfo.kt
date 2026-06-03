// Copyright 2017 The Bazel Authors. All rights reserved.
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

/** DefaultInfo is provided by all targets implicitly and contains all standard fields.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
abstract class DefaultInfo private constructor(location: net.starlark.java.syntax.Location?) : NativeInfo(),
    DefaultInfoApi {
    private val location: net.starlark.java.syntax.Location

    init {
        this.location = com.google.common.base.MoreObjects.firstNonNull<net.starlark.java.syntax.Location>(
            location,
            net.starlark.java.syntax.Location.BUILTIN
        )
    }

    /**
     * Returns the source location where this DefaultInfo was created, or [Location.BUILTIN] if
     * it was instantiated by Java code. Used only for error reporting.
     */
    fun getCreationLocation(): net.starlark.java.syntax.Location {
        return location
    }

    public override fun getProvider(): DefaultInfoProvider {
        return PROVIDER
    }

    /**
     * Returns a set of runfiles acting as both the data runfiles and the default runfiles.
     * 
     * 
     * This is kept for legacy reasons.
     */
    abstract fun getStatelessRunfiles(): com.google.devtools.build.lib.analysis.Runfiles?

    public abstract override fun getDataRunfiles(): com.google.devtools.build.lib.analysis.Runfiles?

    public abstract override fun getDefaultRunfiles(): com.google.devtools.build.lib.analysis.Runfiles?

    /**
     * If the rule producing this info object is marked 'executable' or 'test', this is an artifact
     * representing the file that should be executed to run the target. This is null otherwise.
     */
    abstract fun getExecutable(): Artifact?

    public abstract override fun getFilesToRun(): FilesToRunProvider?

    /** Default implementation of DefaultInfo object for Starlark targets.  */
    private class DefaultDefaultInfo(
        loc: net.starlark.java.syntax.Location?,
        files: Depset?,
        runfiles: com.google.devtools.build.lib.analysis.Runfiles?,
        dataRunfiles: com.google.devtools.build.lib.analysis.Runfiles?,
        defaultRunfiles: com.google.devtools.build.lib.analysis.Runfiles?,
        executable: Artifact?,
        filesToRunProvider: FilesToRunProvider?
    ) : DefaultInfo(loc) {
        private val files: Depset?
        private val runfiles: com.google.devtools.build.lib.analysis.Runfiles?
        private val dataRunfiles: com.google.devtools.build.lib.analysis.Runfiles?
        private val defaultRunfiles: com.google.devtools.build.lib.analysis.Runfiles?
        private val executable: Artifact?
        private val filesToRunProvider: FilesToRunProvider?

        init {
            this.files = files
            this.runfiles = runfiles
            this.dataRunfiles = dataRunfiles
            this.defaultRunfiles = defaultRunfiles
            this.executable = executable
            this.filesToRunProvider = filesToRunProvider
        }

        public override fun getFiles(): Depset? {
            return files
        }

        override fun getFilesToRun(): FilesToRunProvider? {
            return filesToRunProvider
        }

        override fun getStatelessRunfiles(): com.google.devtools.build.lib.analysis.Runfiles? {
            return runfiles
        }

        override fun getDataRunfiles(): com.google.devtools.build.lib.analysis.Runfiles? {
            return dataRunfiles
        }

        override fun getDefaultRunfiles(): com.google.devtools.build.lib.analysis.Runfiles? {
            if (dataRunfiles == null && defaultRunfiles == null) {
                // This supports the legacy Starlark runfiles constructor -- if the 'runfiles' attribute
                // is used, then default_runfiles will return all runfiles.
                return runfiles
            } else {
                return defaultRunfiles
            }
        }

        override fun getExecutable(): Artifact? {
            return executable
        }
    }

    /** Optimised implementation of DefaultInfo object for native targets.  */
    private class DelegatingDefaultInfo(target: AbstractConfiguredTarget) :
        DefaultInfo(net.starlark.java.syntax.Location.BUILTIN) {
        private val target: AbstractConfiguredTarget

        init {
            this.target = target
        }

        public override fun getFiles(): Depset? {
            return Depset.of(Artifact::class.java, target.getProvider<P?>(FileProvider::class.java).getFilesToBuild())
        }

        override fun getFilesToRun(): FilesToRunProvider? {
            return target.getProvider<FilesToRunProvider?>(FilesToRunProvider::class.java)
        }

        override fun getDataRunfiles(): com.google.devtools.build.lib.analysis.Runfiles? {
            val runfilesProvider: RunfilesProvider? =
                target.getProvider<RunfilesProvider?>(RunfilesProvider::class.java)
            return if (runfilesProvider == null) com.google.devtools.build.lib.analysis.Runfiles.Companion.EMPTY else runfilesProvider.getDataRunfiles()
        }

        override fun getDefaultRunfiles(): com.google.devtools.build.lib.analysis.Runfiles? {
            val runfilesProvider: RunfilesProvider? =
                target.getProvider<RunfilesProvider?>(RunfilesProvider::class.java)
            return if (runfilesProvider == null) com.google.devtools.build.lib.analysis.Runfiles.Companion.EMPTY else runfilesProvider.getDefaultRunfiles()
        }

        override fun getStatelessRunfiles(): com.google.devtools.build.lib.analysis.Runfiles? {
            return null
        }

        override fun getExecutable(): Artifact? {
            return target.getProvider<FilesToRunProvider?>(FilesToRunProvider::class.java).getExecutable()
        }
    }

    /**
     * Provider implementation for [DefaultInfoApi].
     */
    class DefaultInfoProvider private constructor() :
        BuiltinProvider<DefaultInfo?>("DefaultInfo", DefaultInfo::class.java),
        DefaultInfoApi.DefaultInfoApiProvider<com.google.devtools.build.lib.analysis.Runfiles?, Artifact?> {
        @Throws(net.starlark.java.eval.EvalException::class)
        public override fun constructor(
            files: Any?,
            runfilesObj: Any?,
            dataRunfilesObj: Any?,
            defaultRunfilesObj: Any?,
            executable: Any?,
            thread: StarlarkThread
        ): DefaultInfoApi? {
            val statelessRunfiles: com.google.devtools.build.lib.analysis.Runfiles? =
                Companion.castNoneToNull<com.google.devtools.build.lib.analysis.Runfiles?>(
                    com.google.devtools.build.lib.analysis.Runfiles::class.java,
                    runfilesObj
                )
            val dataRunfiles: com.google.devtools.build.lib.analysis.Runfiles? =
                Companion.castNoneToNull<com.google.devtools.build.lib.analysis.Runfiles?>(
                    com.google.devtools.build.lib.analysis.Runfiles::class.java,
                    dataRunfilesObj
                )
            val defaultRunfiles: com.google.devtools.build.lib.analysis.Runfiles? =
                Companion.castNoneToNull<com.google.devtools.build.lib.analysis.Runfiles?>(
                    com.google.devtools.build.lib.analysis.Runfiles::class.java,
                    defaultRunfilesObj
                )

            if ((statelessRunfiles != null) && (dataRunfiles != null || defaultRunfiles != null)) {
                throw Starlark.errorf(
                    "Cannot specify the provider 'runfiles' together with 'data_runfiles' or"
                            + " 'default_runfiles'"
                )
            }

            return DefaultDefaultInfo(
                thread.getCallerLocation(),
                Companion.castNoneToNull<Depset?>(Depset::class.java, files),
                statelessRunfiles,
                dataRunfiles,
                defaultRunfiles,
                Companion.castNoneToNull<Artifact?>(Artifact::class.java, executable),
                null
            )
        }
    }

    companion object {
        /** Singleton instance of the provider type for [DefaultInfo].  */
        val PROVIDER: DefaultInfoProvider = DefaultInfoProvider()

        /** Constructs an optimised DefaultInfo for native targets.  */
        fun build(target: AbstractConfiguredTarget): DefaultInfo {
            return DelegatingDefaultInfo(target)
        }

        private fun <T> castNoneToNull(clazz: java.lang.Class<T?>, value: Any?): T? {
            if (value === Starlark.NONE) {
                return null
            } else {
                return clazz.cast(value)
            }
        }
    }
}

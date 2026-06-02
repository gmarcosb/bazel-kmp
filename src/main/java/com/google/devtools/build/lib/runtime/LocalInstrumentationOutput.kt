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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.buildtool.BuildResult.BuildToolLogCollection

/** Used when instrumentation output is written to a local file.  */
internal class LocalInstrumentationOutput(
    private val name: String?,
    path: com.google.devtools.build.lib.vfs.Path,
    convenienceName: String?,
    append: Boolean?,
    internal: Boolean?,
    createParent: Boolean
) : InstrumentationOutput {
    private val path: com.google.devtools.build.lib.vfs.Path
    private val convenienceName: String?
    private val append: Boolean?
    private val internal: Boolean?
    private val createParent: Boolean

    init {
        this.path = path
        this.convenienceName = convenienceName
        this.append = append
        this.internal = internal
        this.createParent = createParent
    }

    override fun publish(buildToolLogCollection: BuildToolLogCollection) {
        buildToolLogCollection.addLocalFile(name, path)
    }

    @Throws(IOException::class)
    fun makeConvenienceLink() {
        if (convenienceName != null) {
            val link: com.google.devtools.build.lib.vfs.Path = path.getParentDirectory().getChild(convenienceName)
            link.delete()
            link.createSymbolicLink(PathFragment.create(path.getBaseName()))
        }
    }

    @Throws(IOException::class)
    override fun createOutputStream(): java.io.OutputStream? {
        if (createParent) {
            path.getParentDirectory().createDirectoryAndParents()
        }
        if (append != null && internal != null) {
            return path.getOutputStream(append, internal)
        }
        if (append != null) {
            return path.getOutputStream(append)
        }
        return path.getOutputStream()
    }

    val pathString: String?
        get() = path.getPathString()

    /** Builder for [LocalInstrumentationOutput].  */
    class Builder : InstrumentationOutputBuilder {
        private var name: String? = null
        private var path: com.google.devtools.build.lib.vfs.Path? = null
        private var convenienceName: String? = null
        private var append: Boolean? = null
        private var internal: Boolean? = null
        private var createParent = false

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun setName(name: String?): Builder {
            this.name = name
            return this
        }

        /** Sets the path to the local [InstrumentationOutput].  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setPath(path: com.google.devtools.build.lib.vfs.Path?): Builder {
            this.path = path
            return this
        }

        /**
         * Set the convenience name for the instrumentation output. A symlink at `name` will
         * be created pointing to the output when [ ][LocalInstrumentationOutput.makeConvenienceLink] is called.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setConvenienceName(name: String?): Builder {
            this.convenienceName = name
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setAppend(append: Boolean?): Builder {
            this.append = append
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setInternal(internal: Boolean?): Builder {
            this.internal = internal
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun setCreateParent(createParent: Boolean): Builder {
            this.createParent = createParent
            return this
        }

        override fun build(): LocalInstrumentationOutput {
            return LocalInstrumentationOutput(
                com.google.common.base.Preconditions.checkNotNull<String?>(
                    name,
                    "Cannot create LocalInstrumentationOutputBuilder without name"
                ),
                com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path?>(
                    path,
                    "Cannot create LocalInstrumentationOutputBuilder without path"
                ),
                convenienceName,
                append,
                internal,
                createParent
            )
        }
    }
}

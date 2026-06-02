// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.buildjar.genclass

import com.google.common.base.Preconditions
import java.nio.file.Path

/** The options for a [GenClass] action.  */
class GenClassOptions private constructor(manifest: Path?, classJar: Path?, outputJar: Path?) {
    /** A builder for [GenClassOptions].  */
    class Builder {
        private var manifest: Path? = null
        private var classJar: Path? = null
        private var outputJar: Path? = null

        fun setManifest(manifest: Path?) {
            this.manifest = manifest
        }

        fun setClassJar(classJar: Path?) {
            this.classJar = classJar
        }

        fun setOutputJar(outputJar: Path?) {
            this.outputJar = outputJar
        }

        fun build(): GenClassOptions {
            return GenClassOptions(manifest, classJar, outputJar)
        }
    }

    private val manifest: Path
    private val classJar: Path
    private val outputJar: Path

    init {
        this.manifest = Preconditions.checkNotNull<Path>(manifest)
        this.classJar = Preconditions.checkNotNull<Path>(classJar)
        this.outputJar = Preconditions.checkNotNull<Path>(outputJar)
    }

    /** The path to the compilation manifest proto.  */
    fun manifest(): Path {
        return manifest
    }

    /** The path to the compilation's class jar.  */
    fun classJar(): Path {
        return classJar
    }

    /** The path to write the output to.  */
    fun outputJar(): Path {
        return outputJar
    }

    companion object {
        /** Returns a builder for [GenClassOptions].  */
        fun builder(): Builder {
            return Builder()
        }
    }
}

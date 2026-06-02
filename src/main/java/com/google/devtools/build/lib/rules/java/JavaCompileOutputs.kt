// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.java

import com.google.devtools.build.lib.actions.Artifact

/** The outputs of a [JavaCompileAction].  */
@AutoValue
abstract class JavaCompileOutputs<T : Artifact?> {
    /** The class jar Artifact to create with the Action  */
    abstract fun output(): T?

    /** The output artifact for the manifest proto emitted from JavaBuilder  */
    abstract fun manifestProto(): T?

    abstract fun depsProto(): T?

    /** The generated class jar, or `null` if no annotation processing is expected.  */
    abstract fun genClass(): T?

    /**
     * The generated sources jar Artifact to create with the Action (null if no sources will be
     * generated).
     */
    abstract fun genSource(): T?

    /** An archive of generated native header files.  */
    abstract fun nativeHeader(): T?

    abstract fun toBuilder(): Builder<T?>?

    fun withOutput(output: T?): JavaCompileOutputs<T?>? {
        return toBuilder()!!.output(output)!!.build()
    }

    @AutoValue.Builder
    internal abstract class Builder<T : Artifact?> {
        abstract fun output(artifact: T?): Builder<T?>?

        abstract fun manifestProto(artifact: T?): Builder<T?>?

        abstract fun depsProto(artifact: T?): Builder<T?>?

        abstract fun genClass(artifact: T?): Builder<T?>?

        abstract fun genSource(artifact: T?): Builder<T?>?

        abstract fun nativeHeader(artifact: T?): Builder<T?>?

        abstract fun build(): JavaCompileOutputs<T?>?
    }

    companion object {
        fun <T : Artifact?> builder(): Builder<T?> {
            return Builder()
        }
    }
}

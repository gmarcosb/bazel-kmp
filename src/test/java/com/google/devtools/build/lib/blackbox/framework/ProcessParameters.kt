// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.blackbox.framework

import com.google.auto.value.AutoValue
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import java.io.File
import java.nio.file.Path
import java.util.*

/** [ProcessRunner] parameters  */
@AutoValue
abstract class ProcessParameters {
    abstract fun name(): String?

    abstract fun arguments(): ImmutableList<String?>?

    abstract fun workingDirectory(): File?

    abstract fun expectedExitCode(): Int

    abstract fun expectedToFail(): Boolean

    abstract fun expectedEmptyError(): Boolean

    abstract fun environment(): Optional<ImmutableMap<String?, String?>?>?

    abstract fun timeoutMillis(): Long

    abstract fun redirectOutput(): Optional<Path?>?

    abstract fun redirectError(): Optional<Path?>?

    /** Builder class  */
    @AutoValue.Builder
    abstract class Builder {
        abstract fun setName(value: String?): Builder?

        abstract fun setArguments(vararg args: String?): Builder?

        abstract fun setArguments(args: ImmutableList<String?>?): Builder?

        fun setArguments(args: MutableList<String?>): Builder {
            setArguments(ImmutableList.copyOf<String?>(args))
            return this
        }

        abstract fun setWorkingDirectory(value: File?): Builder?

        abstract fun setExpectedExitCode(value: Int): Builder?

        abstract fun setExpectedToFail(value: Boolean): Builder?

        abstract fun setExpectedEmptyError(value: Boolean): Builder?

        abstract fun setEnvironment(map: ImmutableMap<String?, String?>?): Builder?

        fun setEnvironment(map: MutableMap<String?, String?>): Builder {
            setEnvironment(ImmutableMap.copyOf<String?, String?>(map))
            return this
        }

        abstract fun setTimeoutMillis(millis: Long): Builder?

        abstract fun setRedirectOutput(path: Path?): Builder?

        abstract fun setRedirectError(path: Path?): Builder?

        abstract fun build(): ProcessParameters?
    }

    companion object {
        fun builder(): Builder {
            return Builder()
                .setExpectedExitCode(0)
                .setExpectedEmptyError(true)
                .setExpectedToFail(false)
                .setTimeoutMillis(30 * 1000)
                .setArguments()!!
        }
    }
}

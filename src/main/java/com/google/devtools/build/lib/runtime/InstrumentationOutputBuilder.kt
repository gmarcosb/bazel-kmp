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

import com.google.devtools.build.lib.runtime.CommandEnvironment
import com.google.devtools.build.lib.runtime.InstrumentationOutput
import com.google.devtools.build.lib.runtime.InstrumentationOutputFactory.DestinationRelativeTo
import com.google.devtools.build.lib.vfs.PathFragment

/** Builds different [InstrumentationOutput] objects with correct input parameters.  */
interface InstrumentationOutputBuilder {
    /** Sets the name of the [InstrumentationOutput].  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setName(name: String?): InstrumentationOutputBuilder?

    /**
     * Sets the relative or absolute path for write the [LocalInstrumentationOutput] or download
     * the redirect [InstrumentationOutput] in the downstream filesystem.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setDestination(destination: PathFragment?): InstrumentationOutputBuilder {
        return this
    }

    /** Specifies type of directory the output path is relative to.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setDestinationRelatedToType(
        relatedToType: DestinationRelativeTo?
    ): InstrumentationOutputBuilder {
        return this
    }

    /**
     * Provides the [CommandEnvironment] necessary for building the [ ].
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setCommandEnvironment(
        commandEnvironment: CommandEnvironment?
    ): InstrumentationOutputBuilder {
        return this
    }

    /** Specifies whether output parent directory should be created.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setCreateParent(createParent: Boolean): InstrumentationOutputBuilder?

    /** Builds the [InstrumentationOutput] object.  */
    fun build(): InstrumentationOutput?
}

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

/** Stores and publishes the instrumentation output information.  */
interface InstrumentationOutput {
    /** Creates the [OutputStream] for instrumentation output writes.  */
    @Throws(IOException::class)
    fun createOutputStream(): java.io.OutputStream?

    /** Publishes instrumentation output information to the [BuildToolLogCollection].  */
    fun publish(buildToolLogCollection: BuildToolLogCollection?)

    val pathString: String?
        /** Returns the string of output path.  */
        get() = null
}

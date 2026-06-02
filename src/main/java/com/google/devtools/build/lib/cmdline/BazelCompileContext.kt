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
package com.google.devtools.build.lib.cmdline

import com.google.auto.value.AutoValue

/**
 * BazelCompileContext records Bazel-specific information associated with a .bzl [ ] during bzl compilation.
 * 
 * 
 * Maintainer's note: This object is determined prior to the module's compilation in
 * BzlCompileFunction. It is saved in the `Module` used for compilation as [ ][Module.getClientData]. The `Module` used during .bzl evaluation is separate and
 * uses [BazelModuleContext] as client data.
 */
@AutoValue
abstract class BazelCompileContext {
    /** Label associated with the Starlark [net.starlark.java.eval.Module].  */
    abstract fun label(): com.google.devtools.build.lib.cmdline.Label?

    /** Returns the name of the module's .bzl file, as provided to the parser.  */
    abstract fun filename(): String?

    companion object {
        fun create(label: com.google.devtools.build.lib.cmdline.Label?, filename: String?): BazelCompileContext {
            return AutoValue_BazelCompileContext(label, filename)
        }
    }
}

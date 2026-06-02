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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.actions.Artifact

/** Structure for C++ module maps. Stores the name of the module and a .cppmap artifact.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class CppModuleMap(moduleMap: StarlarkInfo) {
    private val moduleMap: StarlarkInfo

    init {
        this.moduleMap = moduleMap
    }

    val artifact: Artifact
        get() {
            try {
                return moduleMap.getValue("file", Artifact::class.java)
            } catch (e: net.starlark.java.eval.EvalException) {
                throw java.lang.IllegalStateException(e)
            }
        }

    val name: String
        get() {
            try {
                return moduleMap.getValue("name", String::class.java)
            } catch (e: net.starlark.java.eval.EvalException) {
                throw java.lang.IllegalStateException(e)
            }
        }
}

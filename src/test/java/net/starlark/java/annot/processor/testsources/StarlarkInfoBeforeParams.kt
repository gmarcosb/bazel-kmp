// Copyright 2018 The Bazel Authors. All rights reserved.
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
package net.starlark.java.annot.processor.testsources

import net.starlark.java.eval.StarlarkInt

/**
 * Test case for a StarlarkCallable method which specifies StarlarkThread before other parameters.
 */
class StarlarkInfoBeforeParams : StarlarkValue {
    @StarlarkMethod(
        name = "skylark_info_wrong_order",
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "one",
            named = true
        ), net.starlark.java.annot.Param(name = "two", named = true), net.starlark.java.annot.Param(
            name = "three",
            named = true
        )],
        useStarlarkThread = true
    )
    fun threeArgMethod(thread: StarlarkThread?, one: String?, two: StarlarkInt?, three: String?): String {
        return "bar"
    }
}

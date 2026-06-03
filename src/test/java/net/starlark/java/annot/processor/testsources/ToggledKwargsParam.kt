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

import net.starlark.java.eval.Dict

/**
 * Test case for a StarlarkMethod which has a "extraKeywords" parameter which has enableOnlyWithFlag
 * set. (This is unsupported.)
 */
class ToggledKwargsParam : StarlarkValue {
    @StarlarkMethod(
        name = "toggled_kwargs_method",
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "one",
            named = true
        ), net.starlark.java.annot.Param(name = "two", named = true)],
        extraPositionals = net.starlark.java.annot.Param(name = "args"),
        extraKeywords = net.starlark.java.annot.Param(
            name = "kwargs",
            enableOnlyWithFlag = net.starlark.java.annot.processor.testsources.ToggledKwargsParam.Companion.FOO
        ),
        useStarlarkThread = true
    )
    fun toggledKwargsMethod(
        one: String?, two: StarlarkInt?, args: Sequence<*>?, kwargs: Dict<*, *>?, thread: StarlarkThread?
    ): String {
        return "cat"
    }

    companion object {
        private const val FOO = "-foo"
    }
}

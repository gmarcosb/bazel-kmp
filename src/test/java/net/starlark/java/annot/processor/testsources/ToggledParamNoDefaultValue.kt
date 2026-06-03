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
 * Test case for a StarlarkMethod method which has a parameter which may be disabled with a semantic
 * flag but has no default value.
 */
class ToggledParamNoDefaultValue : StarlarkValue {
    @StarlarkMethod(
        name = "no_default_value_method",
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "one",
            named = true,
            positional = true
        ), net.starlark.java.annot.Param(
            name = "two",
            named = true,
            enableOnlyWithFlag = net.starlark.java.annot.processor.testsources.ToggledParamNoDefaultValue.Companion.FOO,
            positional = true
        )]
    )
    fun noDisabledValueMethod(one: StarlarkInt?, two: StarlarkInt?): Int {
        return 42
    }

    companion object {
        private const val FOO = "-foo"
    }
}

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

import net.starlark.java.eval.StarlarkValue

/**
 * Test case for a StarlarkMethod method which has both enablingFlag and disablingFlag specified.
 */
class EnablingAndDisablingFlag : StarlarkValue {
    @StarlarkMethod(
        name = "someMethod",
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "one",
            named = true
        ), net.starlark.java.annot.Param(name = "two", named = true)],
        enableOnlyWithFlag = net.starlark.java.annot.processor.testsources.EnablingAndDisablingFlag.Companion.FOO,
        disableWithFlag = net.starlark.java.annot.processor.testsources.EnablingAndDisablingFlag.Companion.FOO
    )
    fun someMethod(one: String?, two: StarlarkInt?): String {
        return "foo"
    }

    companion object {
        const val FOO: String = "-foo"
    }
}

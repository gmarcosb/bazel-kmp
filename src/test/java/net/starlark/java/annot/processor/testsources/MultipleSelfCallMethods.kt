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

/** Test case for a class with multiple StarlarkMethod methods which have selfCall=true.  */
class MultipleSelfCallMethods : StarlarkValue {
    @StarlarkMethod(
        name = "selfCallMethod",
        selfCall = true,
        parameters = [net.starlark.java.annot.Param(
            name = "one",
            named = true
        ), net.starlark.java.annot.Param(name = "two", named = true)],
        documented = false
    )
    fun selfCallMethod(one: String?, two: StarlarkInt?): StarlarkInt? {
        return 0
    }

    @StarlarkMethod(name = "selfCallMethodTwo", selfCall = true, documented = false)
    fun selfCallMethodTwo(): StarlarkInt? {
        return 0
    }
}

// Copyright 2020 The Bazel Authors. All rights reserved.
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

/** Test case for a StarlarkMethod method which specifies an undocumented positional parameter.  */
class UndocumentedPositionalParam : StarlarkValue {
    @StarlarkMethod(
        name = "undocumented_positional",
        documented = false,
        parameters = [net.starlark.java.annot.Param(name = "one", documented = false)]
    )
    fun threeArgMethod(one: String?, kwargs: Dict<*, *>?): String {
        return "bar"
    }
}

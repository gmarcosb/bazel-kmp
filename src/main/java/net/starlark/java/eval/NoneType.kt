// Copyright 2019 The Bazel Authors. All rights reserved.
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
package net.starlark.java.eval

/** The type of the Starlark None value.  */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "NoneType",
    documented = false,
    doc = "The type of the Starlark None value."
)
@javax.annotation.concurrent.Immutable
class NoneType private constructor() : net.starlark.java.eval.StarlarkValue, net.starlark.java.syntax.TypeConstructor {
    @Throws(net.starlark.java.syntax.TypeConstructor.Failure::class)
    override fun createStarlarkType(argsTuple: com.google.common.collect.ImmutableList<net.starlark.java.syntax.TypeConstructor.Arg?>?): net.starlark.java.syntax.StarlarkType? {
        return net.starlark.java.syntax.Types.NONE_CONSTRUCTOR.createStarlarkType(argsTuple)
    }

    override fun getStarlarkType(semantics: net.starlark.java.eval.StarlarkSemantics?): net.starlark.java.syntax.StarlarkType? {
        return net.starlark.java.syntax.Types.NONE
    }

    override fun toString(): String {
        return "None"
    }

    override fun isImmutable(): Boolean {
        return true
    }

    override fun truth(): Boolean {
        return false
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        printer.append("None")
    }

    companion object {
        val NONE: NoneType = net.starlark.java.eval.NoneType()
    }
}

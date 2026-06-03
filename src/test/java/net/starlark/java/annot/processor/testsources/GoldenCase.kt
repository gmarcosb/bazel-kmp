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

/** Test source file verifying various proper uses of StarlarkMethod.  */
class GoldenCase : StarlarkValue {
    @StarlarkMethod(name = "struct_field_method", documented = false, structField = true)
    fun structFieldMethod(): String {
        return "foo"
    }

    @StarlarkMethod(name = "zero_arg_method", documented = false)
    fun zeroArgMethod(): Int {
        return 0
    }

    @StarlarkMethod(name = "zero_arg_method_with_thread", documented = false, useStarlarkThread = true)
    fun zeroArgMethodWithThread(thread: StarlarkThread?): Int {
        return 0
    }

    @StarlarkMethod(
        name = "three_arg_method",
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "one",
            named = true
        ), net.starlark.java.annot.Param(name = "two", named = true), net.starlark.java.annot.Param(
            name = "three",
            allowedTypes = [ParamType(type = String::class), ParamType(type = NoneType::class)],
            named = true,
            defaultValue = "None"
        )]
    )
    fun threeArgMethod(one: String?, two: StarlarkInt?, three: Any?): String {
        return "bar"
    }

    @StarlarkMethod(
        name = "three_arg_method_with_params_and_thread",
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
    fun threeArgMethodWithParams(
        one: String?, two: StarlarkInt?, three: String?, thread: StarlarkThread?
    ): String {
        return "baz"
    }

    @StarlarkMethod(
        name = "many_arg_method_mixing_positional_and_named",
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "one",
            positional = true,
            named = false
        ), net.starlark.java.annot.Param(name = "two", positional = true, named = true), net.starlark.java.annot.Param(
            name = "three",
            positional = true,
            named = true,
            defaultValue = "three"
        ), net.starlark.java.annot.Param(
            name = "four",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "five",
            positional = false,
            named = true,
            defaultValue = "five"
        ), net.starlark.java.annot.Param(name = "six", positional = false, named = true)]
    )
    fun manyArgMethodMixingPositionalAndNamed(
        one: String?, two: String?, three: String?, four: String?, five: String?, six: String?
    ): String {
        return "baz"
    }

    @StarlarkMethod(
        name = "two_arg_method_with_params_and_thread_and_kwargs",
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "one",
            named = true
        ), net.starlark.java.annot.Param(name = "two", named = true)],
        extraKeywords = net.starlark.java.annot.Param(name = "kwargs"),
        useStarlarkThread = true
    )
    fun twoArgMethodWithParamsAndInfoAndKwargs(
        one: String?, two: StarlarkInt?, kwargs: Dict<String?, Any?>?, thread: StarlarkThread?
    ): String {
        return "blep"
    }

    @StarlarkMethod(
        name = "two_arg_method_with_env_and_args_and_kwargs",
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "one",
            named = true
        ), net.starlark.java.annot.Param(name = "two", named = true)],
        extraPositionals = net.starlark.java.annot.Param(name = "args"),
        extraKeywords = net.starlark.java.annot.Param(name = "kwargs"),
        useStarlarkThread = true
    )
    fun twoArgMethodWithParamsAndInfoAndKwargs(
        one: String?, two: StarlarkInt?, args: Sequence<*>?, kwargs: Dict<*, *>?, thread: StarlarkThread?
    ): String {
        return "yar"
    }

    @StarlarkMethod(
        name = "selfCallMethod",
        selfCall = true,
        parameters = [net.starlark.java.annot.Param(
            name = "one",
            named = true
        ), net.starlark.java.annot.Param(name = "two", named = true)],
        documented = false
    )
    fun selfCallMethod(one: String?, two: StarlarkInt?): Int {
        return 0
    }

    @StarlarkMethod(
        name = "struct_field_method_with_semantics",
        documented = false,
        structField = true,
        useStarlarkSemantics = true
    )
    fun structFieldMethodWithSemantics(starlarkSemantics: StarlarkSemantics?): String {
        return "dragon"
    }

    @StarlarkMethod(
        name = "method_with_list_and_dict",
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "one",
            named = true
        ), net.starlark.java.annot.Param(name = "two", named = true)]
    )
    fun methodWithListandDict(one: Sequence<*>?, two: Dict<*, *>?): String {
        return "bar"
    }
}

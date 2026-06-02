// Copyright 2016 The Bazel Authors. All rights reserved.
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
package net.starlark.java.annot

/** An annotation for parameters of Starlark built-in functions.  */
@Retention(AnnotationRetention.RUNTIME)
annotation class Param(
    /**
     * Name of the parameter, as viewed from Starlark. Used for matching keyword arguments and for
     * generating documentation.
     */
    val name: String,
    /**
     * Documentation of the parameter.
     */
    val doc: String = "",
    /**
     * Determines whether the parameter appears in generated documentation. Set this to false to
     * suppress parameters whose use is intentionally restricted.
     * 
     * 
     * An undocumented parameter must be [.named] and may not be followed by positional
     * parameters or `**kwargs`.
     */
    val documented: Boolean = true,
    /**
     * Default value for the parameter, written as a Starlark expression (e.g. "False", "True", "[]",
     * "None").
     * 
     * 
     * If this is empty (the default), the parameter is treated as mandatory. (Thus an exception
     * will be thrown if left unspecified by the caller).
     * 
     * 
     * If the function implementation needs to distinguish the case where the caller does not
     * supply a value for this parameter, you can set the default to the magic string "unbound", which
     * maps to the sentinal object [net.starlark.java.eval.Starlark.UNBOUND] (which can't appear
     * in normal Starlark code).
     */
    val defaultValue: String = "",
    /**
     * List of allowed types for the parameter.
     * 
     * 
     * The array may be omitted, in which case the parameter accepts any value whose class is
     * assignable to the class of the parameter variable.
     * 
     * 
     * If a function should accept None, NoneType should be in this list.
     */
    val allowedTypes: Array<net.starlark.java.annot.ParamType> = [],
    /**
     * If true, the parameter may be specified as a named parameter. For example for an integer named
     * parameter `foo` of a method `bar`, then the method call will look like `bar(foo=1)`.
     * 
     * 
     * If false, then [.positional] must be true (otherwise there is no way to reference the
     * parameter via an argument).
     * 
     * 
     * If this parameter represents the 'extra positionals' (args) or 'extra keywords' (kwargs)
     * element of a method, this field has no effect.
     */
    val named: Boolean = false,
    /**
     * If true, the parameter may be specified as a positional parameter. For example for an integer
     * positional parameter `foo` of a method `bar`, then the method call will look like
     * `bar(1)`. If [.named] is `false`, then this will be the only way to call
     * `bar`.
     * 
     * 
     * If false, then [.named] must be true (otherwise there is no way to reference the
     * parameter via an argument)
     * 
     * 
     * Positional arguments should come first.
     * 
     * 
     * If this parameter represents the 'extra positionals' (args) or 'extra keywords' (kwargs)
     * element of a method, this field has no effect.
     */
    val positional: Boolean = true,
    /**
     * If non-empty, the annotated parameter will only be present if the given semantic flag is true.
     * (If the parameter is disabled, it may not be specified by a user, and the Java method will
     * always be invoked with the parameter set to its default value.)
     * 
     * 
     * Note that at most one of [.enableOnlyWithFlag] and [.disableWithFlag] can be
     * non-empty.
     * 
     * 
     * If [.enableOnlyWithFlag] is non-empty, then [.defaultValue] must also be
     * non-empty; mandatory parameters cannot be toggled by a flag.
     */
    val enableOnlyWithFlag: String = "",
    /**
     * If non-empty, the annotated parameter will only be present if the given semantic flag is false.
     * (If the parameter is disabled, it may not be specified by a user, and the Java method will
     * always be invoked with the parameter set to its default value.)
     * 
     * 
     * Note that at most one of [.enableOnlyWithFlag] and [.disableWithFlag] can be
     * non-empty.
     * 
     * 
     * If [.disableWithFlag] is non-empty, then [.defaultValue] must also be non-empty;
     * mandatory parameters cannot be toggled by a flag.
     */
    val disableWithFlag: String = ""
)

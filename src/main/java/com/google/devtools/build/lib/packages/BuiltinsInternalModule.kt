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
package com.google.devtools.build.lib.packages

// TODO(#11437): Note that if Stardoc's current design were to be long-lived, we'd want to factor
// out an API into starlarkbuildapi. As it is that almost certainly won't be necessary.
/**
 * The `_builtins` Starlark object, visible only to `@_builtins` .bzl files, supporting
 * access to internal APIs.
 */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "_builtins",
    category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN,
    documented = false,
    doc = ("A module accessible only to @_builtins .bzls, that permits access to the original "
            + "(uninjected) native builtins, as well as internal-only symbols not accessible to "
            + "users.")
)
class BuiltinsInternalModule(// _builtins.native
    private val uninjectedNativeObject: Any?, // _builtins.toplevel
    private val uninjectedToplevelObject: Any?, // _builtins.internal
    private val internalObject: Any?
) : net.starlark.java.eval.StarlarkValue {
    init {
        this.internalObject = internalObject
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        printer.append("<_builtins module>")
    }

    override fun isImmutable(): Boolean {
        return true
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "native", doc = ("A view of the <code>native</code> object as it would exist if builtins injection were"
                + " disabled. For example, if builtins injection provides a Starlark definition for"
                + " <code>cc_library</code> in <code>exported_rules</code>, then"
                + " <code>native.cc_library</code> in a user .bzl file would refer to that"
                + " definition, but <code>_builtins.native.cc_library</code> in a"
                + " <code>@_builtins</code> .bzl file would still be the one defined in Java code."
                + " (Note that for clarity and to avoid a conceptual cycle, the regular top-level"
                + " <code>native</code> object is not defined for <code>@_builtins</code> .bzl"
                + " files.)"), documented = false, structField = true
    )
    fun getUninjectedNativeObject(): Any? {
        return uninjectedNativeObject
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "toplevel", doc = ("A view of the top-level .bzl symbols that would exist if builtins injection were"
                + " disabled; analogous to <code>_builtins.native</code>. For example, if builtins"
                + " injection provides a Starlark definition for <code>CcInfo</code> in"
                + " <code>exported_toplevels</code>, then <code>_builtins.toplevel.CcInfo</code>"
                + " refers to the original Java definition, not the Starlark one. (Just as for"
                + " <code>_builtins.native</code>, the top-level <code>CcInfo</code> symbol is not"
                + " available to <code>@_builtins</code> .bzl files.)"), documented = false, structField = true
    )
    fun getUninjectedToplevelObject(): Any? {
        return uninjectedToplevelObject
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "internal",
        doc = ("A view of symbols that were registered (via the Java method"
                + "<code>ConfiguredRuleClassProvider#addStarlarkBuiltinsInternal</code>) to be made"
                + " available to <code>@_builtins</code> code but not necessarily user code."),
        documented = false,
        structField = true
    )
    fun getInternalObject(): Any? {
        return internalObject
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "get_flag",
        doc = ("Returns the value of a <code>StarlarkSemantics</code> flag, or a default value if it"
                + " could not be retrieved (either because the flag does not exist or because it was"
                + " not assigned an explicit value). Fails if the flag value exists but is not a"
                + " Starlark value."),
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "name",
            doc = "Name of the flag, without the leading dashes"
        ), net.starlark.java.annot.Param(
            name = "default", doc = "Value to return if flag was not set or does not exist. This should always be set"
                    + " to the same value as the flag's default value."
        )],
        useStarlarkThread = true
    )
    fun getFlag(name: String?, defaultValue: Any?, thread: net.starlark.java.eval.StarlarkThread): Any? {
        val value: Any? = thread.getSemantics().getGeneric(name, defaultValue)
        return net.starlark.java.eval.Starlark.fromJava(value, thread.mutability())
    }
}

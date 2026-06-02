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
package com.google.devtools.build.lib.starlarkbuildapi.apple

import com.google.devtools.build.docgen.annot.DocCategory
import com.google.devtools.build.lib.analysis.starlark.StarlarkRuleContext
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.eval.StarlarkValue

/** A configuration fragment for Objective C.  */
@StarlarkBuiltin(
    name = "objc",
    category = DocCategory.CONFIGURATION_FRAGMENT,
    doc = "A configuration fragment for Objective-C."
)
interface ObjcConfigurationApi : StarlarkValue {
    @get:StarlarkMethod(
        name = "copts_for_current_compilation_mode",
        structField = true,
        doc = ("Returns a list of default options to use for compiling Objective-C in the current "
                + "mode.")
    )
    val coptsForCompilationMode: ImmutableList<String?>?

    @StarlarkMethod(
        name = "uses_device_debug_entitlements",
        structField = true,
        doc = ("Returns whether device debug entitlements should be included when signing an "
                + "application.")
    )
    fun useDeviceDebugEntitlements(): Boolean

    @StarlarkMethod(
        name = "disallow_sdk_frameworks_attributes",
        structField = true,
        doc = "Returns whether sdk_frameworks and weak_sdk_frameworks are disallowed attributes."
    )
    fun disallowSdkFrameworksAttributes(): Boolean

    @StarlarkMethod(
        name = "alwayslink_by_default",
        structField = true,
        doc = "Returns whether objc_library and objc_import should default to alwayslink=True."
    )
    fun alwayslinkByDefault(): Boolean

    @StarlarkMethod(
        name = "target_should_alwayslink",
        documented = false,
        parameters = [Param(name = "ctx")],
        useStarlarkThread = true
    )
    @Throws(
        EvalException::class
    )
    fun targetShouldAlwayslink(ruleContext: StarlarkRuleContext?, thread: StarlarkThread?): Boolean

    @StarlarkMethod(
        name = "strip_executable_safely",
        structField = true,
        doc = ("Returns whether executable strip action should use flag -x, which does not break "
                + "dynamic symbol resolution.")
    )
    fun stripExecutableSafely(): Boolean

    @StarlarkMethod(
        name = "builtin_objc_strip_action",
        structField = true,
        doc = "Returns whether to emit a strip action as part of objc linking."
    )
    fun builtinObjcStripAction(): Boolean
}

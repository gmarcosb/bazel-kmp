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
import com.google.devtools.build.lib.starlarkbuildapi.core.StructApi
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.StarlarkValue

/** An interface for a configuration type containing info for Apple platforms and tools.  */
@StarlarkBuiltin(
    name = "apple",
    doc = "A configuration fragment for Apple platforms.",
    category = DocCategory.CONFIGURATION_FRAGMENT
)
interface AppleConfigurationApi : StarlarkValue {
    @get:StarlarkMethod(
        name = "single_arch_cpu",
        structField = true,
        doc = ("The single \"effective\" architecture for this configuration (e.g., <code>i386</code>"
                + " or <code>arm64</code>) in the context of rule logic that is only concerned with"
                + " a single architecture (such as <code>objc_library</code>, which registers"
                + " single-architecture compile actions).")
    )
    val singleArchitecture: String?

    @get:StarlarkMethod(
        name = "single_arch_platform",
        doc = ("The platform of the current configuration. This should only be invoked in a context "
                + "where only a single architecture may be supported; consider "
                + "<a href='#multi_arch_platform'>multi_arch_platform</a> for other cases."),
        structField = true
    )
    val singleArchPlatform: ApplePlatformApi?

    @get:Throws(EvalException::class)
    @get:StarlarkMethod(name = "apple_cpus", documented = false, structField = true)
    val appleCpusForStarlark: StructApi?

    @get:StarlarkMethod(name = "apple_platform_type", documented = false, structField = true)
    val applePlatformType: String?

    @get:Throws(EvalException::class)
    @get:StarlarkMethod(
        name = "xcode_version_flag",
        documented = false,
        structField = true,
        allowReturnNones = true
    )
    val xcodeVersionFlag: String?

    @StarlarkMethod(name = "ios_sdk_version_flag", documented = false, structField = true, allowReturnNones = true)
    @Throws(
        EvalException::class
    )
    fun iosSdkVersionFlag(): DottedVersionApi<*>?

    @StarlarkMethod(name = "macos_sdk_version_flag", documented = false, structField = true, allowReturnNones = true)
    @Throws(
        EvalException::class
    )
    fun macOsSdkVersionFlag(): DottedVersionApi<*>?

    @StarlarkMethod(name = "tvos_sdk_version_flag", documented = false, structField = true, allowReturnNones = true)
    @Throws(
        EvalException::class
    )
    fun tvOsSdkVersionFlag(): DottedVersionApi<*>?

    @StarlarkMethod(name = "watchos_sdk_version_flag", documented = false, structField = true, allowReturnNones = true)
    @Throws(
        EvalException::class
    )
    fun watchOsSdkVersionFlag(): DottedVersionApi<*>?

    @StarlarkMethod(name = "ios_minimum_os_flag", documented = false, structField = true, allowReturnNones = true)
    @Throws(
        EvalException::class
    )
    fun iosMinimumOsFlag(): DottedVersionApi<*>?

    @StarlarkMethod(name = "macos_minimum_os_flag", documented = false, structField = true, allowReturnNones = true)
    @Throws(
        EvalException::class
    )
    fun macOsMinimumOsFlag(): DottedVersionApi<*>?

    @StarlarkMethod(name = "tvos_minimum_os_flag", documented = false, structField = true, allowReturnNones = true)
    @Throws(
        EvalException::class
    )
    fun tvOsMinimumOsFlag(): DottedVersionApi<*>?

    @StarlarkMethod(name = "watchos_minimum_os_flag", documented = false, structField = true, allowReturnNones = true)
    @Throws(
        EvalException::class
    )
    fun watchOsMinimumOsFlag(): DottedVersionApi<*>?

    @StarlarkMethod(name = "prefer_mutual_xcode", documented = false, structField = true)
    @Throws(EvalException::class)
    fun shouldPreferMutualXcode(): Boolean

    @StarlarkMethod(name = "include_xcode_exec_requirements", documented = false, structField = true)
    @Throws(
        EvalException::class
    )
    fun includeXcodeExecRequirementsFlag(): Boolean
}

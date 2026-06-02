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
package com.google.devtools.build.lib.starlarkbuildapi.cpp

import com.google.devtools.build.docgen.annot.DocCategory
import com.google.devtools.build.lib.cmdline.Label
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Sequence
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.eval.StarlarkValue

/** The C++ configuration fragment.  */
@StarlarkBuiltin(name = "cpp", doc = "A configuration fragment for C++.", category = DocCategory.CONFIGURATION_FRAGMENT)
interface CppConfigurationApi<InvalidConfigurationExceptionT : Exception?>
    : StarlarkValue {
    @StarlarkMethod(name = "experimental_link_static_libraries_once", documented = false, useStarlarkThread = true)
    @Throws(
        EvalException::class
    )
    fun getExperimentalLinkStaticLibrariesOnce(thread: StarlarkThread?): Boolean

    @get:Throws(EvalException::class)
    @get:StarlarkMethod(
        name = "copts",
        structField = true,
        doc = ("The flags passed to Bazel by <a href=\"\${link user-manual#flag--copt}\">"
                + "<code>--copt</code></a> option.")
    )
    val copts: ImmutableList<String?>?

    @get:Throws(EvalException::class)
    @get:StarlarkMethod(
        name = "cxxopts",
        structField = true,
        doc = ("The flags passed to Bazel by <a href=\"\${link user-manual#flag--cxxopt}\">"
                + "<code>--cxxopt</code></a> option.")
    )
    val cxxopts: ImmutableList<String?>?

    @get:Throws(EvalException::class)
    @get:StarlarkMethod(
        name = "conlyopts",
        structField = true,
        doc = ("The flags passed to Bazel by <a href=\"\${link user-manual#flag--conlyopt}\">"
                + "<code>--conlyopt</code></a> option.")
    )
    val conlyopts: ImmutableList<String?>?

    @get:Throws(EvalException::class)
    @get:StarlarkMethod(
        name = "objccopts",
        structField = true,
        doc = ("The flags passed to Bazel by <a href=\"\${link user-manual#flag--objccopt}\">"
                + "<code>--objccopt</code></a> option.")
    )
    val objcopts: ImmutableList<String?>?

    @get:Throws(EvalException::class)
    @get:StarlarkMethod(
        name = "linkopts",
        structField = true,
        doc = ("The flags passed to Bazel by <a href=\"\${link user-manual#flag--linkopt}\">"
                + "<code>--linkopt</code></a> option.")
    )
    val linkopts: ImmutableList<String?>?

    @StarlarkMethod(
        name = "custom_malloc",
        allowReturnNones = true,
        structField = true,
        doc = ("Returns label pointed to by <a href=\"\${link user-manual#flag--custom_malloc}\">"
                + "<code>--custom_malloc</code></a> option. Can be accessed with"
                + " <a href=\"../globals/bzl.html#configuration_field\"><code>configuration_field"
                + "</code></a>:<br/>"
                + "<pre>attr.label(<br/>"
                + "    default = configuration_field(<br/>"
                + "        fragment = \"cpp\",<br/>"
                + "        name = \"custom_malloc\"<br/>"
                + "    )<br/>"
                + ")</pre>")
    )
    fun customMalloc(): Label?

    @StarlarkMethod(
        name = "do_not_use_macos_set_install_name",
        structField = true,
        documented = false,
        doc = "Deprecated, always true"
    )
    fun macosSetInstallName(): Boolean

    @StarlarkMethod(name = "force_pic", documented = false, useStarlarkThread = true)
    @Throws(EvalException::class)
    fun forcePicStarlark(thread: StarlarkThread?): Boolean

    @StarlarkMethod(name = "generate_llvm_lcov", documented = false, useStarlarkThread = true)
    @Throws(EvalException::class)
    fun generateLlvmLcovStarlark(thread: StarlarkThread?): Boolean

    @StarlarkMethod(name = "fdo_instrument", documented = false, useStarlarkThread = true, allowReturnNones = true)
    @Throws(
        EvalException::class
    )
    fun fdoInstrumentStarlark(thread: StarlarkThread?): String?

    @StarlarkMethod(name = "cs_fdo_instrument", documented = false, useStarlarkThread = true, allowReturnNones = true)
    @Throws(
        EvalException::class
    )
    fun csFdoInstrumentStarlark(thread: StarlarkThread?): String?

    @StarlarkMethod(name = "process_headers_in_dependencies", documented = false, useStarlarkThread = true)
    @Throws(
        EvalException::class
    )
    fun processHeadersInDependenciesStarlark(thread: StarlarkThread?): Boolean

    @StarlarkMethod(name = "save_feature_state", documented = false, useStarlarkThread = true)
    @Throws(EvalException::class)
    fun saveFeatureStateStarlark(thread: StarlarkThread?): Boolean

    @StarlarkMethod(name = "fission_active_for_current_compilation_mode", documented = false, useStarlarkThread = true)
    @Throws(
        EvalException::class
    )
    fun fissionActiveForCurrentCompilationModeStarlark(thread: StarlarkThread?): Boolean

    @StarlarkMethod(
        name = "apple_generate_dsym",
        doc = "Whether to generate Apple debug symbol(.dSYM) artifacts.",
        structField = true
    )
    fun appleGenerateDsym(): Boolean

    @StarlarkMethod(
        name = "objc_generate_linkmap",
        doc = "(Apple-only) Whether to generate linkmap artifacts.",
        structField = true
    )
    fun objcGenerateLinkmap(): Boolean

    @StarlarkMethod(
        name = "objc_should_strip_binary",
        structField = true,
        doc = "(Apple-only) whether to perform symbol and dead-code strippings on linked binaries."
    )
    fun objcShouldStripBinary(): Boolean

    @StarlarkMethod(name = "strip_opts", documented = false, useStarlarkThread = true)
    @Throws(EvalException::class)
    fun getStripOptsStarlark(thread: StarlarkThread?): Sequence<String?>?

    @StarlarkMethod(name = "should_strip_binaries", useStarlarkThread = true, documented = false)
    @Throws(EvalException::class)
    fun shouldStripBinariesForStarlark(thread: StarlarkThread?): Boolean

    @StarlarkMethod(name = "build_test_dwp", documented = false, useStarlarkThread = true)
    @Throws(EvalException::class)
    fun buildTestDwpIsActivatedStarlark(thread: StarlarkThread?): Boolean

    @StarlarkMethod(name = "grte_top", documented = false, useStarlarkThread = true, allowReturnNones = true)
    @Throws(
        EvalException::class
    )
    fun getLibcTopLabelStarlark(thread: StarlarkThread?): Label?

    @StarlarkMethod(name = "share_native_deps", documented = false, useStarlarkThread = true)
    @Throws(EvalException::class)
    fun shareNativeDepsStarlark(thread: StarlarkThread?): Boolean

    @StarlarkMethod(name = "disable_nocopts", documented = false, useStarlarkThread = true)
    @Throws(EvalException::class)
    fun disableNocoptsStarlark(thread: StarlarkThread?): Boolean

    @StarlarkMethod(name = "save_temps", documented = false, useStarlarkThread = true)
    @Throws(EvalException::class)
    fun getSaveTempsForStarlark(thread: StarlarkThread?): Boolean
}

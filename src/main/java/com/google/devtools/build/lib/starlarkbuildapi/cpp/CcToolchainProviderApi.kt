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
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.StarlarkValue

/**
 * This is a dummy interface which is not deleted because stardoc does not yet work for providers.
 * Delete this and point to Starlark implementation once it does.
 */
@StarlarkBuiltin(
    name = "CcToolchainInfo",
    category = DocCategory.PROVIDER,
    doc = "Information about the C++ compiler being used."
)
interface CcToolchainProviderApi : StarlarkValue {
    @StarlarkMethod(
        name = "needs_pic_for_dynamic_libraries",
        doc = ("Returns true if this rule's compilations should apply -fPIC, false otherwise. "
                + "Determines if we should apply -fPIC for this rule's C++ compilations depending "
                + "on the C++ toolchain and presence of `--force_pic` Bazel option."),
        parameters = [Param(
            name = "feature_configuration",
            doc = "Feature configuration to be queried.",
            positional = false,
            named = true
        )]
    )
    fun usePicForDynamicLibrariesFromStarlark(featureConfigurationApi: Any?) {
        throw UnsupportedOperationException(
            "Native CcToolchainInfo API no longer exists, use Starlark provider instead."
        )
    }

    @get:StarlarkMethod(
        name = "built_in_include_directories",
        doc = "Returns the list of built-in directories of the compiler.",
        structField = true
    )
    val builtInIncludeDirectoriesAsStrings: Unit
        get() {
            throw UnsupportedOperationException(
                "Native CcToolchainInfo API no longer exists, use Starlark provider instead."
            )
        }

    @get:StarlarkMethod(
        name = "all_files", doc = ("Returns all toolchain files (so they can be passed to actions using this "
                + "toolchain as inputs)."), structField = true
    )
    val allFilesForStarlark: Unit
        get() {
            throw UnsupportedOperationException(
                "Native CcToolchainInfo API no longer exists, use Starlark provider instead."
            )
        }

    @StarlarkMethod(
        name = "static_runtime_lib",
        doc = ("Returns the files from `static_runtime_lib` attribute (so they can be passed to actions "
                + "using this toolchain as inputs). The caller should check whether the "
                + "feature_configuration enables `static_link_cpp_runtimes` feature (if not, "
                + "neither `static_runtime_lib` nor `dynamic_runtime_lib` should be used), and "
                + "use `dynamic_runtime_lib` if dynamic linking mode is active."),
        parameters = [Param(
            name = "feature_configuration",
            doc = "Feature configuration to be queried.",
            positional = false,
            named = true
        )]
    )
    fun getStaticRuntimeLibForStarlark(featureConfiguration: Any?) {
        throw UnsupportedOperationException(
            "Native CcToolchainInfo API no longer exists, use Starlark provider instead."
        )
    }

    @StarlarkMethod(
        name = "dynamic_runtime_lib",
        doc = ("Returns the files from `dynamic_runtime_lib` attribute (so they can be passed to"
                + " actions using this toolchain as inputs). The caller can check whether the "
                + "feature_configuration enables `static_link_cpp_runtimes` feature (if not, neither"
                + " `static_runtime_lib` nor `dynamic_runtime_lib` have to be used), and use"
                + " `static_runtime_lib` if static linking mode is active."),
        parameters = [Param(
            name = "feature_configuration",
            doc = "Feature configuration to be queried.",
            positional = false,
            named = true
        )]
    )
    fun getDynamicRuntimeLibForStarlark(featureConfiguration: Any?) {
        throw UnsupportedOperationException(
            "Native CcToolchainInfo API no longer exists, use Starlark provider instead."
        )
    }

    @get:StarlarkMethod(
        name = "sysroot",
        structField = true,
        doc = ("Returns the sysroot to be used. If the toolchain compiler does not support "
                + "different sysroots, or the sysroot is the same as the default sysroot, then "
                + "this method returns <code>None</code>.")
    )
    val sysroot: Unit
        get() {
            throw UnsupportedOperationException(
                "Native CcToolchainInfo API no longer exists, use Starlark provider instead."
            )
        }

    @get:StarlarkMethod(name = "compiler", structField = true, doc = "C++ compiler.")
    val compiler: Unit
        get() {
            throw UnsupportedOperationException(
                "Native CcToolchainInfo API no longer exists, use Starlark provider instead."
            )
        }

    @get:StarlarkMethod(name = "libc", structField = true, doc = "libc version string.")
    val targetLibc: Unit
        get() {
            throw UnsupportedOperationException(
                "Native CcToolchainInfo API no longer exists, use Starlark provider instead."
            )
        }

    @StarlarkMethod(name = "cpu", structField = true, doc = "Target CPU of the C++ toolchain.")
    fun targetCpu() {
        throw UnsupportedOperationException(
            "Native CcToolchainInfo API no longer exists, use Starlark provider instead."
        )
    }

    @get:StarlarkMethod(
        name = "target_gnu_system_name",
        structField = true,
        doc = "The GNU System Name."
    )
    val targetGnuSystemName: Unit
        get() {
            throw UnsupportedOperationException(
                "Native CcToolchainInfo API no longer exists, use Starlark provider instead."
            )
        }

    @StarlarkMethod(name = "objcopy_executable", structField = true, doc = "The path to the objcopy binary.")
    fun objcopyExecutable() {
        throw UnsupportedOperationException(
            "Native CcToolchainInfo API no longer exists, use Starlark provider instead."
        )
    }

    @StarlarkMethod(name = "compiler_executable", structField = true, doc = "The path to the compiler binary.")
    fun compilerExecutable() {
        throw UnsupportedOperationException(
            "Native CcToolchainInfo API no longer exists, use Starlark provider instead."
        )
    }

    @StarlarkMethod(name = "preprocessor_executable", structField = true, doc = "The path to the preprocessor binary.")
    fun preprocessorExecutable() {
        throw UnsupportedOperationException(
            "Native CcToolchainInfo API no longer exists, use Starlark provider instead."
        )
    }

    @StarlarkMethod(name = "nm_executable", structField = true, doc = "The path to the nm binary.")
    fun nmExecutable() {
        throw UnsupportedOperationException(
            "Native CcToolchainInfo API no longer exists, use Starlark provider instead."
        )
    }

    @StarlarkMethod(name = "objdump_executable", structField = true, doc = "The path to the objdump binary.")
    fun objdumpExecutable() {
        throw UnsupportedOperationException(
            "Native CcToolchainInfo API no longer exists, use Starlark provider instead."
        )
    }

    @StarlarkMethod(name = "ar_executable", structField = true, doc = "The path to the ar binary.")
    fun arExecutable() {
        throw UnsupportedOperationException(
            "Native CcToolchainInfo API no longer exists, use Starlark provider instead."
        )
    }

    @StarlarkMethod(name = "strip_executable", structField = true, doc = "The path to the strip binary.")
    fun stripExecutable() {
        throw UnsupportedOperationException(
            "Native CcToolchainInfo API no longer exists, use Starlark provider instead."
        )
    }

    @StarlarkMethod(name = "ld_executable", structField = true, doc = "The path to the ld binary.")
    fun ldExecutable() {
        throw UnsupportedOperationException(
            "Native CcToolchainInfo API no longer exists, use Starlark provider instead."
        )
    }

    @StarlarkMethod(name = "gcov_executable", structField = true, doc = "The path to the gcov binary.")
    fun gcovExecutable() {
        throw UnsupportedOperationException(
            "Native CcToolchainInfo API no longer exists, use Starlark provider instead."
        )
    }
}

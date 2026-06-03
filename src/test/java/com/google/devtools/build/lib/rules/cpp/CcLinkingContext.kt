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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.actions.Artifact

/** Helper class for accessing information from the CcLinkingContext provider.  */
class CcLinkingContext private constructor(ccLinkingContext: StarlarkInfo) : CcLinkingContextApi {
    private val ccLinkingContext: StarlarkInfo

    init {
        this.ccLinkingContext = ccLinkingContext
    }

    /**
     * Wraps any input to the linker, be it libraries, linker scripts, linkstamps or linking options.
     */
    object LinkerInput {
        @Deprecated("Use only in tests")
        fun getOwner(linkerInput: StarlarkInfo): Label {
            try {
                return linkerInput.getValue("owner", Label::class.java)
            } catch (e: net.starlark.java.eval.EvalException) {
                throw com.google.common.base.VerifyException(e)
            }
        }

        @Deprecated("Use only in tests")
        fun getLibraries(linkerInput: StarlarkInfo): com.google.common.collect.ImmutableList<LibraryToLink?> {
            try {
                val libraries: com.google.common.collect.ImmutableList<LibraryToLink?> =
                    (linkerInput.getValue("libraries", MutableList::class.java) as MutableList<StarlarkInfo?>)
                        .stream().map<LibraryToLink?>(java.util.function.Function { value: StarlarkInfo? ->
                            LibraryToLink.Companion.wrap(value)
                        }).collect(com.google.common.collect.ImmutableList.toImmutableList<LibraryToLink?>())
                return libraries
            } catch (e: net.starlark.java.eval.EvalException) {
                throw com.google.common.base.VerifyException(e)
            }
        }

        @Deprecated("Use only in tests")
        fun getUserLinkFlags(linkerInput: StarlarkInfo): MutableList<String?>? {
            try {
                val userLinkFlags =
                    linkerInput.getValue("user_link_flags", MutableList::class.java) as MutableList<String?>?
                return userLinkFlags
            } catch (e: net.starlark.java.eval.EvalException) {
                throw com.google.common.base.VerifyException(e)
            }
        }

        @Deprecated("Use only in tests")
        @Throws(net.starlark.java.eval.EvalException::class)
        fun getNonCodeInputs(linkerInput: StarlarkInfo): MutableList<Artifact?>? {
            val additionalInputs: MutableList<Artifact?>? =
                linkerInput.getValue("additional_inputs", MutableList::class.java) as MutableList<Artifact?>?
            return additionalInputs
        }
    }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    @get:Deprecated("Only use in tests")
    val staticModeParamsForExecutableLibraries: MutableList<Artifact>
        get() {
            val libraryListBuilder: com.google.common.collect.ImmutableList.Builder<Artifact?> =
                com.google.common.collect.ImmutableList.builder<Artifact?>()
            for (libraryToLink in this.libraries.toList()) {
                if (libraryToLink.getStaticLibrary() != null) {
                    libraryListBuilder.add(libraryToLink.getStaticLibrary())
                } else if (libraryToLink.getPicStaticLibrary() != null) {
                    libraryListBuilder.add(libraryToLink.getPicStaticLibrary())
                } else if (libraryToLink.getInterfaceLibrary() != null) {
                    libraryListBuilder.add(libraryToLink.getInterfaceLibrary())
                } else {
                    libraryListBuilder.add(libraryToLink.getDynamicLibrary())
                }
            }
            return libraryListBuilder.build()
        }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    @get:Deprecated("Only use in tests")
    val staticModeParamsForDynamicLibraryLibraries: MutableList<Artifact>
        get() {
            val artifactListBuilder: com.google.common.collect.ImmutableList.Builder<Artifact?> =
                com.google.common.collect.ImmutableList.builder<Artifact?>()
            for (library in this.libraries.toList()) {
                if (library.getPicStaticLibrary() != null) {
                    artifactListBuilder.add(library.getPicStaticLibrary())
                } else if (library.getStaticLibrary() != null) {
                    artifactListBuilder.add(library.getStaticLibrary())
                } else if (library.getInterfaceLibrary() != null) {
                    artifactListBuilder.add(library.getInterfaceLibrary())
                } else {
                    artifactListBuilder.add(library.getDynamicLibrary())
                }
            }
            return artifactListBuilder.build()
        }

    @Deprecated("Use only in tests. @Deprecated")
    @Throws(net.starlark.java.eval.EvalException::class)
    fun getDynamicLibrariesForRuntime(linkingStatically: Boolean): MutableList<Artifact?> {
        return LibraryToLink.Companion.getDynamicLibrariesForRuntime(linkingStatically, this.libraries.toList())
    }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    @get:Deprecated("Use only in tests")
    val libraries: NestedSet<LibraryToLink?>
        get() {
            val libraries: NestedSetBuilder<LibraryToLink?> = NestedSetBuilder.linkOrder()
            for (linkerInput in this.linkerInputs.toList()) {
                libraries.addAll(LinkerInput.getLibraries(linkerInput))
            }
            return libraries.build()
        }

    @get:Deprecated("Use only in tests")
    val linkerInputs: NestedSet<StarlarkInfo?>
        get() {
            try {
                return Depset.cast(
                    ccLinkingContext.getValue("linker_inputs"), StarlarkInfo::class.java, "linker_inputs"
                )
            } catch (e: net.starlark.java.eval.EvalException) {
                throw com.google.common.base.VerifyException(e)
            }
        }

    @get:Deprecated("Only use in tests. Inline, using LinkerInputs.")
    val flattenedUserLinkFlags: com.google.common.collect.ImmutableList<String?>
        get() = this.linkerInputs.toList().stream()
            .flatMap({ linkerInput -> LinkerInput.getUserLinkFlags(linkerInput).stream() })
            .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())

    @get:Throws(net.starlark.java.eval.EvalException::class)
    @get:Deprecated("Only use in tests. Inline, using LinkerInputs.")
    val nonCodeInputs: NestedSet<Artifact?>
        get() {
            val nonCodeInputs: NestedSetBuilder<Artifact?> = NestedSetBuilder.linkOrder()
            for (linkerInput in this.linkerInputs.toList()) {
                nonCodeInputs.addAll(LinkerInput.getNonCodeInputs(linkerInput))
            }
            return nonCodeInputs.build()
        }

    companion object {
        fun of(ccLinkingContext: StarlarkInfo): CcLinkingContext {
            return CcLinkingContext(ccLinkingContext)
        }
    }
}

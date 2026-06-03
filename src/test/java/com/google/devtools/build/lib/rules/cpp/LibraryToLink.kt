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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.actions.Artifact

/**
 * Unwraps information for linking a library from a Starlark struct.
 */
class LibraryToLink private constructor(value: StarlarkInfo) {
    private val value: StarlarkInfo

    init {
        this.value = value
    }

    val staticLibrary: Artifact?
        get() = if (value.getValue("static_library") is Artifact) artifact else null

    val picStaticLibrary: Artifact?
        get() = if (value.getValue("pic_static_library") is Artifact) artifact else null

    val dynamicLibrary: Artifact?
        get() = if (value.getValue("dynamic_library") is Artifact) artifact else null

    val resolvedSymlinkDynamicLibrary: Artifact?
        get() = if (value.getValue("resolved_symlink_dynamic_library") is Artifact)
            artifact
        else
            null

    val interfaceLibrary: Artifact?
        get() = if (value.getValue("interface_library") is Artifact) artifact else null

    val resolvedSymlinkInterfaceLibrary: Artifact?
        get() = if (value.getValue("resolved_symlink_interface_library") is Artifact)
            artifact
        else
            null

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val objectFiles: com.google.common.collect.ImmutableList<Artifact?>?
        get() = net.starlark.java.eval.Sequence.cast<T?>(value.getValue("objects"), Artifact::class.java, "objects")
            .getImmutableList()

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val picObjectFiles: com.google.common.collect.ImmutableList<Artifact?>?
        get() = net.starlark.java.eval.Sequence.cast<T?>(
            value.getValue("pic_objects"),
            Artifact::class.java,
            "pic_objects"
        )
            .getImmutableList()

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val ltoCompilationContextBitcodeFiles: Dict<*, *>?
        get() {
            if (value.getValue("_lto_compilation_context") is StarlarkInfo) {
                return ctx.getValue("lto_bitcode_inputs", Dict::class.java)
            }
            return null
        }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val picLtoCompilationContextBitcodeFiles: Dict<*, *>?
        get() {
            if (value.getValue("_pic_lto_compilation_context") is StarlarkInfo) {
                return ctx.getValue("lto_bitcode_inputs", Dict::class.java)
            }
            return null
        }

    val alwayslink: Boolean
        get() = value.getValue("alwayslink") is Boolean && bool

    fun getDynamicLibraryForRuntimeOrNull(linkingStatically: Boolean): Artifact? {
        if (this.dynamicLibrary == null) {
            return null
        }
        if (linkingStatically && (this.staticLibrary != null || this.picStaticLibrary != null)) {
            return null
        }
        return this.dynamicLibrary
    }

    companion object {
        fun getDynamicLibrariesForRuntime(
            linkingStatically: Boolean, libraries: Iterable<LibraryToLink>
        ): com.google.common.collect.ImmutableList<Artifact?> {
            val dynamicLibrariesForRuntimeBuilder: com.google.common.collect.ImmutableList.Builder<Artifact?> =
                com.google.common.collect.ImmutableList.builder<Artifact?>()
            for (libraryToLink in libraries) {
                val artifact: Artifact? = libraryToLink.getDynamicLibraryForRuntimeOrNull(linkingStatically)
                if (artifact != null) {
                    dynamicLibrariesForRuntimeBuilder.add(artifact)
                }
            }
            return dynamicLibrariesForRuntimeBuilder.build()
        }

        fun getDynamicLibrariesForLinking(
            libraries: NestedSet<LibraryToLink?>
        ): com.google.common.collect.ImmutableList<Artifact?> {
            val dynamicLibrariesForLinkingBuilder: com.google.common.collect.ImmutableList.Builder<Artifact?> =
                com.google.common.collect.ImmutableList.builder<Artifact?>()
            for (libraryToLink in libraries.toList()) {
                if (libraryToLink.getInterfaceLibrary() != null) {
                    dynamicLibrariesForLinkingBuilder.add(libraryToLink.getInterfaceLibrary())
                } else if (libraryToLink.getDynamicLibrary() != null) {
                    dynamicLibrariesForLinkingBuilder.add(libraryToLink.getDynamicLibrary())
                }
            }
            return dynamicLibrariesForLinkingBuilder.build()
        }

        fun wrap(value: StarlarkInfo): LibraryToLink {
            return LibraryToLink(value)
        }

        fun wrap(libraries: NestedSet<StarlarkInfo?>): NestedSet<LibraryToLink?> {
            return NestedSetBuilder.wrap(
                Order.STABLE_ORDER,
                libraries.toList().stream().map(LibraryToLink::wrap)
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
            )
        }
    }
}

// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.auto.value.AutoBuilder
import com.google.devtools.build.lib.bazel.bzlmod.TagClass

/**
 * A module extension object, which can be used to perform arbitrary logic in order to create repos.
 * 
 * @param definingBzlFileLabel The .bzl file where the module extension object was originally
 * defined.
 * 
 * Note that if the extension object was then loaded and re-exported by a different .bzl file
 * before being used in a MODULE.bazel file, the output of this function may differ from the
 * corresponding ModuleExtensionUsage#getExtensionBzlFile and ModuleExtensionId#getBzlFileLabel.
 */
class ModuleExtension(
    implementation: net.starlark.java.eval.StarlarkCallable?,
    tagClasses: com.google.common.collect.ImmutableMap<String?, TagClass?>?,
    doc: java.util.Optional<String?>?,
    definingBzlFileLabel: com.google.devtools.build.lib.cmdline.Label?,
    location: net.starlark.java.syntax.Location?,
    envVariables: com.google.common.collect.ImmutableList<String?>?,
    val osDependent: Boolean,
    val archDependent: Boolean,
    val factsVersion: Int
) : net.starlark.java.eval.StarlarkValue {
    /** Builder for [ModuleExtension].  */
    @AutoBuilder
    abstract class Builder {
        abstract fun setDoc(value: java.util.Optional<String?>?): Builder?

        abstract fun setDefiningBzlFileLabel(value: com.google.devtools.build.lib.cmdline.Label?): Builder?

        abstract fun setLocation(value: net.starlark.java.syntax.Location?): Builder?

        abstract fun setImplementation(value: net.starlark.java.eval.StarlarkCallable?): Builder?

        abstract fun setTagClasses(value: com.google.common.collect.ImmutableMap<String?, TagClass?>?): Builder?

        abstract fun setEnvVariables(value: com.google.common.collect.ImmutableList<String?>?): Builder?

        abstract fun setOsDependent(osDependent: Boolean): Builder?

        abstract fun setArchDependent(archDependent: Boolean): Builder?

        abstract fun setFactsVersion(factsVersion: Int): Builder?

        abstract fun build(): ModuleExtension?
    }

    val implementation: net.starlark.java.eval.StarlarkCallable?
    val tagClasses: com.google.common.collect.ImmutableMap<String?, TagClass?>?
    val doc: java.util.Optional<String?>?
    val definingBzlFileLabel: com.google.devtools.build.lib.cmdline.Label?
    val location: net.starlark.java.syntax.Location?
    val envVariables: com.google.common.collect.ImmutableList<String?>?

    init {
        this.envVariables = envVariables
        this.location = location
        this.definingBzlFileLabel = definingBzlFileLabel
        this.doc = doc
        this.tagClasses = tagClasses
        this.implementation = implementation
        StarlarkCallable > java.util.Objects.requireNonNull<net.starlark.java.eval.StarlarkCallable?>(
            implementation,
            "implementation"
        )
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableMap<String?, TagClass?>?>(
            tagClasses,
            "tagClasses"
        )
        java.util.Objects.requireNonNull<java.util.Optional<String?>?>(doc, "doc")
        Label > java.util.Objects.requireNonNull<com.google.devtools.build.lib.cmdline.Label?>(
            definingBzlFileLabel,
            "definingBzlFileLabel"
        )
        Location > java.util.Objects.requireNonNull<net.starlark.java.syntax.Location?>(location, "location")
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableList<String?>?>(
            envVariables,
            "envVariables"
        )
    }

    companion object {
        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return AutoBuilder_ModuleExtension_Builder()
        }
    }
}

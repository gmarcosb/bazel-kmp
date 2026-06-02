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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec

/** A unique identifier for a [ModuleExtension].  */
@AutoCodec
class ModuleExtensionId(
    bzlFileLabel: com.google.devtools.build.lib.cmdline.Label?,
    extensionName: String?,
    isolationKey: java.util.Optional<IsolationKey?>?
) {
    /**
     * A unique identifier for a single isolated usage of a fixed module extension.
     * 
     * @param module The module which contains this isolated usage of a module extension.
     * @param usageExportedName The exported name of the first extension proxy for this usage.
     */
    @AutoCodec
    internal class IsolationKey(module: ModuleKey?, val usageExportedName: String?) {
        override fun toString(): String {
            return this.module.toString() + "+" + this.usageExportedName
        }

        val module: ModuleKey?

        init {
            this.module = module
            java.util.Objects.requireNonNull<ModuleKey?>(module, "module")
            java.util.Objects.requireNonNull<String?>(usageExportedName, "usageExportedName")
        }

        companion object {
            val LEXICOGRAPHIC_COMPARATOR: java.util.Comparator<IsolationKey?> =
                java.util.Comparator.comparing<IsolationKey?, ModuleKey?>(
                    IsolationKey::module,
                    ModuleKey.Companion.LEXICOGRAPHIC_COMPARATOR
                )
                    .thenComparing<String?>(IsolationKey::usageExportedName)

            fun create(module: ModuleKey?, usageExportedName: String?): IsolationKey {
                return IsolationKey(module, usageExportedName)
            }

            @Throws(com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException::class)
            fun fromString(s: String): IsolationKey {
                val isolationKeyParts: MutableList<String?> = com.google.common.base.Splitter.on("+").splitToList(s)
                return create(
                    ModuleKey.Companion.fromString(isolationKeyParts.get(0)), isolationKeyParts.get(1)
                )
            }
        }
    }

    val isInnate: Boolean
        get() = this.extensionName.contains(" ")

    override fun toString(): String {
        val isolationKeyPart: String =
            this.isolationKey.map<String>(java.util.function.Function { key: IsolationKey? -> "%" + key }).orElse("")
        return java.lang.String.format(
            "%s%%%s%s",
            this.bzlFileLabel.getUnambiguousCanonicalForm(), this.extensionName, isolationKeyPart
        )
    }

    val bzlFileLabel: com.google.devtools.build.lib.cmdline.Label?
    val extensionName: String?
    val isolationKey: java.util.Optional<IsolationKey?>?

    init {
        this.isolationKey = isolationKey
        this.extensionName = extensionName
        this.bzlFileLabel = bzlFileLabel
        java.util.Objects.requireNonNull<com.google.devtools.build.lib.cmdline.Label?>(bzlFileLabel, "bzlFileLabel")
        java.util.Objects.requireNonNull<String?>(extensionName, "extensionName")
        java.util.Objects.requireNonNull<java.util.Optional<IsolationKey?>?>(isolationKey, "isolationKey")
    }

    companion object {
        val LEXICOGRAPHIC_COMPARATOR: java.util.Comparator<ModuleExtensionId?>? =
            java.util.Comparator.comparing<ModuleExtensionId?, com.google.devtools.build.lib.cmdline.Label?>(
                ModuleExtensionId::bzlFileLabel
            )
                .thenComparing<String?>(ModuleExtensionId::extensionName)
                .thenComparing<java.util.Optional<IsolationKey?>?>(
                    ModuleExtensionId::isolationKey,
                    com.google.common.collect.Comparators.emptiesFirst<IsolationKey?>(IsolationKey.Companion.LEXICOGRAPHIC_COMPARATOR)
                )

        fun create(
            bzlFileLabel: com.google.devtools.build.lib.cmdline.Label?,
            extensionName: String?,
            isolationKey: java.util.Optional<IsolationKey?>?
        ): ModuleExtensionId {
            return ModuleExtensionId(bzlFileLabel, extensionName, isolationKey)
        }
    }
}

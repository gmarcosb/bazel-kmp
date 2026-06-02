// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.bzlmod.modcommand

import com.google.common.collect.*
import com.google.devtools.build.lib.cmdline.Label
import com.google.devtools.build.lib.cmdline.RepositoryMapping
import com.google.devtools.build.lib.server.FailureDetails.ModCommand.Code
import com.google.devtools.common.options.Converter
import com.google.devtools.common.options.Converters
import com.google.devtools.common.options.OptionsParsingException
import java.lang.String
import java.util.*
import kotlin.Int
import kotlin.toString

/**
 * Represents a reference to a module extension, parsed from a command-line argument in the form of
 * `<module><bzl_file_label>%<extension_name>`. The `<module>` part is parsed as a
 * [ModuleArg]. Valid examples include `@rules_java//java:extensions.bzl%toolchains`,
 * `rules_java@6.1.1//java:extensions.bzl%toolchains`, etc.
 */
@kotlin.jvm.JvmRecord
data class ExtensionArg(val moduleArg: ModuleArg?, val repoRelativeBzlLabel: String, val extensionName: String?) {
    /** Resolves this [ExtensionArg] to a [ModuleExtensionId].  */
    @Throws(InvalidArgumentException::class)
    fun resolveToExtensionId(
        modulesIndex: ImmutableMap<String?, ImmutableSet<ModuleKey?>?>?,
        depGraph: ImmutableMap<ModuleKey?, AugmentedModule?>?,
        moduleKeyToCanonicalNames: ImmutableMap<ModuleKey?, RepositoryName?>,
        baseModuleDeps: ImmutableBiMap<String?, ModuleKey?>?,
        baseModuleUnusedDeps: ImmutableBiMap<String?, ModuleKey?>?
    ): ModuleExtensionId {
        val refModules: ImmutableSet<ModuleKey?> =
            this.moduleArg!!
                .resolveToModuleKeys(
                    modulesIndex,
                    depGraph,
                    moduleKeyToCanonicalNames,
                    baseModuleDeps,
                    baseModuleUnusedDeps,  /* includeUnused= */
                    false,  /* warnUnused= */
                    false
                )
        if (refModules.size() != 1) {
            throw InvalidArgumentException(
                String.format(
                    "Module %s, as part of the extension specifier, should represent exactly one module"
                            + " version. Choose one of: %s.",
                    this.moduleArg, refModules
                ),
                Code.INVALID_ARGUMENTS
            )
        }
        val key: ModuleKey? = Iterables.getOnlyElement<ModuleKey?>(refModules)
        try {
            val label =
                Label.parseWithRepoContext(
                    this.repoRelativeBzlLabel,
                    RepoContext.of(
                        moduleKeyToCanonicalNames.get(key),  // Intentionally allow no repo mapping here: it's a repo-relative label!
                        RepositoryMapping.create(
                            ImmutableMap.of<kotlin.String?, RepositoryName?>(),
                            moduleKeyToCanonicalNames.get(key)
                        )
                    )
                )
            // TODO(wyv): support isolated extension usages?
            return ModuleExtensionId.Companion.create(label, this.extensionName, Optional.empty<IsolationKey?>())
        } catch (e: LabelSyntaxException) {
            throw InvalidArgumentException(
                String.format("bad label format in %s: %s", this.repoRelativeBzlLabel, e.getMessage()),
                Code.INVALID_ARGUMENTS,
                e
            )
        }
    }

    override fun toString(): kotlin.String {
        return this.moduleArg.toString() + this.repoRelativeBzlLabel + "%" + this.extensionName
    }

    /** Converter for [ExtensionArg].  */
    class ExtensionArgConverter : Converter.Contextless<ExtensionArg?>() {
        @Throws(OptionsParsingException::class)
        override fun convert(input: kotlin.String): ExtensionArg {
            val slashIdx: Int = input.indexOf('/'.code)
            if (slashIdx < 0) {
                throw OptionsParsingException("Invalid argument " + input + ": missing .bzl label")
            }
            val percentIdx: Int = input.indexOf('%'.code)
            if (percentIdx < slashIdx) {
                throw OptionsParsingException("Invalid argument " + input + ": missing extension name")
            }
            val moduleArg: ModuleArg = ModuleArgConverter.Companion.INSTANCE.convert(input.substring(0, slashIdx))
            return create(
                moduleArg, input.substring(slashIdx, percentIdx), input.substring(percentIdx + 1)
            )
        }

        override fun getTypeDescription(): kotlin.String {
            return "an <extension> identifier in the format of <module><bzl_label>%<extension_name>"
        }

        companion object {
            @kotlin.jvm.JvmField
            val INSTANCE: ExtensionArgConverter = ExtensionArgConverter()
        }
    }

    /** Converter for a comma-separated list of [ExtensionArg]s.  */
    class CommaSeparatedExtensionArgListConverter

        : Converter.Contextless<ImmutableList<ExtensionArg?>?>() {
        @Throws(OptionsParsingException::class)
        override fun convert(input: kotlin.String): ImmutableList<ExtensionArg?> {
            val args = Converters.CommaSeparatedNonEmptyOptionListConverter().convert(input)
            val extensionArgs = ImmutableList.Builder<ExtensionArg?>()
            for (arg in args) {
                extensionArgs.add(ExtensionArgConverter.Companion.INSTANCE.convert(arg))
            }
            return extensionArgs.build()
        }

        override fun getTypeDescription(): kotlin.String {
            return "a comma-separated list of <extension>s"
        }
    }

    init {
        Objects.requireNonNull<ModuleArg?>(moduleArg, "moduleArg")
        Objects.requireNonNull<kotlin.String?>(repoRelativeBzlLabel, "repoRelativeBzlLabel")
        Objects.requireNonNull<kotlin.String?>(extensionName, "extensionName")
    }

    companion object {
        @kotlin.jvm.JvmStatic
        fun create(
            moduleArg: ModuleArg?, repoRelativeBzlLabel: kotlin.String, extensionName: kotlin.String?
        ): ExtensionArg {
            return ExtensionArg(moduleArg, repoRelativeBzlLabel, extensionName)
        }
    }
}

// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.common.base.Ascii
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.common.collect.ImmutableSetMultimap
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule.ResolutionReason
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey
import com.google.devtools.build.lib.bazel.bzlmod.Version
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor.ResultNode
import java.io.PrintWriter
import java.lang.String
import java.util.*
import java.util.function.Function
import java.util.stream.Collectors
import kotlin.Boolean
import kotlin.IllegalArgumentException
import kotlin.toString

/**
 * Contains the output formatters for the graph-based results of [ModExecutor] that can be
 * specified using [ModOptions.outputFormat].
 */
object OutputFormatters {
    private val textFormatter: OutputFormatter = TextOutputFormatter()
    private val jsonFormatter: OutputFormatter = JsonOutputFormatter()
    private val graphvizFormatter: OutputFormatter = GraphvizOutputFormatter()

    @kotlin.jvm.JvmStatic
    fun getFormatter(format: ModOptions.OutputFormat?): OutputFormatter {
        return when (format) {
            ModOptions.OutputFormat.TEXT -> textFormatter
            ModOptions.OutputFormat.JSON -> jsonFormatter
            ModOptions.OutputFormat.GRAPH -> graphvizFormatter
            null -> throw IllegalArgumentException("Output format cannot be null.")
            else -> throw IllegalArgumentException("Unsupported output format: " + format)
        }
    }

    internal abstract class OutputFormatter {
        protected var result: ImmutableMap<ModuleKey?, ResultNode?>? = null
        protected var depGraph: ImmutableMap<ModuleKey?, AugmentedModule?>? = null
        protected var extensionRepos: ImmutableSetMultimap<ModuleExtensionId?, String?>? = null
        protected var extensionRepoImports: ImmutableMap<ModuleExtensionId?, ImmutableSetMultimap<String?, ModuleKey?>?>? =
            null
        protected var printer: PrintWriter? = null
        protected var options: ModOptions? = null

        /**
         * Compact representation of the data provided by the `--verbose` flag.
         * 
         * @param changedVersion The version from/to which the module was changed after resolution.
         * @param requestedByModules The list of modules who originally requested the selected version
         * in the case of Minimal-Version-Selection.
         */
        @kotlin.jvm.JvmRecord
        internal data class Explanation(
            val changedVersion: Version?,
            val resolutionReason: ResolutionReason?,
            val requestedByModules: ImmutableSet<ModuleKey?>?
        ) {
            /**
             * Gets the exact label that is printed next to the module if the `--verbose` flag is
             * enabled.
             */
            fun toExplanationString(unused: Boolean): String? {
                val changedVersionLabel: String? =
                    if (this.changedVersion == Version.Companion.EMPTY) "_" else this.changedVersion.toString()
                val toOrWasString = if (unused) "to" else "was"
                val reasonString =
                    if (this.requestedByModules != null)
                        this.requestedByModules.stream().map<String?>(Function { obj: ModuleKey? -> obj.toString() })
                            .collect(Collectors.joining(", "))
                    else
                        Ascii.toLowerCase(this.resolutionReason.toString())
                return String.format("(%s %s, cause %s)", toOrWasString, changedVersionLabel, reasonString)
            }

            init {
                Objects.requireNonNull<Version?>(changedVersion, "changedVersion")
                Objects.requireNonNull<ResolutionReason?>(resolutionReason, "resolutionReason")
            }

            companion object {
                fun create(
                    version: Version?, reason: ResolutionReason?, requestedByModules: ImmutableSet<ModuleKey?>?
                ): Explanation {
                    return Explanation(version, reason, requestedByModules)
                }
            }
        }

        /** Exposed API of the formatter during which the necessary objects are injected.  */
        fun output(
            result: ImmutableMap<ModuleKey?, ResultNode?>?,
            depGraph: ImmutableMap<ModuleKey?, AugmentedModule?>,
            extensionRepos: ImmutableSetMultimap<ModuleExtensionId?, kotlin.String?>?,
            extensionRepoImports: ImmutableMap<ModuleExtensionId?, ImmutableSetMultimap<kotlin.String?, ModuleKey?>?>?,
            printer: PrintWriter,
            options: ModOptions
        ) {
            this.result = result
            this.depGraph = depGraph
            this.extensionRepos = extensionRepos
            this.extensionRepoImports = extensionRepoImports
            this.printer = printer
            this.options = options
            output()
            printer.flush()
        }

        /** Internal implementation of the formatter output function.  */
        protected abstract fun output()

        /**
         * Exists only for testing, because normally the depGraph and options are injected inside the
         * public API call.
         */
        fun getExtraResolutionExplanation(
            key: ModuleKey,
            parent: ModuleKey?,
            depGraph: ImmutableMap<ModuleKey?, AugmentedModule?>,
            options: ModOptions
        ): Explanation? {
            this.depGraph = depGraph
            this.options = options
            return getExtraResolutionExplanation(key, parent)
        }

        /**
         * Returns `null` if the module version has not changed during resolution or if the module
         * is *&lt;root&gt;*.
         */
        protected fun getExtraResolutionExplanation(key: ModuleKey, parent: ModuleKey?): Explanation? {
            if (key == ModuleKey.Companion.ROOT) {
                return null
            }
            val module = depGraph!!.get(key)
            val parentModule = depGraph!!.get(parent)
            val repoName = parentModule!!.getAllDeps(options!!.getIncludeUnused()).get(key)
            val changedVersion: Version?
            var changedByModules: ImmutableSet<ModuleKey?>? = null
            val reason = parentModule.depReasons.get(repoName)
            val replacement =
                if (module!!.isUsed()) module else depGraph!!.get(parentModule.deps.get(repoName))
            if (reason != ResolutionReason.ORIGINAL) {
                if (!module.isUsed()) {
                    changedVersion = replacement!!.version
                } else {
                    val old = depGraph!!.get(parentModule.unusedDeps.get(repoName))
                    changedVersion = old!!.version
                }
                if (reason == ResolutionReason.MINIMAL_VERSION_SELECTION) {
                    changedByModules = replacement!!.originalDependants
                }
                return Explanation.Companion.create(changedVersion, reason, changedByModules)
            }
            return null
        }
    }
}

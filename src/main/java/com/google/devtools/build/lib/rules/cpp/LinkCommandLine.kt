// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.AbstractCommandLine

/**
 * Represents the command line of a linker invocation. It supports executables and dynamic libraries
 * as well as static libraries.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class LinkCommandLine private constructor(
    val actionName: String?,
    private val forcedToolPath: String?,
    splitCommandLine: Boolean,
    parameterFileType: ParameterFileType?,
    variables: CcToolchainVariables,
    featureConfiguration: FeatureConfiguration?
) : AbstractCommandLine() {
    private val variables: CcToolchainVariables

    // The feature config can be null for tests.
    private val featureConfiguration: FeatureConfiguration?

    private val splitCommandLine: Boolean
    private val parameterFileType: ParameterFileType?

    init {
        this.variables = variables
        this.featureConfiguration = featureConfiguration
        this.splitCommandLine = splitCommandLine
        this.parameterFileType = parameterFileType
    }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val linkerPathString: String?
        /** Returns the path to the linker.  */
        get() {
            if (forcedToolPath != null) {
                return forcedToolPath
            } else {
                if (!featureConfiguration.actionIsConfigured(actionName)) {
                    throw net.starlark.java.eval.Starlark.errorf(
                        "Expected action_config for '%s' to be configured",
                        actionName
                    )
                }
                return featureConfiguration.getToolPathForAction(actionName)
            }
        }

    @get:com.google.common.annotations.VisibleForTesting
    val buildVariables: CcToolchainVariables
        /** Returns the build variables used to template the crosstool for this linker invocation.  */
        get() = this.variables

    @Throws(CommandLineExpansionException::class)
    fun getParamCommandLine(
        inputMetadataProvider: InputMetadataProvider?, pathMapper: PathMapper?
    ): com.google.common.collect.ImmutableList<String?> {
        val argv: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        try {
            if (variables.isAvailable(LINKER_PARAM_FILE)) {
                // Filter out linker_param_file
                val linkerParamFile: String? =
                    variables
                        .getVariable(LINKER_PARAM_FILE, pathMapper)
                        .getStringValue(LINKER_PARAM_FILE, pathMapper)
                argv.addAll(
                    featureConfiguration
                        .getCommandLine(actionName, variables, inputMetadataProvider, pathMapper)
                        .stream()
                        .filter(java.util.function.Predicate { s: String? -> !s.contains(linkerParamFile) })
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())
                )
            } else {
                argv.addAll(
                    featureConfiguration.getCommandLine(
                        actionName, variables, inputMetadataProvider, pathMapper
                    )
                )
            }
        } catch (e: com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException) {
            throw CommandLineExpansionException(e.getMessage())
        }
        return argv.build()
    }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val commandLines: CommandLines
        get() {
            val builder: CommandLines.Builder = CommandLines.builder()
            builder.addSingleArgument(this.linkerPathString)
            builder.addCommandLine(this, this.paramFileInfo)
            return builder.build()
        }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val paramFileInfo: ParamFileInfo?
        get() {
            var paramFileInfo: ParamFileInfo? = null
            if (splitCommandLine) {
                try {
                    val formatString: java.util.Optional<String?> =
                        featureConfiguration
                            .getCommandLine(actionName, variables, null, PathMapper.NOOP)
                            .stream()
                            .filter(java.util.function.Predicate { s: String? -> s.contains("LINKER_PARAM_FILE_PLACEHOLDER") })
                            .findAny()
                    if (formatString.isPresent()) {
                        paramFileInfo =
                            ParamFileInfo.builder(parameterFileType)
                                .setFlagFormatString(
                                    formatString
                                        .get()
                                        .replace("%", "%%")
                                        .replace("LINKER_PARAM_FILE_PLACEHOLDER", "%s")
                                )
                                .setUseAlways(true)
                                .build()
                    }
                } catch (e: com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException) {
                    throw net.starlark.java.eval.EvalException(e)
                }
            }
            return paramFileInfo
        }

    @Throws(CommandLineExpansionException::class)
    public override fun arguments(): MutableList<String?> {
        return arguments(null, PathMapper.NOOP)
    }

    @Throws(CommandLineExpansionException::class)
    public override fun arguments(
        inputMetadataProvider: InputMetadataProvider?,
        pathMapper: PathMapper?
    ): MutableList<String?> {
        return getParamCommandLine(inputMetadataProvider, pathMapper)
    }

    /** A builder for a [LinkCommandLine].  */
    class Builder {
        private var forcedToolPath: String? = null
        private var splitCommandLine = false
        private var parameterFileType: ParameterFileType? = ParameterFileType.UNQUOTED
        private var variables: CcToolchainVariables? = null
        private var featureConfiguration: FeatureConfiguration? = null
        private var actionName: String? = null

        fun build(): LinkCommandLine {
            if (variables == null) {
                variables = CcToolchainVariables.Companion.empty()
            }

            return LinkCommandLine(
                actionName,
                forcedToolPath,
                splitCommandLine,
                parameterFileType,
                variables,
                featureConfiguration
            )
        }

        /** Use given tool path instead of the one from feature configuration  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun forceToolPath(forcedToolPath: String?): Builder {
            this.forcedToolPath = forcedToolPath
            return this
        }

        /** Sets the feature configuration for this link action.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setFeatureConfiguration(featureConfiguration: FeatureConfiguration?): Builder {
            this.featureConfiguration = featureConfiguration
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setSplitCommandLine(splitCommandLine: Boolean): Builder {
            this.splitCommandLine = splitCommandLine
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setParameterFileType(parameterFileType: ParameterFileType?): Builder {
            this.parameterFileType = parameterFileType
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setBuildVariables(variables: CcToolchainVariables?): Builder {
            this.variables = variables
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setActionName(actionName: String?): Builder {
            this.actionName = actionName
            return this
        }
    }

    companion object {
        private const val LINKER_PARAM_FILE = "linker_param_file"
    }
}

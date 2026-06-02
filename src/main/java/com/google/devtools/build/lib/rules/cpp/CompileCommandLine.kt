// Copyright 2017 The Bazel Authors. All rights reserved.
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

/** The compile command line for the C++ compile action.  */
class CompileCommandLine private constructor(
    coptsFilter: CoptsFilter,
    featureConfiguration: FeatureConfiguration?,
    variables: CcToolchainVariables?,
    actionName: String?
) {
    private val coptsFilter: CoptsFilter
    private val featureConfiguration: FeatureConfiguration
    private val variables: CcToolchainVariables?
    private val actionName: String?

    init {
        this.coptsFilter = coptsFilter
        this.featureConfiguration =
            com.google.common.base.Preconditions.checkNotNull<FeatureConfiguration>(featureConfiguration)
        this.variables = variables
        this.actionName = actionName
    }

    /** Returns the environment variables that should be set for C++ compile actions.  */
    @Throws(CommandLineExpansionException::class)
    fun getEnvironment(pathMapper: PathMapper?): com.google.common.collect.ImmutableMap<String?, String?>? {
        try {
            return featureConfiguration.getEnvironmentVariables(actionName, variables, pathMapper)
        } catch (e: com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException) {
            throw CommandLineExpansionException(e.getMessage())
        }
    }

    @get:com.google.common.annotations.VisibleForTesting
    val toolPath: String?
        /** Returns the tool path for the compilation based on the current feature configuration.  */
        get() {
            com.google.common.base.Preconditions.checkArgument(
                featureConfiguration.actionIsConfigured(actionName),
                "Expected action_config for '%s' to be configured",
                actionName
            )
            return featureConfiguration.getToolPathForAction(actionName)
        }

    /**
     * Returns the arguments for the compilation.
     * 
     * @param overwrittenVariables: Variables that will overwrite original build variables. When null,
     * unmodified original variables are used.
     * @param pathMapper: The path mapper to remap paths within the output directory.
     */
    @Throws(CommandLineExpansionException::class)
    fun getArguments(
        overwrittenVariables: CcToolchainVariables?, pathMapper: PathMapper
    ): MutableList<String?> {
        return getArgumentsWithCompilerOptions(
            pathMapper, getCompilerOptions(overwrittenVariables, pathMapper)
        )
    }

    /**
     * Returns the arguments for the compilation when compilerOptions have already been generated.
     * 
     * @param pathMapper: The path mapper to remap paths within the output directory.
     * @param compilerOptions: The compiler options to use. Essentially all arguments except the tool
     * itself.
     */
    fun getArgumentsWithCompilerOptions(
        pathMapper: PathMapper, compilerOptions: MutableList<String?>?
    ): MutableList<String?> {
        val commandLine: MutableList<String?> = java.util.ArrayList<String?>()
        // first: The command name.
        commandLine.add(getToolPathForCommandLine(pathMapper))
        // second: The compiler options.
        commandLine.addAll(compilerOptions!!)
        return commandLine
    }

    /**
     * Returns the arguments for the compilation when using a parameter file.
     * 
     * @param pathMapper: The path mapper to remap paths within the output directory.
     * @param parameterFilePath: The path to the parameter file. When null, the arguments will be
     * returned without using a parameter file.
     */
    fun getArgumentsWithParameterFile(
        pathMapper: PathMapper, parameterFilePath: PathFragment
    ): MutableList<String?> {
        val commandLine: MutableList<String?> = java.util.ArrayList<String?>()
        // first: The command name.
        commandLine.add(getToolPathForCommandLine(pathMapper))
        // second: The parameter file path.
        commandLine.add("@" + parameterFilePath.getSafePathString())
        return commandLine
    }

    private fun getToolPathForCommandLine(pathMapper: PathMapper): String? {
        if (pathMapper.isNoop()) {
            return this.toolPath
        } else {
            // getToolPath() ultimately returns a PathFragment's getSafePathString(), so its safe to
            // reparse it here with no risk of e.g. altering a user-specified absolute path.
            return pathMapper.map(PathFragment.create(this.toolPath)).getSafePathString()
        }
    }

    /**
     * Returns [CommandLine] instance that contains the exactly same command line as the [ ].
     * 
     * @param cppCompileAction - [CppCompileAction] owning this [CompileCommandLine].
     */
    fun getFilteredFeatureConfigurationCommandLine(cppCompileAction: CppCompileAction): CommandLine {
        return object : AbstractCommandLine() {
            @Throws(CommandLineExpansionException::class)
            public override fun arguments(): Iterable<String?> {
                val overwrittenVariables: CcToolchainVariables? = cppCompileAction.getOverwrittenVariables()
                val compilerOptions = getCompilerOptions(overwrittenVariables, PathMapper.NOOP)
                return com.google.common.collect.ImmutableList.builder<String?>().add(this.toolPath)
                    .addAll(compilerOptions).build()
            }
        }
    }

    @Throws(CommandLineExpansionException::class)
    fun getCompilerOptions(
        overwrittenVariables: CcToolchainVariables?, pathMapper: PathMapper?
    ): MutableList<String?> {
        try {
            val options: MutableList<String?> = java.util.ArrayList<String?>()

            var updatedVariables: CcToolchainVariables? = variables
            if (variables != null && overwrittenVariables != null) {
                val variablesBuilder: com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.Builder =
                    CcToolchainVariables.Companion.builder(variables)
                variablesBuilder.addAllNonTransitive(overwrittenVariables)
                updatedVariables = variablesBuilder.build()
            }
            addFilteredOptions(
                options,
                featureConfiguration.getPerFeatureExpansions(actionName, updatedVariables, pathMapper)
            )

            return options
        } catch (e: com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException) {
            throw CommandLineExpansionException(e.getMessage())
        }
    }

    // For each option in 'in', add it to 'out' unless it is matched by the 'coptsFilter' regexp.
    private fun addFilteredOptions(
        out: MutableList<String?>,
        expandedFeatures: MutableList<com.google.devtools.build.lib.util.Pair<String?, MutableList<String?>?>>
    ) {
        for (pair in expandedFeatures) {
            if (pair.getFirst() == CppRuleClasses.UNFILTERED_COMPILE_FLAGS_FEATURE_NAME) {
                out.addAll(pair.getSecond())
                continue
            }
            // We do not uses Java's stream API here as it causes a substantial overhead compared to the
            // very little work that this is actually doing.
            for (flag in pair.getSecond()) {
                if (coptsFilter.passesFilter(flag)) {
                    out.add(flag)
                }
            }
        }
    }

    fun getVariables(): CcToolchainVariables? {
        return variables
    }

    /**
     * Returns all user provided copts flags.
     * 
     * 
     * TODO(b/64108724): Get rid of this method when we don't need to parse copts to collect
     * include directories anymore (meaning there is a way of specifying include directories using an
     * explicit attribute, not using platform-dependent garbage bag that copts is).
     */
    fun getCopts(pathMapper: PathMapper?): com.google.common.collect.ImmutableList<String?> {
        if (variables.isAvailable(CompileBuildVariables.USER_COMPILE_FLAGS.getVariableName())) {
            try {
                return CcToolchainVariables.Companion.toStringList(
                    variables, CompileBuildVariables.USER_COMPILE_FLAGS.getVariableName(), pathMapper
                )
            } catch (e: com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException) {
                throw java.lang.IllegalStateException(
                    "Should not happen - 'user_compile_flags' should be a string list, but wasn't.", e
                )
            }
        } else {
            return com.google.common.collect.ImmutableList.of<String?>()
        }
    }

    /** A builder for a [CompileCommandLine].  */
    class Builder private constructor(coptsFilter: CoptsFilter?, actionName: String?) {
        private var coptsFilter: CoptsFilter?
        private var featureConfiguration: FeatureConfiguration? = null
        private var variables: CcToolchainVariables? = CcToolchainVariables.Companion.empty()
        private val actionName: String?

        fun build(): CompileCommandLine {
            return CompileCommandLine(
                com.google.common.base.Preconditions.checkNotNull<CoptsFilter?>(coptsFilter),
                com.google.common.base.Preconditions.checkNotNull<FeatureConfiguration?>(featureConfiguration),
                com.google.common.base.Preconditions.checkNotNull<CcToolchainVariables?>(variables),
                com.google.common.base.Preconditions.checkNotNull<String?>(actionName)
            )
        }

        init {
            this.coptsFilter = coptsFilter
            this.actionName = actionName
        }

        /** Sets the feature configuration for this compile action.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setFeatureConfiguration(featureConfiguration: FeatureConfiguration?): Builder {
            this.featureConfiguration = featureConfiguration
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setVariables(variables: CcToolchainVariables?): Builder {
            this.variables = variables
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @com.google.common.annotations.VisibleForTesting
        fun setCoptsFilter(filter: CoptsFilter?): Builder {
            this.coptsFilter = com.google.common.base.Preconditions.checkNotNull<CoptsFilter?>(filter)
            return this
        }
    }

    companion object {
        fun builder(coptsFilter: CoptsFilter?, actionName: String?): Builder {
            return com.google.devtools.build.lib.rules.cpp.CompileCommandLine.Builder(coptsFilter, actionName)
        }
    }
}

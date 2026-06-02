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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.analysis.AnalysisOptions

/**
 * A BuildRequest represents a single invocation of the build tool by a user. A request specifies a
 * list of targets to be built for a single configuration, a pair of output/error streams, and
 * additional options such as --keep_going, --jobs, etc.
 */
class BuildRequest private constructor(
  /** The name of the Blaze command that the user invoked. Used for --announce.  */
  @kotlin.jvm.JvmField val commandName: String?,
  options: com.google.devtools.common.options.OptionsParsingResult,
  startupOptions: com.google.devtools.common.options.OptionsParsingResult?,
  targets: MutableList<String?>?,
  outErr: OutErr?,
  id: UUID?,
  startTimeMillis: Long,
  needsInstrumentationFilter: Boolean,
  runTests: Boolean,
  checkForActionConflicts: Boolean,
  reportIncompatibleTargets: Boolean
) : com.google.devtools.common.options.OptionsProvider {
    /** A Builder class to help create instances of BuildRequest.  */
    class Builder private constructor() {
        private var id: UUID? = null
        private var options: com.google.devtools.common.options.OptionsParsingResult? = null
        private var startupOptions: com.google.devtools.common.options.OptionsParsingResult? = null
        private var commandName: String? = null
        private var outErr: OutErr? = null
        private var targets: MutableList<String?>? = null
        private var startTimeMillis: Long = 0 // milliseconds since UNIX epoch.
        private var needsInstrumentationFilter = false
        private var runTests = false
        private var checkForActionConflicts = true
        private var reportIncompatibleTargets = true

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setId(id: UUID?): Builder {
            this.id = id
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setOptions(options: com.google.devtools.common.options.OptionsParsingResult): Builder {
            this.options = options
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setStartupOptions(startupOptions: com.google.devtools.common.options.OptionsParsingResult?): Builder {
            this.startupOptions = startupOptions
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setCommandName(commandName: String?): Builder {
            this.commandName = commandName
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setOutErr(outErr: OutErr?): Builder {
            this.outErr = outErr
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setTargets(targets: MutableList<String?>?): Builder {
            this.targets = targets
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setStartTimeMillis(startTimeMillis: Long): Builder {
            this.startTimeMillis = startTimeMillis
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setNeedsInstrumentationFilter(needsInstrumentationFilter: Boolean): Builder {
            this.needsInstrumentationFilter = needsInstrumentationFilter
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setRunTests(runTests: Boolean): Builder {
            this.runTests = runTests
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setCheckforActionConflicts(checkForActionConflicts: Boolean): Builder {
            this.checkForActionConflicts = checkForActionConflicts
            return this
        }

        /**
         * If true, build status depends on whether or not requested targets are platform-compatible
         * ([com.google.devtools.build.lib.analysis.IncompatiblePlatformProvider]). If false, this
         * doesn't matter.
         * 
         * 
         * This should be true for builds (where users care if their targets produce meaningful
         * output) and false for queries (where users want to understand target relationships or
         * diagnose why incompatible targets are incompatible).
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setReportIncompatibleTargets(report: Boolean): Builder {
            this.reportIncompatibleTargets = report
            return this
        }

        fun build(): BuildRequest {
            return BuildRequest(
                commandName,
                options,
                startupOptions,
                targets,
                outErr,
                id,
                startTimeMillis,
                needsInstrumentationFilter,
                runTests,
                checkForActionConflicts,
                reportIncompatibleTargets
            )
        }
    }

    private val id: UUID?
    private val optionsCache: com.github.benmanes.caffeine.cache.LoadingCache<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?, com.google.common.base.Optional<com.google.devtools.common.options.OptionsBase?>?>

    /**
     * Since the OptionsProvider interface is used by many teams, this method is String-keyed even
     * though it should always contain labels for our purposes. Consumers of this method should
     * probably use the [BuildOptions.labelizeStarlarkOptions] method before doing meaningful
     * work with the results.
     */
    val starlarkOptions: MutableMap<String?, Any?>?
    val starlarkOptionsAllowingMultiple: MutableSet<String?>?
    val scopesAttributes: MutableMap<String?, String?>?
    val onLeaveScopeValues: MutableMap<String?, Any?>?

    /** Returns the human-readable description of the non-default options for this build request.  */
    /** A human-readable description of all the non-default option settings.  */
    @kotlin.jvm.JvmField
    val optionsDescription: String

    /** Returns the name of the Blaze command that the user invoked.  */

    private val outErr: OutErr?

    /** Returns the (immutable) list of targets to build in commandline form.  */
    @kotlin.jvm.JvmField
    val targets: MutableList<String?>?

    /**
     * Return the time (according to System.currentTimeMillis()) at which the service of this request
     * was started.
     */
    val startTime: Long // milliseconds since UNIX epoch.

    private val needsInstrumentationFilter: Boolean
    val isRunningInEmacs: Boolean
    private val runTests: Boolean
    val checkForActionConflicts: Boolean
    private val reportIncompatibleTargets: Boolean
    private val userOptions: com.google.common.collect.ImmutableMap<String?, String?>

    init {
        this.optionsDescription = OptionsUtils.asShellEscapedString(options)
        this.outErr = outErr
        this.targets = targets
        this.id = id
        this.startTime = startTimeMillis
        this.userOptions =
            if (options.getUserOptions() == null)
                com.google.common.collect.ImmutableMap.of<String?, String?>()
            else
                com.google.common.collect.ImmutableMap.copyOf<String?, String?>(options.getUserOptions())
        this.optionsCache =
            Caffeine.newBuilder()
                .build<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?, com.google.common.base.Optional<com.google.devtools.common.options.OptionsBase?>?>(
                    com.github.benmanes.caffeine.cache.CacheLoader { key: java.lang.Class<out com.google.devtools.common.options.OptionsBase?>? ->
                        var result: com.google.devtools.common.options.OptionsBase? = options.getOptions(key)
                        if (result == null && startupOptions != null) {
                            result = startupOptions.getOptions(key)
                        }
                        com.google.common.base.Optional.fromNullable<com.google.devtools.common.options.OptionsBase?>(
                            result
                        )
                    })
        this.starlarkOptions = options.getStarlarkOptions()
        this.starlarkOptionsAllowingMultiple = options.getStarlarkOptionsAllowingMultiple()
        this.scopesAttributes = options.getScopesAttributes()
        this.onLeaveScopeValues = options.getOnLeaveScopeValues()
        this.needsInstrumentationFilter = needsInstrumentationFilter
        this.runTests = runTests
        this.checkForActionConflicts = checkForActionConflicts
        this.reportIncompatibleTargets = reportIncompatibleTargets

        for (optionsClass in MANDATORY_OPTIONS) {
            com.google.common.base.Preconditions.checkNotNull(getOptions(optionsClass))
        }

        // All this, just to pass a global boolean from the client to the server. :(
        this.isRunningInEmacs = options.getOptions<UiOptions?>(UiOptions::class.java).getRunningInEmacs()
    }

    val isMultiConfigBuild: Boolean
        /**
         * Return whether this BuildRequest contains multiple top-level configs
         * 
         * 
         * Note: The ability to have a multi-top-level-config build is currently completely disabled.
         * However, certain parts of the infra would fail horribly if it was ever enabled at all so
         * keeping this flag for those parts to check as a sort of mild future-proofing.
         */
        get() = false

    val explicitCommandLineStarlarkOptions: MutableMap<String?, Any?>?
        get() {
            throw java.lang.UnsupportedOperationException("No known callers to this implementation")
        }

    /**
     * Returns the list of options that were parsed from either a user blazerc file or the command
     * line.
     */
    override fun getUserOptions(): com.google.common.collect.ImmutableMap<String?, String?> {
        return userOptions
    }

    /** Returns a unique identifier that universally identifies this build.  */
    fun getId(): UUID? {
        return id
    }

    /** Returns true if tests should be run by the build tool.  */
    fun shouldRunTests(): Boolean {
        return runTests
    }

    /**
     * Returns the output/error streams to which errors and progress messages should be sent during
     * the fulfillment of this request.
     */
    fun getOutErr(): OutErr? {
        return outErr
    }

    override fun <T : com.google.devtools.common.options.OptionsBase?> getOptions(clazz: java.lang.Class<T?>?): T? {
        return optionsCache.get(clazz).orNull() as T?
    }

    val buildOptions: BuildRequestOptions?
        /** Returns the set of command-line options specified for this request.  */
        get() = getOptions<BuildRequestOptions?>(BuildRequestOptions::class.java)

    val packageOptions: PackageOptions?
        /** Returns the set of options related to the loading phase.  */
        get() = getOptions<PackageOptions?>(PackageOptions::class.java)

    val loadingOptions: LoadingOptions?
        /** Returns the set of options related to the loading phase.  */
        get() = getOptions<LoadingOptions?>(LoadingOptions::class.java)

    val viewOptions: AnalysisOptions?
        /** Returns the set of command-line options related to the view specified for this request.  */
        get() = getOptions<T?>(AnalysisOptions::class.java)

    val keepGoing: Boolean
        /** Returns the value of the --keep_going option.  */
        get() = getOptions<KeepGoingOption?>(KeepGoingOption::class.java).getKeepGoing()

    val loadingPhaseThreadCount: Int
        /** Returns the value of the --loading_phase_threads option.  */
        get() = getOptions<LoadingPhaseThreadsOption?>(LoadingPhaseThreadsOption::class.java).getThreads()

    val executionOptions: ExecutionOptions?
        /** Returns the set of execution options specified for this request.  */
        get() = getOptions<ExecutionOptions?>(ExecutionOptions::class.java)

    fun needsInstrumentationFilter(): Boolean {
        return needsInstrumentationFilter
    }

    /**
     * Validates the options for this BuildRequest.
     * 
     * 
     * Issues warnings or throws `InvalidConfigurationException` for option settings that
     * conflict.
     * 
     * @return list of warnings
     */
    fun validateOptions(): MutableList<String?> {
        val warnings: MutableList<String?> = java.util.ArrayList<String?>()

        val localTestJobs: Int = this.executionOptions.getLocalTestJobs()
        val jobs: Int = this.buildOptions.getJobs()
        if (localTestJobs > jobs) {
            warnings.add(
                java.lang.String.format(
                    "High value for --local_test_jobs: %d. This exceeds the value for --jobs: "
                            + "%d. Only up to %d local tests will run concurrently.",
                    localTestJobs, jobs, jobs
                )
            )
        }

        return warnings
    }

    val topLevelArtifactContext: TopLevelArtifactContext?
        /** Creates a new TopLevelArtifactContext from this build request.  */
        get() {
            val buildOptions: BuildRequestOptions? = this.buildOptions
            return TopLevelArtifactContext(
                getOptions<ExecutionOptions?>(ExecutionOptions::class.java).getTestStrategy() == "exclusive",
                getOptions<BuildEventProtocolOptions?>(BuildEventProtocolOptions::class.java).getExpandFilesets(),
                OutputGroupInfo.determineOutputGroups(
                    buildOptions.getOutputGroups(),
                    validationMode(),  /* shouldRunTests= */
                    shouldRunTests()
                )
            )
        }

    val aspects: com.google.common.collect.ImmutableList<String?>
        get() {
            val aspects: MutableList<String?> = this.buildOptions.getAspects()
            val result: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>().addAll(aspects)
            if (!aspects.contains(AspectCollection.VALIDATION_ASPECT_NAME) && useValidationAspect()) {
                result.add(AspectCollection.VALIDATION_ASPECT_NAME)
            }
            return result.build()
        }

    @get:Throws(ViewCreationFailedException::class)
    val aspectsParameters: com.google.common.collect.ImmutableMap<String?, String?>?
        get() {
            val aspectsParametersList: MutableList<MutableMap.MutableEntry<String?, String?>?> =
                this.buildOptions.getAspectsParameters()
            try {
                return aspectsParametersList.stream()
                    .collect(TODO("Cannot convert element")) < java.util.Map.Entry < String
                TODO(
                    """
                |Cannot convert element
                |With text:
                |String>, String, String>toImmutableMap(Map.Entry::getKey, Map.Entry::getValue)
                """.trimMargin()
                )
            } catch (e: java.lang.IllegalArgumentException) {
                val errorMessage = "Error in top-level aspects parameters"
                throw ViewCreationFailedException(
                    errorMessage,
                    FailureDetail.newBuilder()
                        .setMessage(errorMessage)
                        .setAnalysis(Analysis.newBuilder().setCode(Analysis.Code.ASPECT_CREATION_FAILED))
                        .build(),
                    e
                )
            }
        }

    /** Whether {@value AspectCollection#VALIDATION_ASPECT_NAME} is in use.  */
    fun useValidationAspect(): Boolean {
        return validationMode() === OutputGroupInfo.ValidationMode.ASPECT
    }

    private fun validationMode(): OutputGroupInfo.ValidationMode {
        val buildOptions: BuildRequestOptions? = this.buildOptions
        if (!buildOptions.getRunValidationActions()) {
            return OutputGroupInfo.ValidationMode.OFF
        }
        return if (buildOptions.getUseValidationAspect())
            OutputGroupInfo.ValidationMode.ASPECT
        else
            OutputGroupInfo.ValidationMode.OUTPUT_GROUP
    }

    fun reportIncompatibleTargets(): Boolean {
        return reportIncompatibleTargets
    }

    companion object {
        private val MANDATORY_OPTIONS: com.google.common.collect.ImmutableList<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> =
            com.google.common.collect.ImmutableList.of<E?>(
                BuildRequestOptions::class.java,
                PackageOptions::class.java,
                BuildLanguageOptions::class.java,
                LoadingOptions::class.java,
                AnalysisOptions::class.java,
                ExecutionOptions::class.java,
                KeepGoingOption::class.java,
                LoadingPhaseThreadsOption::class.java
            )

        /** Returns a new Builder instance.  */
        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return com.google.devtools.build.lib.buildtool.BuildRequest.Builder()
        }
    }
}

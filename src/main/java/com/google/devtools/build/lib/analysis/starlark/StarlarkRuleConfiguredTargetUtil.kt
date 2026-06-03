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
package com.google.devtools.build.lib.analysis.starlark

import com.google.devtools.build.lib.actions.ActionConflictException

/**
 * A helper class to build Rule Configured Targets via runtime loaded rule implementations defined
 * using the Starlark Build Extension Language.
 */
object StarlarkRuleConfiguredTargetUtil {
    /**
     * Evaluates the rule's implementation function and returns what it returns (raw providers).
     * 
     * 
     * If there were errors during the evaluation or the type of the returned object is obviously
     * wrong, it sets ruleErrors on the ruleContext and returns null.
     * 
     * 
     * Unchecked exception `UncheckedEvalException`s and `MissingDepException` may be
     * thrown.
     * 
     * @param ruleContext the rule context
     * @param ruleClass the rule class for which to evaluate the implementation function gets. This
     * serves extended rules, where for parent's implementation function needs to be evaluated.
     */
    // TODO(blaze-team): Legacy providers are preventing to change the return type to Sequence<Info>.
    @Throws(java.lang.InterruptedException::class)
    fun evalRule(ruleContext: RuleContext, ruleClass: RuleClass): Any? {
        // TODO(blaze-team): expect_failure attribute is special for all rule classes, but it should
        // be special only for analysis tests
        val expectFailure: String = ruleContext.attributes().get("expect_failure", Type.STRING)
        val providersRaw: Any?

        try {
            // call rule.implementation(ctx)
            providersRaw =
                Starlark.positionalOnlyCall(
                    ruleContext.getStarlarkThread(),
                    ruleClass.getConfiguredTargetFunction(),
                    ruleContext.getStarlarkRuleContext()
                )
        } catch (ex: UncheckedEvalException) {
            // MissingDepException is expected to transit through Starlark execution.
            throw if (ex.getCause() is MissingDepException)
                ex.getCause() as MissingDepException?
            else
                ex
        } catch (ex: net.starlark.java.eval.EvalException) {
            // An error occurred during the rule.implementation call

            // If the error was expected by an analysis test, return None, to produce an empty target.

            if (!expectFailure.isEmpty() && ex.getMessage().matches(expectFailure)) {
                return Starlark.NONE
            }

            // Emit a single event that spans multiple lines:
            //     ERROR p/BUILD:1:1: in foo_library rule //p:p:
            //     Traceback:
            //        File foo.bzl, line 1, in foo_library_impl:
            //        ...
            ruleContext.ruleError("\n" + ex.getMessageWithStack())
            return null
        }

        // Errors already reported?
        if (ruleContext.hasErrors()) {
            return null
        }

        // Wrong result type?
        if (!(providersRaw is Info
                    || providersRaw === Starlark.NONE || providersRaw is Iterable<*>)
        ) {
            ruleContext.ruleError(
                java.lang.String.format(
                    "Rule should return a struct or a list, but got %s", Starlark.type(providersRaw)
                )
            )
            return null
        }

        // Did the Starlark implementation function fail to fail as expected?
        if (!expectFailure.isEmpty()) {
            ruleContext.ruleError("Expected failure not found: " + expectFailure)
            return null
        }

        return providersRaw
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    private fun checkDeclaredProviders(
        configuredTarget: ConfiguredTarget, advertisedProviders: AdvertisedProviderSet
    ) {
        for (providerId in advertisedProviders.getStarlarkProviders()) {
            if (configuredTarget.get(providerId) == null) {
                throw Starlark.errorf(
                    "rule advertised the '%s' provider, but this provider was not among those returned",
                    providerId
                )
            }
        }
    }

    /** Returns the location of the rule implementation function.  */
    private fun implLoc(context: RuleContext): net.starlark.java.syntax.Location {
        return context.getRule().getRuleClassObject().getConfiguredTargetFunction().getLocation()
    }

    /**
     * Creates a Rule Configured Target from the raw providers returned by the rule's implementation
     * function.
     * 
     * 
     * If there are problems with the raw providers, it sets ruleErrors on the ruleContext and
     * returns null.
     */
    @Throws(java.lang.InterruptedException::class, ActionConflictException::class)
    fun createTarget(
        context: RuleContext,
        rawProviders: Any?,
        advertisedProviders: AdvertisedProviderSet,
        isDefaultExecutableCreated: Boolean,
        requiredConfigFragmentsProvider: RequiredConfigFragmentsProvider?
    ): ConfiguredTarget? {
        val builder: RuleConfiguredTargetBuilder = RuleConfiguredTargetBuilder(context)

        // TODO(adonovan): clean up addProviders' error handling,
        // reporting provider validity errors through ruleError
        // where possible. This allows for multiple events, with independent
        // locations, even for the same root cause.
        // The required change is fiddly due to frequent and nested use of
        // Structure.getField, Sequence.cast, and similar operators.
        try {
            addProviders(context, builder, rawProviders, isDefaultExecutableCreated)
        } catch (ex: net.starlark.java.eval.EvalException) {
            // Emit a single event that spans two lines (see infoError).
            // The message typically starts with another location, e.g. of provider creation.
            //     ERROR p/BUILD:1:1: in foo_library rule //p:p:
            //     ...message...
            context.ruleError("\n" + ex.getMessage())
            return null
        }

        // This provider is kept out of `addProviders` method, because it's not generated by the
        // Starlark rule and because `addProviders` will be simplified by the legacy providers removal
        // RequiredConfigFragmentsProvider may be removed with removal of Android feature flags.
        if (requiredConfigFragmentsProvider != null) {
            builder.addProvider(requiredConfigFragmentsProvider)
        }

        val ct: ConfiguredTarget?
        try {
            // This also throws InterruptedException from a convoluted dependency:
            // TestActionBuilder -> TestTargetExecutionSettings -> CommandLine -> Starlark.
            ct = builder.build() // may be null
        } catch (ex: java.lang.IllegalArgumentException) {
            // TODO(adonovan): eliminate this abuse of unchecked exceptions.
            // Emit a single event that spans two lines (see infoError).
            // The message typically starts with another location, e.g. of provider creation.
            //     ERROR p/BUILD:1:1: in foo_library rule //p:p:
            //     ...message...
            context.ruleError("\n" + implLoc(context) + ": " + ex.getMessage())
            return null
        }

        if (ct != null) {
            // If there was error creating the ConfiguredTarget, no further validation is needed.
            // Null will be returned and the errors thus reported.
            try {
                // Check all artifacts have actions. Despite signature, must be done after build().
                StarlarkProviderValidationUtil.validateArtifacts(context)
                // Check all advertised providers were created.
                checkDeclaredProviders(ct, advertisedProviders)
            } catch (ex: net.starlark.java.eval.EvalException) {
                context.ruleError("\n" + implLoc(context) + ": " + ex.getMessage())
                return null
            }
        }

        return ct
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    fun convertToOutputGroupValue(outputGroup: String, objects: Any?): NestedSet<Artifact?> {
        // regrettable preemptive allocation of error message
        val what = "output group '" + outputGroup + "'"
        return if (objects is net.starlark.java.eval.Sequence)
            NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
                .addAll(net.starlark.java.eval.Sequence.cast<T?>(objects, Artifact::class.java, what))
                .build()
        else
            Depset.cast(objects, Artifact::class.java, what)
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    private fun addProviders(
        context: RuleContext,
        builder: RuleConfiguredTargetBuilder,
        rawProviders: Any?,
        isDefaultExecutableCreated: Boolean
    ) {
        // Handle "return provider" vs "return [provider]"

        val declaredProviders: MutableMap<Provider.Key?, Info> = LinkedHashMap<Provider.Key?, Info>()
        if (rawProviders is Info) {
            if (getProviderKey(rawProviders, context).equals(StructProvider.STRUCT.getKey())) {
                throw errorWithLoc(
                    implLoc(context),
                    "Returning a struct from a rule implementation function is deprecated."
                )
            }

            // A single declared provider (not in a list)
            if (rawProviders is StarlarkInfo) {
                rawProviders = info.unsafeOptimizeMemoryLayout()
            }
            val providerKey: Provider.Key = getProviderKey(rawProviders, context)
            // Single declared provider
            declaredProviders.put(providerKey, rawProviders)
        } else if (rawProviders is net.starlark.java.eval.Sequence) {
            // Sequence of declared providers
            for (provider in net.starlark.java.eval.Sequence.cast<Info>(
                rawProviders,
                Info::class.java,
                "result of rule implementation function"
            )) {
                var provider: Info = provider
                if (provider is StarlarkInfo) {
                    // Provider instances are optimised recursively, without optimising elements of the list.
                    // Tradeoff is that some object may be duplicated if they are reachable by more than one
                    // path, but we don't expect that much in practice.
                    provider = provider.unsafeOptimizeMemoryLayout()
                }
                val providerKey: Provider.Key = getProviderKey(provider, context)
                if (declaredProviders.put(providerKey, provider) != null) {
                    context.ruleError("Multiple conflicting returned providers with key " + providerKey)
                }
            }
        } else if (rawProviders !== Starlark.NONE) {
            throw Starlark.errorf(
                "Expected a list of providers, but got %s", Starlark.type(rawProviders)
            )
        }

        if (context.getRule().getRuleClassObject().isMaterializerRule()) {
            if (declaredProviders.size() != 1
                || !declaredProviders.containsKey(MaterializedDepsInfo.Companion.PROVIDER.getKey())
            ) {
                throw Starlark.errorf(
                    "Materializer rules must return exactly one MaterializedDepsInfo provider, but got %s",
                    declaredProviders.keySet()
                )
            }
        }

        val runEnvironmentInfo: RunEnvironmentInfo? =
            declaredProviders.get(RunEnvironmentInfo.PROVIDER.getKey()) as RunEnvironmentInfo?
        if (runEnvironmentInfo != null && !context.getRule().getRuleClassObject()
                .isExecutableStarlark() && !context.isTestTarget()
        ) {
            val message =
                "Returning RunEnvironmentInfo from a non-executable, non-test target has no effect"
            if (runEnvironmentInfo.shouldErrorOnNonExecutableRule()) {
                context.ruleError(message)
                declaredProviders.remove(RunEnvironmentInfo.PROVIDER.getKey())
            } else {
                context.ruleWarning(message)
            }
        }

        var defaultProviderProvidedExplicitly = false
        for (declaredProvider in declaredProviders.values()) {
            if (declaredProvider is DefaultInfo) {
                parseDefaultProviderFields(
                    declaredProvider,
                    declaredProvider.getCreationLocation(),
                    context,
                    builder,
                    isDefaultExecutableCreated,
                    runEnvironmentInfo
                )
                defaultProviderProvidedExplicitly = true
            } else {
                builder.addStarlarkDeclaredProvider(declaredProvider)
            }
        }

        if (!defaultProviderProvidedExplicitly) {
            parseDefaultProviderFields( /* defaultInfo= */
                null,
                implLoc(context),
                context,
                builder,
                isDefaultExecutableCreated,
                runEnvironmentInfo
            )
        }
    }

    // Returns an EvalException whose message has a location as the prefix. The exception is intended
    // to be reported as ruleErrors by createTarget.
    @com.google.errorprone.annotations.FormatMethod
    private fun errorWithLoc(
        loc: net.starlark.java.syntax.Location?,
        format: String,
        vararg args: Any?
    ): net.starlark.java.eval.EvalException? {
        return Starlark.errorf("%s: %s", loc, java.lang.String.format(format, *args))
    }

    /**
     * Returns the provider key from an info (provider instance).
     * 
     * @throws EvalException if the provider for this info object has not been exported, which can
     * occur if the provider was declared in a non-global scope (for example a rule implementation
     * function)
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    private fun getProviderKey(info: Info, context: RuleContext): Provider.Key {
        val provider: Provider = info.getProvider()
        if (!provider.isExported()) {
            // TODO(adonovan): report separate error events at distinct locations:
            //  "cannot return non-exported provider" (at location of instantiation), and
            //  "provider definition not at top level" (at location of definition).
            throw errorWithLoc(
                implLoc(context),
                ("The rule implementation function returned an instance of an unnamed provider. "
                        + "A provider becomes named by being assigned to a global variable in a .bzl file. "
                        + "(Provider defined at %s.)"),
                provider.getLocation()
            )
        }
        return provider.getKey()
    }

    /** Parses fields of a default provider.  */
    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    private fun parseDefaultProviderFields(
        defaultInfo: DefaultInfo?,
        locForError: net.starlark.java.syntax.Location?,
        context: RuleContext,
        builder: RuleConfiguredTargetBuilder,
        isDefaultExecutableCreated: Boolean,
        runEnvironmentInfo: RunEnvironmentInfo?
    ) {
        val files: Depset? = if (defaultInfo == null) null else defaultInfo.getFiles()
        val statelessRunfiles: com.google.devtools.build.lib.analysis.Runfiles? =
            if (defaultInfo == null) null else defaultInfo.getStatelessRunfiles()
        val dataRunfiles: com.google.devtools.build.lib.analysis.Runfiles? =
            if (defaultInfo == null) null else defaultInfo.getDataRunfiles()
        val defaultRunfiles: com.google.devtools.build.lib.analysis.Runfiles? =
            if (defaultInfo == null) null else defaultInfo.getDefaultRunfiles()
        var executable: Artifact? = if (defaultInfo == null) null else defaultInfo.getExecutable()

        if (executable != null && !executable.getArtifactOwner().equals(context.getOwner())) {
            throw errorWithLoc(
                locForError,
                "'executable' provided by an executable rule '%s' should be created by the same rule.",
                context.getRule().getRuleClass()
            )
        }

        val isExecutable: Boolean = context.getRule().getRuleClassObject().isExecutableStarlark()
        if (executable != null && isExecutable && isDefaultExecutableCreated) {
            val defaultExecutable: Artifact? = context.createOutputArtifact()
            if (!executable.equals(defaultExecutable)) {
                throw errorWithLoc(
                    locForError,
                    "The rule '%s' both accesses 'ctx.outputs.executable' and provides a different"
                            + " executable '%s'. Do not use 'ctx.output.executable'.",
                    context.getRule().getRuleClass(),
                    executable.getRootRelativePathString()
                )
            }
        }

        if (context.getRule().isAnalysisTest()) {
            // The Starlark Build API should already throw exception if the rule implementation attempts
            // to register any actions. This is just a check of this invariant.
            com.google.common.base.Preconditions.checkState(
                context.getAnalysisEnvironment().getRegisteredActions().isEmpty(),
                "%s",
                context.getLabel()
            )

            executable = context.createOutputArtifactScriptForAnalysisTest()
        }

        if (executable == null && isExecutable) {
            if (isDefaultExecutableCreated) {
                // This doesn't actually create a new Artifact just returns the one
                // created in StarlarkRuleContext.
                executable = context.createOutputArtifact()
            } else {
                throw errorWithLoc(
                    locForError,
                    "The rule '%s' is executable. It needs to create an executable File and pass it as the"
                            + " 'executable' parameter to the DefaultInfo it returns.",
                    context.getRule().getRuleClass()
                )
            }
        }

        addSimpleProviders(
            builder,
            context,
            executable,
            files,
            statelessRunfiles,
            dataRunfiles,
            defaultRunfiles,
            runEnvironmentInfo
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    private fun addSimpleProviders(
        builder: RuleConfiguredTargetBuilder,
        ruleContext: RuleContext,
        executable: Artifact?,
        files: Depset?,
        statelessRunfiles: com.google.devtools.build.lib.analysis.Runfiles?,
        dataRunfiles: com.google.devtools.build.lib.analysis.Runfiles?,
        defaultRunfiles: com.google.devtools.build.lib.analysis.Runfiles?,
        runEnvironmentInfo: RunEnvironmentInfo?
    ) {
        // TODO(bazel-team) if both 'files' and 'executable' are provided, 'files' overrides
        // 'executable'

        var statelessRunfiles: com.google.devtools.build.lib.analysis.Runfiles? = statelessRunfiles
        val filesToBuild: NestedSetBuilder<Artifact?> =
            NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ().addAll(ruleContext.getOutputArtifacts())
        if (executable != null) {
            filesToBuild.add(executable)
        }
        builder.setFilesToBuild(filesToBuild.build())

        if (files != null) {
            // If we specify files_to_build we don't have the executable in it by default.
            builder.setFilesToBuild(Depset.cast(files, Artifact::class.java, "files"))
        }

        if (statelessRunfiles == null && dataRunfiles == null && defaultRunfiles == null) {
            // No runfiles specified, set default
            statelessRunfiles = com.google.devtools.build.lib.analysis.Runfiles.Companion.EMPTY
        }

        // This works because we only allowed to call a rule *_test iff it's a test type rule.
        val testRule: Boolean = TargetUtils.isTestRuleName(ruleContext.getRule().getRuleClass())
        val isExecutableOrTest = executable != null || testRule
        val runfilesProvider: RunfilesProvider?
        if (statelessRunfiles != null) {
            runfilesProvider =
                RunfilesProvider.Companion.simple(mergeFiles(statelessRunfiles, executable, ruleContext))
        } else {
            var mergedDefaultRunfiles: com.google.devtools.build.lib.analysis.Runfiles =
                if (defaultRunfiles != null) defaultRunfiles else com.google.devtools.build.lib.analysis.Runfiles.Companion.EMPTY
            if (isExecutableOrTest) {
                // The executable is only merged in if needed when using stateful runfiles to preserve
                // long-standing behavior.
                mergedDefaultRunfiles = mergeFiles(mergedDefaultRunfiles, executable, ruleContext)
            }
            runfilesProvider =
                RunfilesProvider.Companion.withData(
                    mergedDefaultRunfiles,
                    if (dataRunfiles != null) dataRunfiles else com.google.devtools.build.lib.analysis.Runfiles.Companion.EMPTY
                )
        }
        builder.addProvider<RunfilesProvider?>(RunfilesProvider::class.java, runfilesProvider)

        val computedDefaultRunfiles: com.google.devtools.build.lib.analysis.Runfiles =
            runfilesProvider.getDefaultRunfiles()
        if (testRule && computedDefaultRunfiles.isEmpty()) {
            throw Starlark.errorf("Test rules have to define runfiles")
        }
        if (isExecutableOrTest) {
            var runfilesSupport: RunfilesSupport? = null
            if (!computedDefaultRunfiles.isEmpty()) {
                com.google.common.base.Preconditions.checkNotNull<Any?>(executable, "executable must not be null")
                runfilesSupport =
                    RunfilesSupport.Companion.withExecutable(
                        ruleContext, computedDefaultRunfiles, executable, runEnvironmentInfo
                    )
            }
            builder.setRunfilesSupport(runfilesSupport, executable)
        }

        if (ruleContext.getRule().getRuleClassObject().isStarlarkTestable()) {
            val actions: Info =
                ActionsProvider.create(ruleContext.getAnalysisEnvironment().getRegisteredActions())
            builder.addStarlarkDeclaredProvider(actions)
        }
    }

    private fun mergeFiles(
        runfiles: com.google.devtools.build.lib.analysis.Runfiles, executable: Artifact?, ruleContext: RuleContext
    ): com.google.devtools.build.lib.analysis.Runfiles {
        if (executable == null) {
            return runfiles
        }
        return com.google.devtools.build.lib.analysis.Runfiles.Builder(ruleContext.getWorkspaceName())
            .addArtifact(executable)
            .merge(runfiles)
            .build()
    }
}

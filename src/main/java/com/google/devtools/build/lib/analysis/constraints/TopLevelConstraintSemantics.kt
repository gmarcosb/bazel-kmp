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
package com.google.devtools.build.lib.analysis.constraints

import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue

/**
 * Constraint semantics that apply to top-level targets.
 * 
 * 
 * Top-level targets are "special" because they have no parents that can assert expected
 * environment compatibility. So these expectations have to be declared by other means.
 * 
 * 
 * For all other targets see [ConstraintSemantics].
 */
class TopLevelConstraintSemantics(
    constraintSemantics: RuleContextConstraintSemantics,
    packageManager: PackageManager,
    evaluator: MemoizingEvaluator,
    eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler
) {
    private val constraintSemantics: RuleContextConstraintSemantics
    private val packageManager: PackageManager
    private val evaluator: MemoizingEvaluator
    private val eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler

    /**
     * Constructor with helper classes for loading targets.
     * 
     * @param constraintSemantics core constraints implementation logic
     * @param packageManager object for retrieving loaded targets
     * @param evaluator for looking up already evaluated values
     * @param eventHandler the build's event handler
     */
    init {
        this.constraintSemantics = constraintSemantics
        this.packageManager = packageManager
        this.evaluator = evaluator
        this.eventHandler = eventHandler
    }

    internal class MissingEnvironment private constructor(
        environment: com.google.devtools.build.lib.cmdline.Label?,
        culprit: RemovedEnvironmentCulprit?
    ) {
        private val environment: com.google.devtools.build.lib.cmdline.Label?

        // If null, the top-level target just didn't declare a required environment. If not null, that
        // means the declaration got "refined" away due to some select() somewhere in its deps. See
        // ConstraintSemantics's documentation for an explanation of refinement.
        private val culprit: RemovedEnvironmentCulprit?

        init {
            this.environment = environment
            this.culprit = culprit
        }
    }

    /**
     * Checks that the all top-level targets are compatible with the target platform.
     * 
     * 
     * If any target doesn't support the target platform it will be either marked as "to be
     * skipped" or marked as "errored".
     * 
     * 
     * Targets that are incompatible with the target platform and are not explicitly requested on
     * the command line should be skipped.
     * 
     * 
     * Targets that are incompatible with the target platform and *are* explicitly requested on the
     * command line are errored unless --skip_incompatible_explicit_targets is enabled. Having one or
     * more errored targets will cause the entire build to fail with an error message.
     * 
     * @param topLevelTargets the build's top-level targets
     * @param explicitTargetPatterns the set of explicit target patterns specified by the user on the
     * command line. Every target must be in the unambiguous canonical form (i.e., with the "@"
     * prefix for all targets including in the main repository).
     * @return the set of to-be-skipped and errored top-level targets.
     * @throws ViewCreationFailedException if any top-level target was explicitly requested on the
     * command line.
     */
    @Throws(ViewCreationFailedException::class)
    fun checkPlatformRestrictions(
        topLevelTargets: com.google.common.collect.ImmutableSet<ConfiguredTarget>,
        explicitTargetPatterns: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?>,
        keepGoing: Boolean,
        skipIncompatibleExplicitTargets: Boolean
    ): PlatformRestrictionsResult? {
        val incompatibleTargets: com.google.common.collect.ImmutableSet.Builder<ConfiguredTarget?> =
            com.google.common.collect.ImmutableSet.builder<ConfiguredTarget?>()
        val incompatibleButRequestedTargets: com.google.common.collect.ImmutableSet.Builder<ConfiguredTarget?> =
            com.google.common.collect.ImmutableSet.builder<ConfiguredTarget?>()

        try {
            for (target in topLevelTargets) {
                val platformCompatibility =
                    compatibilityWithPlatformRestrictions(
                        target,
                        eventHandler,  /* eagerlyThrowError= */
                        !keepGoing,
                        explicitTargetPatterns.contains(target.getOriginalLabel()),
                        skipIncompatibleExplicitTargets
                    )
                if (PlatformCompatibility.INCOMPATIBLE_EXPLICIT == platformCompatibility) {
                    incompatibleButRequestedTargets.add(target)
                } else if (PlatformCompatibility.INCOMPATIBLE_IMPLICIT == platformCompatibility) {
                    incompatibleTargets.add(target)
                }
            }
        } catch (e: TargetCompatibilityCheckException) {
            throw ViewCreationFailedException(e.getFailureDetail(),  /*cause=*/e)
        }

        return PlatformRestrictionsResult.Companion.builder()
            .targetsToSkip(com.google.common.collect.ImmutableSet.copyOf<ConfiguredTarget?>(incompatibleTargets.build()))
            .targetsWithErrors(
                com.google.common.collect.ImmutableSet.copyOf<ConfiguredTarget?>(
                    incompatibleButRequestedTargets.build()
                )
            )
            .build()
    }

    /**
     * Checks that if this is an environment-restricted build, all top-level targets support expected
     * top-level environments. Expected top-level environments can be declared explicitly through
     * `--target_environment` or implicitly through `--auto_cpu_environment_group`. For
     * the latter, top-level targets must be compatible with the build's target configuration CPU.
     * 
     * 
     * If any target doesn't support an explicitly expected environment declared through [ ][CoreOptions.targetEnvironments], the entire build fails with an error.
     * 
     * 
     * If any target doesn't support an implicitly expected environment declared through [ ][CoreOptions.autoCpuEnvironmentGroup], the target is skipped during execution while remaining
     * targets execute as normal.
     * 
     * @param topLevelTargets the build's top-level targets
     * @return the set of bad top-level targets.
     * @throws ViewCreationFailedException if any target doesn't support an explicitly expected
     * environment declared through [CoreOptions.targetEnvironments]
     */
    @Throws(ViewCreationFailedException::class, java.lang.InterruptedException::class)
    fun checkTargetEnvironmentRestrictions(
        topLevelTargets: com.google.common.collect.ImmutableSet<ConfiguredTarget>
    ): MutableSet<ConfiguredTarget?> {
        val badTargets: com.google.common.collect.ImmutableSet.Builder<ConfiguredTarget?> =
            com.google.common.collect.ImmutableSet.builder<ConfiguredTarget?>()
        // Maps targets that are missing *explicitly* required environments to the set of environments
        // they're missing. These targets trigger a ViewCreationFailedException, which halts the build.
        // Targets with missing *implicitly* required environments don't belong here, since the build
        // continues while skipping them.
        val exceptionInducingTargets: com.google.common.collect.Multimap<ConfiguredTarget?, MissingEnvironment?> =
            com.google.common.collect.ArrayListMultimap.create<ConfiguredTarget?, MissingEnvironment?>()
        try {
            for (topLevelTarget in topLevelTargets) {
                val compatibility: EnvironmentCompatibility =
                    com.google.common.base.Preconditions.checkNotNull<EnvironmentCompatibility>(
                        compatibilityWithTargetEnvironment(
                            topLevelTarget,
                            getConfigurationValue(topLevelTarget.getConfigurationKey()),
                            com.google.devtools.build.lib.analysis.constraints.TopLevelConstraintSemantics.TargetLookup { label: com.google.devtools.build.lib.cmdline.Label? ->
                                this.getOrLoadTarget(
                                    label
                                )
                            },
                            eventHandler
                        )
                    )
                if (compatibility.isCompatible) {
                    continue
                }
                if (compatibility.severeMissingEnvironments != null) {
                    exceptionInducingTargets.putAll(
                        topLevelTarget, compatibility.severeMissingEnvironments
                    )
                }
                badTargets.add(topLevelTarget)
            }
        } catch (e: TargetCompatibilityCheckException) {
            throw ViewCreationFailedException(e.message, e.getFailureDetail(), e)
        }

        if (!exceptionInducingTargets.isEmpty()) {
            val badTargetsUserMessage =
                getBadTargetsUserMessage(constraintSemantics, exceptionInducingTargets)
            throw ViewCreationFailedException(
                badTargetsUserMessage,
                FailureDetail.newBuilder()
                    .setMessage(badTargetsUserMessage)
                    .setAnalysis(Analysis.newBuilder().setCode(Code.TARGETS_MISSING_ENVIRONMENTS))
                    .build()
            )
        }
        return badTargets.build()
    }

    @Throws(java.lang.InterruptedException::class)
    private fun getConfigurationValue(key: BuildConfigurationKey?): BuildConfigurationValue? {
        if (key == null) {
            return null
        }
        return evaluator.getExistingValue(key) as BuildConfigurationValue?
    }

    @Throws(NoSuchPackageException::class, NoSuchTargetException::class, java.lang.InterruptedException::class)
    private fun getOrLoadTarget(label: com.google.devtools.build.lib.cmdline.Label): com.google.devtools.build.lib.packages.Target? {
        val pkgVal: PackageValue? = evaluator.getExistingValue(label.getPackageIdentifier()) as PackageValue?
        if (pkgVal != null) {
            return pkgVal.getPackage().getTarget(label.getName())
        }
        // Fall back to loading the target. Top-level targets are already in the graph, but referenced
        // environment targets may not yet be loaded.
        return packageManager.getTarget(eventHandler, label)
    }

    /** Tells the compatibility of a ConfiguredTarget with the target environment.  */
    class EnvironmentCompatibility(
      @kotlin.jvm.JvmField val isCompatible: Boolean,
      severeMissingEnvironments: com.google.common.collect.ImmutableSet<MissingEnvironment?>?
    ) {
        val severeMissingEnvironments: com.google.common.collect.ImmutableSet<MissingEnvironment?>?

        init {
            this.severeMissingEnvironments = severeMissingEnvironments
        }

        companion object {
            fun compatible(): EnvironmentCompatibility {
                return EnvironmentCompatibility( /* isCompatible= */
                    true,  /* severeMissingEnvironments= */null
                )
            }

            fun nonSevereIncompatible(): EnvironmentCompatibility {
                return EnvironmentCompatibility( /* isCompatible= */
                    false,  /* severeMissingEnvironments= */null
                )
            }

            fun severeIncompatible(
                severeMissingEnvironments: com.google.common.collect.ImmutableSet<MissingEnvironment?>?
            ): EnvironmentCompatibility {
                return EnvironmentCompatibility( /* isCompatible= */false, severeMissingEnvironments)
            }
        }
    }

    /** Tells the compatibility of a ConfiguredTarget with the platform.  */
    enum class PlatformCompatibility {
        COMPATIBLE,
        INCOMPATIBLE_IMPLICIT,
        INCOMPATIBLE_EXPLICIT
    }

    /** For Exceptions that arise during the compatibility checking of a target.  */
    class TargetCompatibilityCheckException : AbstractSaneAnalysisException {
        private val failureDetail: FailureDetail?

        constructor(message: String?, failureDetail: FailureDetail?) : super(message) {
            this.failureDetail = failureDetail
        }

        constructor(message: String?, failureDetail: FailureDetail?, cause: Throwable?) : super(message, cause) {
            this.failureDetail = failureDetail
        }

        fun getFailureDetail(): FailureDetail? {
            return failureDetail
        }

        override fun getDetailedExitCode(): DetailedExitCode {
            return DetailedExitCode.of(failureDetail)
        }
    }

    /** Provides a method to look up a Target, given its Label.  */
    fun interface TargetLookup {
        // Returns null if the implementation involves a Skyframe lookup and the value is missing.
        @Throws(NoSuchPackageException::class, NoSuchTargetException::class, java.lang.InterruptedException::class)
        fun getTarget(label: com.google.devtools.build.lib.cmdline.Label?): com.google.devtools.build.lib.packages.Target?
    }

    companion object {
        /**
         * Returns the compatibility of a ConfiguredTarget with the platform.
         * 
         * 
         * See [.checkPlatformRestrictions].
         */
        @Throws(TargetCompatibilityCheckException::class)
        fun compatibilityWithPlatformRestrictions(
            configuredTarget: ConfiguredTarget,
            eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler,
            eagerlyThrowError: Boolean,
            explicitlyRequested: Boolean,
            skipIncompatibleExplicitTargets: Boolean
        ): PlatformCompatibility {
            val incompatibleCheckResult: RuleContextConstraintSemantics.IncompatibleCheckResult =
                RuleContextConstraintSemantics.checkForIncompatibility(configuredTarget)
            if (!incompatibleCheckResult.isIncompatible()) {
                return PlatformCompatibility.COMPATIBLE
            }

            // We need the label in unambiguous form here. I.e. with the "@" prefix for targets in the
            // main repository. explicitTargetPatterns is also already in the unambiguous form to make
            // comparison succeed regardless of the provided form.
            if (!skipIncompatibleExplicitTargets && explicitlyRequested) {
                if (eagerlyThrowError) {
                    // Use the slightly simpler form for printing error messages. I.e. no "@" prefix for
                    // targets in the main repository.
                    throw getExceptionForExplicitlyRequestedIncompatibleTarget(
                        configuredTarget, incompatibleCheckResult.underlyingTarget()
                    )
                }
                eventHandler.handle(
                    com.google.devtools.build.lib.events.Event.warn(
                        getIncompatibleMessage(
                            configuredTarget, incompatibleCheckResult.underlyingTarget()
                        )
                    )
                )
                return PlatformCompatibility.INCOMPATIBLE_EXPLICIT
            }
            // We can safely skip this target if it wasn't explicitly requested or we've been instructed
            // to skip explicitly requested targets.
            return PlatformCompatibility.INCOMPATIBLE_IMPLICIT
        }

        private fun getExceptionForExplicitlyRequestedIncompatibleTarget(
            configuredTarget: ConfiguredTarget, underlyingTarget: ConfiguredTarget?
        ): TargetCompatibilityCheckException {
            val targetIncompatibleMessage = getIncompatibleMessage(configuredTarget, underlyingTarget)
            return TargetCompatibilityCheckException(
                targetIncompatibleMessage,
                FailureDetail.newBuilder()
                    .setMessage(targetIncompatibleMessage)
                    .setAnalysis(Analysis.newBuilder().setCode(Code.INCOMPATIBLE_TARGET_REQUESTED))
                    .build()
            )
        }

        private fun getIncompatibleMessage(
            configuredTarget: ConfiguredTarget, underlyingTarget: ConfiguredTarget?
        ): String? {
            return String.format(
                "Target %s is incompatible and cannot be built, but was explicitly requested.%s",
                configuredTarget.getOriginalLabel(),  // We need access to the provider so we pass in the underlying target here that is
                // responsible for the incompatibility.
                reportOnIncompatibility(underlyingTarget)
            )
        }

        /**
         * Returns the compatibility with the target environment.
         * 
         * 
         * See [.checkTargetEnvironmentRestrictions].
         * 
         * @return null if the `targetLookup` performs a Skyframe lookup and the value is missing.
         */
        @Throws(java.lang.InterruptedException::class, TargetCompatibilityCheckException::class)
        fun compatibilityWithTargetEnvironment(
            configuredTarget: ConfiguredTarget,
            buildConfigurationValue: BuildConfigurationValue?,
            targetLookup: TargetLookup,
            eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler
        ): EnvironmentCompatibility? {
            // TODO(bazel-team): support file targets (they should apply package-default constraints).
            if (buildConfigurationValue == null || !buildConfigurationValue.enforceConstraints() || buildConfigurationValue.getTargetEnvironments()
                    .isEmpty()
            ) {
                return EnvironmentCompatibility.Companion.compatible()
            }

            val target: com.google.devtools.build.lib.packages.Target?
            try {
                target =
                    com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.packages.Target?>(
                        targetLookup.getTarget(configuredTarget.getLabel())
                    )
            } catch (e: NoSuchPackageException) {
                eventHandler.handle(
                    com.google.devtools.build.lib.events.Event.error(
                        "Unable to get target from package when checking environment restrictions. " + e
                    )
                )
                return EnvironmentCompatibility.Companion.compatible()
            } catch (e: NoSuchTargetException) {
                eventHandler.handle(
                    com.google.devtools.build.lib.events.Event.error(
                        "Unable to get target from package when checking environment restrictions. " + e
                    )
                )
                return EnvironmentCompatibility.Companion.compatible()
            }

            if (target.getAssociatedRule() == null
                || !target.getAssociatedRule().getRuleClassObject().supportsConstraintChecking()
            ) {
                return EnvironmentCompatibility.Companion.compatible()
            }

            // Check explicitly expected environments.
            val severeMissingEnvironments: com.google.common.collect.ImmutableSet<MissingEnvironment?>? =
                getMissingEnvironments(
                    configuredTarget, buildConfigurationValue.getTargetEnvironments(), targetLookup
                )
            // Missing value.
            if (severeMissingEnvironments == null) {
                return null
            }

            if (!severeMissingEnvironments.isEmpty()) {
                return EnvironmentCompatibility.Companion.severeIncompatible(severeMissingEnvironments)
            }

            return EnvironmentCompatibility.Companion.compatible()
        }

        /**
         * Assembles the explanation for a platform incompatibility.
         * 
         * 
         * This is useful when trying to explain to the user why an explicitly requested target on the
         * command line is considered incompatible. The goal is to print out the dependency chain and the
         * constraint that wasn't satisfied so that the user can immediately figure out what happened.
         * 
         * @param target the incompatible target that was explicitly requested on the command line.
         * @return the verbose error message to show to the user.
         */
        private fun reportOnIncompatibility(target: ConfiguredTarget?): String {
            var target: ConfiguredTarget? = target
            com.google.common.base.Preconditions.checkNotNull<ConfiguredTarget?>(target)

            var message = "\nDependency chain:"
            var provider: IncompatiblePlatformProvider? = null

            // TODO(austinschuh): While the first error is helpful, reporting all the errors at once would
            // save the user bazel round trips.
            while (target != null) {
                message += String.format(
                    "\n    %s (%s)",
                    target.getLabel(), target.getConfigurationChecksum().substring(0, 6)
                )
                provider = target.get<IncompatiblePlatformProvider?>(IncompatiblePlatformProvider.Companion.PROVIDER)
                val targetList: com.google.common.collect.ImmutableList<ConfiguredTarget?>? =
                    provider.targetsResponsibleForIncompatibility
                if (targetList == null) {
                    target = null
                } else {
                    target = targetList.get(0)
                }
            }

            message += String.format(
                "   <-- target platform (%s) didn't satisfy constraint", provider.targetPlatform
            )
            if (provider.constraintsResponsibleForIncompatibility.size == 1) {
                message += " " + provider.constraintsResponsibleForIncompatibility.get(0).label()
                return message
            }

            message += "s ["

            var first = true
            for (constraintValueInfo in provider.constraintsResponsibleForIncompatibility) {
                if (first) {
                    first = false
                } else {
                    message += ", "
                }
                message += constraintValueInfo.label()
            }

            message += "]"

            return message
        }

        /**
         * Returns the expected environments that the given top-level target doesn't support.
         * 
         * @param topLevelTarget the top-level target to check
         * @param expectedEnvironmentLabels the environments this target is expected to support
         * @param targetLookup a function that is used to look up a Target given its Label.
         * @throws InterruptedException if environment target resolution fails
         * @throws TargetCompatibilityCheckException if an expected environment isn't a valid target
         */
        @Throws(java.lang.InterruptedException::class, TargetCompatibilityCheckException::class)
        private fun getMissingEnvironments(
            topLevelTarget: ConfiguredTarget,
            expectedEnvironmentLabels: MutableList<com.google.devtools.build.lib.cmdline.Label?>,
            targetLookup: TargetLookup
        ): com.google.common.collect.ImmutableSet<MissingEnvironment?>? {
            // Convert expected environment labels to actual environments.
            var topLevelTarget: ConfiguredTarget = topLevelTarget
            val expectedEnvironmentsBuilder: com.google.devtools.build.lib.analysis.constraints.EnvironmentCollection.Builder =
                com.google.devtools.build.lib.analysis.constraints.EnvironmentCollection.Builder()
            for (envLabel in expectedEnvironmentLabels) {
                try {
                    val env: com.google.devtools.build.lib.packages.Target? = targetLookup.getTarget(envLabel)
                    // Missing value.
                    if (env == null) {
                        return null
                    }
                    expectedEnvironmentsBuilder.put(
                        ConstraintSemantics.Companion.getEnvironmentGroup(env).getEnvironmentLabels(), envLabel
                    )
                } catch (e: NoSuchPackageException) {
                    throw TargetCompatibilityCheckException(
                        "invalid target environment: " + e.message,
                        e.getDetailedExitCode().getFailureDetail(),
                        e
                    )
                } catch (e: NoSuchTargetException) {
                    throw TargetCompatibilityCheckException(
                        "invalid target environment: " + e.message,
                        e.getDetailedExitCode().getFailureDetail(),
                        e
                    )
                } catch (e: EnvironmentLookupException) {
                    throw TargetCompatibilityCheckException(
                        "invalid target environment: " + e.message,
                        e.getDetailedExitCode().getFailureDetail(),
                        e
                    )
                }
            }
            val expectedEnvironments: EnvironmentCollection? = expectedEnvironmentsBuilder.build()

            // Dereference any aliases that might be present.
            topLevelTarget = topLevelTarget.getActual()
            // Now check the target against expected environments.
            val asProvider: TransitiveInfoCollection
            if (topLevelTarget is OutputFileConfiguredTarget) {
                asProvider = topLevelTarget.getGeneratingRule()
            } else {
                asProvider = topLevelTarget
            }
            val provider: SupportedEnvironmentsProvider =
                com.google.common.base.Verify.verifyNotNull<SupportedEnvironmentsProvider>(
                    asProvider.getProvider<SupportedEnvironmentsProvider?>(
                        SupportedEnvironmentsProvider::class.java
                    )
                )
            val ans: com.google.common.collect.ImmutableSet.Builder<MissingEnvironment?> =
                com.google.common.collect.ImmutableSet.builder<MissingEnvironment?>()
            for (unsupportedEnv in RuleContextConstraintSemantics.getUnsupportedEnvironments(
                provider.getRefinedEnvironments(), expectedEnvironments
            )) {
                // We apply this filter because the target might also not support default environments in
                // other environment groups. We don't care about those. We only care about the environments
                // explicitly referenced.
                if (!expectedEnvironmentLabels.contains(unsupportedEnv)) {
                    continue
                }

                val envAndFulfillers: MutableList<com.google.devtools.build.lib.cmdline.Label?> =
                    java.util.ArrayList<com.google.devtools.build.lib.cmdline.Label?>()
                envAndFulfillers.add(unsupportedEnv)
                for (envGroup in provider.getStaticEnvironments().getGroups()) {
                    envAndFulfillers.addAll(envGroup.getFulfillers(unsupportedEnv))
                }
                var culprit: RemovedEnvironmentCulprit? = null
                var i = 0
                while (i < envAndFulfillers.size && culprit == null) {
                    culprit = provider.getRemovedEnvironmentCulprit(envAndFulfillers.get(i))
                    i++
                }
                // culprit could still be null here. See MissingEnvironment class comments for implications.
                ans.add(MissingEnvironment(unsupportedEnv, culprit))
            }
            return ans.build()
        }

        /**
         * Prepares a user-friendly error message for a list of targets missing support for required
         * environments.
         */
        private fun getBadTargetsUserMessage(
            constraintSemantics: RuleContextConstraintSemantics,
            badTargets: com.google.common.collect.Multimap<ConfiguredTarget?, MissingEnvironment?>
        ): String? {
            val msg: java.util.StringJoiner = java.util.StringJoiner("\n")
            msg.add("This is a restricted-environment build.")
            for (entry in badTargets.asMap().entries) {
                msg.add(getErrorMessageForTarget(constraintSemantics, entry.key, entry.value))
            }
            return msg.add(" ").toString()
        }

        fun getErrorMessageForTarget(
            constraintSemantics: RuleContextConstraintSemantics,
            configuredTarget: ConfiguredTarget,
            missingEnvironments: MutableCollection<MissingEnvironment>
        ): String? {
            val msg: java.util.StringJoiner = java.util.StringJoiner("\n")
            var targetWithProvider: ConfiguredTarget = configuredTarget.getActual()
            if (targetWithProvider is OutputFileConfiguredTarget) {
                targetWithProvider = targetWithProvider.getGeneratingRule()
            }
            val supportedEnvironments: SupportedEnvironmentsProvider? =
                targetWithProvider.getProvider<SupportedEnvironmentsProvider?>(SupportedEnvironmentsProvider::class.java)
            val declaredEnvs: String? =
                supportedEnvironments.getStaticEnvironments().getEnvironments().stream()
                    .map<String?> { obj: com.google.devtools.build.lib.cmdline.Label? -> obj.toString() }
                    .collect(Collectors.joining(", "))

            msg.add(" ")
                .add(configuredTarget.getLabel().toString() + " declares compatibility with:")
                .add("  [" + declaredEnvs + "]")
                .add("but does not support:")
            var isFirst = true
            var lastEntryWasMultiline = false
            for (missingEnvironment in missingEnvironments) {
                if (missingEnvironment.culprit == null) {
                    // The target didn't declare support for this environment.
                    if (lastEntryWasMultiline) {
                        // Pretty-format: if the last environment message was multi-line, make it clear this
                        // one is a different entry. But we don't want to do that if all entries are single-line
                        // because that would be pointlessly long.
                        msg.add(" ")
                    }
                    msg.add("  " + missingEnvironment.environment)
                    lastEntryWasMultiline = false
                } else {
                    // The target declared support, but it was refined out by a select() somewhere in its
                    // transitive deps.
                    if (!isFirst) {
                        msg.add(" ") // Pretty-format for clarity.
                    }
                    msg.add(
                        constraintSemantics.getMissingEnvironmentCulpritMessage(
                            configuredTarget.getLabel(),
                            missingEnvironment.environment,
                            missingEnvironment.culprit
                        )
                    )
                    lastEntryWasMultiline = true
                }
                isFirst = false
            }
            return msg.toString()
        }
    }
}

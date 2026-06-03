// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.packages.DeclaredExecGroup.DEFAULT_EXEC_GROUP_NAME

/**
 * [TransitionFactory] implementation which creates a [PatchTransition] which will
 * transition to a configuration suitable for building dependencies for the execution platform of
 * the depending target.
 * 
 * 
 * Note that execGroup is not directly consumed by the involved transition but instead stored
 * here. Instead, the rule definition stores it in this factory. Then, toolchain resolution extracts
 * and consumes it to store an execution platform in attrs. Finally, the execution platform is read
 * by the factory to create the transition.
 */
class ExecutionTransitionFactory
private constructor(private val execGroup: String?) : TransitionFactory<AttributeTransitionData?>,
    ExecTransitionFactoryApi {
    @Throws(TransitionCreationException::class)
    public override fun create(dataWithTargetAttributes: AttributeTransitionData): PatchTransition? {
        // Delete AttributeTransitionData.attributes() so the exec transition doesn't try to read the
        // attributes of the target it's attached to. This is for two reasons:
        //
        //   1) While per-target exec transitions may be interesting, we're not ready to expose that
        //      level of API flexibility
        //   2) No need for StarlarkTransitionCache misses due to different StarlarkTransition instances
        //       bound to different attributes that shouldn't affect output.
        val data: AttributeTransitionData =
            AttributeTransitionData.builder()
                .analysisData(dataWithTargetAttributes.analysisData())
                .executionPlatform(dataWithTargetAttributes.executionPlatform())
                .build()

        if (data.analysisData() == null) {
            // TODO: https://github.com/bazelbuild/rules_license/issues/148 - This is a temporary hack to
            // prevent the license() rule's license_kinds attribute from failing.
            //
            // license() rules are already configured in NoConfigTransition (see the PR that produced this
            // comment). So when they traverse 'cfg = "exec"' on their license_kind attribute, without
            // this special case they'd throw the below TransitionCreationException.
            //
            // What we really want is to remove 'cfg = "exec"' from license_kind's attribute definition
            // because it's redundant (its purpose was to avoid unnecesary forking from target
            // configurations, not to explicitly use the exec configuration). The problem is that the
            // attribute's definition is in in rules_license Starlark code, but the code that makes its
            // parent license() use NoConfigTransition is in Bazel. We need to wait until that code is
            // in a proper Bazel release before to avoid accidental target forking.
            //
            // So the actionable is: when rules_license() users use a new enough Bazel version, remove
            // 'cfg = exec"' from license()'s license_kinds() attribute, then remove this check.
            if (dataWithTargetAttributes.attributes().has("license_kinds")) {
                return NoConfigTransition.INSTANCE
            }
            throw TransitionCreationException(
                "expected a Starlark exec transition definition, but was null"
            )
        }
        val starlarkExecTransitionProvider: TransitionFactory<AttributeTransitionData?> =
            data.analysisData() as TransitionFactory<AttributeTransitionData?>

        return transitionInstanceCache.get( // A Starlark transition keeps the same instance unless we modify its .bzl file.
            Pair.of(data.executionPlatform(), starlarkExecTransitionProvider.hashCode()),
            java.util.function.Function { p: Pair<Label?, Int?>? ->
                ExecTransitionFinalizer(
                    data.executionPlatform(), starlarkExecTransitionProvider.create(data)
                )
            })
    }

    public override fun transitionType(): TransitionType {
        return TransitionType.ATTRIBUTE
    }

    fun getExecGroup(): String? {
        return execGroup
    }

    public override fun isTool(): Boolean {
        return true
    }

    /**
     * Complete exec transition.
     * 
     * 
     * Takes as input the execution platform and the main transition. Calls the main transition,
     * then runs finalizer logic that has to be handled natively.
     */
    private class ExecTransitionFinalizer(executionPlatform: Label?, mainTransition: ConfigurationTransition) :
        PatchTransition {
        private val executionPlatform: Label?

        private val mainTransition: ConfigurationTransition

        init {
            this.executionPlatform = executionPlatform
            this.mainTransition = mainTransition
        }

        public override fun getName(): String {
            return "exec"
        }

        /**
         * Implement [ConfigurationTransition.visit]} so [ ] caches application if
         * this is a Starlark transition.
         */
        @Throws(E::class)
        public override fun <E : java.lang.Exception?> visit(visitor: Visitor<E?>?) {
            this.mainTransition.visit(visitor)
        }

        public override fun requiresOptionFragments(): com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?> {
            // This is technically a lie since the call to underlying().createExecOptions is transitively
            // reading and potentially modifying all fragments. There is currently no way for the
            // transition to actually list all fragments like this and thus only lists the ones that are
            // directly being read here. Note that this transition is exceptional in its implementation.
            return FRAGMENTS
        }

        @Throws(java.lang.InterruptedException::class)
        public override fun patch(options: BuildOptionsView, eventHandler: EventHandler?): BuildOptions? {
            if (executionPlatform == null) {
                // No execution platform is known, so don't change anything.
                return options.underlying()
            }

            val splitOptions: MutableMap.MutableEntry<String?, BuildOptions>? =
                com.google.common.collect.Iterables.getOnlyElement<T?>(
                    mainTransition.apply(options, eventHandler).entrySet()
                )
            val execOptions: BuildOptions = splitOptions.getValue()

            // Set the target to the saved execution platform if there is one.
            val platformOptions: PlatformOptions? = execOptions.get<T?>(PlatformOptions::class.java)
            if (platformOptions != null) {
                platformOptions.setPlatforms(com.google.common.collect.ImmutableList.of<E?>(executionPlatform))
            }

            // Remove any FeatureFlags that were set.
            val featureFlags: com.google.common.collect.ImmutableList<Label?> =
                execOptions.getStarlarkOptions().entrySet().stream()
                    .filter(java.util.function.Predicate { entry: MutableMap.MutableEntry<Label?, Any?>? -> entry.getValue() is FeatureFlagValue })
                    .map<Label?>(java.util.function.Function { java.util.Map.Entry.getKey() })
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<Label?>())

            var result: BuildOptions = execOptions
            if (!featureFlags.isEmpty()) {
                val resultBuilder: com.google.devtools.build.lib.analysis.config.BuildOptions.Builder =
                    result.toBuilder()
                featureFlags.forEach(java.util.function.Consumer { key: Label? -> resultBuilder.removeStarlarkOption(key) })
                result = resultBuilder.build()
            }

            // The conditional use of a Builder above may have replaced result and underlying options
            // with a clone so must refresh it.
            val coreOptions: CoreOptions? = result.get<T?>(CoreOptions::class.java)
            coreOptions.setCommandLineFlagAliases(
                options.underlying().get<T?>(CoreOptions::class.java).getCommandLineFlagAliases()
            )
            // TODO(blaze-configurability-team): These updates probably requires a bit too much knowledge
            //   of exactly how the immutable state and mutable state of BuildOptions is interacting.
            //   Might be good to have an option to wipeout that state rather than cloning so much.
            coreOptions.setPlatformSuffix("exec")
            coreOptions.setExecutionInfoModifier(
                options.underlying().get<T?>(CoreOptions::class.java).getExecutionInfoModifier()
            )
            coreOptions.setOverridePlatformCpuName(
                options.underlying().get<T?>(CoreOptions::class.java).getOverridePlatformCpuName()
            )
            coreOptions.setDisabledSelectOptions(
                options.underlying().get<T?>(CoreOptions::class.java).getDisabledSelectOptions()
            )
            coreOptions.setIncompatibleTargetCpuFromPlatform(
                options.underlying().get<T?>(CoreOptions::class.java).getIncompatibleTargetCpuFromPlatform()
            )
            return result
        }

        companion object {
            private val FRAGMENTS: com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?> =
                com.google.common.collect.ImmutableSet.of<E?>(CoreOptions::class.java, PlatformOptions::class.java)
        }
    }

    companion object {
        /** Returns a new [ExecutionTransitionFactory] for the default [DeclaredExecGroup].  */
        fun createFactory(): ExecutionTransitionFactory {
            return ExecutionTransitionFactory(DEFAULT_EXEC_GROUP_NAME)
        }

        /** Returns a new [ExecutionTransitionFactory] for the given [DeclaredExecGroup].  */
        fun createFactory(execGroup: String?): ExecutionTransitionFactory {
            return ExecutionTransitionFactory(execGroup)
        }

        /**
         * Guarantees we don't duplicate instances of the same transition.
         * 
         * 
         * Bazel's Starlark logic also maintains a distinct instance for each Starlark transition.
         * While that makes this cache seem unnecessary, it still has value. The exec transition uniquely
         * takes an extra parameter: the execution platform label. This is provided by toolchain
         * resolution - the transition can't read it from input build options. So we need to cache on
         * `label, originalTransition` pairs.
         */
        private val transitionInstanceCache: com.github.benmanes.caffeine.cache.Cache<Pair<Label?, Int?>?, PatchTransition?> =
            Caffeine.newBuilder().weakValues().build<Pair<Label?, Int?>?, PatchTransition?>()
    }
}

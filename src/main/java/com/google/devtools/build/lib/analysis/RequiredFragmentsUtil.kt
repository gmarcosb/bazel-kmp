// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.analysis.config.FragmentClassSet

/**
 * Utility methods for determining what [Fragment]s are required to analyze targets.
 * 
 * 
 * For example if a target reads `--copt` as part of its analysis logic, it requires
 * the [com.google.devtools.build.lib.rules.cpp.CppConfiguration] fragment.
 * 
 * 
 * Used by [ ][com.google.devtools.build.lib.query2.cquery.CqueryOptions.showRequiredConfigFragments].
 */
object RequiredFragmentsUtil {
    /**
     * Returns a [RequiredConfigFragmentsProvider] identifying all pieces of configuration a
     * target requires, or `null` if required config fragments are not enabled (see [ ][CoreOptions.includeRequiredConfigFragmentsProvider]).
     * 
     * 
     * The returned config state includes things that are known to be required at the time when the
     * target's dependencies have already been analyzed but before it's been analyzed itself. See
     * [RuleConfiguredTargetBuilder.maybeAddRequiredConfigFragmentsProvider] for the remaining
     * pieces of config state.
     * 
     * 
     * If `configuration` is [CoreOptions.IncludeConfigFragmentsEnum.DIRECT], the
     * result includes only the config state considered to be directly required by this target. If
     * it's [CoreOptions.IncludeConfigFragmentsEnum.TRANSITIVE], it also includes config state
     * needed by transitive dependencies. If it's [CoreOptions.IncludeConfigFragmentsEnum.OFF],
     * this method returns `null`.
     * 
     * 
     * `select()`s and toolchain dependencies are considered when looking at what config
     * state is required.
     * 
     * @param target the target
     * @param configuration the configuration for this target
     * @param universallyRequiredFragments fragments that are always required even if not explicitly
     * specified for this target
     * @param configConditions `config_settings` required by this target's `select
    ` * s. Used for a) figuring out which options `select`s read and b) figuring
     * out which transitions are attached to the target. [TransitionFactory], which
     * determines the transitions, may read the target's attributes.
     * @param prerequisites all prerequisites of `target`
     * @param starlarkExecTransition the Starlark transition implementing the exec transition
     * @return [RequiredConfigFragmentsProvider] or `null` if not enabled
     */
    fun getRuleRequiredFragmentsIfEnabled(
        target: Rule,
        configuration: BuildConfigurationValue,
        universallyRequiredFragments: FragmentClassSet?,
        configConditions: ConfigConditions,
        prerequisites: Iterable<ConfiguredTarget>,
        starlarkExecTransition: StarlarkAttributeTransitionProvider?
    ): RequiredConfigFragmentsProvider? {
        val mode: IncludeConfigFragmentsEnum = getRequiredFragmentsMode(configuration)
        if (mode == IncludeConfigFragmentsEnum.OFF) {
            return null
        }
        val ruleClass: RuleClass = target.getRuleClassObject()
        val attributes: ConfiguredAttributeMapper? =
            ConfiguredAttributeMapper.of(target, configConditions.asProviders, configuration)
        val requiredFragments: RequiredConfigFragmentsProvider.Builder =
            getRequiredFragments(
                mode,
                configuration,
                universallyRequiredFragments,
                ruleClass.getConfigurationFragmentPolicy(),
                configConditions,
                prerequisites
            )
        if (!ruleClass.isStarlark()) {
            ruleClass
                .getConfiguredTargetFactory(RuleConfiguredTargetFactory::class.java)
                .addRuleImplSpecificRequiredConfigFragments(requiredFragments, attributes, configuration)
        }
        addRequiredFragmentsFromRuleTransitions(
            configConditions,
            configuration.checksum(),
            requiredFragments,
            target,
            attributes,
            configuration.getBuildOptionDetails(),
            starlarkExecTransition
        )

        // We consider build settings (which are both targets and configuration) to require themselves.
        if (target.isBuildSetting()) {
            requiredFragments.addStarlarkOption(target.getLabel())
        }

        return requiredFragments.build()
    }

    /**
     * Variation of [.getRuleRequiredFragmentsIfEnabled] for aspects.
     * 
     * @param aspect the aspect
     * @param aspectFactory the corresponding [ConfiguredAspectFactory]
     * @param associatedTarget the target this aspect is attached to
     * @param configuration the configuration for this aspect
     * @param universallyRequiredFragments fragments that are always required even if not explicitly
     * specified for this aspect
     * @param configConditions `config_settings` required by `select`s on the
     * associated target. Used for figuring out which transitions are attached to the target.
     * @param prerequisites all prerequisites of `aspect`
     * @param starlarkExecTransition the Starlark transition implementing the exec transition
     * @return [RequiredConfigFragmentsProvider] or `null` if not enabled
     */
    fun getAspectRequiredFragmentsIfEnabled(
        aspect: Aspect,
        aspectFactory: ConfiguredAspectFactory,
        associatedTarget: Rule?,
        configuration: BuildConfigurationValue,
        universallyRequiredFragments: FragmentClassSet?,
        configConditions: ConfigConditions,
        prerequisites: Iterable<ConfiguredTarget>,
        starlarkExecTransition: StarlarkAttributeTransitionProvider?
    ): RequiredConfigFragmentsProvider? {
        val mode: IncludeConfigFragmentsEnum = getRequiredFragmentsMode(configuration)
        if (mode == IncludeConfigFragmentsEnum.OFF) {
            return null
        }
        val requiredFragments: RequiredConfigFragmentsProvider.Builder =
            getRequiredFragments(
                mode,
                configuration,
                universallyRequiredFragments,
                aspect.getDefinition().getConfigurationFragmentPolicy(),
                configConditions,
                prerequisites
            )
        aspectFactory.addAspectImplSpecificRequiredConfigFragments(requiredFragments)
        addRequiredFragmentsFromAspectTransitions(
            requiredFragments,
            aspect,
            ConfiguredAttributeMapper.of(
                associatedTarget, configConditions.asProviders, configuration
            ),
            configuration.getBuildOptionDetails(),
            starlarkExecTransition
        )
        return requiredFragments.build()
    }

    /** Internal implementation that handles requirements common to both rules and aspects.  */
    private fun getRequiredFragments(
        mode: IncludeConfigFragmentsEnum?,
        configuration: BuildConfigurationValue,
        universallyRequiredFragments: FragmentClassSet?,
        configurationFragmentPolicy: ConfigurationFragmentPolicy,
        configConditions: ConfigConditions,
        prerequisites: Iterable<ConfiguredTarget>
    ): RequiredConfigFragmentsProvider.Builder {
        val requiredFragments: RequiredConfigFragmentsProvider.Builder =
            RequiredConfigFragmentsProvider.builder()

        if (mode == IncludeConfigFragmentsEnum.TRANSITIVE) {
            // Add transitive requirements first, which results in better performance. See explanation on
            // RequiredConfigFragmentsProvider.Builder.
            addTransitivelyRequiredFragments(requiredFragments, prerequisites)
        } else {
            addStarlarkBuildSettings(requiredFragments, prerequisites)
        }

        // Add directly required fragments:
        requiredFragments // Fragments explicitly required by the native target/aspect definition API:
            .addFragmentClasses(configurationFragmentPolicy.getRequiredConfigurationFragments()) // Fragments explicitly required by the Starlark target/aspect definition API (nulls are
            // filtered because the rule definition may reference non-existent fragments):
            .addFragmentClasses(
                com.google.common.collect.Collections2.filter<E?>(
                    com.google.common.collect.Collections2.transform<F?, T?>(
                        configurationFragmentPolicy.getRequiredStarlarkFragments(),
                        com.google.common.base.Function { name: F? -> configuration.getStarlarkFragmentByName(name) }),
                    com.google.common.base.Predicate { obj: E? -> java.util.Objects.nonNull(obj) })
            ) // Fragments universally required by everything:
            .addFragmentClasses(universallyRequiredFragments)
        // Fragments required by attached select()s. Propagating fragments from the config conditions as
        // configured targets (rather than as providers) is necessary in case of a dependency on an
        // alias that resolves to a config setting. Providers only reflect the resolved settings, which
        // won't include fragments required to resolve a select within the alias rule (b/237534193).
        for (targetAndData in configConditions.asConfiguredTargets.values()) {
            requiredFragments.merge(
                targetAndData.getConfiguredTarget().getProvider(RequiredConfigFragmentsProvider::class.java)
            )
        }
        return requiredFragments
    }

    /**
     * Adds required fragments from transitions "attached" to a target.
     * 
     * 
     * "Attached" means the transition is attached to the target itself or one of its attributes.
     * 
     * 
     * These are the transitions required for a target to successfully analyze. Technically,
     * transitions attached to the target are evaluated during its parent's analysis, which is where
     * the configuration for the child is determined. We still consider these the child's requirements
     * because the child's properties determine that dependency.
     */
    private fun addRequiredFragmentsFromRuleTransitions(
        configConditions: ConfigConditions,
        configHash: String?,
        requiredFragments: RequiredConfigFragmentsProvider.Builder?,
        target: Rule,
        attributeMap: ConfiguredAttributeMapper?,
        optionDetails: BuildOptionDetails?,
        starlarkExecTransition: StarlarkAttributeTransitionProvider?
    ) {
        target
            .getRuleClassObject()
            .getTransitionFactory()
            .create(RuleTransitionData.create(target, configConditions.asProviders, configHash))
            .addRequiredFragments(requiredFragments, optionDetails)
        // We don't set the execution platform in this data because a) that doesn't affect which
        // fragments are required and b) it's one less parameter we have to pass to
        // RequiredFragmenstUtil's public interface.
        val attributeTransitionData: AttributeTransitionData? =
            AttributeTransitionData.builder()
                .attributes(attributeMap)
                .analysisData(starlarkExecTransition)
                .build()
        for (attribute in target.getRuleClassObject().getAttributeProvider().getAttributes()) {
            if (attribute.getTransitionFactory() != null) {
                attribute
                    .getTransitionFactory()
                    .create(attributeTransitionData)
                    .addRequiredFragments(requiredFragments, optionDetails)
            }
        }
    }

    /**
     * Adds required fragments from transitions "attached" to an aspect.
     * 
     * 
     * "Attached" means the transition is attached to one of the aspect's attributes. Transitions
     * can't be attached directly to aspects themselves.
     */
    private fun addRequiredFragmentsFromAspectTransitions(
        requiredFragments: RequiredConfigFragmentsProvider.Builder?,
        aspect: Aspect,
        attributeMap: ConfiguredAttributeMapper?,
        optionDetails: BuildOptionDetails?,
        starlarkExecTransition: StarlarkAttributeTransitionProvider?
    ) {
        val attributeTransitionData: AttributeTransitionData? =
            AttributeTransitionData.builder()
                .attributes(attributeMap)
                .analysisData(starlarkExecTransition)
                .build()
        for (attribute in aspect.getDefinition().getAttributes().values()) {
            if (attribute.getTransitionFactory() != null) {
                attribute
                    .getTransitionFactory()
                    .create(attributeTransitionData)
                    .addRequiredFragments(requiredFragments, optionDetails)
            }
        }
    }

    private fun addTransitivelyRequiredFragments(
        requiredFragments: RequiredConfigFragmentsProvider.Builder,
        prerequisites: Iterable<ConfiguredTarget>
    ) {
        for (prereq in prerequisites) {
            val depProvider: RequiredConfigFragmentsProvider? =
                prereq.getProvider(RequiredConfigFragmentsProvider::class.java)
            if (depProvider != null) {
                requiredFragments.merge(depProvider)
            }
        }
    }

    /**
     * Adds dependencies on Starlark build settings.
     * 
     * 
     * Starlark build settings are considered direct requirements on the rule even though they are
     * technically dependencies. Note that this method only needs to be called in [ ][IncludeConfigFragmentsEnum.DIRECT] mode, since [IncludeConfigFragmentsEnum.TRANSITIVE]
     * mode will already pick up these requirements from dependencies.
     */
    private fun addStarlarkBuildSettings(
        requiredFragments: RequiredConfigFragmentsProvider.Builder,
        prerequisites: Iterable<ConfiguredTarget>
    ) {
        for (prereq in prerequisites) {
            val buildSettingProvider: BuildSettingProvider? = prereq.getProvider(BuildSettingProvider::class.java)
            if (buildSettingProvider != null) {
                requiredFragments.addStarlarkOption(buildSettingProvider.getLabel())
            }
        }
    }

    private fun getRequiredFragmentsMode(
        config: BuildConfigurationValue
    ): IncludeConfigFragmentsEnum {
        return com.google.common.base.Preconditions.checkNotNull<T>(
            config.getOptions().get<T?>(CoreOptions::class.java).getIncludeRequiredConfigFragmentsProvider()
        )
    }
}

// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.starlark.FunctionTransitionUtil.applyAndValidate

/**
 * Implements [TransitionFactory] to provide a starlark-defined transition that rules can
 * apply to their own configuration. This transition has access to (1) a map of the current
 * configuration's build settings and (2) the configured attributes of the given rule (not its
 * dependencies').
 * 
 * 
 * In some corner cases, we can't access the configured attributes the configuration of the child
 * may be different than the configuration of the parent. For now, forbid all access to attributes
 * that read selects.
 * 
 * 
 * For starlark-defined attribute transitions, see [StarlarkAttributeTransitionProvider].
 */
class StarlarkRuleTransitionProvider internal constructor(starlarkDefinedConfigTransition: StarlarkDefinedConfigTransition) :
    TransitionFactory<RuleTransitionData?> {
    private val starlarkDefinedConfigTransition: StarlarkDefinedConfigTransition

    init {
        this.starlarkDefinedConfigTransition = starlarkDefinedConfigTransition
    }

    @com.google.common.annotations.VisibleForTesting
    fun getStarlarkDefinedConfigTransitionForTesting(): StarlarkDefinedConfigTransition {
        return starlarkDefinedConfigTransition
    }

    public override fun create(ruleData: RuleTransitionData?): PatchTransition {
        // This wouldn't be safe if rule transitions could read attributes with select(), in which case
        // the rule alone isn't sufficient to define the transition's semantics (both the rule and its
        // configuration are needed). Rule transitions can't read select()s, so this is a non-issue.
        //
        // We could cache-optimize further by distinguishing transitions that read attributes vs. those
        // that don't. Every transition has a {@code def impl(settings, attr) } signature, even if the
        // transition never reads {@code attr}. If we had a way to formally identify such transitions,
        // we wouldn't need {@code rule} in the cache key.
        return starlarkDefinedConfigTransition.createRuleTransition(
            ruleData,
            { ruleData: RuleTransitionData -> this.createTransition(ruleData) })
    }

    public override fun transitionType(): TransitionType {
        return TransitionType.RULE
    }

    fun allowImmutableFlagChanges(): Boolean {
        return false
    }

    private fun createTransition(ruleData: RuleTransitionData): FunctionPatchTransition {
        val rule: Rule = ruleData.rule()
        val configConditions: com.google.common.collect.ImmutableMap<Label?, ConfigMatchingProvider?>? =
            ruleData.configConditions()
        val configHash: String? = ruleData.configHash()
        val attributes: LinkedHashMap<String?, Any?> = LinkedHashMap<String?, Any?>()
        val attributeMapper: RawAttributeMapper = RawAttributeMapper.of(rule)
        val configuredAttributeMapper: ConfiguredAttributeMapper =
            ConfiguredAttributeMapper.of(rule, configConditions, configHash, false)
        val transitionOutputs: com.google.common.collect.ImmutableCollection<String?> =
            this.starlarkDefinedConfigTransition.getOutputs()

        for (attribute in rule.getAttributes()) {
            // If the value is present, even if it is null, add to the attribute map.
            var `val`: Any? = attributeMapper.getRawAttributeValue(rule, attribute)
            if (`val` is SelectorList<*>) {
                val result =
                    handleConfiguredAttribute(
                        configConditions, configuredAttributeMapper, transitionOutputs, attribute, `val`
                    )
                if (!result.success) {
                    // Skip this attribute.
                    continue
                } else {
                    `val` = result.resolved
                }
            }

            attributes.put(
                Attribute.getStarlarkName(attribute.getPublicName()), Attribute.valueToStarlark(`val`)
            )
        }

        val attrObject: StructImpl? =
            StructProvider.STRUCT.create(
                attributes,
                ("No attribute '%s'. Either this attribute does not exist for this rule or the attribute"
                        + " was not resolved because it is set by a select that reads flags the transition"
                        + " may set.")
            )
        return FunctionPatchTransition(attrObject)
    }

    /**
     * A container class for the result of [.handleConfiguredAttribute].
     * 
     * 
     * The most important point is that the `success` field tells whether the attribute was
     * resolved. It is entirely possible to resolve an attribute to `null`.
     */
    @kotlin.jvm.JvmRecord
    private data class Result(val success: Boolean, val resolved: Any?) {
        companion object {
            fun failure(): Result {
                return com.google.devtools.build.lib.analysis.starlark.StarlarkRuleTransitionProvider.Result(
                    false,
                    null
                )
            }

            fun success(resolved: Any?): Result {
                return com.google.devtools.build.lib.analysis.starlark.StarlarkRuleTransitionProvider.Result(
                    true,
                    resolved
                )
            }
        }
    }

    private fun handleConfiguredAttribute(
        configConditions: com.google.common.collect.ImmutableMap<Label?, ConfigMatchingProvider?>?,
        configuredAttributeMapper: ConfiguredAttributeMapper,
        transitionOutputs: com.google.common.collect.ImmutableCollection<String?>,
        attribute: Attribute?,
        `val`: SelectorList<*>
    ): Result {
        // If there are no configConditions then nothing is resolvable.
        if (configConditions == null || configConditions.isEmpty()) {
            return com.google.devtools.build.lib.analysis.starlark.StarlarkRuleTransitionProvider.Result.Companion.failure()
        }

        // If any of the select keys reference the outputs, this isn't resolvable.
        if (selectBranchesReferenceOutputs(configConditions, transitionOutputs, `val`)) {
            return com.google.devtools.build.lib.analysis.starlark.StarlarkRuleTransitionProvider.Result.Companion.failure()
        }

        // Resolve the attribute, ignoring any failures. They will be reported (and fail analysis) later
        // in the rule analysis.
        val result: ConfiguredAttributeMapper.AttributeResolutionResult<*> =
            configuredAttributeMapper.getResolvedAttribute(attribute)
        return when (result.getType()) {
            FAILURE -> com.google.devtools.build.lib.analysis.starlark.StarlarkRuleTransitionProvider.Result.Companion.failure()
            SUCCESS -> com.google.devtools.build.lib.analysis.starlark.StarlarkRuleTransitionProvider.Result.Companion.success(
                result.getSuccess().orElse(null)
            )
        }
    }

    private fun selectBranchesReferenceOutputs(
        configConditions: com.google.common.collect.ImmutableMap<Label?, ConfigMatchingProvider?>,
        transitionOutputs: com.google.common.collect.ImmutableCollection<String?>,
        `val`: SelectorList<*>
    ): Boolean {
        for (label in `val`.getKeyLabels()) {
            val configMatchingProvider: ConfigMatchingProvider? = configConditions.get(label)
            if (checkIfAttributeSelectOnAFlagTransitionChanges(
                    configMatchingProvider, transitionOutputs
                )
            ) {
                return true
            }
        }
        return false
    }

    private fun checkIfAttributeSelectOnAFlagTransitionChanges(
        configMatchingProvider: ConfigMatchingProvider,
        transitionOutputs: com.google.common.collect.ImmutableCollection<String?>
    ): Boolean {
        // check settingMap
        val nativeFlagLabels: MutableSet<String?> = HashSet<String?>()
        for (key in configMatchingProvider.settingsMap().keySet()) {
            val modified = "//command_line_option:" + key
            nativeFlagLabels.add(modified)
        }
        // check flags values
        val flagSettingsMap: com.google.common.collect.ImmutableMap<Label?, String?> =
            configMatchingProvider.flagSettingsMap()
        val flagLabels: MutableSet<String?> = HashSet<String?>()
        for (flag in flagSettingsMap.keySet()) {
            flagLabels.add(flag.getCanonicalForm())
        }

        for (output in transitionOutputs) {
            if (nativeFlagLabels.contains(output) || flagLabels.contains(output)) {
                return true
            }
        }
        return false
    }

    /** The actual transition used by the rule.  */
    private inner class FunctionPatchTransition(attrObject: StructImpl?) :
        StarlarkTransition(starlarkDefinedConfigTransition), PatchTransition {
        private val attrObject: StructImpl?
        private val hashCode: Int

        init {
            this.attrObject = attrObject
            this.hashCode = java.util.Objects.hash(attrObject, super.hashCode())
        }

        /**
         * @return the post-transition build options or a clone of the original build options if an
         * error was encountered during transition application/validation.
         */
        // TODO(b/121134880): validate that the targets these transitions are applied on don't read any
        // attributes that are then configured by the outputs of these transitions.
        @Throws(java.lang.InterruptedException::class)
        public override fun patch(
            buildOptionsView: BuildOptionsView,
            eventHandler: com.google.devtools.build.lib.events.EventHandler
        ): BuildOptions? {
            // Starlark transitions already have logic to enforce they only access declared inputs and
            // outputs. Rather than complicate BuildOptionsView with more access points to BuildOptions,
            // we just use the original BuildOptions and trust the transition's enforcement logic.
            val buildOptions: BuildOptions = buildOptionsView.underlying()
            val result: MutableMap<String?, BuildOptions?>? =
                applyAndValidate(
                    buildOptions,
                    starlarkDefinedConfigTransition,
                    allowImmutableFlagChanges(),
                    isExecTransition(),
                    attrObject,
                    eventHandler
                )
            if (result == null) {
                return buildOptions.clone()
            }
            if (result.size() != 1) {
                eventHandler.handle(
                    com.google.devtools.build.lib.events.Event.error(
                        starlarkDefinedConfigTransition.getLocation(),
                        "Rule transition only allowed to return a single transitioned configuration."
                    )
                )
                return buildOptions.clone()
            }
            return com.google.common.collect.Iterables.getOnlyElement<BuildOptions?>(result.values())
        }

        public override fun isExecTransition(): Boolean {
            return false
        }

        override fun equals(`object`: Any?): Boolean {
            if (`object` === this) {
                return true
            }
            if (`object` !is FunctionPatchTransition) {
                return false
            }
            return attrObject == `object`.attrObject && super.equals(`object`)
        }

        override fun hashCode(): Int {
            return hashCode
        }
    }
}

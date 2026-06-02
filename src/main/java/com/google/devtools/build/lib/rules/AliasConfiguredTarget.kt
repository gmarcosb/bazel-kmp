// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules

import com.google.devtools.build.lib.actions.ActionLookupKey

/**
 * A [ConfiguredTarget] that pretends to be whatever type of target [.getActual] is,
 * mirroring its label and transitive info providers.
 * 
 * 
 * Transitive info providers may also be overridden. At a minimum, [.getProvider] provides
 * [AliasProvider] and an explicit [VisibilityProvider] which takes precedent over the
 * actual target's visibility.
 * 
 * 
 * The [ConfiguredTarget.getConfigurationKey] returns the configuration of the alias itself
 * and not the configuration of [AliasConfiguredTarget.actual] for the following reasons.
 * 
 * 
 *  * `actual` might be an input file, in which case its configuration key is null, and we
 * don't want to have rules with a null configuration key.
 *  * `actual` has a self transition. Self transitions don't get applied to the alias rule,
 * and so the configuration keys actually differ.
 * 
 * 
 * 
 * An `alias` target may not be used to redirect a `package_group` target in a `visibility` declaration or a `package_group`'s `includes` attribute.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
@AutoCodec
class AliasConfiguredTarget @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization internal constructor(
    actionLookupKey: ActionLookupKey,
    actual: ConfiguredTarget?,
    overrides: com.google.common.collect.ImmutableClassToInstanceMap<TransitiveInfoProvider?>?,
    configConditions: com.google.common.collect.ImmutableMap<Label?, ConfigMatchingProvider?>?
) : ConfiguredTarget, net.starlark.java.eval.Structure {
    private val actionLookupKey: ActionLookupKey
    private val actual: ConfiguredTarget
    private val overrides: com.google.common.collect.ImmutableClassToInstanceMap<TransitiveInfoProvider?>
    private val configConditions: com.google.common.collect.ImmutableMap<Label?, ConfigMatchingProvider?>? = null

    init {
        this.actionLookupKey = actionLookupKey
            .also {
                this.actual = it
            }<ConfiguredTarget> com . google . common . base . Preconditions . checkNotNull < kotlin . Any ? > (actual)
            .also {
                this.overrides = it
            } < ImmutableClassToInstanceMap < TransitiveInfoProvider shr com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableClassToInstanceMap<TransitiveInfoProvider?>?>(
            overrides
        )
        TODO(
            """
            |Cannot convert element
            |With text:
            |this.configConditions = <ImmutableMap<Label,ConfigMatchingProvider>>checkNotNull(configConditions);
            """.trimMargin()
        )
    }

    val actualNoFollow: ConfiguredTarget
        get() = actual

    val lookupKey: ActionLookupKey
        get() = this.actionLookupKey

    val isImmutable: Boolean
        get() = true // immutable and Starlark-hashable

    public override fun getConfigConditions(): com.google.common.collect.ImmutableMap<Label?, ConfigMatchingProvider?>? {
        return configConditions
    }

    public override fun <P : TransitiveInfoProvider?> getProvider(provider: java.lang.Class<P?>): P? {
        val p: P? = overrides.getInstance<P?>(provider)
        return if (p != null) p else actual.getProvider(provider)
    }

    val label: Label
        // TODO(bazel-team): It's a bit confusing that we're returning the label of the target we directly
        get() = actual.getLabel()

    public override fun get(providerKey: String?): Any {
        return actual.get(providerKey)
    }

    public override fun get(providerKey: Provider.Key?): Info? {
        return actual.get(providerKey)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun getIndex(semantics: net.starlark.java.eval.StarlarkSemantics?, key: Any?): Any {
        return actual.getIndex(semantics, key)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun containsKey(semantics: net.starlark.java.eval.StarlarkSemantics?, key: Any?): Boolean {
        return actual.containsKey(semantics, key)
    }

    /* Structure methods */
    override fun getValue(name: String): Any? {
        if (name == LABEL_FIELD) {
            return this.label
        } else if (name == FILES_FIELD) {
            // A shortcut for files to build in Starlark. FileConfiguredTarget and RuleConfiguredTarget
            // always has FileProvider and Error- and PackageGroupConfiguredTarget-s shouldn't be
            // accessible in Starlark.
            return Depset.of(
                Artifact::class.java,
                TODO("Cannot convert element")
            )<P> getProvider < P ? > (FileProvider::class.java).getFilesToBuild()
        }
        return actual.getValue(name)
    }

    val fieldNames: com.google.common.collect.ImmutableCollection<String?>
        get() = actual.getFieldNames()

    override fun getErrorMessageForUnknownField(name: String?): String? {
        // Use the default error message.
        return null
    }

    public override fun getActual(): ConfiguredTarget {
        // This will either dereference an alias chain, or return the final ConfiguredTarget.
        return actual.getActual()
    }

    val originalLabel: Label
        get() = actionLookupKey.getLabel()

    val providersDictForQuery: net.starlark.java.eval.Dict<String?, Any?>
        get() = actual.getProvidersDictForQuery()

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        printer.append(
            "<alias target " + actionLookupKey.getLabel() + " of " + actual.getLabel() + ">"
        )
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("label", actionLookupKey.getLabel())
            .add("configurationKey", getConfigurationKey())
            .add("actual", actual)
            .add("overrides", overrides)
            .add("configConditions", configConditions)
            .toString()
    }

    companion object {
        /**
         * Convenience wrapper for [.createWithOverrides] that does not specify any additional
         * overrides.
         */
        fun create(
            ruleContext: RuleContext,
            actual: ConfiguredTarget?,
            visibility: NestedSet<PackageGroupContents?>?
        ): AliasConfiguredTarget {
            return createWithOverrides(
                ruleContext,
                actual,
                visibility,  /*overrides=*/
                com.google.common.collect.ImmutableClassToInstanceMap.of<TransitiveInfoProvider?>()
            )
        }

        /**
         * Constructs an `AliasConfiguredTarget` that forwards most of the providers of `actual`, with certain providers shadowed.
         * 
         * 
         * The shadowed providers are anything given in `overrides`, plus the following built-in
         * changes which take priority above both `actual` and `overrides`:
         * 
         * 
         *  * [AliasProvider] is set to indicate that this is an alias configured target.
         *  * [VisibilityProvider] has the information describing this alias target (as passed
         * here in the `visibility` parameter), not the information describing the `actual` underlying target.
         *  * [RequiredConfigFragmentsProvider] may be set}
         * 
         */
        fun createWithOverrides(
            ruleContext: RuleContext,
            actual: ConfiguredTarget?,
            visibility: NestedSet<PackageGroupContents?>?,
            overrides: com.google.common.collect.ImmutableClassToInstanceMap<TransitiveInfoProvider?>
        ): AliasConfiguredTarget {
            val allOverrides: com.google.common.collect.ImmutableClassToInstanceMap.Builder<TransitiveInfoProvider?> =
                com.google.common.collect.ImmutableClassToInstanceMap.builder<TransitiveInfoProvider?>()
                    .putAll<TransitiveInfoProvider?>(overrides)
                    .put<T?>(AliasProvider::class.java, AliasProvider.fromAliasRule(ruleContext.getRule(), actual))
                    .put<T?>(
                        VisibilityProvider::class.java,
                        VisibilityProviderImpl(
                            visibility,  /* isCreatedInSymbolicMacro= */
                            ruleContext
                                .getRule()
                                .isCreatedInSymbolicMacro()
                        )
                    )
            if (ruleContext.getRequiredConfigFragments() != null) {
                // This causes "blaze cquery --show_config_fragments=direct" to only show the
                // fragments/options the alias directly uses, not those of its actual target. Since alias
                // has a narrow API this practically means whatever a select() in the alias requires.
                allOverrides.put<T?>(
                    RequiredConfigFragmentsProvider::class.java, ruleContext.getRequiredConfigFragments()
                )
            }
            return AliasConfiguredTarget(
                ruleContext.getOwner(), actual, allOverrides.build(), ruleContext.getConfigConditions()
            )
        }
    }
}

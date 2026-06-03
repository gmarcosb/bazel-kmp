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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.cmdline.Label

/** A provider that gives information about the aliases a rule was resolved through.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class AliasProvider private constructor(aliasChain: com.google.common.collect.ImmutableList<Label?>) :
    TransitiveInfoProvider {
    // Non-empty list of labels of alias targets, with first one pointing to second pointing to third
    // etc., terminating in the alias that points to the actual non-alias target.
    // We don't expect long alias chains, so it's better to have a list instead of a nested set
    private val aliasChain: com.google.common.collect.ImmutableList<Label?>

    init {
        this.aliasChain = aliasChain
    }

    /**
     * Returns the list of aliases from top to bottom (i.e. the last alias depends on the actual
     * resolved target and the first alias is the one that was in the attribute of the rule currently
     * being analyzed)
     */
    fun getAliasChain(): com.google.common.collect.ImmutableList<Label?> {
        return aliasChain
    }

    /** The way [reports the][.describeTargetWithAliases] */
    enum class TargetMode {
        WITH_KIND,  // Specify the kind of the target
        WITHOUT_KIND,  // Only say "target"
    }

    /**
     * A provider to be advertised by [LateBoundAlias] rules.
     * 
     * 
     * This is a separate provider from [AliasProvider] because [LateBoundAlias] rules
     * do not always create an [AliasProvider].
     */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    class LateBoundAliasProvider private constructor() : TransitiveInfoProvider
    companion object {
        /** Singleton instance of [LateBoundAliasProvider].  */
        val LATE_BOUND_ALIAS_PROVIDER: LateBoundAliasProvider = LateBoundAliasProvider()

        /**
         * Creates an alias provider indicating that `aliasRule` is an alias to `actual`.
         * 
         * 
         * `aliasRule` must either explicitly advertise [AliasProvider] or advertise that
         * it [can have any provider][AdvertisedProviderSet.canHaveAnyProvider].
         */
        fun fromAliasRule(aliasRule: Rule?, actual: ConfiguredTarget): AliasProvider {
            com.google.common.base.Preconditions.checkArgument(
                mayBeAlias(aliasRule),
                "%s does not advertise AliasProvider",
                aliasRule
            )

            val chain: com.google.common.collect.ImmutableList<Label?>?
            val dep: AliasProvider? = actual.getProvider(AliasProvider::class.java)
            if (dep == null) {
                // No other aliases to chain.
                chain = com.google.common.collect.ImmutableList.of<E?>(aliasRule.getLabel())
            } else {
                // Put ourselves at the head of the new chain.
                chain =
                    com.google.common.collect.ImmutableList.builderWithExpectedSize<Label?>(dep.aliasChain.size + 1)
                        .add(aliasRule.getLabel())
                        .addAll(dep.aliasChain)
                        .build()
            }
            return AliasProvider(chain)
        }

        /**
         * Returns the label by which `dep` was referred to in the BUILD file.
         * 
         * 
         * For non-alias rules, it's the label of the rule itself. For alias rules, it's the label of
         * the alias rule.
         * 
         * 
         * Note that [ConfiguredTarget.getLabel] isn't suitable for this use case because [ ]'s implementation of this method returns the alias's `actual`
         * prerequisite (and not even the transitive actual target at the end of the chain!).
         */
        // TODO(bazel-team): Rename this and getDependencyLabels to something more descriptive.
        fun getDependencyLabel(dep: TransitiveInfoCollection): Label? {
            val aliasProvider: AliasProvider? = dep.getProvider(AliasProvider::class.java)
            return if (aliasProvider != null) aliasProvider.aliasChain.get(0) else dep.getLabel()
        }

        /**
         * Returns all labels by which it can be referred to in the BUILD file.
         * 
         * 
         * For non-alias rules, it's the label of the rule itself. For alias rules, they're the label
         * of the alias and the label of alias' target rule.
         */
        // TODO(bazel-team): "all labels"? This returns at most two labels, so it omits the entire middle
        // of the alias chain. Should we change this behavior or the javadoc?
        fun getDependencyLabels(dep: TransitiveInfoCollection): com.google.common.collect.ImmutableList<Label?> {
            val aliasProvider: AliasProvider? = dep.getProvider(AliasProvider::class.java)
            return if (aliasProvider != null)
                com.google.common.collect.ImmutableList.of<E?>(aliasProvider.aliasChain.get(0), dep.getLabel())
            else
                com.google.common.collect.ImmutableList.of<E?>(dep.getLabel())
        }

        /**
         * Prints a nice description of a target.
         * 
         * Also adds the aliases it was reached through, if any.
         * 
         * @param target the target to describe
         * @param targetMode how to express the kind of the target
         */
        fun describeTargetWithAliases(
            target: ConfiguredTargetAndData, targetMode: TargetMode?
        ): String {
            val kind: String? = if (targetMode == TargetMode.WITH_KIND) target.getTargetKind() else "target"
            val aliasProvider: AliasProvider? = target.getConfiguredTarget().getProvider(AliasProvider::class.java)
            if (aliasProvider == null) {
                return kind + " '" + target.getTargetLabel() + "'"
            }

            val aliasChain: com.google.common.collect.ImmutableList<Label?> = aliasProvider.aliasChain
            val result: java.lang.StringBuilder = java.lang.StringBuilder()
            result.append("alias '").append(aliasChain.get(0)).append("'")
            result
                .append(" referring to ")
                .append(kind)
                .append(" '")
                .append(target.getTargetLabel())
                .append("'")
            if (aliasChain.size > 1) {
                result
                    .append(" through '")
                    .append(com.google.common.base.Joiner.on("' -> '").join(aliasChain.subList(1, aliasChain.size)))
                    .append("'")
            }

            return result.toString()
        }

        /**
         * Returns `true` iff the given [TransitiveInfoCollection] has an [ ].
         */
        fun isAlias(dep: TransitiveInfoCollection): Boolean {
            return dep.getProvider(AliasProvider::class.java) != null
        }

        /**
         * Returns `true` if the given target *may* contain an [AliasProvider] when
         * analyzed.
         * 
         * 
         * This method returns `true` for the `alias` rule as well as some other alias-like
         * rules such as `bind`.
         * 
         * 
         * Note that due to the presence of late-bound aliases, this may return `true` even if
         * [.isAlias] on the configured target returns `false`.
         */
        fun mayBeAlias(target: Target?): Boolean {
            if (target !is Rule) {
                return false
            }
            val providerSet: AdvertisedProviderSet = target.getRuleClassObject().getAdvertisedProviders()
            return providerSet.canHaveAnyProvider()
                    || providerSet.getBuiltinProviders().contains(AliasProvider::class.java)
                    || providerSet.getBuiltinProviders().contains(LateBoundAliasProvider::class.java)
        }
    }
}

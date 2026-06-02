// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ActionConfig
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.CollidingProvidesException
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.CrosstoolSelectable
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration
import com.google.devtools.build.lib.vfs.PathFragment
import java.util.ArrayDeque
import java.util.Collections
import java.util.HashSet

/**
 * Implements the feature selection algorithm.
 * 
 * 
 * Feature selection is done by first enabling all features reachable by an 'implies' edge, and
 * then iteratively pruning features that have unmet requirements.
 */
internal class FeatureSelection(
    requestedFeatures: com.google.common.collect.ImmutableSet<String?>,
    selectablesByName: com.google.common.collect.ImmutableMap<String?, CrosstoolSelectable?>,
    selectables: com.google.common.collect.ImmutableList<CrosstoolSelectable>,
    provides: com.google.common.collect.ImmutableMultimap<String?, CrosstoolSelectable?>,
    implies: com.google.common.collect.ImmutableMultimap<CrosstoolSelectable?, CrosstoolSelectable?>,
    impliedBy: com.google.common.collect.ImmutableMultimap<CrosstoolSelectable?, CrosstoolSelectable?>,
    requires: com.google.common.collect.ImmutableMultimap<CrosstoolSelectable?, com.google.common.collect.ImmutableSet<CrosstoolSelectable?>?>,
    requiredBy: com.google.common.collect.ImmutableMultimap<CrosstoolSelectable?, CrosstoolSelectable?>,
    actionConfigsByActionName: com.google.common.collect.ImmutableMap<String?, ActionConfig?>?,
    ccToolchainPath: PathFragment?
) {
    /**
     * The selectables Bazel would like to enable; either because they are supported and generally
     * useful, or because the user required them (for example through the command line).
     */
    private val requestedSelectables: com.google.common.collect.ImmutableSet<CrosstoolSelectable?>

    /** All features requested by the user regardless of whether they exist in CROSSTOOLs or not.  */
    private val requestedFeatures: com.google.common.collect.ImmutableSet<String?>?

    /**
     * The currently enabled selectable; during feature selection, we first put all selectables
     * reachable via an 'implies' edge into the enabled selectable set, and than prune that set from
     * selectables that have unmet requirements.
     */
    private val enabled: MutableSet<CrosstoolSelectable?> = HashSet<CrosstoolSelectable?>()

    /**
     * All features and action configs in the order in which they were specified in the configuration.
     * 
     * 
     * We guarantee the command line to be in the order in which the flags were specified in the
     * configuration.
     */
    private val selectables: com.google.common.collect.ImmutableList<CrosstoolSelectable>

    /**
     * Maps an action's name to the ActionConfig.
     */
    private val actionConfigsByActionName: com.google.common.collect.ImmutableMap<String?, ActionConfig?>?

    /**
     * Maps from a selectable to a set of all the selectables it has a direct 'implies' edge to.
     */
    private val implies: com.google.common.collect.ImmutableMultimap<CrosstoolSelectable?, CrosstoolSelectable?>

    /**
     * Maps from a selectable to all features that have an direct 'implies' edge to this
     * selectable.
     */
    private val impliedBy: com.google.common.collect.ImmutableMultimap<CrosstoolSelectable?, CrosstoolSelectable?>

    /**
     * Maps from a selectable to a set of selecatable sets, where:
     * 
     *  * a selectable set satisfies the 'requires' condition, if all selectables in the
     * selectable set are enabled
     *  * the 'requires' condition is satisfied, if at least one of the selectable sets satisfies
     * the 'requires' condition.
     * 
     */
    private val requires: com.google.common.collect.ImmutableMultimap<CrosstoolSelectable?, com.google.common.collect.ImmutableSet<CrosstoolSelectable?>?>


    /**
     * Maps from a string to the set of selectables that 'provide' it.
     */
    private val provides: com.google.common.collect.ImmutableMultimap<String?, CrosstoolSelectable?>

    /**
     * Maps from a selectable to all selectables that have a requirement referencing it.
     * 
     * 
     * This will be used to determine which selectables need to be re-checked after a selectable
     * was disabled.
     */
    private val requiredBy: com.google.common.collect.ImmutableMultimap<CrosstoolSelectable?, CrosstoolSelectable?>

    /** Location of the cc_toolchain in use.  */
    private val ccToolchainPath: PathFragment?

    init {
        this.requestedFeatures = requestedFeatures
        val builder: com.google.common.collect.ImmutableSet.Builder<CrosstoolSelectable?> =
            com.google.common.collect.ImmutableSet.builder<CrosstoolSelectable?>()
        for (name in requestedFeatures) {
            if (selectablesByName.containsKey(name)) {
                builder.add(selectablesByName.get(name))
            }
        }
        this.requestedSelectables = builder.build()
        this.selectables = selectables
        this.provides = provides
        this.implies = implies
        this.impliedBy = impliedBy
        this.requires = requires
        this.requiredBy = requiredBy
        this.actionConfigsByActionName = actionConfigsByActionName
        this.ccToolchainPath = ccToolchainPath
    }

    /**
     * @return a [FeatureConfiguration] that reflects the set of activated features and action
     * configs.
     */
    @Throws(CollidingProvidesException::class)
    fun run(): FeatureConfiguration {
        for (selectable in requestedSelectables) {
            enableAllImpliedBy(selectable)
        }

        disableUnsupportedActivatables()
        val enabledActivatablesInOrderBuilder: com.google.common.collect.ImmutableList.Builder<CrosstoolSelectable?> =
            com.google.common.collect.ImmutableList.builder<CrosstoolSelectable?>()
        for (selectable in selectables) {
            if (enabled.contains(selectable)) {
                enabledActivatablesInOrderBuilder.add(selectable)
            }
        }

        val enabledActivatablesInOrder: com.google.common.collect.ImmutableList<CrosstoolSelectable?> =
            enabledActivatablesInOrderBuilder.build()
        val enabledFeaturesInOrder: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Feature?> =
            enabledActivatablesInOrder
                .stream()
                .filter(java.util.function.Predicate { a: CrosstoolSelectable? -> a is com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Feature })
                .map<com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Feature?>(java.util.function.Function { f: CrosstoolSelectable? -> f as com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Feature })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Feature?>())
        val enabledActionConfigsInOrder: Iterable<ActionConfig> =
            com.google.common.collect.Iterables.filter<ActionConfig?>(
                enabledActivatablesInOrder,
                ActionConfig::class.java
            )

        for (provided in provides.keys()) {
            val conflicts: MutableList<String?> = java.util.ArrayList<String?>()
            for (selectableProvidingString in provides.get(provided)) {
                if (enabledActivatablesInOrder.contains(selectableProvidingString)) {
                    conflicts.add(selectableProvidingString.getName())
                }
            }

            if (conflicts.size() > 1) {
                throw CollidingProvidesException(
                    java.lang.String.format(
                        CcToolchainFeatures.Companion.COLLIDING_PROVIDES_ERROR,
                        provided,
                        com.google.common.base.Joiner.on(" ").join(conflicts)
                    )
                )
            }
        }

        val enabledActionConfigNames: com.google.common.collect.ImmutableSet.Builder<String?> =
            com.google.common.collect.ImmutableSet.builder<String?>()
        for (actionConfig in enabledActionConfigsInOrder) {
            enabledActionConfigNames.add(actionConfig.getActionName())
        }

        return FeatureConfiguration(
            requestedFeatures,
            enabledFeaturesInOrder,
            enabledActionConfigNames.build(),
            actionConfigsByActionName,
            ccToolchainPath
        )
    }

    /**
     * Transitively and unconditionally enable all selectables implied by the given selectable and the
     * selectable itself to the enabled selectable set.
     */
    private fun enableAllImpliedBy(selectable: CrosstoolSelectable?) {
        if (enabled.contains(selectable)) {
            return
        }
        enabled.add(selectable)
        for (implied in implies.get(selectable)) {
            enableAllImpliedBy(implied)
        }
    }

    /** Remove all unsupported features from the enabled feature set.  */
    private fun disableUnsupportedActivatables() {
        val check: java.util.Queue<CrosstoolSelectable?> = ArrayDeque<CrosstoolSelectable?>(enabled)
        while (!check.isEmpty()) {
            checkActivatable(check.poll())
        }
    }

    /**
     * Check if the given selectable is still satisfied within the set of currently enabled
     * selectables.
     * 
     * 
     * If it is not, remove the selectable from the set of enabled selectables, and re-check all
     * selectables that may now also become disabled.
     */
    private fun checkActivatable(selectable: CrosstoolSelectable?) {
        if (!enabled.contains(selectable) || isSatisfied(selectable)) {
            return
        }
        enabled.remove(selectable)

        // Once we disable a selectable, we have to re-check all selectables that can be affected
        // by that removal.
        // 1. A selectable that implied the current selectable is now going to be disabled.
        for (impliesCurrent in impliedBy.get(selectable)) {
            checkActivatable(impliesCurrent)
        }
        // 2. A selectable that required the current selectable may now be disabled, depending on
        // whether the requirement was optional.
        for (requiresCurrent in requiredBy.get(selectable)) {
            checkActivatable(requiresCurrent)
        }
        // 3. A selectable that this selectable implied may now be disabled if no other selectables
        // also implies it.
        for (implied in implies.get(selectable)) {
            checkActivatable(implied)
        }
    }

    /**
     * @return whether all requirements of the selectable are met in the set of currently enabled
     * selectables.
     */
    private fun isSatisfied(selectable: CrosstoolSelectable?): Boolean {
        return (requestedSelectables.contains(selectable) || isImpliedByEnabledActivatable(selectable))
                && allImplicationsEnabled(selectable)
                && allRequirementsMet(selectable)
    }

    /** @return whether a currently enabled selectable implies the given selectable.
     */
    private fun isImpliedByEnabledActivatable(selectable: CrosstoolSelectable?): Boolean {
        return !Collections.disjoint(impliedBy.get(selectable), enabled)
    }

    /** @return whether all implications of the given feature are enabled.
     */
    private fun allImplicationsEnabled(selectable: CrosstoolSelectable?): Boolean {
        for (implied in implies.get(selectable)) {
            if (!enabled.contains(implied)) {
                return false
            }
        }
        return true
    }

    /**
     * @return whether all requirements are enabled.
     * 
     * This implies that for any of the selectable sets all of the specified selectable are
     * enabled.
     */
    private fun allRequirementsMet(feature: CrosstoolSelectable?): Boolean {
        if (!requires.containsKey(feature)) {
            return true
        }
        for (requiresAllOf in requires.get(feature)) {
            var requirementMet = true
            for (required in requiresAllOf) {
                if (!enabled.contains(required)) {
                    requirementMet = false
                    break
                }
            }
            if (requirementMet) {
                return true
            }
        }
        return false
    }
}

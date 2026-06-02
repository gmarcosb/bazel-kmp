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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.Label

/**
 * Parts of an [EnvironmentGroup] that are needed for analysis. Since [EnvironmentGroup]
 * keeps a reference to a [Package] object, it is too heavyweight to store in analysis.
 * 
 * 
 * Constructor should only be called by [EnvironmentGroup], and this object must never be
 * accessed externally until after [EnvironmentGroup.processMemberEnvironments] is called. The
 * mutability of fulfillersMap means that we must take care to wait until it is set before doing
 * anything with this class.e
 */
class EnvironmentLabels private constructor(
    label: Label,
    environments: MutableCollection<Label?>,
    defaults: MutableCollection<Label?>,
    fulfillersMap: MutableMap<Label?, com.google.common.collect.ImmutableSortedSet<Label?>?>?
) {
    val label: Label
    val environments: com.google.common.collect.ImmutableSet<Label?>
    val defaults: com.google.common.collect.ImmutableSet<Label?>

    /**
     * Maps a member environment to the set of environments that directly fulfill it. Note that we
     * can't set this map until all Target instances for member environments have been initialized,
     * which occurs after group instantiation (this makes the class mutable).
     */
    private var fulfillersMap: MutableMap<Label?, com.google.common.collect.ImmutableSortedSet<Label?>?>?

    internal constructor(
        label: Label,
        environments: MutableCollection<Label?>,
        defaults: MutableCollection<Label?>
    ) : this(label, environments, defaults, null)

    /**
     * Only for use by serialization: the mutable fulfillersMap object is not properly initialized
     * otherwise during deserialization.
     */
    init {
        this.label = label
        this.environments = com.google.common.collect.ImmutableSortedSet.copyOf<Label?>(environments)
        this.defaults = com.google.common.collect.ImmutableSortedSet.copyOf<Label?>(defaults)
        this.fulfillersMap =
            if (fulfillersMap == null) null else com.google.common.collect.ImmutableSortedMap.copyOf<Label?, com.google.common.collect.ImmutableSortedSet<Label?>?>(
                fulfillersMap
            )
    }

    fun assertNotInitialized() {
        com.google.common.base.Preconditions.checkState(fulfillersMap == null, this)
    }

    fun checkInitialized() {
        com.google.common.base.Preconditions.checkNotNull<MutableMap<Label?, com.google.common.collect.ImmutableSortedSet<Label?>?>?>(
            fulfillersMap,
            this
        )
    }

    fun setFulfillersMap(fulfillersMap: MutableMap<Label?, com.google.common.collect.ImmutableSortedSet<Label?>?>) {
        com.google.common.base.Preconditions.checkState(this.fulfillersMap == null, this)
        this.fulfillersMap =
            com.google.common.collect.ImmutableSortedMap.copyOf<Label?, com.google.common.collect.ImmutableSortedSet<Label?>?>(
                fulfillersMap
            )
    }

    fun getEnvironments(): MutableSet<Label?> {
        checkInitialized()
        return environments
    }

    fun getDefaults(): MutableSet<Label?> {
        checkInitialized()
        return defaults
    }

    /**
     * Determines whether or not an environment is a default. Returns false if the environment doesn't
     * belong to this group.
     */
    fun isDefault(environment: Label?): Boolean {
        checkInitialized()
        return defaults.contains(environment)
    }

    /**
     * Returns the set of environments that transitively fulfill the specified environment. The
     * environment must be a valid member of this group.
     * 
     * 
     * >For example, if the input is `":foo"` and `":bar"` fulfills `
     * ":foo"` and `":baz"` fulfills `":bar"`, this returns `
     * [":foo", ":bar", ":baz"]`.
     * 
     * 
     * If no environments fulfill the input, returns an empty set.
     */
    fun getFulfillers(environment: Label?): com.google.common.collect.ImmutableSortedSet<Label?> {
        checkInitialized()
        val ans: com.google.common.collect.ImmutableSortedSet<Label?>? = fulfillersMap!!.get(environment)
        return if (ans == null) com.google.common.collect.ImmutableSortedSet.of<Label?>() else ans
    }

    fun getLabel(): Label {
        checkInitialized()
        return label
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("label", label)
            .add("sizes", environments.size().toString() + ", " + defaults.size() + ", " + fulfillersMap.size())
            .add("environments", environments)
            .add("defaults", defaults)
            .add("fulfillersMap", fulfillersMap)
            .toString()
    }

    override fun hashCode(): Int {
        checkInitialized()
        return java.util.Objects.hash(label, environments, defaults, fulfillersMap.keySet())
    }

    override fun equals(o: Any?): Boolean {
        checkInitialized()
        if (this === o) {
            return true
        }
        if (o == null || getClass() != o.getClass()) {
            return false
        }
        val that = o as EnvironmentLabels
        that.checkInitialized()
        return label.equals(that.label)
                && environments == that.environments
                && defaults == that.defaults
                && fulfillersMap == that.fulfillersMap
    }
}

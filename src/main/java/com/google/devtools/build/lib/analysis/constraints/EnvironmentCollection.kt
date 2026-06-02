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
package com.google.devtools.build.lib.analysis.constraints

import com.google.devtools.build.lib.packages.EnvironmentLabels
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant
import java.util.HashSet

/** Contains a set of [Environment] labels and their associated groups.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class EnvironmentCollection private constructor(map: com.google.common.collect.ImmutableListMultimap<EnvironmentLabels?, com.google.devtools.build.lib.cmdline.Label?>) {
    private val map: com.google.common.collect.ImmutableListMultimap<EnvironmentLabels?, com.google.devtools.build.lib.cmdline.Label?>

    init {
        this.map = map
    }

    /** Stores an environment's build label along with the group it belongs to.  */
    internal class EnvironmentWithGroup(
        environment: com.google.devtools.build.lib.cmdline.Label?,
        group: EnvironmentLabels?
    ) {
        val environment: com.google.devtools.build.lib.cmdline.Label?
        val group: EnvironmentLabels?

        init {
            this.group = group
            this.environment = environment
            java.util.Objects.requireNonNull<com.google.devtools.build.lib.cmdline.Label?>(environment, "environment")
            java.util.Objects.requireNonNull<EnvironmentLabels?>(group, "group")
        }

        companion object {
            fun create(
                environment: com.google.devtools.build.lib.cmdline.Label?,
                group: EnvironmentLabels?
            ): EnvironmentWithGroup {
                return EnvironmentWithGroup(environment, group)
            }
        }
    }

    /**
     * Returns the build labels of each environment in this collection, ordered by their insertion
     * order in [Builder].
     */
    fun getEnvironments(): com.google.common.collect.ImmutableCollection<com.google.devtools.build.lib.cmdline.Label?> {
        return map.values()
    }

    /**
     * Returns the environments in this collection that belong to the given group, ordered by their
     * insertion order in [Builder]. If no environments belong to the given group, returns an
     * empty collection.
     */
    fun getEnvironments(group: EnvironmentLabels?): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.cmdline.Label?> {
        return map.get(group)
    }

    /**
     * Returns the set of groups the environments in this collection belong to, ordered by their
     * insertion order in [Builder]
     */
    fun getGroups(): com.google.common.collect.ImmutableSet<EnvironmentLabels?> {
        return map.keySet()
    }

    /**
     * Returns the build labels of each environment in this collection paired with the group each
     * environment belongs to, ordered by their insertion order in [Builder].
     */
    fun getGroupedEnvironments(): com.google.common.collect.ImmutableSet<EnvironmentWithGroup?> {
        val builder: com.google.common.collect.ImmutableSet.Builder<EnvironmentWithGroup?> =
            com.google.common.collect.ImmutableSet.builderWithExpectedSize<EnvironmentWithGroup?>(map.asMap().size)
        map.forEach(java.util.function.BiConsumer { group: EnvironmentLabels?, env: com.google.devtools.build.lib.cmdline.Label? ->
            builder.add(
                EnvironmentWithGroup.Companion.create(env, group)
            )
        })
        return builder.build()
    }

    fun isEmpty(): Boolean {
        return map.isEmpty()
    }

    override fun hashCode(): Int {
        return 31 * map.hashCode() + map.keySet().asList().hashCode() // Consider order of keys.
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o !is EnvironmentCollection) {
            return false
        }
        // ImmutableListMultimap equality considers the order of each value list but not the order of
        // keys. Additionally check equality of the keys as a list to reflect ordering.
        return map == o.map && map.keySet().asList() == o.map.keySet().asList()
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("size", map.size())
            .add("hashCode", map.hashCode())
            .add("map", map)
            .toString()
    }

    /** Builder for [EnvironmentCollection].  */
    class Builder {
        // ImmutableListMultimap.Builder allows duplicate values, which we don't want.
        private val addedLabels: MutableSet<com.google.devtools.build.lib.cmdline.Label?> =
            HashSet<com.google.devtools.build.lib.cmdline.Label?>()
        private val mapBuilder: com.google.common.collect.ImmutableListMultimap.Builder<EnvironmentLabels?, com.google.devtools.build.lib.cmdline.Label?> =
            com.google.common.collect.ImmutableListMultimap.builder<EnvironmentLabels?, com.google.devtools.build.lib.cmdline.Label?>()

        /** Inserts the given environment / owning group pair.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun put(group: EnvironmentLabels, environment: com.google.devtools.build.lib.cmdline.Label): Builder {
            if (addedLabels.add(environment)) {
                mapBuilder.put(group, environment)
            }
            return this
        }

        /** Inserts the given set of environments, all belonging to the specified group.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun putAll(
            group: EnvironmentLabels,
            environments: Iterable<com.google.devtools.build.lib.cmdline.Label>
        ): Builder {
            for (env in environments) {
                if (addedLabels.add(env)) {
                    mapBuilder.put(group, env)
                }
            }
            return this
        }

        /** Inserts the contents of another [EnvironmentCollection] into this one.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun putAll(other: EnvironmentCollection): Builder {
            for (entry in other.map.entries()) {
                if (addedLabels.add(entry.value)) {
                    mapBuilder.put(entry)
                }
            }
            return this
        }

        fun build(): EnvironmentCollection {
            val map: com.google.common.collect.ImmutableListMultimap<EnvironmentLabels?, com.google.devtools.build.lib.cmdline.Label?> =
                mapBuilder.build()
            return if (map.isEmpty()) EMPTY else interner.intern(EnvironmentCollection(map))
        }
    }

    companion object {
        /** An empty [EnvironmentCollection].  */
        @SerializationConstant
        val EMPTY: EnvironmentCollection =
            EnvironmentCollection(com.google.common.collect.ImmutableListMultimap.of<EnvironmentLabels?, com.google.devtools.build.lib.cmdline.Label?>())

        private val interner: com.google.common.collect.Interner<EnvironmentCollection?> =
            com.google.devtools.build.lib.concurrent.BlazeInterners.newWeakInterner<EnvironmentCollection?>()
    }
}

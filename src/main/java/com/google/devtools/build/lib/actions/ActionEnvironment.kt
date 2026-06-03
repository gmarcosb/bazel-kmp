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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.concurrent.BlazeInterners

/**
 * Environment variables for build or test actions.
 * 
 * 
 * The action environment consists of two parts.
 * 
 * 
 *  1. All the environment variables with a fixed value, stored in a map.
 *  1. All the environment variables inherited from the client environment, stored in a set.
 * 
 * 
 * 
 * Inherited environment variables must be declared in the Action interface (see [ ][Action.getClientEnvironmentVariables]), so that the dependency on the client environment is known
 * to the execution framework for correct incremental builds.
 * 
 * 
 * By splitting the environment, we can handle environment variable changes more efficiently -
 * the dependency of the action on the environment variable are tracked in Skyframe (and in the
 * action cache), such that Bazel knows exactly which actions it needs to rerun, and does not have
 * to reanalyze the entire dependency graph.
 */
abstract class ActionEnvironment private constructor() {
    /**
     * Returns the 'fixed' part of the environment, i.e., those environment variables that are set to
     * fixed values and their values. This should only be used for testing and to compute the cache
     * keys of actions. Use [.resolve] instead to get the complete environment.
     */
    abstract fun getFixedEnv(): com.google.common.collect.ImmutableMap<String?, String?>?

    /**
     * Returns the 'inherited' part of the environment, i.e., those environment variables that are
     * inherited from the client environment and therefore have no fixed value here. This should only
     * be used for testing and to compute the cache keys of actions. Use [.resolve] instead to
     * get the complete environment.
     */
    abstract fun getInheritedEnv(): com.google.common.collect.ImmutableSet<String?>?

    /**
     * Returns an upper bound on the combined size of the fixed and inherited environments. A call to
     * [.resolve] may add fewer entries than this number if environment variables are contained
     * in both the fixed and the inherited environment.
     */
    abstract fun estimatedSize(): Int

    /**
     * Resolves the action environment and adds the resulting entries to the given `result` map,
     * by looking up any inherited env variables in the given `clientEnv`.
     * 
     * 
     * We pass in a map to mutate to avoid creating and merging intermediate maps.
     */
    fun resolve(result: MutableMap<String?, String?>, clientEnv: MutableMap<String?, String?>?) {
        com.google.common.base.Preconditions.checkNotNull<MutableMap<String?, String?>?>(clientEnv)
        result.putAll(getFixedEnv())
        for (`var` in getInheritedEnv()) {
            val value = clientEnv!!.get(`var`)
            if (value != null) {
                result.put(`var`, value)
            }
        }
    }

    fun addTo(f: Fingerprint) {
        f.addStringMap(getFixedEnv())
        f.addStrings(getInheritedEnv())
    }

    /**
     * Returns a copy of the environment with the given fixed variables added to it, *overwriting
     * any existing occurrences of those variables*.
     */
    fun withAdditionalFixedVariables(fixedVars: MutableMap<String?, String?>): ActionEnvironment {
        if (fixedVars.isEmpty()) {
            return this
        }
        if (this === EMPTY) {
            return actionEnvironmentInterner.intern(
                SimpleActionEnvironment(
                    com.google.common.collect.ImmutableMap.copyOf<String?, String?>(fixedVars),
                    com.google.common.collect.ImmutableSet.of<String?>()
                )
            )
        }
        return actionEnvironmentInterner.intern(
            CompoundActionEnvironment(this, com.google.common.collect.ImmutableMap.copyOf<String?, String?>(fixedVars))
        )
    }

    private class EmptyActionEnvironment : ActionEnvironment() {
        override fun getFixedEnv(): com.google.common.collect.ImmutableMap<String?, String?> {
            return com.google.common.collect.ImmutableMap.of<String?, String?>()
        }

        override fun getInheritedEnv(): com.google.common.collect.ImmutableSet<String?> {
            return com.google.common.collect.ImmutableSet.of<String?>()
        }

        override fun estimatedSize(): Int {
            return 0
        }
    }

    private class SimpleActionEnvironment(
        fixedEnv: com.google.common.collect.ImmutableMap<String?, String?>,
        inheritedEnv: com.google.common.collect.ImmutableSet<String?>
    ) : ActionEnvironment() {
        private val fixedEnv: com.google.common.collect.ImmutableMap<String?, String?>
        private val inheritedEnv: com.google.common.collect.ImmutableSet<String?>

        init {
            this.fixedEnv = fixedEnv
            this.inheritedEnv = inheritedEnv
        }

        override fun getFixedEnv(): com.google.common.collect.ImmutableMap<String?, String?> {
            return fixedEnv
        }

        override fun getInheritedEnv(): com.google.common.collect.ImmutableSet<String?> {
            return inheritedEnv
        }

        override fun estimatedSize(): Int {
            return fixedEnv.size + inheritedEnv.size
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is SimpleActionEnvironment) {
                return false
            }
            return fixedEnv == o.fixedEnv && inheritedEnv == o.inheritedEnv
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(fixedEnv, inheritedEnv)
        }
    }

    private class CompoundActionEnvironment(
        private val base: ActionEnvironment,
        fixedVars: com.google.common.collect.ImmutableMap<String?, String?>
    ) : ActionEnvironment() {
        private val fixedVars: com.google.common.collect.ImmutableMap<String?, String?>

        init {
            this.fixedVars = fixedVars
        }

        override fun getFixedEnv(): com.google.common.collect.ImmutableMap<String?, String?> {
            return com.google.common.collect.ImmutableMap.builder<String?, String?>()
                .putAll(base.getFixedEnv())
                .putAll(fixedVars)
                .buildKeepingLast()
        }

        override fun getInheritedEnv(): com.google.common.collect.ImmutableSet<String?>? {
            return base.getInheritedEnv()
        }

        override fun estimatedSize(): Int {
            return base.estimatedSize() + fixedVars.size
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is CompoundActionEnvironment) {
                return false
            }
            return base == o.base && fixedVars == o.fixedVars
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(base, fixedVars)
        }
    }

    companion object {
        @kotlin.jvm.JvmField
        val EMPTY: ActionEnvironment = EmptyActionEnvironment()

        private val actionEnvironmentInterner: com.google.common.collect.Interner<ActionEnvironment> =
            BlazeInterners.newWeakInterner()

        /**
         * Creates a new [ActionEnvironment].
         * 
         * 
         * If an environment variable is contained both as a key in `fixedEnv` and in `inheritedEnv`, the result of [.resolve] will contain the value inherited from the client
         * environment.
         */
        /** Convenience method for creating an [ActionEnvironment] with no inherited variables.  */
        @kotlin.jvm.JvmOverloads
        fun create(
            fixedEnv: com.google.common.collect.ImmutableMap<String?, String?>,
            inheritedEnv: com.google.common.collect.ImmutableSet<String?> = com.google.common.collect.ImmutableSet.of<String?>()
        ): ActionEnvironment {
            if (fixedEnv.isEmpty() && inheritedEnv.isEmpty()) {
                return EMPTY
            }
            return actionEnvironmentInterner.intern(SimpleActionEnvironment(fixedEnv, inheritedEnv))
        }

        /**
         * Splits the given map into a map of variables with a fixed value, and a set of variables that
         * should be inherited, the latter of which are identified by having a `null` value in the
         * given map. Returns these two parts as a new [ActionEnvironment] instance.
         */
        fun split(env: MutableMap<String?, String?>): ActionEnvironment {
            val fixedEnv: MutableMap<String?, String?> = TreeMap<String?, String?>()
            val inheritedEnv: MutableSet<String?> = TreeSet<String?>()
            for (entry in env.entries) {
                if (entry.value != null) {
                    fixedEnv.put(entry.key, entry.value)
                } else {
                    inheritedEnv.add(entry.key)
                }
            }
            return create(
                com.google.common.collect.ImmutableMap.copyOf<String?, String?>(fixedEnv),
                com.google.common.collect.ImmutableSet.copyOf<String?>(inheritedEnv)
            )
        }
    }
}

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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.Label

/**
 * Model for the "environment_group' rule: the piece of Bazel's rule constraint system that binds
 * thematically related environments together and determines which environments a rule supports by
 * default. See [com.google.devtools.build.lib.analysis.constraints.ConstraintSemantics] for
 * precise semantic details of how this information is used.
 * 
 * 
 * Note that "environment_group" is implemented as a loading-time function, not a rule. This is
 * to support proper discovery of defaults: Say rule A has no explicit constraints and depends on
 * rule B, which is explicitly constrained to environment ":bar". Since A declares nothing
 * explicitly, it's implicitly constrained to DEFAULTS (whatever that is). Therefore, the dependency
 * is only allowed if DEFAULTS doesn't include environments beyond ":bar". To figure that out, we
 * need to be able to look up the environment group for ":bar", which is what this class provides.
 * 
 * 
 * If we implemented this as a rule, we'd have to provide that lookup via rule dependencies, e.g.
 * something like: `
 * environment(
 * name = 'bar',
 * group = [':sample_environments'],
 * is_default = 1
 * )
` * 
 * 
 * 
 * But this won't work. This would let us find the environment group for ":bar", but the only way
 * to determine what other environments belong to the group is to have the group somehow reference
 * them. That would produce circular dependencies in the build graph, which is no good.
 */
@Immutable // This is a lie, but this object is only mutable until its containing package is loaded.
class EnvironmentGroup internal constructor(
    label: Label?,
    packageoid: Packageoid,
    environments: MutableList<Label?>?,
    defaults: MutableList<Label?>?,
    location: net.starlark.java.syntax.Location?
) : com.google.devtools.build.lib.packages.Target {
    private val environmentLabels: EnvironmentLabels
    private val location: net.starlark.java.syntax.Location?
    private val containingPackageoid: Packageoid

    /**
     * Predicate that matches labels from a different package than the initialized package.
     */
    private class DifferentPackage(containingPackageoid: Packageoid) : com.google.common.base.Predicate<Label?> {
        private val containingPackageoid: Packageoid

        init {
            this.containingPackageoid = containingPackageoid
        }

        override fun apply(environment: Label): Boolean {
            return !environment.getPackageName().equals(containingPackageoid.getMetadata().getName())
        }
    }

    /**
     * Instantiates a new group without verifying the soundness of its contents. See the validation
     * methods below for appropriate checks.
     * 
     * @param label the build label identifying this group
     * @param pkg the package this group belongs to
     * @param environments the set of environments that belong to this group
     * @param defaults the environments a rule implicitly supports unless otherwise specified
     * @param location location in the BUILD file of this group
     */
    init {
        this.environmentLabels = EnvironmentLabels(label, environments, defaults)
        this.location = location
        // TODO(https://github.com/bazelbuild/bazel/issues/23852): verify that packageoid is top-level.
        this.containingPackageoid = packageoid
    }

    fun getEnvironmentLabels(): EnvironmentLabels {
        environmentLabels.checkInitialized()
        return environmentLabels
    }

    /**
     * Checks that all environments declared by this group are in the same package as the group (so
     * we can perform an environment --> environment_group lookup and know the package is available)
     * and checks that all defaults are legitimate members of the group.
     * 
     * 
     * Does **not** check that the referenced environments exist (see
     * [.processMemberEnvironments]).
     * 
     * @return a list of validation errors that occurred
     */
    fun validateMembership(): MutableList<Event?> {
        val events: MutableList<Event?> = java.util.ArrayList<Event?>()

        // All environments should belong to the same package as this group.
        for (environment in com.google.common.collect.Iterables.filter<Label?>(
            environmentLabels.environments, DifferentPackage(containingPackageoid)
        )) {
            events.add(
                com.google.devtools.build.lib.packages.Package.Companion.error(
                    location,
                    environment.toString() + " is not in the same package as group " + environmentLabels.label,
                    Code.ENVIRONMENT_IN_DIFFERENT_PACKAGE
                )
            )
        }

        // The defaults must be a subset of the member environments.
        for (unknownDefault in com.google.common.collect.Sets.difference<Label?>(
            environmentLabels.defaults,
            environmentLabels.environments
        )) {
            events.add(
                com.google.devtools.build.lib.packages.Package.Companion.error(
                    location,
                    java.lang.String.format(
                        "default %s is not a declared environment for group %s",
                        unknownDefault, getLabel()
                    ),
                    Code.DEFAULT_ENVIRONMENT_UNDECLARED
                )
            )
        }

        return events
    }

    /**
     * Checks that the group's declared environments are legitimate same-package environment rules and
     * prepares the "fulfills" relationships between these environments to support [ ][EnvironmentLabels.getFulfillers].
     * 
     * @param pkgTargets mapping from label name to target instance for this group's package
     * @return a list of validation errors that occurred
     */
    fun processMemberEnvironments(pkgTargets: MutableMap<String?, com.google.devtools.build.lib.packages.Target?>): MutableList<Event?> {
        val events: MutableList<Event?> = java.util.ArrayList<Event?>()
        // Maps an environment to the environments that directly fulfill it.
        val directFulfillers: com.google.common.collect.Multimap<Label?, Label?> =
            com.google.common.collect.HashMultimap.create<Label?, Label?>()

        for (envName in environmentLabels.environments) {
            val env: com.google.devtools.build.lib.packages.Target? = pkgTargets.get(envName.name)
            if (isValidEnvironment(env, envName, "", events)) {
                val attr: com.google.devtools.build.lib.packages.AttributeMap =
                    NonconfigurableAttributeMapper.Companion.of(env as com.google.devtools.build.lib.packages.Rule?)
                for (fulfilledEnv in attr.get<MutableList<Label>?>("fulfills", BuildType.LABEL_LIST)) {
                    if (isValidEnvironment(
                            pkgTargets.get(fulfilledEnv.name), fulfilledEnv,
                            "in \"fulfills\" attribute of " + envName + ": ", events
                        )
                    ) {
                        directFulfillers.put(fulfilledEnv, envName)
                    }
                }
            }
        }

        val fulfillersMap: MutableMap<Label?, com.google.common.collect.ImmutableSortedSet<Label?>?> =
            HashMap<Label?, com.google.common.collect.ImmutableSortedSet<Label?>?>()
        // Now that we know which environments directly fulfill each other, compute which environments
        // transitively fulfill each other. We could alternatively compute this on-demand, but since
        // we don't expect these chains to be very large we opt toward computing them once at package
        // load time.
        environmentLabels.assertNotInitialized()
        for (envName in environmentLabels.environments) {
            setTransitiveFulfillers(envName, directFulfillers, fulfillersMap)
        }

        environmentLabels.setFulfillersMap(fulfillersMap)
        return events
    }

    private fun isValidEnvironment(
        env: com.google.devtools.build.lib.packages.Target?,
        envName: Label?,
        prefix: String?,
        events: MutableList<Event?>
    ): Boolean {
        if (env == null) {
            events.add(
                com.google.devtools.build.lib.packages.Package.Companion.error(
                    location,
                    prefix + "environment " + envName + " does not exist",
                    Code.ENVIRONMENT_DOES_NOT_EXIST
                )
            )
            return false
        } else if (env.getTargetKind() != "environment rule") {
            events.add(
                com.google.devtools.build.lib.packages.Package.Companion.error(
                    location,
                    prefix + env.getLabel() + " is not a valid environment",
                    Code.ENVIRONMENT_INVALID
                )
            )
            return false
        } else if (!environmentLabels.environments.contains(env.getLabel())) {
            events.add(
                com.google.devtools.build.lib.packages.Package.Companion.error(
                    location,
                    prefix + env.getLabel() + " is not a member of this group",
                    Code.ENVIRONMENT_NOT_IN_GROUP
                )
            )
            return false
        }
        return true
    }

    /**
     * Returns the environments that belong to this group.
     */
    fun getEnvironments(): MutableSet<Label>? {
        return environmentLabels.environments
    }

    /**
     * Returns the environments a rule supports by default, i.e. if it has no explicit references to
     * environments in this group.
     */
    fun getDefaults(): MutableSet<Label?>? {
        return environmentLabels.defaults
    }

    override fun getLabel(): Label? {
        return environmentLabels.label
    }

    override fun getPackageoid(): Packageoid {
        return containingPackageoid
    }

    override fun getPackageMetadata(): com.google.devtools.build.lib.packages.Package.Metadata? {
        return containingPackageoid.getMetadata()
    }

    override fun getPackageDeclarations(): Declarations? {
        return containingPackageoid.getDeclarations()
    }

    override fun getTargetKind(): String {
        return targetKind()
    }

    override fun getAssociatedRule(): com.google.devtools.build.lib.packages.Rule? {
        return null
    }

    override fun getLicense(): License {
        return License.Companion.NO_LICENSE
    }

    override fun getLocation(): net.starlark.java.syntax.Location? {
        return location
    }

    override fun toString(): String {
        return targetKind() + " " + getLabel()
    }

    override fun getRawVisibility(): RuleVisibility? {
        return null
    }

    override fun getVisibility(): RuleVisibility {
        // No rule should be referencing an environment_group.
        // (We override getRawVisibility() separately so as to not display this value during
        // introspection.)
        return RuleVisibility.Companion.PRIVATE
    }

    override fun isConfigurable(): Boolean {
        return false
    }

    override fun reduceForSerialization(): TargetData {
        return AutoValue_EnvironmentGroup_EnvironmentGroupData(getLocation(), getLabel())
    }

    @AutoValue
    internal abstract class EnvironmentGroupData : TargetData {
        override fun getTargetKind(): String {
            return targetKind()
        }
    }

    companion object {
        /**
         * Given an environment and set of environments that directly fulfill it, computes a nested set of
         * environments that *transitively* fulfill it, places it into transitiveFulfillers, and
         * returns that set.
         */
        private fun setTransitiveFulfillers(
            env: Label?,
            directFulfillers: com.google.common.collect.Multimap<Label?, Label?>,
            transitiveFulfillers: MutableMap<Label?, com.google.common.collect.ImmutableSortedSet<Label?>?>
        ): com.google.common.collect.ImmutableSortedSet<Label?>? {
            if (transitiveFulfillers.containsKey(env)) {
                return transitiveFulfillers.get(env)
            } else if (!directFulfillers.containsKey(env)) {
                // Nobody fulfills this environment.
                transitiveFulfillers.put(env, com.google.common.collect.ImmutableSortedSet.of<Label?>())
                return com.google.common.collect.ImmutableSortedSet.of<Label?>()
            } else {
                val set: HashSet<Label?> = HashSet<Label?>()
                for (fulfillingEnv in directFulfillers.get(env)) {
                    set.add(fulfillingEnv)
                    set.addAll(setTransitiveFulfillers(fulfillingEnv, directFulfillers, transitiveFulfillers))
                }
                val builtSet: com.google.common.collect.ImmutableSortedSet<Label?> =
                    com.google.common.collect.ImmutableSortedSet.copyOf<Label?>(set)
                transitiveFulfillers.put(env, builtSet)
                return builtSet
            }
        }

        fun targetKind(): String {
            return "environment group"
        }
    }
}

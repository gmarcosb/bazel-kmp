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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.Label

/**
 * This class can be extended to define [SkyFunction]s that traverse a target and its
 * transitive dependencies and return values based on that traversal.
 * 
 * 
 * The `ProcessedTargetsT` type parameter represents the result of processing a target and
 * its transitive dependencies.
 * 
 * 
 * `TransitiveBaseTraversalFunction` asks for one to be constructed via [ ][.processTarget], and then asks for it to be updated based on the current target's attributes'
 * dependencies via [.processDeps], and then asks for it to be updated based on the current
 * target' aspects' dependencies via [.processDeps]. Finally, it calls [ ][.computeSkyValue] with the {#code ProcessedTargets} to get the [SkyValue] to return.
 */
abstract class TransitiveBaseTraversalFunction<ProcessedTargetsT> : SkyFunction {
    /**
     * Returns a [SkyKey] corresponding to the traversal of a target specified by `label`
     * and its transitive dependencies.
     * 
     * 
     * Extenders of this class should implement this function to return a key with their
     * specialized [SkyFunction]'s name.
     * 
     * 
     * [TransitiveBaseTraversalFunction] calls this for each dependency of a target, and
     * then gets their values from the environment.
     * 
     * 
     * The key's [SkyFunction] may throw at most [NoSuchPackageException] and
     * [NoSuchTargetException]. Other exception types are not handled by [ ].
     */
    abstract fun getKey(label: Label?): SkyKey?

    abstract fun processTarget(targetAndErrorIfAny: TargetAndErrorIfAny?): ProcessedTargetsT?

    abstract fun processDeps(
        processedTargets: ProcessedTargetsT?,
        eventHandler: EventHandler?,
        targetAndErrorIfAny: TargetAndErrorIfAny?,
        depEntries: SkyframeLookupResult?,
        depKeys: Iterable<out SkyKey?>?
    )

    /**
     * Returns a [SkyValue] based on the target and any errors it has, and the values
     * accumulated across it and a traversal of its transitive dependencies.
     */
    abstract fun computeSkyValue(
        targetAndErrorIfAny: TargetAndErrorIfAny?, processedTargets: ProcessedTargetsT?
    ): SkyValue?

    abstract fun argumentFromKey(key: SkyKey?): Label

    @Throws(TransitiveBaseTraversalFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(key: SkyKey?, env: SkyFunction.Environment): SkyValue? {
        val label: Label = argumentFromKey(key)
        val targetAndErrorIfAny: TargetAndErrorIfAny?
        try {
            targetAndErrorIfAny = loadTarget(env, label)
        } catch (e: NoSuchTargetException) {
            throw TransitiveBaseTraversalFunctionException(e)
        } catch (e: NoSuchPackageException) {
            throw TransitiveBaseTraversalFunctionException(e)
        }
        if (targetAndErrorIfAny == null) {
            return null
        }

        // Process deps from attributes. It is essential that the last getValue(s) call we made to
        // skyframe for building this node was for the corresponding PackageValue.
        val labelDepKeys: MutableCollection<SkyKey?>? = getLabelDepKeys(env, targetAndErrorIfAny)

        val depMap: SkyframeLookupResult = env.getValuesAndExceptions(labelDepKeys)
        if (env.valuesMissing()) {
            return null
        }
        // Process deps from aspects. It is essential that the second-to-last getValue(s) call we
        // made to skyframe for building this node was for the corresponding PackageValue.
        val labelAspectKeys: Iterable<SkyKey?> =
            getStrictLabelAspectDepKeys(env, depMap, targetAndErrorIfAny)
        if (env.valuesMissing()) {
            return null
        }
        val labelAspectEntries: SkyframeLookupResult = env.getValuesAndExceptions(labelAspectKeys)
        if (env.valuesMissing()) {
            return null
        }

        val processedTargets = processTarget(targetAndErrorIfAny)
        processDeps(processedTargets, env.getListener(), targetAndErrorIfAny, depMap, labelDepKeys)
        processDeps(
            processedTargets,
            env.getListener(),
            targetAndErrorIfAny,
            labelAspectEntries,
            labelAspectKeys
        )

        return computeSkyValue(targetAndErrorIfAny, processedTargets)
    }

    @Throws(java.lang.InterruptedException::class)
    open fun getLabelDepKeys(
        env: SkyFunction.Environment?, targetAndErrorIfAny: TargetAndErrorIfAny
    ): MutableCollection<SkyKey?>? {
        val depsBuilder: com.google.common.collect.ImmutableSet.Builder<SkyKey?> =
            com.google.common.collect.ImmutableSet.builder<SkyKey?>()
        LabelVisitationUtils.visitTarget(
            targetAndErrorIfAny.getTarget(),
            DependencyFilter.NO_NODEP_ATTRIBUTES_EXCEPT_VISIBILITY,
            { fromTarget, attribute, toLabel -> depsBuilder.add(getKey(toLabel)) })
        return depsBuilder.build()
    }

    @Throws(java.lang.InterruptedException::class)
    open fun getStrictLabelAspectDepKeys(
        env: SkyFunction.Environment?,
        depMap: SkyframeLookupResult,
        targetAndErrorIfAny: TargetAndErrorIfAny
    ): Iterable<SkyKey?> {
        return getStrictLabelAspectKeys(targetAndErrorIfAny.getTarget(), depMap, env)
    }

    override fun extractTag(skyKey: SkyKey?): String {
        return Label.print(argumentFromKey(skyKey))
    }

    /**
     * Return an Iterable of SkyKeys corresponding to the Aspect-related dependencies of target.
     * 
     * 
     * This method may return a precise set of aspect keys, but may need to request additional
     * dependencies from the env to do so.
     */
    @Throws(java.lang.InterruptedException::class)
    private fun getStrictLabelAspectKeys(
        target: Target?, depMap: SkyframeLookupResult, env: SkyFunction.Environment?
    ): Iterable<SkyKey?> {
        if (target !is Rule) {
            // Aspects can be declared only for Rules.
            return com.google.common.collect.ImmutableList.of<SkyKey?>()
        }

        if (!target.hasAspects()) {
            return com.google.common.collect.ImmutableList.of<SkyKey?>()
        }

        val depKeys: MutableList<SkyKey?> = com.google.common.collect.Lists.newArrayList<SkyKey?>()
        val transitions: com.google.common.collect.Multimap<Attribute, Label?> =
            target.getTransitions(DependencyFilter.NO_NODEP_ATTRIBUTES)
        for (attribute in transitions.keySet()) {
            for (aspect in attribute.getAspects(target)) {
                if (hasDepThatSatisfies(aspect, transitions.get(attribute), depMap, env)) {
                    AspectDefinition.forEachLabelDepFromAllAttributesOfAspect(
                        aspect,
                        DependencyFilter.ALL_DEPS,
                        { aspectAttribute, aspectLabel -> depKeys.add(getKey(aspectLabel)) })
                }
            }
        }
        return depKeys
    }

    @Throws(java.lang.InterruptedException::class)
    protected abstract fun getAdvertisedProviderSet(
        toLabel: Label?, toVal: SkyValue?, env: SkyFunction.Environment?
    ): AdvertisedProviderSet?

    @Throws(java.lang.InterruptedException::class)
    private fun hasDepThatSatisfies(
        aspect: Aspect?, depLabels: Iterable<Label?>, fullDepMap: SkyframeLookupResult, env: SkyFunction.Environment?
    ): Boolean {
        for (depLabel in depLabels) {
            val toVal: SkyValue?
            try {
                toVal =
                    fullDepMap.getOrThrow<E1?, E2?>(
                        getKey(depLabel), NoSuchPackageException::class.java, NoSuchTargetException::class.java
                    )
            } catch (e: NoSuchPackageException) {
                continue
            } catch (e: NoSuchTargetException) {
                continue
            }
            val advertisedProviderSet: AdvertisedProviderSet? = getAdvertisedProviderSet(depLabel, toVal, env)
            if (advertisedProviderSet != null
                && AspectDefinition.satisfies(aspect, advertisedProviderSet)
            ) {
                return true
            }
        }
        return false
    }

    @Throws(NoSuchTargetException::class, NoSuchPackageException::class, java.lang.InterruptedException::class)
    open fun loadTarget(env: SkyFunction.Environment?, label: Label): TargetAndErrorIfAny? {
        val o: Any? = TargetLoadingUtil.loadTarget(env, label)
        return if (o is TargetAndErrorIfAny) o as TargetAndErrorIfAny else null
    }

    /**
     * Used to declare all the exception types that can be wrapped in the exception thrown by [ ][TransitiveTraversalFunction.compute].
     */
    class TransitiveBaseTraversalFunctionException : SkyFunctionException {
        /**
         * Used to propagate an error from a direct target dependency to the target that depended on
         * it.
         */
        constructor(e: NoSuchPackageException?) : super(e, Transience.PERSISTENT)

        /**
         * In nokeep_going mode, used to propagate an error from a direct target dependency to the
         * target that depended on it.
         * 
         * 
         * In keep_going mode, used the same way, but only for targets that could not be loaded at
         * all (we proceed with transitive loading on targets that contain errors).
         */
        constructor(e: NoSuchTargetException?) : super(e, Transience.PERSISTENT)
    }
}

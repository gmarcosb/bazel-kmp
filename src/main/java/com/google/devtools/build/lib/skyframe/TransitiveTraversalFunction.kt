// Copyright 2014 The Bazel Authors. All rights reserved.
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
 * This class is like [TransitiveTargetFunction], but the values it returns do not contain
 * [com.google.devtools.build.lib.collect.nestedset.NestedSet]s. It performs the side-effects
 * of [TransitiveTargetFunction] (i.e., ensuring that transitive targets and their packages
 * have been loaded). It evaluates to a [TransitiveTraversalValue] that contains the first
 * error message it encountered, and a set of names of providers if the target is a rule.
 */
open class TransitiveTraversalFunction

    : TransitiveBaseTraversalFunction<FirstErrorMessageAccumulator?>() {
    public override fun argumentFromKey(key: SkyKey): Label? {
        return key.argument() as Label?
    }

    public override fun getKey(label: Label?): SkyKey? {
        return TransitiveTraversalValue.Companion.key(label)
    }

    public override fun processTarget(targetAndErrorIfAny: TargetAndErrorIfAny): FirstErrorMessageAccumulator {
        val errorIfAny: NoSuchTargetException? = targetAndErrorIfAny.getErrorLoadingTarget()
        val errorMessageIfAny: String? = if (errorIfAny == null) null else errorIfAny.getMessage()
        return FirstErrorMessageAccumulator(errorMessageIfAny)
    }

    public override fun processDeps(
        accumulator: FirstErrorMessageAccumulator,
        eventHandler: EventHandler?,
        targetAndErrorIfAny: TargetAndErrorIfAny?,
        depEntries: SkyframeLookupResult,
        depKeys: Iterable<out SkyKey?>
    ) {
        for (skyKey in depKeys) {
            val transitiveTraversalValue: TransitiveTraversalValue?
            try {
                transitiveTraversalValue =
                    depEntries.getOrThrow<E1?, E2?>(
                        skyKey, NoSuchPackageException::class.java, NoSuchTargetException::class.java
                    ) as TransitiveTraversalValue?
            } catch (e: NoSuchPackageException) {
                accumulator.maybeSet(e.getMessage())
                continue
            } catch (e: NoSuchTargetException) {
                accumulator.maybeSet(e.getMessage())
                continue
            }
            if (transitiveTraversalValue == null) {
                BugReport.sendBugReport(
                    java.lang.IllegalStateException(
                        "TransitiveTargetValue " + skyKey + " was missing, this should never happen"
                    )
                )
                continue
            }
            val errorMessage: String? = transitiveTraversalValue.getErrorMessage()
            if (errorMessage != null) {
                accumulator.maybeSet(errorMessage)
            }
        }
    }

    protected override fun getAdvertisedProviderSet(
        toLabel: Label?, toVal: SkyValue, env: Environment?
    ): AdvertisedProviderSet? {
        return (toVal as TransitiveTraversalValue).getProviders()
    }

    public override fun computeSkyValue(
        targetAndErrorIfAny: TargetAndErrorIfAny, accumulator: FirstErrorMessageAccumulator
    ): SkyValue? {
        val targetLoadedSuccessfully = targetAndErrorIfAny.getErrorLoadingTarget() == null
        val errorMessage = accumulator.firstErrorMessage
        return if (targetLoadedSuccessfully)
            TransitiveTraversalValue.Companion.forTarget(targetAndErrorIfAny.target, errorMessage)
        else
            TransitiveTraversalValue.Companion.unsuccessfulTransitiveTraversal(
                errorMessage, targetAndErrorIfAny.target
            )
    }

    @Throws(java.lang.InterruptedException::class)
    public override fun getLabelDepKeys(
        env: com.google.devtools.build.skyframe.SkyFunction.Environment, targetAndErrorIfAny: TargetAndErrorIfAny?
    ): MutableCollection<SkyKey?>? {
        // As a performance optimization we may already know the deps we are  about to request from
        // last time #compute was called. By requesting these from the environment, we can avoid
        // repeating the label visitation step. For TransitiveTraversalFunction#compute, the label deps
        // dependency group is requested immediately after the package.
        //
        // IMPORTANT: No other package values should be requested inside
        // TransitiveTraversalFunction#compute from this point forward.
        val oldDepKeys: MutableCollection<SkyKey?>? = getDepsAfterLastPackageDep(env,  /* offset= */1)
        return if (oldDepKeys == null) super.getLabelDepKeys(env, targetAndErrorIfAny) else oldDepKeys
    }

    @Throws(java.lang.InterruptedException::class)
    public override fun getStrictLabelAspectDepKeys(
        env: com.google.devtools.build.skyframe.SkyFunction.Environment,
        depMap: SkyframeLookupResult?,
        targetAndErrorIfAny: TargetAndErrorIfAny?
    ): Iterable<SkyKey?>? {
        // As a performance optimization we may already know the deps we are  about to request from
        // last time #compute was called. By requesting these from the environment, we can avoid
        // repeating the label visitation step. For TransitiveTraversalFunction#compute, the label
        // aspect deps dependency group is requested two groups after the package.
        val oldAspectDepKeys: MutableCollection<SkyKey?>? = getDepsAfterLastPackageDep(env,  /* offset= */2)
        return if (oldAspectDepKeys == null)
            super.getStrictLabelAspectDepKeys(env, depMap, targetAndErrorIfAny)
        else
            oldAspectDepKeys
    }

    /**
     * Keeps track of the first error message encountered while traversing itself and its
     * dependencies.
     */
    internal class FirstErrorMessageAccumulator(var firstErrorMessage: String?) {
        /** Remembers `errorMessage` if it is the first error message.  */
        fun maybeSet(errorMessage: String?) {
            com.google.common.base.Preconditions.checkNotNull<String?>(errorMessage)
            if (firstErrorMessage == null) {
                firstErrorMessage = errorMessage
            }
        }
    }

    companion object {
        private fun getDepsAfterLastPackageDep(
            env: com.google.devtools.build.skyframe.SkyFunction.Environment, offset: Int
        ): MutableCollection<SkyKey?>? {
            val temporaryDirectDeps: GroupedDeps? = env.getTemporaryDirectDeps()
            if (temporaryDirectDeps == null) {
                return null
            }
            val lastPackageDepIndex = getLastPackageValueIndex(temporaryDirectDeps)
            if (lastPackageDepIndex == -1
                || temporaryDirectDeps.numGroups() <= lastPackageDepIndex + offset
            ) {
                return null
            }
            return temporaryDirectDeps.getDepGroup(lastPackageDepIndex + offset)
        }

        private fun getLastPackageValueIndex(directDeps: GroupedDeps): Int {
            val directDepsNumGroups: Int = directDeps.numGroups()
            for (i in directDepsNumGroups - 1 downTo 0) {
                val depGroup: MutableList<SkyKey?> = directDeps.getDepGroup(i)
                if (depGroup.size == 1 && depGroup.get(0).functionName() == SkyFunctions.PACKAGE) {
                    return i
                }
            }
            return -1
        }
    }
}

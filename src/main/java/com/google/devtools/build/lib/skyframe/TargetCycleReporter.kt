// Copyright 2020 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ActionLookupKey

/**
 * Reports cycles between targets. These may be in the form of [ ]s or [TransitiveTargetValue]s.
 */
internal class TargetCycleReporter(packageProvider: PackageProvider?) : AbstractLabelCycleReporter(packageProvider) {
    protected override fun shouldSkipOnPathToCycle(key: SkyKey): Boolean {
        return SkyFunctions.PREPARE_ANALYSIS_PHASE.equals(key.functionName()) // BuildDriverKeys don't provide any relevant info for the end user.
                || SkyFunctions.BUILD_DRIVER.equals(key.functionName())
    }

    protected override fun canReportCycle(topLevelKey: SkyKey?, cycleInfo: CycleInfo): Boolean {
        return CONFIGURED_TARGET_OR_TRANSITIVE_RDEP.apply(topLevelKey)
                && cycleInfo.getPathToCycle().stream().allMatch(CONFIGURED_TARGET_OR_TRANSITIVE_RDEP)
                && cycleInfo.getCycle().stream().allMatch(CONFIGURED_TARGET_OR_TRANSITIVE_RDEP)
    }

    public override fun prettyPrint(key: Any?): String {
        if (key is ConfiguredTargetKey) {
            return key.prettyPrint()
        } else if (key is AspectKey) {
            return key.prettyPrint()
        } else {
            return getLabel(key as SkyKey?).toString()
        }
    }

    public override fun getLabel(key: SkyKey): Label? {
        if (key is ActionLookupKey) {
            return com.google.common.base.Preconditions.checkNotNull(
                (key.argument() as ActionLookupKey).getLabel(),
                key
            )
        } else if (key is TransitiveTargetKey) {
            return key.getLabel()
        } else {
            throw java.lang.UnsupportedOperationException(key.toString())
        }
    }

    public override fun getAdditionalMessageAboutCycle(
        eventHandler: ExtendedEventHandler?, topLevelKey: SkyKey, cycleInfo: CycleInfo
    ): String {
        val keys: MutableList<SkyKey> = com.google.common.collect.Lists.newArrayList<SkyKey?>()
        if (!cycleInfo.getPathToCycle().isEmpty()) {
            if (!shouldSkipOnPathToCycle(topLevelKey)) {
                keys.add(topLevelKey)
            }
            cycleInfo.getPathToCycle().stream()
                .filter(java.util.function.Predicate { key: SkyKey? -> !shouldSkipOnPathToCycle(key) })
                .forEach(java.util.function.Consumer { e: SkyKey? -> keys.add(e) })
        }
        keys.addAll(cycleInfo.getCycle())
        // Make sure we check the edge from the last element of the cycle to the first element of the
        // cycle.
        keys.add(cycleInfo.getCycle().get(0))

        var currentTarget: Target = getTargetForLabel(eventHandler, getLabel(keys.get(0)))
        for (nextKey in keys) {
            val nextLabel: Label? = getLabel(nextKey)
            val nextTarget: Target = getTargetForLabel(eventHandler, nextLabel)
            // TODO(aranguyen): remove this code as a result of b/128716030
            // This is inefficient but it's no big deal since we only do this when there's a cycle.
            if (!nextTarget.getTargetKind().equals(PackageGroup.targetKind())
                && com.google.common.collect.Iterables.contains(
                    currentTarget.getVisibilityDependencyLabels(),
                    nextLabel
                )
            ) {
                return ("\nThe cycle is caused by a visibility edge from "
                        + currentTarget.getLabel()
                        + " to the non-package_group target "
                        + nextTarget.getLabel()
                        + ". Note that "
                        + "visibility labels are supposed to be package_group targets, which prevents cycles "
                        + "of this form.")
            }
            currentTarget = nextTarget
        }
        return ""
    }

    companion object {
        private val CONFIGURED_TARGET_OR_TRANSITIVE_RDEP: com.google.common.base.Predicate<SkyKey?> =
            com.google.common.base.Predicates.or<T?>(
                SkyFunctions.isSkyFunction(SkyFunctions.CONFIGURED_TARGET),
                SkyFunctions.isSkyFunction(SkyFunctions.ASPECT),
                SkyFunctions.isSkyFunction(SkyFunctions.TOP_LEVEL_ASPECTS),
                SkyFunctions.isSkyFunction(TransitiveTargetKey.Companion.NAME),
                SkyFunctions.isSkyFunction(SkyFunctions.PREPARE_ANALYSIS_PHASE),
                SkyFunctions.isSkyFunction(SkyFunctions.BUILD_DRIVER)
            )
    }
}

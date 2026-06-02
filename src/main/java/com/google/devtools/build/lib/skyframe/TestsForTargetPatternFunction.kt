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
 * Returns all tests that need to be run when testing is requested for a given set of targets.
 * 
 * 
 * This requires resolving `test_suite` rules.
 */
internal class TestsForTargetPatternFunction : SkyFunction {
    @Throws(java.lang.InterruptedException::class)
    override fun compute(key: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val expansion: TestsForTargetPatternKey = key.argument() as TestsForTargetPatternKey
        val targets: ResolvedTargets<Target?>? = labelsToTargets(env, expansion.getTargets(), false)
        val testsInSuitesKeys: MutableList<SkyKey> = java.util.ArrayList<SkyKey>()
        for (target in targets.getTargets()) {
            if (TargetUtils.isTestSuiteRule(target)) {
                testsInSuitesKeys.add(TestExpansionValue.Companion.key(target, true))
            }
        }
        val testsInSuites: SkyframeLookupResult = env.getValuesAndExceptions(testsInSuitesKeys)
        if (env.valuesMissing()) {
            return null
        }

        val result: MutableSet<Label?> = LinkedHashSet<Label?>()
        var hasError: Boolean = targets.hasError()
        var keyIndex = 0
        for (target in targets.getTargets()) {
            if (TargetUtils.isTestRule(target)) {
                result.add(target.getLabel())
            } else if (TargetUtils.isTestSuiteRule(target)) {
                val value: TestExpansionValue? =
                    testsInSuites.get(testsInSuitesKeys.get(keyIndex++)) as TestExpansionValue?
                if (value == null) {
                    return null
                }
                result.addAll(value.getLabels().getTargets())
                hasError = hasError or value.getLabels().hasError()
            } else {
                result.add(target.getLabel())
            }
        }
        // We use ResolvedTargets in order to associate an error flag; the result should never contain
        // any filtered targets.
        return TestsForTargetPatternValue(ResolvedTargets(result, hasError))
    }

    companion object {
        @Throws(java.lang.InterruptedException::class)
        fun labelsToTargets(
            env: SkyFunction.Environment, labels: com.google.common.collect.ImmutableSet<Label>, hasError: Boolean
        ): ResolvedTargets<Target?>? {
            val pkgIdentifiers: MutableSet<PackageIdentifier> = LinkedHashSet<PackageIdentifier>()
            for (label in labels) {
                pkgIdentifiers.add(label.getPackageIdentifier())
            }
            val packages: SkyframeLookupResult = env.getValuesAndExceptions(pkgIdentifiers)
            if (env.valuesMissing()) {
                return null
            }

            val builder: ResolvedTargets.Builder<Target?> = ResolvedTargets.builder()
            builder.mergeError(hasError)
            val packageMap: MutableMap<PackageIdentifier?, Package?> = HashMap<PackageIdentifier?, Package?>()
            for (packagesKey in pkgIdentifiers) {
                // Don't bother to check for exceptions - the incoming list should only contain valid targets.
                val packagesValue: PackageValue? = packages.get(packagesKey) as PackageValue?
                if (packagesValue == null) {
                    BugReport.sendBugReport(
                        java.lang.IllegalStateException(
                            "PackageValue " + packagesKey + " was missing, this should never happen"
                        )
                    )
                    return null
                }
                packageMap.put(packagesKey.argument() as PackageIdentifier?, packagesValue.getPackage())
            }

            for (label in labels) {
                val pkg: Package? = packageMap.get(label.getPackageIdentifier())
                if (pkg == null) {
                    continue
                }
                try {
                    builder.add(pkg.getTarget(label.name))
                    if (pkg.containsErrors()) {
                        builder.setError()
                    }
                } catch (e: NoSuchTargetException) {
                    builder.setError()
                }
            }
            return builder.build()
        }
    }
}

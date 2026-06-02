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
 * TestExpansionFunction takes a single test_suite target and expands all of the tests it contains,
 * possibly recursively.
 */
// TODO(ulfjack): What about test_suite rules that include each other.
internal class TestExpansionFunction : SkyFunction {
    @Throws(java.lang.InterruptedException::class)
    override fun compute(key: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val expansion: TestExpansionKey = key.argument() as TestExpansionKey
        val packageKey: SkyKey? = expansion.getLabel().getPackageIdentifier()
        val pkg: PackageValue? = env.getValue(packageKey) as PackageValue?
        if (env.valuesMissing()) {
            return null
        }
        val rule: Rule = pkg.getPackage().getRule(expansion.getLabel().name)
        val result: ResolvedTargets<Label?> = computeExpandedTests(env, rule, expansion.isStrict())
        if (env.valuesMissing()) {
            return null
        }
        return TestExpansionValue(result)
    }

    companion object {
        private fun toLabels(targets: MutableSet<Target?>): MutableSet<Label?> {
            return targets.stream().map<Any?>(Target::getLabel).collect(Collectors.toSet())
        }

        /**
         * Populates 'result' with all the tests associated with the specified 'rule'. Throws an exception
         * if any target is missing.
         * 
         * 
         * CAUTION! Keep this logic consistent with `TestSuite`!
         */
        @Throws(java.lang.InterruptedException::class)
        private fun computeExpandedTests(
            env: SkyFunction.Environment, rule: Rule, strict: Boolean
        ): ResolvedTargets<Label?> {
            val result: MutableSet<Target?> = HashSet<Target?>()
            var hasError = false

            val prerequisites: MutableList<Target> = java.util.ArrayList<Target>()
            // Note that prerequisites can contain input file targets; the test_suite rule does not
            // restrict the set of targets that can appear in tests or suites.
            hasError = hasError or getPrerequisites(env, rule, "tests", prerequisites)

            // 1. Add all tests
            for (test in prerequisites) {
                if (TargetUtils.isTestRule(test)) {
                    result.add(test)
                } else if (strict && !TargetUtils.isTestSuiteRule(test)) {
                    // If strict mode is enabled, then give an error for any non-test, non-test-suite targets.
                    // TODO(ulfjack): We need to throw to end the process if we happen to be in --nokeep_going,
                    // but we can't know whether or not we are at this point.
                    env.getListener()
                        .handle(
                            Event.error(
                                rule.getLocation(),
                                ("in test_suite rule '"
                                        + rule.getLabel()
                                        + "': expecting a test or a test_suite rule but '"
                                        + test.getLabel()
                                        + "' is not one.")
                            )
                        )
                    hasError = true
                }
            }

            // 2. Add implicit dependencies on tests in same package, if any.
            val implicitTests: MutableList<Target> = java.util.ArrayList<Target>()
            hasError = hasError or getPrerequisites(env, rule, "\$implicit_tests", implicitTests)
            for (target in implicitTests) {
                // The Package construction of $implicit_tests ensures that this check never fails, but we
                // add it here anyway for compatibility with future code.
                if (TargetUtils.isTestRule(target)) {
                    result.add(target)
                }
            }

            // 3. Filter based on tags, size, env.
            TestTargetUtils.filterTests(rule, result)

            // 4. Expand all rules recursively, collecting labels.
            val labelsBuilder: ResolvedTargets.Builder<Label?> = ResolvedTargets.builder()
            // Don't set filtered targets; they would be removed from the containing test suite.
            labelsBuilder.merge(
                ResolvedTargets(
                    toLabels(result),
                    com.google.common.collect.ImmutableSet.of<E?>(),
                    hasError
                )
            )

            for (suite in prerequisites) {
                if (TargetUtils.isTestSuiteRule(suite)) {
                    val value: TestExpansionValue? =
                        env.getValue(TestExpansionValue.Companion.key(suite, strict)) as TestExpansionValue?
                    if (value == null) {
                        continue
                    }
                    labelsBuilder.merge(value.getLabels())
                }
            }

            return labelsBuilder.build()
        }

        /**
         * Adds the set of targets found in the attribute named `attrName`, which must be of label
         * or label list type, of the `test_suite` rule named `testSuite`. Returns true if the
         * method found a problem during the lookup process; the actual error message is reported to the
         * environment.
         */
        @Throws(java.lang.InterruptedException::class)
        private fun getPrerequisites(
            env: SkyFunction.Environment, rule: Rule?, attrName: String?, targets: MutableList<Target>
        ): Boolean {
            val mapper: AggregatingAttributeMapper = AggregatingAttributeMapper.of(rule)
            val labels: MutableList<Label> = java.util.ArrayList<Label>()
            val pkgIdentifiers: MutableSet<PackageIdentifier?> = HashSet<PackageIdentifier?>()
            mapper.visitLabels(
                attrName,
                { label ->
                    labels.add(label)
                    pkgIdentifiers.add(label.getPackageIdentifier())
                })
            val packages: SkyframeLookupResult = env.getValuesAndExceptions(pkgIdentifiers)
            if (env.valuesMissing()) {
                return false
            }
            var hasError = false
            val packageMap: MutableMap<PackageIdentifier?, Package?> = HashMap<PackageIdentifier?, Package?>()
            for (key in pkgIdentifiers) {
                try {
                    val packageValue: PackageValue? =
                        packages.getOrThrow<E?>(key, NoSuchPackageException::class.java) as PackageValue?
                    if (packageValue == null) {
                        return false
                    }
                    packageMap.put(key, packageValue.getPackage())
                } catch (e: NoSuchPackageException) {
                    env.getListener().handle(Event.error(e.getMessage()))
                    hasError = true
                }
            }

            for (label in labels) {
                val pkg: Package? = packageMap.get(label.getPackageIdentifier())
                if (pkg == null) {
                    continue
                }
                if (pkg.containsErrors()) {
                    hasError = true
                    // Abort the build if --nokeep_going.
                    try {
                        env.getValueOrThrow<E?>(
                            PackageErrorFunction.key(label.getPackageIdentifier()),
                            BuildFileContainsErrorsException::class.java
                        )
                        return false
                    } catch (e: BuildFileContainsErrorsException) {
                        // PackageErrorFunction always throws this exception, and this fact is used by Skyframe to
                        // abort the build. If we get here, it's either because of error bubbling or because we're
                        // in --keep_going mode. In either case, we *should* ignore the exception.
                    }
                }
                try {
                    targets.add(pkg.getTarget(label.name))
                } catch (e: NoSuchTargetException) {
                    env.getListener().handle(Event.error(e.getMessage()))
                    hasError = true
                }
            }
            return hasError
        }
    }
}

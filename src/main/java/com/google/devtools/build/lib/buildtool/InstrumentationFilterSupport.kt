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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.packages.BuildType
import com.google.devtools.build.lib.packages.NonconfigurableAttributeMapper
import com.google.devtools.build.lib.packages.TargetUtils
import com.google.devtools.build.lib.vfs.PathFragment
import java.util.SortedSet

/**
 * Helper class to heuristically compute an instrumentation filter from a list of tests to run.
 */
object InstrumentationFilterSupport {
    const val INSTRUMENTATION_FILTER_FLAG: String = "instrumentation_filter"

    /**
     * Method implements a heuristic used to set default value of the --instrumentation_filter option.
     * The following algorithm is used:
     * 
     * 
     *  * Identify all test targets on the command line.
     *  * Expand all test suites into the individual test targets.
     *  * Calculate list of package names containing all test targets above.
     *  * Replace all "javatests" directories in packages with "java". Similarly, replace
     * "test/java/" with "main/java". Also, strip trailing "/internal", "/public", and "/tests"
     * from packages. (See [.getInstrumentedPrefix].)
     *  * Set --instrumentation_filter default value to instrument everything in those packages.
     * 
     */
    @com.google.common.annotations.VisibleForTesting
    fun computeInstrumentationFilter(
        eventHandler: com.google.devtools.build.lib.events.EventHandler,
        testTargets: MutableCollection<com.google.devtools.build.lib.packages.Target>
    ): String {
        val packageFilters: SortedSet<String?> = com.google.common.collect.Sets.newTreeSet<String?>()
        collectInstrumentedPackages(testTargets, packageFilters)
        optimizeFilterSet(packageFilters)

        var instrumentationFilter =
            com.google.common.base.Joiner.on("[/:],^//")
                .appendTo(java.lang.StringBuilder("^//"), packageFilters)
                .append("[/:]")
                .toString()
        // Fix up if one of the test targets is a top-level target. "//foo[/:]" matches everything
        // under //foo and subpackages, but "//[/:]" only matches targets directly under the top-level
        // package.
        if (instrumentationFilter == "^//[/:]") {
            instrumentationFilter = "^//"
        }
        if (!packageFilters.isEmpty()) {
            eventHandler.handle(
                com.google.devtools.build.lib.events.Event.info(
                    ("Using default value for --instrumentation_filter: \""
                            + instrumentationFilter + "\".")
                )
            )
            eventHandler.handle(
                com.google.devtools.build.lib.events.Event.info(
                    "Override the above default with --"
                            + INSTRUMENTATION_FILTER_FLAG
                )
            )
        }
        return instrumentationFilter
    }

    private fun collectInstrumentedPackages(
        targets: MutableCollection<com.google.devtools.build.lib.packages.Target>, packageFilters: MutableSet<String?>
    ) {
        for (target in targets) {
            // Add package-based filters for every test target.
            packageFilters.add(getInstrumentedPrefix(target.getLabel().getPackageName()))
            if (TargetUtils.isTestSuiteRule(target)) {
                val attributes: com.google.devtools.build.lib.packages.AttributeMap =
                    NonconfigurableAttributeMapper.of(target as com.google.devtools.build.lib.packages.Rule)
                // We don't need to handle $implicit_tests attribute since we already added
                // test_suite package to the set.
                for (label in attributes.get<MutableList<com.google.devtools.build.lib.cmdline.Label>?>(
                    "tests",
                    BuildType.LABEL_LIST
                )) {
                    // Add package-based filters for all tests in the test suite.
                    packageFilters.add(getInstrumentedPrefix(label.getPackageName()))
                }
            }
        }
    }

    /**
     * Returns prefix string that should be instrumented for a given package. Input string should
     * be formatted like the output of Label.getPackageName().
     * Generally, package name will be used as such string with two modifications.
     * - "javatests/ directories will be substituted with "java/", since we do
     * not want to instrument java test code. "java/" directories in "test/" will
     * be replaced by the same in "main/".
     * - "/internal", "/public", and "tests/" package suffix will be dropped, since usually we would
     * want to instrument code in the parent package as well
     */
    @kotlin.jvm.JvmStatic
    @com.google.common.annotations.VisibleForTesting
    fun getInstrumentedPrefix(packageName: String): String? {
        var packageName = packageName
        if (packageName.endsWith("/internal")) {
            packageName = packageName.substring(0, packageName.length() - "/internal".length())
        } else if (packageName.endsWith("/public")) {
            packageName = packageName.substring(0, packageName.length() - "/public".length())
        } else if (packageName.endsWith("/tests")) {
            packageName = packageName.substring(0, packageName.length() - "/tests".length())
        }
        return packageName
            .replaceFirst("(?<=^|/)javatests/", "java/")
            .replaceFirst("(?<=^|/)test/java/", "main/java/")
    }

    private fun optimizeFilterSet(packageFilters: SortedSet<String?>) {
        var iterator: MutableIterator<String?> = packageFilters.iterator()
        if (iterator.hasNext()) {
            // Optimize away nested filters.
            iterator = packageFilters.iterator()
            var prev: PathFragment = PathFragment.create(iterator.next())
            while (iterator.hasNext()) {
                val current: PathFragment = PathFragment.create(iterator.next())
                if (current.startsWith(prev)) {
                    iterator.remove()
                } else {
                    prev = current
                }
            }
        }
    }
}

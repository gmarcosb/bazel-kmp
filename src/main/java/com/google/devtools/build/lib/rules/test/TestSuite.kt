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
package com.google.devtools.build.lib.rules.test

import com.google.devtools.build.lib.actions.ActionConflictException

/**
 * Implementation for the "test_suite" rule.
 */
class TestSuite : RuleConfiguredTargetFactory {
    @Throws(java.lang.InterruptedException::class, RuleErrorException::class, ActionConflictException::class)
    public override fun create(ruleContext: RuleContext): ConfiguredTarget? {
        checkTestsAndSuites(ruleContext, "tests")
        if (ruleContext.hasErrors()) {
            return null
        }

        //
        //  CAUTION!  Keep this logic consistent with lib.query2.TestsFunction!
        //
        val tagsAttribute: MutableList<String?> =
            java.util.ArrayList<Any?>(ruleContext.attributes().get("tags", Types.STRING_LIST))
        // TODO(ulfjack): This is inconsistent with the other places that do test_suite expansion.
        tagsAttribute.remove("manual")
        val requiredExcluded: com.google.devtools.build.lib.util.Pair<MutableCollection<String?>?, MutableCollection<String?>?> =
            TestTargetUtils.sortTagsBySense(tagsAttribute)

        val directTestsAndSuitesBuilder: MutableList<TransitiveInfoCollection?> =
            java.util.ArrayList<TransitiveInfoCollection?>()

        // The set of implicit tests is determined in
        // {@link com.google.devtools.build.lib.packages.Package}.
        // Manual tests are already filtered out there. That is what $implicit_tests is about.
        for (dep in com.google.common.collect.Iterables.concat<Any>(
            getPrerequisites(ruleContext, "tests"),
            getPrerequisites(ruleContext, "\$implicit_tests")
        )) {
            if (dep.getProvider(TestTagsProvider::class.java) != null) {
                // getTestTags maps to Rule.getRuleTags.
                val tags: MutableList<String?>? = dep.getProvider(TestTagsProvider::class.java).getTestTags()
                if (!TestTargetUtils.testMatchesFilters(
                        tags, requiredExcluded.first, requiredExcluded.second
                    )
                ) {
                    // This test does not match our filter. Ignore it.
                    continue
                }
            }
            directTestsAndSuitesBuilder.add(dep)
        }

        val runfiles: Runfiles? =
            Builder(ruleContext.getWorkspaceName())
                .addTargets(
                    directTestsAndSuitesBuilder,
                    RunfilesProvider.DATA_RUNFILES,
                    ruleContext.getConfiguration().alwaysIncludeFilesToBuildInData()
                )
                .build()

        return RuleConfiguredTargetBuilder(ruleContext)
            .add(
                RunfilesProvider::class.java,
                RunfilesProvider.withData(Runfiles.EMPTY, runfiles)
            )
            .add(TransitiveTestsProvider::class.java, TransitiveTestsProvider())
            .build()
    }

    private fun getPrerequisites(
        ruleContext: RuleContext, attributeName: String?
    ): Iterable<out TransitiveInfoCollection?>? {
        if (ruleContext.attributes().has(attributeName, BuildType.LABEL_LIST)) {
            return ruleContext.getPrerequisites(attributeName)
        } else {
            return com.google.common.collect.ImmutableList.of<TransitiveInfoCollection?>()
        }
    }

    private fun checkTestsAndSuites(ruleContext: RuleContext, attributeName: String?) {
        if (!ruleContext.attributes().has(attributeName, BuildType.LABEL_LIST)) {
            return
        }
        for (dep in ruleContext.getPrerequisites(attributeName)) {
            // TODO(bazel-team): Maybe convert the TransitiveTestsProvider into an inner interface.
            val provider: TransitiveTestsProvider? = dep.getProvider(TransitiveTestsProvider::class.java)
            val tagsProvider: TestTagsProvider? = dep.getProvider(TestTagsProvider::class.java)
            if (provider == null && tagsProvider == null) {
                ruleContext.attributeError(
                    attributeName,
                    "expecting a test or a test_suite rule but '" + dep.label + "' is not one"
                )
            }
        }
    }
}

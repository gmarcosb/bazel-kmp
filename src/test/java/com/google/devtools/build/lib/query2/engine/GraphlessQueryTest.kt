// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.engine

import com.google.common.base.Predicate
import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.cmdline.Label
import org.junit.Test

/**
 * Tests for the query engine, generic over the result type. This allows us to share the tests
 * between the different implementations, and also parameterize it over the set of options, such as
 * `--keep_going`.
 */
@RunWith(JUnit4::class)
class GraphlessQueryTest : AbstractQueryTest<Target?>() {
    override fun includeCppToolchainDependencies(): Boolean {
        // These don't exist in graphless mode.
        return false
    }

    @Throws(Exception::class)
    override fun boundedRdepsWithError() {
        writeFile(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "foo",
            deps = [":dep"],
        )

        foo_library(
            name = "dep",
            deps = ["//bar:missing"],
        )
        
        """.trimIndent()
        )
        Truth.assertThat(
            evalThrows("rdeps(//foo:foo, //foo:dep, 1)",  /* unconditionallyThrows= */false)
                .getMessage()
        )
            .contains("preloading transitive closure failed")
    }

    @Test
    override fun testGraphOrderOfWildcards() {
        // Test assumes that the result is of type DigraphQueryEvalResult, which is not true for the
        // GraphlessBlazeQueryEnvironment.
    }

    override fun createQueryHelper(): QueryHelper<Target?> {
        return object : SkyframeQueryHelper() {
            val rootDirectoryNameForSetup: String
                get() = "/workspace"

            override fun performAdditionalClientSetup(mockToolsConfig: MockToolsConfig?) {}

            override fun makeQueryEnvironmentFactory(): QueryEnvironmentFactory? {
                return object : QueryEnvironmentFactory() {
                    public override fun create(
                        queryTransitivePackagePreloader: QueryTransitivePackagePreloader?,
                        graphFactory: WalkableGraphFactory?,
                        targetProvider: TargetProvider?,
                        cachingPackageLocator: CachingPackageLocator?,
                        targetPatternPreloader: TargetPatternPreloader?,
                        targetParser: TargetPattern.Parser?,
                        relativeWorkingDirectory: PathFragment?,
                        keepGoing: Boolean,
                        strictScope: Boolean,
                        orderedResults: Boolean,
                        universeScope: UniverseScope?,
                        loadingPhaseThreads: Int,
                        trackIncrementalState: Boolean,
                        labelFilter: Predicate<Label?>?,
                        eventHandler: ExtendedEventHandler?,
                        settings: MutableSet<QueryEnvironment.Setting?>?,
                        extraFunctions: Iterable<QueryFunction?>?,
                        packagePath: PathPackageLocator?,
                        useGraphlessQuery: Boolean,
                        labelPrinter: LabelPrinter?
                    ): AbstractBlazeQueryEnvironment<Target?>? {
                        return GraphlessBlazeQueryEnvironment(
                            queryTransitivePackagePreloader,
                            targetProvider,
                            cachingPackageLocator,
                            targetPatternPreloader,
                            targetParser,
                            keepGoing,
                            strictScope,
                            loadingPhaseThreads,
                            labelFilter,
                            eventHandler,
                            settings,
                            extraFunctions,
                            labelPrinter
                        )
                    }
                }
            }

            val extraQueryFunctions: Iterable<QueryFunction>
                get() = ImmutableList.of<QueryFunction?>()
        }
    }
}

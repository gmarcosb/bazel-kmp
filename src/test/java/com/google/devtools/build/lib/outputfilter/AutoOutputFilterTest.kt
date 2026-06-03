// Copyright 2019 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.outputfilter

import com.google.common.collect.ArrayListMultimap
import com.google.common.collect.ImmutableList
import com.google.common.collect.ListMultimap
import com.google.devtools.build.lib.analysis.platform.PlatformConstants
import com.google.devtools.build.lib.events.OutputFilter
import org.junit.Test
import org.junit.runners.Parameterized

/** Tests for the [AutoOutputFilter] class.  */
@RunWith(Enclosed::class)
object AutoOutputFilterTest {
    private fun assertFilter(
        extractedRegex: String?, autoFilter: AutoOutputFilter, targetLabels: MutableList<Label?>?
    ) {
        val filter = autoFilter.getFilter(targetLabels)
        val extraRegex =
            if (autoFilter === AutoOutputFilter.NONE)
                ""
            else
                "(unknown)|" + PlatformConstants.INTERNAL_PLATFORM.getCanonicalForm() + "|"
        Truth.assertWithMessage("output filter %s returned wrong filter:", autoFilter)
            .that(filter.toString())
            .isEqualTo(extraRegex + extractedRegex)
    }

    private fun targets(vararg targetLabels: String?): ImmutableList<Label?> {
        // Sort targets by package
        val targetsPerPackage: ListMultimap<String?, String?> = ArrayListMultimap.create<String?, String?>()
        for (targetName in targetLabels) {
            val label: Label = Label.parseCanonicalUnchecked(targetName)
            targetsPerPackage.put(label.getPackageName(), label.name)
        }

        // Collect targets
        val targets: ImmutableList.Builder<Label?> = ImmutableList.builder<Label?>()
        for (targetName in targetLabels) {
            targets.add(Label.parseCanonicalUnchecked(targetName))
        }
        return targets.build()
    }

    @RunWith(Parameterized::class)
    class NoneTest {
        @Parameterized.Parameter(0)
        var targets: MutableList<Label?>? = null

        @Parameterized.Parameter(1)
        var expectedOutputFilter: OutputFilter? = null

        @Test
        fun testFilter() {
            Truth.assertThat(AutoOutputFilter.NONE.getFilter(this.targets)).isEqualTo(this.expectedOutputFilter)
        }

        companion object {
            @Parameterized.Parameters(name = "{0}")
            fun filters(): ImmutableList<Array<Any?>?> {
                return ImmutableList.of<Array<Any?>?>(
                    arrayOf<Any?>(targets(), OutputFilter.OUTPUT_EVERYTHING),
                    arrayOf<Any?>(targets("//a"), OutputFilter.OUTPUT_EVERYTHING),
                    arrayOf<Any?>(targets("//a", "//b"), OutputFilter.OUTPUT_EVERYTHING)
                )
            }
        }
    }

    @RunWith(Parameterized::class)
    class AllTest {
        @Parameterized.Parameter(0)
        var targets: MutableList<Label?>? = null

        @Parameterized.Parameter(1)
        var expectedOutputFilter: OutputFilter? = null

        @Test
        fun testFilter() {
            Truth.assertThat(AutoOutputFilter.ALL.getFilter(this.targets)).isEqualTo(this.expectedOutputFilter)
        }

        companion object {
            @Parameterized.Parameters(name = "{0}")
            fun filters(): ImmutableList<Array<Any?>?> {
                return ImmutableList.of<Array<Any?>?>(
                    arrayOf<Any?>(targets(), OutputFilter.OUTPUT_NOTHING),
                    arrayOf<Any?>(targets("//a"), OutputFilter.OUTPUT_NOTHING),
                    arrayOf<Any?>(targets("//a", "//b"), OutputFilter.OUTPUT_NOTHING)
                )
            }
        }
    }

    @RunWith(Parameterized::class)
    class PackagesTest {
        @Parameterized.Parameter(0)
        var expectedRegex: String? = null

        @Parameterized.Parameter(1)
        var targetLabels: ImmutableList<Label?>? = null

        @Test
        fun testFilter() {
            assertFilter(this.expectedRegex, AutoOutputFilter.PACKAGES, this.targetLabels)
        }

        companion object {
            @Parameterized.Parameters(name = "{0}-{1}")
            fun filters(): ImmutableList<Array<Any?>?> {
                return ImmutableList.of<Array<Any?>?>(
                    arrayOf<Any>("^//():", targets()),
                    arrayOf<Any>("^//(a):", targets("//a:b")),
                    arrayOf<Any>("^//(a):", targets("//a:b", "//a:c")),
                    arrayOf<Any>("^//(a|b):", targets("//a:a", "//a:b", "//b:c")),
                    arrayOf<Any>("^//(a|b):", targets("//a:a", "//b:c", "//a:b")),
                    arrayOf<Any>("^//(a/b|a/b/c):", targets("//a/b:b", "//a/b/c:c")),
                    arrayOf<Any>("^//(java(tests)?/a):", targets("//java/a")),
                    arrayOf<Any>("^//(java(tests)?/a):", targets("//javatests/a")),
                    arrayOf<Any>("^//(java(tests)?/a):", targets("//java/a", "//javatests/a")),
                    arrayOf<Any>(
                        "^//(java(tests)?/a|java(tests)?/b):", targets("//java/a", "//javatests/b")
                    ),
                    arrayOf<Any>("^//(a/b|a/b/c):", targets("//a/b:b", "//a/b/c:c")),
                    arrayOf<Any>("^//(a|a/b|a/b/c|b):", targets("//a", "//a/b", "//a/b/c", "//b")),
                    arrayOf<Any>("^//(a|a/b/c|b|b/c/d):", targets("//a", "//a/b/c", "//b", "//b/c/d")),
                    arrayOf<Any>(
                        "^//(java(tests)?/a|java(tests)?/a/b):", targets("//java/a", "//javatests/a/b")
                    ),
                    arrayOf<Any>(
                        "^//(java(tests)?/a|java(tests)?/a/b/c):", targets("//javatests/a", "//java/a/b/c")
                    )
                )
            }
        }
    }

    @RunWith(Parameterized::class)
    class SubpackagesTest {
        @Parameterized.Parameter(0)
        var expectedRegex: String? = null

        @Parameterized.Parameter(1)
        var targetLabels: ImmutableList<Label?>? = null

        @Test
        fun testFilter() {
            assertFilter(this.expectedRegex, AutoOutputFilter.SUBPACKAGES, this.targetLabels)
        }

        companion object {
            @Parameterized.Parameters(name = "{0}-{1}")
            fun filters(): ImmutableList<Array<Any?>?> {
                return ImmutableList.of<Array<Any?>?>(
                    arrayOf<Any>("^//()[/:]", targets()),
                    arrayOf<Any>("^//(a)[/:]", targets("//a:b")),
                    arrayOf<Any>("^//(a)[/:]", targets("//a:b", "//a:c")),
                    arrayOf<Any>("^//(a|b)[/:]", targets("//a:a", "//a:b", "//b:c")),
                    arrayOf<Any>("^//(a|b)[/:]", targets("//a:a", "//b:c", "//a:b")),
                    arrayOf<Any>("^//(java(tests)?/a)[/:]", targets("//java/a")),
                    arrayOf<Any>("^//(java(tests)?/a)[/:]", targets("//javatests/a")),
                    arrayOf<Any>("^//(java(tests)?/a)[/:]", targets("//java/a", "//javatests/a")),
                    arrayOf<Any>(
                        "^//(java(tests)?/a|java(tests)?/b)[/:]", targets("//java/a", "//javatests/b")
                    ),
                    arrayOf<Any>("^//(a/b)[/:]", targets("//a/b:b", "//a/b/c:c")),
                    arrayOf<Any>("^//(a|b)[/:]", targets("//a", "//a/b", "//a/b/c", "//b")),
                    arrayOf<Any>("^//(a|b)[/:]", targets("//a", "//a/b/c", "//b", "//b/c/d")),
                    arrayOf<Any>("^//(java(tests)?/a)[/:]", targets("//java/a", "//javatests/a/b")),
                    arrayOf<Any>("^//(java(tests)?/a)[/:]", targets("//javatests/a", "//java/a/b/c"))
                )
            }
        }
    }
}

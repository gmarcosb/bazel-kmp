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
package com.google.devtools.build.skyframe

import com.google.devtools.build.skyframe.GraphTester
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import org.junit.runner.RunWith
import java.util.stream.IntStream

/** Tests for [GroupedDeps].  */
@RunWith(TestParameterInjector::class)
class GroupedDepsTest {
    @TestParameter
    private val withHashSet = false

    private fun createEmpty(): GroupedDeps {
        if (withHashSet) {
            return WithHashSet()
        }
        return GroupedDeps()
    }

    @org.junit.Test
    fun singleGroup(@TestParameter("0", "1", "2", "10") size: Int) {
        val deps: GroupedDeps = createEmpty()
        val elements: com.google.common.collect.ImmutableList<SkyKey?> =
            IntStream.range(0, size).mapToObj<Any?>(java.util.function.IntFunction { i: Int -> key(i.toString()) })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        deps.appendGroup(elements)
        checkGroups(
            deps,
            if (size == 0) com.google.common.collect.ImmutableList.of<com.google.common.collect.ImmutableList<SkyKey?>?>() else com.google.common.collect.ImmutableList.of<com.google.common.collect.ImmutableList<SkyKey?>?>(
                elements
            )
        )
    }

    @org.junit.Test
    fun appendEmptyGroup_noOp() {
        val deps: GroupedDeps = createEmpty()
        deps.appendGroup(com.google.common.collect.ImmutableList.of<E?>())
        assertThat(deps.isEmpty()).isTrue()
        deps.appendSingleton(key("a"))
        deps.appendGroup(com.google.common.collect.ImmutableList.of<E?>())
        deps.appendSingleton(key("b"))
        checkGroups(
            deps, com.google.common.collect.ImmutableList.of<com.google.common.collect.ImmutableList<SkyKey?>?>(
                com.google.common.collect.ImmutableList.of<SkyKey?>(
                    key("a")
                ), com.google.common.collect.ImmutableList.of<SkyKey?>(key("b"))
            )
        )
    }

    @org.junit.Test
    fun identical_equal() {
        val abc1: GroupedDeps = createEmpty()
        val abc2: GroupedDeps = createEmpty()
        abc1.appendGroup(com.google.common.collect.ImmutableList.of<E?>(key("a"), key("b"), key("c")))
        abc2.appendGroup(com.google.common.collect.ImmutableList.of<E?>(key("a"), key("b"), key("c")))
        assertThat(abc1).isEqualTo(abc2)
    }

    @org.junit.Test
    fun appendSingletonAndAppendGroupSizeOne_equal() {
        val aSingleton: GroupedDeps = createEmpty()
        val aGroup: GroupedDeps = createEmpty()
        aSingleton.appendSingleton(key("a"))
        aGroup.appendGroup(com.google.common.collect.ImmutableList.of<E?>(key("a")))
        assertThat(aSingleton).isEqualTo(aGroup)
    }

    @org.junit.Test
    fun differentOrderWithinGroup_equal() {
        val ab: GroupedDeps = createEmpty()
        val ba: GroupedDeps = createEmpty()
        ab.appendGroup(com.google.common.collect.ImmutableList.of<E?>(key("a"), key("b")))
        ba.appendGroup(com.google.common.collect.ImmutableList.of<E?>(key("b"), key("a")))
        assertThat(ab).isEqualTo(ba)
    }

    @org.junit.Test
    fun differentElements_notEqual() {
        val abc: GroupedDeps = createEmpty()
        val xyz: GroupedDeps = createEmpty()
        abc.appendGroup(com.google.common.collect.ImmutableList.of<E?>(key("a"), key("b"), key("c")))
        xyz.appendGroup(com.google.common.collect.ImmutableList.of<E?>(key("x"), key("y"), key("z")))
        assertThat(abc).isNotEqualTo(xyz)
    }

    @org.junit.Test
    fun differentOrderOfGroups_notEqual() {
        val ab: GroupedDeps = createEmpty()
        val ba: GroupedDeps = createEmpty()
        ab.appendSingleton(key("a"))
        ab.appendSingleton(key("b"))
        ba.appendSingleton(key("b"))
        ba.appendSingleton(key("a"))
        assertThat(ab).isNotEqualTo(ba)
    }

    @org.junit.Test
    fun differentGroupings_notEqual() {
        val abGroup: GroupedDeps = createEmpty()
        val abSingletons: GroupedDeps = createEmpty()
        abGroup.appendGroup(com.google.common.collect.ImmutableList.of<E?>(key("a"), key("b")))
        abSingletons.appendSingleton(key("a"))
        abSingletons.appendSingleton(key("b"))
        assertThat(abGroup).isNotEqualTo(abSingletons)
    }

    @org.junit.Test
    fun groups() {
        val deps: GroupedDeps = createEmpty()
        val groups: com.google.common.collect.ImmutableList<com.google.common.collect.ImmutableList<SkyKey?>?> =
            com.google.common.collect.ImmutableList.of<com.google.common.collect.ImmutableList<SkyKey?>?>(
                com.google.common.collect.ImmutableList.of<SkyKey?>(key("1")),
                com.google.common.collect.ImmutableList.of<SkyKey?>(key("2a"), key("2b")),
                com.google.common.collect.ImmutableList.of<SkyKey?>(key("3")),
                com.google.common.collect.ImmutableList.of<SkyKey?>(key("4")),
                com.google.common.collect.ImmutableList.of<SkyKey?>(key("5a"), key("5b"), key("5c")),
                com.google.common.collect.ImmutableList.of<SkyKey?>(key("6a"), key("6b"), key("6c"))
            )
        groups.forEach(deps::appendGroup)
        checkGroups(deps, groups)
    }

    @org.junit.Test
    fun remove_groupsIntact() {
        val deps: GroupedDeps = createEmpty()
        deps.appendGroup(com.google.common.collect.ImmutableList.of<E?>(key("1a"), key("1b")))
        deps.appendGroup(com.google.common.collect.ImmutableList.of<E?>(key("2a"), key("2b"), key("2c")))
        deps.appendGroup(com.google.common.collect.ImmutableList.of<E?>(key("3a"), key("3b")))

        deps.remove(com.google.common.collect.ImmutableSet.of<E?>(key("2c")))

        checkGroups(
            deps,
            com.google.common.collect.ImmutableList.of<com.google.common.collect.ImmutableList<SkyKey?>?>(
                com.google.common.collect.ImmutableList.of<SkyKey?>(key("1a"), key("1b")),
                com.google.common.collect.ImmutableList.of<SkyKey?>(key("2a"), key("2b")),
                com.google.common.collect.ImmutableList.of<SkyKey?>(key("3a"), key("3b"))
            )
        )
    }

    @org.junit.Test
    fun remove_groupBecomesSingleton() {
        val deps: GroupedDeps = createEmpty()
        deps.appendGroup(com.google.common.collect.ImmutableList.of<E?>(key("1a"), key("1b")))
        deps.appendGroup(com.google.common.collect.ImmutableList.of<E?>(key("2a"), key("2b"), key("2c")))
        deps.appendGroup(com.google.common.collect.ImmutableList.of<E?>(key("3a"), key("3b")))

        deps.remove(com.google.common.collect.ImmutableSet.of<E?>(key("2b"), key("2c")))

        checkGroups(
            deps,
            com.google.common.collect.ImmutableList.of<com.google.common.collect.ImmutableList<SkyKey?>?>(
                com.google.common.collect.ImmutableList.of<SkyKey?>(key("1a"), key("1b")),
                com.google.common.collect.ImmutableList.of<SkyKey?>(key("2a")),
                com.google.common.collect.ImmutableList.of<SkyKey?>(key("3a"), key("3b"))
            )
        )
    }

    @org.junit.Test
    fun remove_groupBecomesEmpty() {
        val deps: GroupedDeps = createEmpty()
        deps.appendGroup(com.google.common.collect.ImmutableList.of<E?>(key("1a"), key("1b")))
        deps.appendGroup(com.google.common.collect.ImmutableList.of<E?>(key("2a"), key("2b"), key("2c")))
        deps.appendGroup(com.google.common.collect.ImmutableList.of<E?>(key("3a"), key("3b")))

        deps.remove(com.google.common.collect.ImmutableSet.of<E?>(key("2a"), key("2b"), key("2c")))

        checkGroups(
            deps,
            com.google.common.collect.ImmutableList.of<com.google.common.collect.ImmutableList<SkyKey?>?>(
                com.google.common.collect.ImmutableList.of<SkyKey?>(key("1a"), key("1b")),
                com.google.common.collect.ImmutableList.of<SkyKey?>(
                    key("3a"), key("3b")
                )
            )
        )
    }

    @org.junit.Test
    fun remove_singleton() {
        val deps: GroupedDeps = createEmpty()
        deps.appendGroup(com.google.common.collect.ImmutableList.of<E?>(key("1a"), key("1b")))
        deps.appendSingleton(key("2"))
        deps.appendGroup(com.google.common.collect.ImmutableList.of<E?>(key("3a"), key("3b")))

        deps.remove(com.google.common.collect.ImmutableSet.of<E?>(key("2")))

        checkGroups(
            deps,
            com.google.common.collect.ImmutableList.of<com.google.common.collect.ImmutableList<SkyKey?>?>(
                com.google.common.collect.ImmutableList.of<SkyKey?>(key("1a"), key("1b")),
                com.google.common.collect.ImmutableList.of<SkyKey?>(
                    key("3a"), key("3b")
                )
            )
        )
    }

    @org.junit.Test
    fun remove_wholeGroupedDepsBecomesEmpty() {
        val deps: GroupedDeps = createEmpty()
        deps.appendGroup(com.google.common.collect.ImmutableList.of<E?>(key("1a"), key("1b")))
        deps.appendGroup(com.google.common.collect.ImmutableList.of<E?>(key("2a"), key("2b"), key("2c")))
        deps.appendGroup(com.google.common.collect.ImmutableList.of<E?>(key("3a"), key("3b")))

        deps.remove(
            com.google.common.collect.ImmutableSet.< E > of < E ? > (
                    key("1a"), key("1b"), key("2a"), key("2b"), key("2c"), key("3a"), key("3b")
        ))

        checkGroups(
            deps,
            com.google.common.collect.ImmutableList.of<com.google.common.collect.ImmutableList<SkyKey?>?>()
        )
    }

    @org.junit.Test
    fun remove_elementNotPresent_throws() {
        val deps: GroupedDeps = createEmpty()
        deps.appendGroup(com.google.common.collect.ImmutableList.of<E?>(key("1a"), key("1b")))
        deps.appendGroup(com.google.common.collect.ImmutableList.of<E?>(key("2a"), key("2b"), key("2c")))
        deps.appendGroup(com.google.common.collect.ImmutableList.of<E?>(key("3a"), key("3b")))

        org.junit.Assert.assertThrows<java.lang.RuntimeException?>(
            java.lang.RuntimeException::class.java,
            org.junit.function.ThrowingRunnable {
                deps.remove(
                    com.google.common.collect.ImmutableSet.of<E?>(
                        key("2c"), key("2d")
                    )
                )
            })
    }

    companion object {
        private fun key(arg: String?): SkyKey? {
            return GraphTester.Companion.skyKey(arg)
        }

        private fun checkGroups(
            deps: GroupedDeps,
            expectedGroups: MutableList<com.google.common.collect.ImmutableList<SkyKey?>?>
        ) {
            assertThat(deps.isEmpty()).isEqualTo(expectedGroups.isEmpty())
            assertThat(deps.numGroups()).isEqualTo(expectedGroups.size)
            assertThat(deps).containsExactlyElementsIn(expectedGroups).inOrder()

            val expectedFlattened: com.google.common.collect.ImmutableList<SkyKey?> =
                com.google.common.collect.ImmutableList.copyOf<SkyKey?>(
                    com.google.common.collect.Iterables.concat<SkyKey?>(
                        expectedGroups
                    )
                )
            assertThat(deps.numElements()).isEqualTo(expectedFlattened.size)
            assertThat(deps.getAllElementsAsIterable())
                .containsExactlyElementsIn(expectedFlattened)
                .inOrder()
            if (deps is GroupedDeps.WithHashSet) {
                assertThat(deps.toSet()).containsExactlyElementsIn(expectedFlattened)
            } else {
                assertThat(deps.toSet()).containsExactlyElementsIn(expectedFlattened).inOrder()
            }

            checkCompression(deps)
        }

        private fun checkCompression(deps: GroupedDeps) {
            @GroupedDeps.Compressed val compressed: Any? = deps.compress()
            assertThat(GroupedDeps.numElements(compressed)).isEqualTo(deps.numElements())
            assertThat(GroupedDeps.isEmpty(compressed)).isEqualTo(deps.isEmpty())
            assertThat(GroupedDeps.compressedToIterable(compressed))
                .containsExactlyElementsIn(deps.getAllElementsAsIterable())
                .inOrder()
            assertThat(GroupedDeps.decompress(compressed)).containsExactlyElementsIn(deps).inOrder()
            assertThat(GroupedDeps.decompress(compressed)).isNotInstanceOf(GroupedDeps.WithHashSet::class.java)
        }
    }
}

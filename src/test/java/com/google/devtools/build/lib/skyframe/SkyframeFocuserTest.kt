// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.Action

/** Tests for [SkyframeFocuser].  */
@RunWith(JUnit4::class)
class SkyframeFocuserTest : BuildViewTestCase() {
    @org.junit.Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @org.mockito.Mock
    private val mockActionCache: ActionCache? = null

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun testFocus_emptyInputsReturnsEmptyResult() {
        val graph: InMemoryGraph? = skyframeExecutor.getEvaluator().getInMemoryGraph()
        val focusResult: FocusResult =
            SkyframeFocuser.focus(
                graph,
                mockActionCache,
                com.google.common.collect.Sets.newHashSet<E?>(),
                com.google.common.collect.Sets.newHashSet<E?>()
            )

        assertThat(focusResult.deps()).isEmpty()
        assertThat(focusResult.rdeps()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun testFocus_keepsLeafs() {
        val graph: InMemoryGraph = skyframeExecutor.getEvaluator().getInMemoryGraph()
        val cat: SkyKey =
            com.google.devtools.build.lib.skyframe.SkyframeFocuserTest.SkyKeyWithSkyKeyInterner.Companion.create("cat")
        val dog: SkyKey =
            com.google.devtools.build.lib.skyframe.SkyframeFocuserTest.SkyKeyWithSkyKeyInterner.Companion.create("dog")
        val keys: com.google.common.collect.ImmutableList<SkyKey?> =
            com.google.common.collect.ImmutableList.of<SkyKey?>(cat, dog)

        graph.createIfAbsentBatch(null, Reason.OTHER, keys)

        createEdgesAndMarkDone(
            graph,
            cat,
            com.google.common.collect.ImmutableList.of<SkyKey?>(),
            com.google.common.collect.ImmutableList.of<SkyKey?>()
        )
        createEdgesAndMarkDone(
            graph,
            dog,
            com.google.common.collect.ImmutableList.of<SkyKey?>(),
            com.google.common.collect.ImmutableList.of<SkyKey?>()
        )

        val roots: MutableSet<SkyKey?> = com.google.common.collect.Sets.newHashSet<SkyKey?>()
        val leafs: MutableSet<SkyKey?> = com.google.common.collect.Sets.newHashSet<SkyKey?>(cat, dog)

        val focusResult: FocusResult = SkyframeFocuser.focus(graph, mockActionCache, roots, leafs)

        assertThat(focusResult.deps()).isEmpty()
        assertThat(focusResult.rdeps()).containsExactly(cat, dog)
        assertThat(graph.values.keySet()).containsExactly(cat, dog)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun testFocus_dropsUnreachableNodesFromLeafs() {
        val graph: InMemoryGraph = skyframeExecutor.getEvaluator().getInMemoryGraph()
        val cat: SkyKey =
            com.google.devtools.build.lib.skyframe.SkyframeFocuserTest.SkyKeyWithSkyKeyInterner.Companion.create("cat")
        val dog: SkyKey =
            com.google.devtools.build.lib.skyframe.SkyframeFocuserTest.SkyKeyWithSkyKeyInterner.Companion.create("dog")
        val keys: com.google.common.collect.ImmutableList<SkyKey?> =
            com.google.common.collect.ImmutableList.of<SkyKey?>(cat, dog)

        graph.createIfAbsentBatch(null, Reason.OTHER, keys)

        createEdgesAndMarkDone(
            graph,
            cat,
            com.google.common.collect.ImmutableList.of<SkyKey?>(),
            com.google.common.collect.ImmutableList.of<SkyKey?>()
        )
        createEdgesAndMarkDone(
            graph,
            dog,
            com.google.common.collect.ImmutableList.of<SkyKey?>(),
            com.google.common.collect.ImmutableList.of<SkyKey?>()
        )

        val roots: MutableSet<SkyKey?> = com.google.common.collect.Sets.newHashSet<SkyKey?>()
        val leafs: MutableSet<SkyKey?> = com.google.common.collect.Sets.newHashSet(cat) // dog is unreachable

        val focusResult: FocusResult = SkyframeFocuser.focus(graph, mockActionCache, roots, leafs)

        assertThat(focusResult.deps()).isEmpty()
        assertThat(focusResult.rdeps()).containsExactly(cat)
        assertThat(graph.values.keySet()).containsExactly(cat)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun testFocus_keepsReverseDepOfLeafs() {
        val graph: InMemoryGraph = skyframeExecutor.getEvaluator().getInMemoryGraph()
        val cat: SkyKey =
            com.google.devtools.build.lib.skyframe.SkyframeFocuserTest.SkyKeyWithSkyKeyInterner.Companion.create("cat")
        val dog: SkyKey =
            com.google.devtools.build.lib.skyframe.SkyframeFocuserTest.SkyKeyWithSkyKeyInterner.Companion.create("dog")
        val keys: com.google.common.collect.ImmutableList<SkyKey?> =
            com.google.common.collect.ImmutableList.of<SkyKey?>(cat, dog)

        graph.createIfAbsentBatch(null, Reason.OTHER, keys)
        createEdgesAndMarkDone(
            graph,
            cat,
            com.google.common.collect.ImmutableList.of<SkyKey?>(),
            com.google.common.collect.ImmutableList.of<SkyKey?>(dog)
        )
        createEdgesAndMarkDone(
            graph,
            dog,
            com.google.common.collect.ImmutableList.of<SkyKey?>(),
            com.google.common.collect.ImmutableList.of<SkyKey?>()
        )

        val roots: MutableSet<SkyKey?> = com.google.common.collect.Sets.newHashSet<SkyKey?>()
        val leafs: MutableSet<SkyKey?> = com.google.common.collect.Sets.newHashSet(cat) // dog is cat's rdep

        val focusResult: FocusResult = SkyframeFocuser.focus(graph, mockActionCache, roots, leafs)

        assertThat(focusResult.deps()).isEmpty()
        assertThat(focusResult.rdeps()).containsExactly(cat, dog)
        assertThat(graph.values.keySet()).containsExactly(cat, dog)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun testFocus_keepsRoots() {
        val graph: InMemoryGraph = skyframeExecutor.getEvaluator().getInMemoryGraph()
        val cat: SkyKey =
            com.google.devtools.build.lib.skyframe.SkyframeFocuserTest.SkyKeyWithSkyKeyInterner.Companion.create("cat")
        val dog: SkyKey =
            com.google.devtools.build.lib.skyframe.SkyframeFocuserTest.SkyKeyWithSkyKeyInterner.Companion.create("dog")
        val keys: com.google.common.collect.ImmutableList<SkyKey?> =
            com.google.common.collect.ImmutableList.of<SkyKey?>(cat, dog)

        graph.createIfAbsentBatch(null, Reason.OTHER, keys)
        createEdgesAndMarkDone(
            graph,
            cat,
            com.google.common.collect.ImmutableList.of<SkyKey?>(),
            com.google.common.collect.ImmutableList.of<SkyKey?>()
        )
        createEdgesAndMarkDone(
            graph,
            dog,
            com.google.common.collect.ImmutableList.of<SkyKey?>(),
            com.google.common.collect.ImmutableList.of<SkyKey?>()
        )

        val roots: MutableSet<SkyKey?> = com.google.common.collect.Sets.newHashSet<SkyKey?>(cat, dog)
        val leafs: MutableSet<SkyKey?> = com.google.common.collect.Sets.newHashSet<SkyKey?>()

        val focusResult: FocusResult = SkyframeFocuser.focus(graph, mockActionCache, roots, leafs)

        assertThat(focusResult.deps()).containsExactly(cat, dog)
        assertThat(focusResult.rdeps()).isEmpty()
        assertThat(graph.values.keySet()).containsExactly(cat, dog)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun testFocus_dropsUnreachableFromRoots() {
        val graph: InMemoryGraph = skyframeExecutor.getEvaluator().getInMemoryGraph()
        val cat: SkyKey =
            com.google.devtools.build.lib.skyframe.SkyframeFocuserTest.SkyKeyWithSkyKeyInterner.Companion.create("cat")
        val dog: SkyKey =
            com.google.devtools.build.lib.skyframe.SkyframeFocuserTest.SkyKeyWithSkyKeyInterner.Companion.create("dog")
        val keys: com.google.common.collect.ImmutableList<SkyKey?> =
            com.google.common.collect.ImmutableList.of<SkyKey?>(cat, dog)

        graph.createIfAbsentBatch(null, Reason.OTHER, keys)
        createEdgesAndMarkDone(
            graph,
            cat,
            com.google.common.collect.ImmutableList.of<SkyKey?>(),
            com.google.common.collect.ImmutableList.of<SkyKey?>()
        )
        createEdgesAndMarkDone(
            graph,
            dog,
            com.google.common.collect.ImmutableList.of<SkyKey?>(),
            com.google.common.collect.ImmutableList.of<SkyKey?>()
        )

        val roots: MutableSet<SkyKey?> = com.google.common.collect.Sets.newHashSet(cat)
        val leafs: MutableSet<SkyKey?> = com.google.common.collect.Sets.newHashSet<SkyKey?>()

        val focusResult: FocusResult = SkyframeFocuser.focus(graph, mockActionCache, roots, leafs)

        assertThat(focusResult.deps()).containsExactly(cat)
        assertThat(focusResult.rdeps()).isEmpty()
        assertThat(graph.values.keySet()).containsExactly(cat)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun testFocus_keepDirectDepsOfRdepTransitiveClosure() {
        val graph: InMemoryGraph = skyframeExecutor.getEvaluator().getInMemoryGraph()
        val cat: SkyKey =
            com.google.devtools.build.lib.skyframe.SkyframeFocuserTest.SkyKeyWithSkyKeyInterner.Companion.create("cat")
        val dog: SkyKey =
            com.google.devtools.build.lib.skyframe.SkyframeFocuserTest.SkyKeyWithSkyKeyInterner.Companion.create("dog")
        val civet: SkyKey =
            com.google.devtools.build.lib.skyframe.SkyframeFocuserTest.SkyKeyWithSkyKeyInterner.Companion.create("civet")
        val hamster: SkyKey =
            com.google.devtools.build.lib.skyframe.SkyframeFocuserTest.SkyKeyWithSkyKeyInterner.Companion.create("hamster")
        val fish: SkyKey =
            com.google.devtools.build.lib.skyframe.SkyframeFocuserTest.SkyKeyWithSkyKeyInterner.Companion.create("fish")
        val bird: SkyKey =
            com.google.devtools.build.lib.skyframe.SkyframeFocuserTest.SkyKeyWithSkyKeyInterner.Companion.create("bird")
        val monkey: SkyKey =
            com.google.devtools.build.lib.skyframe.SkyframeFocuserTest.SkyKeyWithSkyKeyInterner.Companion.create("monkey")
        val keys: com.google.common.collect.ImmutableList<SkyKey?> =
            com.google.common.collect.ImmutableList.of<SkyKey?>(cat, dog, civet, hamster, fish, bird, monkey)
        graph.createIfAbsentBatch(null, Reason.OTHER, keys)

        // Graph:
        //
        // monkey (isolated)
        //
        //    /-> fish -> bird
        // cat -> dog -> civet*
        //          \-> hamster
        //
        // *Only civet in the active directories.
        createEdgesAndMarkDone(
            graph,
            civet,
            com.google.common.collect.ImmutableList.of<SkyKey?>(),
            com.google.common.collect.ImmutableList.of<SkyKey?>(dog)
        )
        createEdgesAndMarkDone(
            graph,
            hamster,
            com.google.common.collect.ImmutableList.of<SkyKey?>(),
            com.google.common.collect.ImmutableList.of<SkyKey?>(dog)
        )
        createEdgesAndMarkDone(
            graph,
            dog,
            com.google.common.collect.ImmutableList.of<SkyKey?>(civet, hamster),
            com.google.common.collect.ImmutableList.of<SkyKey?>(cat)
        )
        createEdgesAndMarkDone(
            graph,
            bird,
            com.google.common.collect.ImmutableList.of<SkyKey?>(),
            com.google.common.collect.ImmutableList.of<SkyKey?>(fish)
        )
        createEdgesAndMarkDone(
            graph,
            fish,
            com.google.common.collect.ImmutableList.of<SkyKey?>(bird),
            com.google.common.collect.ImmutableList.of<SkyKey?>(cat)
        )
        createEdgesAndMarkDone(
            graph,
            cat,
            com.google.common.collect.ImmutableList.of<SkyKey?>(dog, fish),
            com.google.common.collect.ImmutableList.of<SkyKey?>()
        )
        createEdgesAndMarkDone(
            graph,
            monkey,
            com.google.common.collect.ImmutableList.of<SkyKey?>(),
            com.google.common.collect.ImmutableList.of<SkyKey?>()
        )

        val roots: MutableSet<SkyKey?> = com.google.common.collect.Sets.newHashSet(cat)
        val leafs: MutableSet<SkyKey?> = com.google.common.collect.Sets.newHashSet(civet)

        val focusResult: FocusResult = SkyframeFocuser.focus(graph, mockActionCache, roots, leafs)

        assertThat(focusResult.deps()).containsExactly(hamster, fish)
        assertThat(focusResult.rdeps()).containsExactly(civet, dog, cat)

        // no monkey (isolated) and bird (indirect dep)
        assertThat(graph.values.keySet()).containsExactly(hamster, fish, civet, dog, cat)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun testFocus_removeActionCacheEntries() {
        val graph: InMemoryGraph = skyframeExecutor.getEvaluator().getInMemoryGraph()
        val cat: SkyKey =
            com.google.devtools.build.lib.skyframe.SkyframeFocuserTest.SkyKeyWithSkyKeyInterner.Companion.create("cat")
        val dog: SkyKey =
            com.google.devtools.build.lib.skyframe.SkyframeFocuserTest.SkyKeyWithSkyKeyInterner.Companion.create("dog")
        val hamster: SkyKey =
            com.google.devtools.build.lib.skyframe.SkyframeFocuserTest.SkyKeyWithSkyKeyInterner.Companion.create("hamster")
        val keys: com.google.common.collect.ImmutableList<SkyKey?> =
            com.google.common.collect.ImmutableList.of<SkyKey?>(cat, dog, hamster)
        graph.createIfAbsentBatch(null, Reason.OTHER, keys)

        val artifactRoot: ArtifactRoot? =
            ArtifactRoot.asDerivedRoot(
                this.directories.getExecRoot("workspace"), RootType.OUTPUT, "blaze-out"
            )

        val catAction: Action = NullAction(createArtifact(artifactRoot, "cat"))
        val dogAction: Action = NullAction(createArtifact(artifactRoot, "dog"))
        val hamsterAction: Action = NullAction(createArtifact(artifactRoot, "hamster"))

        createEdgesAndMarkDone(
            graph,
            cat,
            com.google.common.collect.ImmutableList.of<SkyKey?>(),
            com.google.common.collect.ImmutableList.of<SkyKey?>(),
            BasicActionLookupValue(com.google.common.collect.ImmutableList.of<E?>(catAction))
        )
        createEdgesAndMarkDone(
            graph,
            dog,
            com.google.common.collect.ImmutableList.of<SkyKey?>(),
            com.google.common.collect.ImmutableList.of<SkyKey?>(hamster),
            BasicActionLookupValue(com.google.common.collect.ImmutableList.of<E?>(dogAction))
        )
        createEdgesAndMarkDone(
            graph,
            hamster,
            com.google.common.collect.ImmutableList.of<SkyKey?>(dog),
            com.google.common.collect.ImmutableList.of<SkyKey?>(),
            BasicActionLookupValue(com.google.common.collect.ImmutableList.of<E?>(hamsterAction))
        )

        val roots: MutableSet<SkyKey?> = com.google.common.collect.Sets.newHashSet(hamster)
        val leafs: MutableSet<SkyKey?> = com.google.common.collect.Sets.newHashSet(dog)

        val unused: FocusResult? = SkyframeFocuser.focus(graph, mockActionCache, roots, leafs)

        Mockito.verify<Any?>(mockActionCache).remove(catAction.getPrimaryOutput().getExecPathString())
        Mockito.verify<Any?>(mockActionCache, Mockito.never()).remove(dogAction.getPrimaryOutput().getExecPathString())
        Mockito.verify<Any?>(mockActionCache, Mockito.never())
            .remove(hamsterAction.getPrimaryOutput().getExecPathString())
    }

    private class SkyKeyWithSkyKeyInterner(arg: String?) : AbstractSkyKey<String?>(arg) {
        public override fun functionName(): SkyFunctionName {
            return SkyFunctionName.FOR_TESTING
        }

        val skyKeyInterner: SkyKeyInterner<SkyKeyWithSkyKeyInterner?>
            get() = com.google.devtools.build.lib.skyframe.SkyframeFocuserTest.SkyKeyWithSkyKeyInterner.Companion.interner

        companion object {
            private val interner: SkyKeyInterner<SkyKeyWithSkyKeyInterner?> = SkyKey.newInterner()

            fun create(arg: String?): SkyKeyWithSkyKeyInterner {
                return com.google.devtools.build.lib.skyframe.SkyframeFocuserTest.SkyKeyWithSkyKeyInterner.Companion.interner.intern(
                    com.google.devtools.build.lib.skyframe.SkyframeFocuserTest.SkyKeyWithSkyKeyInterner(arg)
                )
            }
        }
    }

    companion object {
        @Throws(java.lang.InterruptedException::class)
        private fun createEdgesAndMarkDone(
            graph: InMemoryGraph,
            k: SkyKey?,
            deps: com.google.common.collect.ImmutableList<SkyKey?>,
            rdeps: com.google.common.collect.ImmutableList<SkyKey?>
        ) {
            createEdgesAndMarkDone(graph, k, deps, rdeps, GraphTester.StringValue("unused"))
        }

        // Create dep and rdep edges for a node, and ensures that it's marked done.
        @Throws(java.lang.InterruptedException::class)
        private fun createEdgesAndMarkDone(
            graph: InMemoryGraph,
            k: SkyKey?,
            deps: com.google.common.collect.ImmutableList<SkyKey?>,
            rdeps: com.google.common.collect.ImmutableList<SkyKey?>,
            value: SkyValue?
        ) {
            val entry: NodeEntry = graph.getIfPresent(k)
            assertThat(entry).isNotNull()
            if (rdeps.isEmpty()) {
                entry.addReverseDepAndCheckIfDone(null)
            } else {
                for (rdep in rdeps) {
                    entry.addReverseDepAndCheckIfDone(rdep)
                }
            }
            entry.markRebuilding()
            for (dep in deps) {
                entry.addSingletonTemporaryDirectDep(dep)
                entry.signalDep(Version.constant(), dep)
            }
            entry.setValue(value, Version.constant(), null)
        }
    }
}

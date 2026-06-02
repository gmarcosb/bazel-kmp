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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.actions.ArtifactRoot.RootType

/** Tests for [MapBasedActionGraph].  */
@RunWith(JUnit4::class)
class MapBasedActionGraphTest {
    private val fileSystem: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
    private val actionKeyContext: ActionKeyContext = ActionKeyContext()

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSmoke() {
        val actionGraph: MutableActionGraph = MapBasedActionGraph(actionKeyContext)
        val execRoot: Path = fileSystem.getPath("/")
        val outSegment = "root"
        val root: Path = execRoot.getChild(outSegment)
        var path: Path? = root.getRelative("foo")
        var output: Artifact =
            createArtifact(
                ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, outSegment), path
            )
        val action: Action =
            TestAction(
                TestAction.Companion.NO_EFFECT,
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                com.google.common.collect.ImmutableSet.of<E?>(output)
            )
        actionGraph.registerAction(action)
        path = root.getRelative("bar")
        output =
            createArtifact(
                ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, outSegment), path
            )
        val action2: Action =
            TestAction(
                TestAction.Companion.NO_EFFECT,
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                com.google.common.collect.ImmutableSet.of<E?>(output)
            )
        actionGraph.registerAction(action)
        actionGraph.registerAction(action2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoActionConflictWhenUnregisteringSharedAction() {
        val actionGraph: MutableActionGraph = MapBasedActionGraph(actionKeyContext)
        val execRoot: Path? = fileSystem.getPath("/")
        val root: Path = fileSystem.getPath("/root")
        val path: Path? = root.getRelative("foo")
        val output: Artifact =
            createArtifact(
                ArtifactRoot.asDerivedRoot(
                    execRoot, RootType.OUTPUT, root.relativeTo(execRoot).getPathString()
                ),
                path
            )
        val action: Action =
            TestAction(
                TestAction.Companion.NO_EFFECT,
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                com.google.common.collect.ImmutableSet.of<E?>(output)
            )
        actionGraph.registerAction(action)
        val otherAction: Action =
            TestAction(
                TestAction.Companion.NO_EFFECT,
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                com.google.common.collect.ImmutableSet.of<E?>(output)
            )
        actionGraph.registerAction(otherAction)
    }

    private inner class ActionRegisterer : AbstractQueueVisitor(
        200,
        1,
        TimeUnit.SECONDS,
        ExceptionHandlingMode.FAIL_FAST,
        "action-graph-test",
        ErrorClassifier.DEFAULT
    ) {
        private val graph: MutableActionGraph = MapBasedActionGraph(ActionKeyContext())
        private val output: Artifact

        // Just to occasionally add actions that were already present.
        private val allActions: MutableSet<Action?> = com.google.common.collect.Sets.newConcurrentHashSet<Action?>()
        private val actionCount: AtomicInteger = AtomicInteger(0)

        init {
            val execRoot: Path = fileSystem.getPath("/")
            val rootSegment = "root"
            val root: Path = execRoot.getChild(rootSegment)
            val path: Path? = root.getChild("foo")
            output =
                createArtifact(
                    ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, rootSegment), path
                )
            allActions.add(
                TestAction(
                    TestAction.Companion.NO_EFFECT,
                    NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                    com.google.common.collect.ImmutableSet.of<E?>(output)
                )
            )
        }

        fun registerAction(action: Action?) {
            execute(
                java.lang.Runnable {
                    try {
                        graph.registerAction(action)
                    } catch (e: ActionConflictException) {
                        throw UncheckedActionConflictException(e)
                    } catch (e: java.lang.InterruptedException) {
                        java.lang.Thread.currentThread().interrupt()
                        throw java.lang.IllegalStateException("Interrupts not expected in this test")
                    }
                    doRandom()
                })
        }

        fun doRandom() {
            if (actionCount.incrementAndGet() > 10000) {
                return
            }
            val action: Action?
            if (java.lang.Math.random() < 0.5) {
                action = com.google.common.collect.Iterables.getFirst<Action?>(allActions, null)
            } else {
                action =
                    TestAction(
                        TestAction.Companion.NO_EFFECT,
                        NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                        com.google.common.collect.ImmutableSet.of<E?>(output)
                    )
                allActions.add(action)
            }
            registerAction(action)
        }

        @Throws(java.lang.InterruptedException::class)
        fun work() {
            awaitQuiescence( /*interruptWorkers=*/true)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSharedActionStressTest() {
        val actionRegisterer = ActionRegisterer()
        actionRegisterer.doRandom()
        actionRegisterer.work()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionConflictShowsIsShareable() {
        val actionGraph: MutableActionGraph = MapBasedActionGraph(actionKeyContext)
        val execRoot: Path = fileSystem.getPath("/")
        val outSegment = "root"
        val root: Path = execRoot.getChild(outSegment)
        val path: Path? = root.getRelative("foo")
        val output: Artifact =
            createArtifact(
                ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, outSegment), path
            )

        val action1: Action =
            object : TestAction(
                TestAction.Companion.NO_EFFECT,
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                com.google.common.collect.ImmutableSet.of<E?>(output)
            ) {
                public override fun isShareable(): Boolean {
                    return true
                }
            }
        actionGraph.registerAction(action1)

        val action2: Action =
            object : TestAction(
                TestAction.Companion.NO_EFFECT,
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                com.google.common.collect.ImmutableSet.of<E?>(output)
            ) {
                public override fun isShareable(): Boolean {
                    return false
                }
            }

        val thrown: T? =
            org.junit.Assert.assertThrows<T?>(
                ActionConflictException::class.java,
                org.junit.function.ThrowingRunnable { actionGraph.registerAction(action2) })
        assertThat(thrown).hasMessageThat().containsMatch("IsShareable: false, true")
    }
}

// Copyright 2016 The Bazel Authors. All rights reserved.
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

/**
 * Test the behavior of ActionOutputMetadataStore and ArtifactFunction with respect to
 * TreeArtifacts.
 */
@RunWith(JUnit4::class)
class TreeArtifactMetadataTest : ArtifactFunctionTestCase() {
    // A list of subpaths for the SetArtifact created by our custom ActionExecutionFunction.
    private var testTreeArtifactContents: MutableList<PathFragment?>? = null

    @Before
    fun setUp() {
        delegateActionExecutionFunction = TreeArtifactExecutionFunction()
    }

    @Throws(java.lang.Exception::class)
    private fun evaluateTreeArtifact(
        treeArtifact: Artifact, children: Iterable<PathFragment>
    ): TreeArtifactValue {
        testTreeArtifactContents = com.google.common.collect.ImmutableList.copyOf<PathFragment?>(children)
        for (child in children) {
            file(treeArtifact.getPath().getRelative(child), child.toString())
        }
        return evaluateArtifactValue(treeArtifact) as TreeArtifactValue
    }

    @Throws(java.lang.Exception::class)
    private fun doTestTreeArtifacts(children: Iterable<PathFragment>): TreeArtifactValue {
        val output: SpecialArtifact = createTreeArtifact("output")
        return doTestTreeArtifacts(output, children)
    }

    @Throws(java.lang.Exception::class)
    private fun doTestTreeArtifacts(
        tree: SpecialArtifact, children: Iterable<PathFragment>
    ): TreeArtifactValue {
        val value: TreeArtifactValue = evaluateTreeArtifact(tree, children)
        assertThat(value.getChildPaths()).containsExactlyElementsIn(com.google.common.collect.ImmutableSet.< E > copyOf < E ? > (children))
        assertThat(value.getChildren())
            .containsExactlyElementsIn(
                com.google.common.collect.Iterables.transform<F?, T?>(
                    children,
                    com.google.common.base.Function { child: F? -> TreeFileArtifact.createTreeOutput(tree, child) })
            )
        return value
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyTreeArtifacts() {
        val value: TreeArtifactValue = doTestTreeArtifacts(com.google.common.collect.ImmutableList.of<PathFragment?>())
        // Additional test, only for this test method: we expect the FileArtifactValue is equal to
        // the digest [0]
        assertThat(value.getMetadata().getDigest()).isEqualTo(value.getDigest())
        // Java zero-fills arrays.
        assertThat(value.getDigest()).isEqualTo(ByteArray(1))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTreeArtifactOrdering() {
        val rangeSize = 100
        val attempts = 10
        val children: MutableList<PathFragment> =
            IntStream.range(0, rangeSize)
                .mapToObj<Any?>(java.util.function.IntFunction { i: Int -> PathFragment.create("file" + i) })
                .collect(Collectors.toList())

        for (i in 0..<attempts) {
            Collections.shuffle(children, Random())
            val treeArtifact: Artifact = createTreeArtifact("out")
            val value: TreeArtifactValue = evaluateTreeArtifact(treeArtifact, children)
            assertThat(value.getChildPaths()).containsExactlyElementsIn(children)
            assertThat(value.getChildPaths()).isInOrder(java.util.Comparator.naturalOrder<T?>())
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEqualTreeArtifacts() {
        val treeArtifact: Artifact = createTreeArtifact("out")
        val children: com.google.common.collect.ImmutableList<PathFragment> =
            com.google.common.collect.ImmutableList.of<E>(PathFragment.create("one"), PathFragment.create("two"))
        val valueOne: TreeArtifactValue = evaluateTreeArtifact(treeArtifact, children)
        // Delete action execution node to force our artifacts to be re-evaluated.
        evaluator.delete({ key -> actions.contains(key.argument()) })
        val valueTwo: TreeArtifactValue = evaluateTreeArtifact(treeArtifact, children)
        assertThat(valueOne.getDigest()).isNotSameInstanceAs(valueTwo.getDigest())
        assertThat(valueOne).isEqualTo(valueTwo)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTreeArtifactsWithDigests() {
        fastDigest = true
        doTestTreeArtifacts(com.google.common.collect.ImmutableList.of<E?>(PathFragment.create("one")))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTreeArtifactsWithoutDigests() {
        fastDigest = false
        doTestTreeArtifacts(com.google.common.collect.ImmutableList.of<E?>(PathFragment.create("one")))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTreeArtifactMultipleDigests() {
        doTestTreeArtifacts(
            com.google.common.collect.ImmutableList.of<E?>(
                PathFragment.create("one"),
                PathFragment.create("two")
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIdenticalTreeArtifactsProduceTheSameDigests() {
        // Make sure different root dirs for set artifacts don't produce different digests.
        val one: Artifact = createTreeArtifact("outOne")
        val two: Artifact = createTreeArtifact("outTwo")
        val children: com.google.common.collect.ImmutableList<PathFragment> =
            com.google.common.collect.ImmutableList.of<E>(PathFragment.create("one"), PathFragment.create("two"))
        val valueOne: TreeArtifactValue = evaluateTreeArtifact(one, children)
        val valueTwo: TreeArtifactValue = evaluateTreeArtifact(two, children)
        assertThat(valueOne.getDigest()).isEqualTo(valueTwo.getDigest())
    }

    /**
     * Tests that ArtifactFunction rethrows transitive [IOException]s as [ ]s.
     */
    @org.junit.Test
    @Throws(Throwable::class)
    fun testIOExceptionEndToEnd() {
        val exception: IOException = IOException("boop")
        setupRoot(
            object : CustomInMemoryFs() {
                @Throws(IOException::class)
                public override fun statIfFound(path: PathFragment, followSymlinks: Boolean): FileStatus {
                    if (path.getBaseName().equals("one")) {
                        throw exception
                    }
                    return super.statIfFound(path, followSymlinks)
                }
            })
        val artifact: Artifact = createTreeArtifact("outOne")
        val e: java.lang.Exception =
            org.junit.Assert.assertThrows<java.lang.Exception>(
                java.lang.Exception::class.java,
                org.junit.function.ThrowingRunnable {
                    evaluateTreeArtifact(
                        artifact,
                        com.google.common.collect.ImmutableList.of<E?>(PathFragment.create("one"))
                    )
                })
        Truth.assertThat(com.google.common.base.Throwables.getRootCause(e)).hasMessageThat().contains(exception.message)
    }

    @Throws(IOException::class)
    private fun createTreeArtifact(path: String?): SpecialArtifact {
        val execPath: PathFragment? = PathFragment.create("out").getRelative(path)
        val fullPath: Path = root.getRelative(execPath)
        val output: SpecialArtifact =
            SpecialArtifact.create(
                ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, "out"),
                execPath,
                ALL_OWNER,
                SpecialArtifactType.TREE
            )
        actions.add(DummyAction(NestedSetBuilder.emptySet(Order.STABLE_ORDER), output))
        fullPath.createDirectoryAndParents()
        return output
    }

    @Throws(java.lang.Exception::class)
    private fun evaluateArtifactValue(artifact: Artifact?): SkyValue {
        val key: SkyKey? = Artifact.key(artifact)
        val result: EvaluationResult<SkyValue?> = evaluate<SkyValue?>(key)
        if (result.hasError()) {
            throw result.getError().getException()
        }
        return result.get(key)
    }

    @Throws(
        java.lang.InterruptedException::class,
        ActionConflictException::class,
        Actions.ArtifactGeneratedByOtherRuleException::class
    )
    private fun setGeneratingActions() {
        if (evaluator.getExistingValue(ALL_OWNER) == null) {
            val generatingActions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?> =
                com.google.common.collect.ImmutableList.copyOf(actions)
            Actions.assignOwnersAndThrowIfConflictToleratingSharedActions(
                actionKeyContext, generatingActions, ALL_OWNER
            )
            differencer.inject(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    ALL_OWNER,
                    Delta.justNew(BasicActionLookupValue(generatingActions))
                )
            )
        }
    }

    @Throws(
        java.lang.InterruptedException::class,
        ActionConflictException::class,
        Actions.ArtifactGeneratedByOtherRuleException::class
    )
    private fun <E : SkyValue?> evaluate(vararg keys: SkyKey?): EvaluationResult<E?> {
        setGeneratingActions()
        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setParallelism(SkyframeExecutor.DEFAULT_THREAD_COUNT)
                .setEventHandler(NullEventHandler.INSTANCE)
                .build()
        return evaluator.evaluate(java.util.Arrays.< T > asList < T ? > (keys), evaluationContext)
    }

    private inner class TreeArtifactExecutionFunction : SkyFunction {
        @Throws(SkyFunctionException::class, java.lang.InterruptedException::class)
        public override fun compute(skyKey: SkyKey, env: Environment): SkyValue {
            val actionLookupData: ActionLookupData = skyKey.argument() as ActionLookupData
            val actionLookupValue: ActionLookupValue =
                env.getValue(actionLookupData.getActionLookupKey()) as ActionLookupValue
            val action: Action = actionLookupValue.getAction(actionLookupData.getActionIndex())
            val output: SpecialArtifact? =
                com.google.common.collect.Iterables.getOnlyElement<T?>(action.getOutputs()) as SpecialArtifact?
            val tree: TreeArtifactValue.Builder = TreeArtifactValue.newBuilder(output)
            for (subpath in testTreeArtifactContents!!) {
                try {
                    val suboutput: TreeFileArtifact = TreeFileArtifact.createTreeOutput(output, subpath)
                    val path: Path = suboutput.getPath()
                    val noDigest: FileArtifactValue? =
                        ActionOutputMetadataStore.fileArtifactValueFromArtifact(
                            suboutput,
                            FileStatusWithDigestAdapter.maybeAdapt(path.statIfFound(Symlinks.NOFOLLOW)),
                            SyscallCache.NO_CACHE,
                            null
                        )
                    val withDigest: FileArtifactValue? =
                        FileArtifactValue.createFromInjectedDigest(noDigest, path.getDigest())
                    tree.putChild(suboutput, withDigest)
                } catch (e: IOException) {
                    throw object : SkyFunctionException(e, Transience.TRANSIENT) {}
                }
            }

            return ActionsTestUtil.createActionExecutionValue( /* artifactData= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(output, tree.build())
            )
        }
    }

    companion object {
        @Throws(java.lang.Exception::class)
        private fun file(path: Path, contents: String?) {
            path.getParentDirectory().createDirectoryAndParents()
            writeFile(path, contents)
        }
    }
}

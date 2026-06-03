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

import com.google.devtools.build.lib.actions.Artifact

/**
 * Test suite for TimestampBuilder.
 * 
 */
@RunWith(JUnit4::class)
class TimestampBuilderTest : TimestampBuilderTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAmnesiacBuilderAlwaysRebuilds() {
        // [action] -> hello
        val hello: Artifact = createDerivedArtifact("hello")
        val button: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button = createActionButton(
            TimestampBuilderTestCase.Companion.emptyNestedSet,
            com.google.common.collect.ImmutableSet.of<Artifact?>(hello)
        )

        button.pressed = false
        buildArtifacts(amnesiacBuilder(), hello)
        Truth.assertThat(button.pressed).isTrue() // built

        button.pressed = false
        buildArtifacts(amnesiacBuilder(), hello)
        Truth.assertThat(button.pressed).isTrue() // rebuilt
    }

    // If we re-use the same builder (even an "amnesiac" builder), it remembers
    // which Actions it has already visited, and doesn't revisit them, even if
    // they would otherwise be rebuilt.
    //
    // That is, Builders conflate traversal and dependency analysis, and don't
    // revisit a node (traversal) even if it needs to be rebuilt (dependency
    // analysis).  We might want to separate these aspects.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuilderDoesntRevisitActions() {
        // [action] -> hello
        val hello: Artifact = createDerivedArtifact("hello")
        val counter: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Counter = createActionCounter(
            TimestampBuilderTestCase.Companion.emptyNestedSet,
            com.google.common.collect.ImmutableSet.of<Artifact?>(hello)
        )

        val amnesiacBuilder: Builder? = amnesiacBuilder()

        counter.count = 0
        buildArtifacts(amnesiacBuilder, hello, hello)
        Truth.assertThat(counter.count).isEqualTo(1) // built only once
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuildingExistingSourcefileSuceeds() {
        val hello: Artifact = createSourceArtifact("hello")
        BlazeTestUtils.makeEmptyFile(hello.getPath())
        buildArtifacts(cachingBuilder(), hello)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCachingBuilderCachesUntilReset() {
        // [action] -> hello
        val hello: Artifact = createDerivedArtifact("hello")
        val button: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button = createActionButton(
            TimestampBuilderTestCase.Companion.emptyNestedSet,
            com.google.common.collect.ImmutableSet.of<Artifact?>(hello)
        )

        button.pressed = false
        buildArtifacts(cachingBuilder(), hello)
        Truth.assertThat(button.pressed).isTrue() // built

        button.pressed = false
        buildArtifacts(cachingBuilder(), hello)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt

        inMemoryCache.reset()

        button.pressed = false
        buildArtifacts(cachingBuilder(), hello)
        Truth.assertThat(button.pressed).isTrue() // rebuilt
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnneededInputs() {
        val hello: Artifact = createSourceArtifact("hello")
        hello.getPath().getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContentAsLatin1(hello.getPath(), "content1")
        val optional: Artifact = createSourceArtifact("hello.optional")
        val goodbye: Artifact = createDerivedArtifact("goodbye")
        val button: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button = createActionButton(
            asNestedSet(hello, optional), com.google.common.collect.ImmutableSet.of<Artifact?>(goodbye)
        )

        button.pressed = false
        buildArtifacts(cachingBuilder(), goodbye)
        Truth.assertThat(button.pressed).isTrue() // built

        button.pressed = false
        buildArtifacts(cachingBuilder(), goodbye)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt

        BlazeTestUtils.makeEmptyFile(optional.getPath())
        FileSystemUtils.writeContentAsLatin1(hello.getPath(), "content2")

        button.pressed = false
        buildArtifacts(cachingBuilder(), goodbye)
        Truth.assertThat(button.pressed).isTrue() // built

        button.pressed = false
        buildArtifacts(cachingBuilder(), goodbye)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt

        optional.getPath().delete()
        FileSystemUtils.writeContentAsLatin1(hello.getPath(), "content3")

        button.pressed = false
        buildArtifacts(cachingBuilder(), goodbye)
        Truth.assertThat(button.pressed).isTrue() // built

        button.pressed = false
        buildArtifacts(cachingBuilder(), goodbye)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testModifyingInputCausesActionReexecution() {
        // hello -> [action] -> goodbye
        val hello: Artifact = createSourceArtifact("hello")
        BlazeTestUtils.makeEmptyFile(hello.getPath())
        val goodbye: Artifact = createDerivedArtifact("goodbye")
        val button: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button = createActionButton(
            asNestedSet(hello), com.google.common.collect.ImmutableSet.of<Artifact?>(goodbye)
        )

        button.pressed = false
        buildArtifacts(cachingBuilder(), goodbye)
        Truth.assertThat(button.pressed).isTrue() // built

        button.pressed = false
        buildArtifacts(cachingBuilder(), goodbye)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt

        hello.getPath().setWritable(true)
        FileSystemUtils.writeContentAsLatin1(hello.getPath(), "new content")

        button.pressed = false
        buildArtifacts(cachingBuilder(), goodbye)
        Truth.assertThat(button.pressed).isTrue() // rebuilt

        button.pressed = false
        buildArtifacts(cachingBuilder(), goodbye)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOnlyModifyingInputContentCausesReexecution() {
        // hello -> [action] -> goodbye
        val hello: Artifact = createSourceArtifact("hello")
        // touch file to create the directory structure
        BlazeTestUtils.makeEmptyFile(hello.getPath())
        FileSystemUtils.writeContentAsLatin1(hello.getPath(), "content1")

        val goodbye: Artifact = createDerivedArtifact("goodbye")
        val button: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button = createActionButton(
            asNestedSet(hello), com.google.common.collect.ImmutableSet.of<Artifact?>(goodbye)
        )

        button.pressed = false
        buildArtifacts(cachingBuilder(), goodbye)
        Truth.assertThat(button.pressed).isTrue() // built

        button.pressed = false
        buildArtifacts(cachingBuilder(), goodbye)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt

        FileSystemUtils.touchFile(hello.getPath())

        button.pressed = false
        buildArtifacts(cachingBuilder(), goodbye)
        Truth.assertThat(button.pressed).isFalse() // still not rebuilt

        FileSystemUtils.writeContentAsLatin1(hello.getPath(), "content2")

        button.pressed = false
        buildArtifacts(cachingBuilder(), goodbye)
        Truth.assertThat(button.pressed).isTrue() // rebuilt

        button.pressed = false
        buildArtifacts(cachingBuilder(), goodbye)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testModifyingOutputCausesActionReexecution() {
        // [action] -> hello
        val hello: Artifact = createDerivedArtifact("hello")
        val button: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button = createActionButton(
            TimestampBuilderTestCase.Companion.emptyNestedSet,
            com.google.common.collect.ImmutableSet.of<Artifact?>(hello)
        )

        button.pressed = false
        buildArtifacts(cachingBuilder(), hello)
        Truth.assertThat(button.pressed).isTrue() // built

        button.pressed = false
        buildArtifacts(cachingBuilder(), hello)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt

        // Changing the *output* file 'hello' causes 'action' to re-execute, to make things consistent
        // again.
        hello.getPath().setWritable(true)
        FileSystemUtils.writeContentAsLatin1(hello.getPath(), "new content")

        button.pressed = false
        buildArtifacts(cachingBuilder(), hello)
        Truth.assertThat(button.pressed).isTrue() // rebuilt

        button.pressed = false
        buildArtifacts(cachingBuilder(), hello)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuildingTransitivePrerequisites() {
        // hello -> [action1] -> wazuup -> [action2] -> goodbye
        val hello: Artifact = createSourceArtifact("hello")
        BlazeTestUtils.makeEmptyFile(hello.getPath())
        val wazuup: Artifact = createDerivedArtifact("wazuup")
        val button1: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button =
            com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button()
        registerAction<T?>(CopyingAction(button1, hello, wazuup))
        val goodbye: Artifact = createDerivedArtifact("goodbye")
        val button2: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button = createActionButton(
            asNestedSet(wazuup), com.google.common.collect.ImmutableSet.of<Artifact?>(goodbye)
        )

        button2.pressed = false
        button1.pressed = button2.pressed
        buildArtifacts(cachingBuilder(), wazuup)
        Truth.assertThat(button1.pressed).isTrue() // built wazuup
        Truth.assertThat(button2.pressed).isFalse() // goodbye not built

        button2.pressed = false
        button1.pressed = button2.pressed
        buildArtifacts(cachingBuilder(), wazuup)
        Truth.assertThat(button1.pressed).isFalse() // wazuup not rebuilt
        Truth.assertThat(button2.pressed).isFalse() // goodbye not built

        button2.pressed = false
        button1.pressed = button2.pressed
        buildArtifacts(cachingBuilder(), goodbye)
        Truth.assertThat(button1.pressed).isFalse() // wazuup not rebuilt
        Truth.assertThat(button2.pressed).isTrue() // built goodbye

        button2.pressed = false
        button1.pressed = button2.pressed
        buildArtifacts(cachingBuilder(), goodbye)
        Truth.assertThat(button1.pressed).isFalse() // wazuup not rebuilt
        Truth.assertThat(button2.pressed).isFalse() // goodbye not rebuilt

        hello.getPath().setWritable(true)
        FileSystemUtils.writeContentAsLatin1(hello.getPath(), "new content")

        button2.pressed = false
        button1.pressed = button2.pressed
        buildArtifacts(cachingBuilder(), goodbye)
        Truth.assertThat(button1.pressed).isTrue() // hello rebuilt
        Truth.assertThat(button2.pressed).isTrue() // goodbye rebuilt
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWillNotRebuildActionsWithEmptyListOfInputsSpuriously() {
        val anOutputFile: Artifact = createDerivedArtifact("anOutputFile")
        val anotherOutputFile: Artifact = createDerivedArtifact("anotherOutputFile")

        val aButton: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button = createActionButton(
            TimestampBuilderTestCase.Companion.emptyNestedSet,
            com.google.common.collect.ImmutableSet.of<Artifact?>(anOutputFile)
        )
        val anotherButton: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button = createActionButton(
            TimestampBuilderTestCase.Companion.emptyNestedSet,
            com.google.common.collect.ImmutableSet.of<Artifact?>(anotherOutputFile)
        )

        buildArtifacts(cachingBuilder(), anOutputFile, anotherOutputFile)

        Truth.assertThat(aButton.pressed).isTrue()
        Truth.assertThat(anotherButton.pressed).isTrue()

        anotherButton.pressed = false
        aButton.pressed = anotherButton.pressed

        buildArtifacts(cachingBuilder(), anOutputFile, anotherOutputFile)

        Truth.assertThat(aButton.pressed).isFalse()
        Truth.assertThat(anotherButton.pressed).isFalse()
    }

    @org.junit.Test
    fun testMissingSourceFileIsAnError() {
        // A missing input to an action must be treated as an error because there's
        // a risk that the action that consumes it will succeed, but with a
        // different behavior (imagine that it globs over the directory, for
        // example).  It's not ok to simply try the action and let the action
        // report "input file not found".
        //
        // (However, there are exceptions to this principle: C++ compilation
        // actions may depend on non-existent headers from stale .d files.  We need
        // to allow the action to proceed to execution in this case.)

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        // doesn't exist
        val `in`: Artifact =
            SourceArtifact(
                ArtifactRoot.asSourceRoot(Root.fromPath(fileSystem.getPath("/src"))),
                PathFragment.create("in/in"),
                { Label.parseCanonicalUnchecked("//in:in") })
        val out: Artifact = createDerivedArtifact("out")

        registerAction<T?>(
            TestAction(
                TestAction.NO_EFFECT,
                asNestedSet(`in`),
                com.google.common.collect.ImmutableSet.of<Artifact>(out)
            )
        )

        val e: BuildFailedException? =
            org.junit.Assert.assertThrows<T?>(
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildArtifacts(amnesiacBuilder(), out) })
        assertThat(e).hasMessageThat().contains("1 input file(s) do not exist")
    }

    companion object {
        private fun asNestedSet(vararg artifacts: Artifact?): NestedSet<Artifact?> {
            return NestedSetBuilder.create(Order.STABLE_ORDER, artifacts)
        }
    }
}

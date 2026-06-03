// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.Label

/** Tests for [CallStack].  */
@RunWith(JUnit4::class)
class CallStackTest {
    @org.junit.Test
    fun emptyCallStack_null() {
        assertThat(CallStack.compact(com.google.common.collect.ImmutableList.of<E?>(), 0)).isNull()
        assertThat(CallStack.compact(com.google.common.collect.ImmutableList.of<E?>(), 1)).isNull()
        assertThat(CallStack.compact(com.google.common.collect.ImmutableList.of<E?>(), 42)).isNull()
    }

    @org.junit.Test
    fun singleFrameCallStack_nullInterior() {
        val stack: com.google.common.collect.ImmutableList<CallStackEntry?> =
            com.google.common.collect.ImmutableList.of<CallStackEntry?>(
                entryFromNameAndLocation(
                    StarlarkThread.TOP_LEVEL,
                    "BUILD",
                    10,
                    20
                )
            )

        val compacted: CallStack.Node = CallStack.compact(stack, 0)
        assertThat(compacted).isNotNull()
        assertThat(compacted.next()).isNull()
        assertThat(CallStack.compact(stack, 1)).isNull()
        assertThat(CallStack.compact(stack, 42)).isNull()
    }

    @org.junit.Test
    fun simpleCallStack() {
        val stack: com.google.common.collect.ImmutableList<CallStackEntry?> =
            com.google.common.collect.ImmutableList.of<CallStackEntry?>(
                entryFromNameAndLocation(StarlarkThread.TOP_LEVEL, "BUILD", 10, 20),
                entryFromNameAndLocation("func", "file.bzl", 20, 30)
            )

        val compacted0: CallStack.Node = CallStack.compact(stack, 0)
        val compacted1: CallStack.Node = CallStack.compact(stack, 1)
        assertThat(compacted0.next()).isEqualTo(compacted1)
        assertThat(compacted1.next()).isNull()
        assertCallStackContents(compacted0, stack, 0)
        assertCallStackContents(compacted1, stack, 1)
        assertThat(CallStack.compact(stack, 2)).isNull()
    }

    @org.junit.Test
    fun callStackWithLoops() {
        val loopEntry1: CallStackEntry? =
            entryFromNameAndLocation("loop1", "file1.bzl", 20, 30)
        val loopEntry2: CallStackEntry? =
            entryFromNameAndLocation("loop2", "file2.bzl", 30, 40)

        val stack: com.google.common.collect.ImmutableList<CallStackEntry?> =
            com.google.common.collect.ImmutableList.of<CallStackEntry?>(
                entryFromNameAndLocation(StarlarkThread.TOP_LEVEL, "BUILD", 10, 20),
                loopEntry1,
                loopEntry2,
                loopEntry1,
                loopEntry2
            )

        assertCallStackContents(CallStack.compact(stack, 1), stack, 1)
    }

    @org.junit.Test
    fun consecutiveCalls() {
        val stackBuilder: com.google.common.collect.ImmutableList.Builder<CallStackEntry?> =
            com.google.common.collect.ImmutableList.builder<CallStackEntry?>()
                .add(entryFromNameAndLocation(StarlarkThread.TOP_LEVEL, "BUILD", 1, 2))
                .add(entryFromNameAndLocation("f1", "f.bzl", 2, 3))
                .add(entryFromNameAndLocation("g1", "g.bzl", 3, 4))
        val stack1: com.google.common.collect.ImmutableList<CallStackEntry?> = stackBuilder.build()
        val stack2: com.google.common.collect.ImmutableList<CallStackEntry?> =
            stackBuilder.add(entryFromNameAndLocation("h1", "h.bzl", 4, 5)).build()

        assertCallStackContents(CallStack.compact(stack1, 1), stack1, 1)
        assertCallStackContents(CallStack.compact(stack2, 1), stack2, 1)
    }

    @org.junit.Test
    fun sharesCommonTail() {
        val stack1: com.google.common.collect.ImmutableList<CallStackEntry?> =
            com.google.common.collect.ImmutableList.of<CallStackEntry?>(
                entryFromNameAndLocation(StarlarkThread.TOP_LEVEL, "a/BUILD", 1, 2),
                entryFromNameAndLocation("java_library_macro", "java_library_macro.bzl", 2, 3),
                entryFromNameAndLocation("java_library", "java_library.bzl", 4, 5)
            )
        val stack2: com.google.common.collect.ImmutableList<CallStackEntry?> =
            com.google.common.collect.ImmutableList.of<CallStackEntry?>(
                entryFromNameAndLocation(StarlarkThread.TOP_LEVEL, "b/BUILD", 6, 7),
                entryFromNameAndLocation("java_library_macro", "java_library_macro.bzl", 2, 3),
                entryFromNameAndLocation("java_library", "java_library.bzl", 4, 5)
            )

        val optimizedInteriorStack1: CallStack.Node? = CallStack.compact(stack1, 1)
        val optimizedInteriorStack2: CallStack.Node = CallStack.compact(stack2, 1)
        val optimizedFullStack1: CallStack.Node = CallStack.compact(stack1, 0)
        val optimizedFullStack2: CallStack.Node = CallStack.compact(stack2, 0)

        assertCallStackContents(optimizedInteriorStack1, stack1, 1)
        assertCallStackContents(optimizedInteriorStack2, stack2, 1)
        assertCallStackContents(optimizedFullStack1, stack1, 0)
        assertCallStackContents(optimizedFullStack2, stack2, 0)
        assertThat(optimizedInteriorStack2.next().next())
            .isSameInstanceAs(optimizedInteriorStack2.next().next())
        assertThat(optimizedFullStack1.next()).isSameInstanceAs(optimizedFullStack2.next())
        assertThat(optimizedFullStack1.next()).isSameInstanceAs(optimizedInteriorStack1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun serialization() {
        val stackEntries1: com.google.common.collect.ImmutableList<CallStackEntry?> =
            com.google.common.collect.ImmutableList.of<CallStackEntry?>(
                entryFromNameAndLocation(StarlarkThread.TOP_LEVEL, "BUILD", 1, 2),
                entryFromNameAndLocation("somename", "f1.bzl", 1, 2),
                entryFromNameAndLocation("someOtherName", "f2.bzl", 2, 4),
                entryFromNameAndLocation("somename", "f1.bzl", 4, 2),
                entryFromNameAndLocation("somethingElse", "f3.bzl", 5, 6)
            )

        val stackEntries2: com.google.common.collect.ImmutableList<CallStackEntry?> =
            com.google.common.collect.ImmutableList.of<CallStackEntry?>(
                entryFromNameAndLocation(
                    StarlarkThread.TOP_LEVEL,
                    "BUILD",
                    9,
                    10
                )
            )

        val interiorStack1: CallStack.Node? = CallStack.compact(stackEntries1, 1)
        val interiorStack2: CallStack.Node? = CallStack.compact(stackEntries2, 1)
        val ruleClass: RuleClass = Mockito.mock<RuleClass>(RuleClass::class.java)
        Mockito.`when`<T?>(ruleClass.getAttributeProvider())
            .thenReturn(< T > mock < T ? > (AttributeProvider::class.java))
        val rule1: Rule =
            Rule(
                < T > mock < T ? > (java.lang.Package::class.java),
        Label.parseCanonicalUnchecked("//pkg:rule1"),
        ruleClass,
        stackEntries1.get(0).location,
        interiorStack1)
        val rule2: Rule =
            Rule(
                < T > mock < T ? > (java.lang.Package::class.java),
        Label.parseCanonicalUnchecked("//pkg:rule2"),
        ruleClass,
        stackEntries2.get(0).location,
        interiorStack2)

        val serializer: SerializationContext =
            ObjectCodecs().getMemoizingSerializationContextForTesting()
        val bytesOut: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val codedOut: CodedOutputStream = CodedOutputStream.newInstance(bytesOut)

        serializer.serialize(CallStack.getFullCallStack(rule1), codedOut)
        serializer.serialize(CallStack.getFullCallStack(rule2), codedOut)
        serializer.serialize(CallStack.getFullCallStack(rule1), codedOut)
        codedOut.flush()

        val deserializer: DeserializationContext =
            ObjectCodecs().getMemoizingDeserializationContextForTesting()
        val codedIn: CodedInputStream = CodedInputStream.newInstance(bytesOut.toByteArray())

        val deserializedCallStack1: CallStack.Node = deserializer.deserialize(codedIn)
        assertThat(deserializedCallStack1.toLocation()).isEqualTo(rule1.getLocation())
        assertCallStackContents(deserializedCallStack1.next(), stackEntries1, 1)

        val deserializedCallStack2: CallStack.Node = deserializer.deserialize(codedIn)
        assertThat(deserializedCallStack2.toLocation()).isEqualTo(rule2.getLocation())
        assertCallStackContents(deserializedCallStack2.next(), stackEntries2, 1)

        val deserializedCallStack1Again: CallStack.Node = deserializer.deserialize(codedIn)
        assertThat(deserializedCallStack1Again.toLocation()).isEqualTo(rule1.getLocation())
        assertCallStackContents(deserializedCallStack1Again.next(), stackEntries1, 1)
    }

    @org.junit.Test
    fun concatenate() {
        val outerStack: com.google.common.collect.ImmutableList<CallStackEntry?> =
            com.google.common.collect.ImmutableList.of<CallStackEntry?>(
                entryFromNameAndLocation(StarlarkThread.TOP_LEVEL, "BUILD", 10, 20),
                entryFromNameAndLocation("foo", "f.bzl", 1, 2),
                entryFromNameAndLocation("bar", "g.bzl", 3, 4)
            )
        val innerStack: com.google.common.collect.ImmutableList<CallStackEntry?> =
            com.google.common.collect.ImmutableList.of<CallStackEntry?>(
                entryFromNameAndLocation("baz", "h.bzl", 5, 6),
                entryFromNameAndLocation("qux", "g.bzl", 7, 8)
            )

        assertThat(CallStack.concatenate(null, null)).isNull()
        assertCallStackContents(
            CallStack.concatenate(CallStack.compact(outerStack, 1), null), outerStack, 1
        )
        assertCallStackContents(
            CallStack.concatenate(null, CallStack.compact(innerStack, 0)), innerStack, 0
        )
        assertCallStackContents(
            CallStack.concatenate(CallStack.compact(outerStack, 1), CallStack.compact(innerStack, 0)),
            com.google.common.collect.ImmutableList.builder<CallStackEntry?>()
                .addAll(outerStack)
                .addAll(innerStack)
                .build(),
            1
        )
    }

    companion object {
        /**
         * Asserts the provided [CallStack.Node] faithfully represents the expected stack, ignoring
         * `expectedStart` of the expected stack's outer frames.
         */
        private fun assertCallStackContents(
            compacted: CallStack.Node?, expected: MutableList<CallStackEntry?>, expectedStart: Int
        ) {
            val reconstituted: MutableList<CallStackEntry?> = java.util.ArrayList<CallStackEntry?>()
            var node: CallStack.Node? = compacted
            while (node != null) {
                reconstituted.add(node.toCallStackEntry())
                node = node.next()
            }
            Truth.assertThat(reconstituted).isEqualTo(expected.subList(expectedStart, expected.size))
        }

        private fun entryFromNameAndLocation(
            name: String?, file: String?, line: Int, col: Int
        ): CallStackEntry? {
            return StarlarkThread.callStackEntry(
                name,
                net.starlark.java.syntax.Location.fromFileLineColumn(file, line, col)
            )
        }
    }
}

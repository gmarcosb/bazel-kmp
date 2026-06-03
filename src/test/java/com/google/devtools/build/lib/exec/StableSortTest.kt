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
//
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.exec.Protos.File

/** Tests for [StableSort].  */
@RunWith(JUnit4::class)
class StableSortTest {
    private class ListOutput : MessageOutputStream<SpawnExec?> {
        var list: java.util.ArrayList<SpawnExec?>

        init {
            list = java.util.ArrayList<SpawnExec?>()
        }

        @Throws(IOException::class)
        public override fun write(m: SpawnExec?) {
            list.add(com.google.common.base.Preconditions.checkNotNull<SpawnExec?>(m))
        }

        @Throws(IOException::class)
        public override fun close() {
        }
    }

    @Throws(java.lang.Exception::class)
    private fun testStableSort(list: MutableList<SpawnExec>): MutableList<SpawnExec?> {
        val baos: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        for (spawn in list) {
            spawn.writeDelimitedTo(baos)
        }

        val `in`: MessageInputStream<SpawnExec?> =
            BinaryInputStreamWrapper(
                ByteArrayInputStream(baos.toByteArray()), SpawnExec.getDefaultInstance()
            )

        val out = ListOutput()

        StableSort.stableSort(`in`, out)
        return out.list
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stableSortEmpty() {
        val l: MutableList<SpawnExec?> = testStableSort(com.google.common.collect.ImmutableList.of<SpawnExec?>())
        Truth.assertThat(l).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stableSortOne() {
        val e1: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>(),
            com.google.common.collect.ImmutableList.of<String?>("output")
        )
        val l: MutableList<SpawnExec?> = testStableSort(com.google.common.collect.ImmutableList.of<SpawnExec?>(e1))
        Truth.assertThat(l).containsExactly(e1).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stableSortTwo_unlinkedLexicographic() {
        val e1: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("leaf1"),
            com.google.common.collect.ImmutableList.of<String?>("a")
        )
        val e2: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("leaf2"),
            com.google.common.collect.ImmutableList.of<String?>("b")
        )

        val l: MutableList<SpawnExec?> = testStableSort(com.google.common.collect.ImmutableList.of<SpawnExec?>(e1, e2))
        Truth.assertThat(l).containsExactly(e1, e2).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stableSortTwo_unlinkedLexicographic_reverse() {
        val e1: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("leaf1"),
            com.google.common.collect.ImmutableList.of<String?>("b")
        )
        val e2: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("leaf2"),
            com.google.common.collect.ImmutableList.of<String?>("a")
        )

        val l: MutableList<SpawnExec?> = testStableSort(com.google.common.collect.ImmutableList.of<SpawnExec?>(e1, e2))
        Truth.assertThat(l).containsExactly(e2, e1).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stableSortTwo_linked() {
        val e1: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("leaf1"),
            com.google.common.collect.ImmutableList.of<String?>("b")
        )
        val e2: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("b"),
            com.google.common.collect.ImmutableList.of<String?>("a")
        )

        val l: MutableList<SpawnExec?> = testStableSort(com.google.common.collect.ImmutableList.of<SpawnExec?>(e1, e2))
        Truth.assertThat(l).containsExactly(e1, e2).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stableSortTwo_linked_inputOrderDoesNotMatter() {
        val e1: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("leaf1"),
            com.google.common.collect.ImmutableList.of<String?>("b")
        )
        val e2: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("b"),
            com.google.common.collect.ImmutableList.of<String?>("a")
        )

        val l: MutableList<SpawnExec?> = testStableSort(com.google.common.collect.ImmutableList.of<SpawnExec?>(e2, e1))
        Truth.assertThat(l).containsExactly(e1, e2).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stableSortTwo_oneOfMultipleInputs() {
        val e1: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("leaf1"),
            com.google.common.collect.ImmutableList.of<String?>("b1", "b2", "b3")
        )
        val e2: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("b2"),
            com.google.common.collect.ImmutableList.of<String?>("a")
        )

        val l: MutableList<SpawnExec?> = testStableSort(com.google.common.collect.ImmutableList.of<SpawnExec?>(e2, e1))
        Truth.assertThat(l).containsExactly(e1, e2).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stableSortTwo_manyOfMultipleInputs() {
        val e1: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("leaf1"),
            com.google.common.collect.ImmutableList.of<String?>("b1", "b2", "b3")
        )
        val e2: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("b2", "b3"),
            com.google.common.collect.ImmutableList.of<String?>("a")
        )

        val l: MutableList<SpawnExec?> = testStableSort(com.google.common.collect.ImmutableList.of<SpawnExec?>(e2, e1))
        Truth.assertThat(l).containsExactly(e1, e2).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stableSortTwo_IrrelevantInputs() {
        val e1: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("leaf1"),
            com.google.common.collect.ImmutableList.of<String?>("b1", "b2", "b3")
        )
        val e2: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("z", "b2", "1"),
            com.google.common.collect.ImmutableList.of<String?>("a")
        )

        val l: MutableList<SpawnExec?> = testStableSort(com.google.common.collect.ImmutableList.of<SpawnExec?>(e2, e1))
        Truth.assertThat(l).containsExactly(e1, e2).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stableSortTwo_ABC() {
        val a: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>(""),
            com.google.common.collect.ImmutableList.of<String?>("a")
        )
        val b: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>(""),
            com.google.common.collect.ImmutableList.of<String?>("b")
        )
        val c: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>(""),
            com.google.common.collect.ImmutableList.of<String?>("c")
        )

        val l: MutableList<SpawnExec?> = testStableSort(com.google.common.collect.ImmutableList.of<SpawnExec?>(a, b, c))
        Truth.assertThat(l).containsExactly(a, b, c).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stableSortTwo_CBA() {
        val a: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("b"),
            com.google.common.collect.ImmutableList.of<String?>("a")
        )
        val b: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("c"),
            com.google.common.collect.ImmutableList.of<String?>("b")
        )
        val c: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>(""),
            com.google.common.collect.ImmutableList.of<String?>("c")
        )

        val l: MutableList<SpawnExec?> = testStableSort(com.google.common.collect.ImmutableList.of<SpawnExec?>(a, b, c))
        Truth.assertThat(l).containsExactly(c, b, a).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stableSortTwo_ACB() {
        val a: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>(""),
            com.google.common.collect.ImmutableList.of<String?>("a")
        )
        val b: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("a", "c"),
            com.google.common.collect.ImmutableList.of<String?>("b")
        )
        val c: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>(""),
            com.google.common.collect.ImmutableList.of<String?>("c")
        )

        val l: MutableList<SpawnExec?> = testStableSort(com.google.common.collect.ImmutableList.of<SpawnExec?>(a, b, c))
        Truth.assertThat(l).containsExactly(a, c, b).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stableSortTwo_CAB() {
        val a: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("c"),
            com.google.common.collect.ImmutableList.of<String?>("a")
        )
        val b: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("c"),
            com.google.common.collect.ImmutableList.of<String?>("b")
        )
        val c: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>(""),
            com.google.common.collect.ImmutableList.of<String?>("c")
        )

        val l: MutableList<SpawnExec?> = testStableSort(com.google.common.collect.ImmutableList.of<SpawnExec?>(a, b, c))
        Truth.assertThat(l).containsExactly(c, a, b).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stableSortTwo_CAB2() {
        val a: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("c1"),
            com.google.common.collect.ImmutableList.of<String?>("a")
        )
        val b: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("c2"),
            com.google.common.collect.ImmutableList.of<String?>("b")
        )
        val c: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>(""),
            com.google.common.collect.ImmutableList.of<String?>("c1", "c2")
        )

        val l: MutableList<SpawnExec?> = testStableSort(com.google.common.collect.ImmutableList.of<SpawnExec?>(a, b, c))
        Truth.assertThat(l).containsExactly(c, a, b).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stableSortTwo_CBAFED() {
        val a: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("b"),
            com.google.common.collect.ImmutableList.of<String?>("a")
        )
        val b: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("c"),
            com.google.common.collect.ImmutableList.of<String?>("b")
        )
        val c: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>(""),
            com.google.common.collect.ImmutableList.of<String?>("c")
        )
        val d: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("e"),
            com.google.common.collect.ImmutableList.of<String?>("d")
        )
        val e: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("f"),
            com.google.common.collect.ImmutableList.of<String?>("e")
        )
        val f: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>(""),
            com.google.common.collect.ImmutableList.of<String?>("f")
        )

        val l: MutableList<SpawnExec?> =
            testStableSort(com.google.common.collect.ImmutableList.of<SpawnExec?>(a, b, c, d, e, f))
        Truth.assertThat(l).containsExactly(c, b, a, f, e, d).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stableSortTwo_InterleavedPaths() {
        val a: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("c"),
            com.google.common.collect.ImmutableList.of<String?>("a")
        )
        val b: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>(""),
            com.google.common.collect.ImmutableList.of<String?>("b")
        )
        val c: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>(""),
            com.google.common.collect.ImmutableList.of<String?>("c")
        )
        val d: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("a"),
            com.google.common.collect.ImmutableList.of<String?>("d")
        )
        val e: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("f"),
            com.google.common.collect.ImmutableList.of<String?>("e")
        )
        val f: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("b"),
            com.google.common.collect.ImmutableList.of<String?>("f")
        )

        val l: MutableList<SpawnExec?> =
            testStableSort(com.google.common.collect.ImmutableList.of<SpawnExec?>(a, b, c, d, e, f))
        Truth.assertThat(l).containsExactly(b, c, a, d, f, e).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stableSortTwo_ManyDependencies() {
        val a: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("b", "c", "f"),
            com.google.common.collect.ImmutableList.of<String?>("a")
        )
        val b: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("d", "e"),
            com.google.common.collect.ImmutableList.of<String?>("b")
        )
        val c: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("e", "d", "f"),
            com.google.common.collect.ImmutableList.of<String?>("c")
        )
        val d: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>(""),
            com.google.common.collect.ImmutableList.of<String?>("d")
        )
        val e: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("f"),
            com.google.common.collect.ImmutableList.of<String?>("e")
        )
        val f: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>(""),
            com.google.common.collect.ImmutableList.of<String?>("f")
        )

        val l: MutableList<SpawnExec?> =
            testStableSort(com.google.common.collect.ImmutableList.of<SpawnExec?>(a, b, c, d, e, f))
        Truth.assertThat(l).containsExactly(d, f, e, b, c, a).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stableSort_NoOutputs() {
        val a: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("a"),
            com.google.common.collect.ImmutableList.of<String?>()
        )
        val b: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("b"),
            com.google.common.collect.ImmutableList.of<String?>()
        )

        val l: MutableList<SpawnExec?> = testStableSort(com.google.common.collect.ImmutableList.of<SpawnExec?>(a, b))
        Truth.assertThat(l).containsExactly(a, b).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stableSort_NoOutputs_reversed() {
        val a: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("a"),
            com.google.common.collect.ImmutableList.of<String?>()
        )
        val b: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("b"),
            com.google.common.collect.ImmutableList.of<String?>()
        )

        val l: MutableList<SpawnExec?> = testStableSort(com.google.common.collect.ImmutableList.of<SpawnExec?>(b, a))
        Truth.assertThat(l).containsExactly(a, b).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stableSort_ListedOutputs() {
        val a: SpawnExec? =
            createSpawnExecBuilder(
                com.google.common.collect.ImmutableList.of<String?>(),
                com.google.common.collect.ImmutableList.of<String?>("a")
            )
                .addCommandArgs("a")
                .build()
        val b: SpawnExec? =
            createSpawnExecBuilder(
                com.google.common.collect.ImmutableList.of<String?>(),
                com.google.common.collect.ImmutableList.of<String?>()
            ).addCommandArgs("b").build()
        val c: SpawnExec? =
            createSpawnExecBuilder(
                com.google.common.collect.ImmutableList.of<String?>(),
                com.google.common.collect.ImmutableList.of<String?>("c")
            )
                .addCommandArgs("c")
                .build()
        val d: SpawnExec? =
            createSpawnExecBuilder(
                com.google.common.collect.ImmutableList.of<String?>(),
                com.google.common.collect.ImmutableList.of<String?>("d")
            )
                .addCommandArgs("d")
                .build()
        val e: SpawnExec? =
            createSpawnExecBuilder(
                com.google.common.collect.ImmutableList.of<String?>(),
                com.google.common.collect.ImmutableList.of<String?>()
            ).addCommandArgs("e").build()
        val f: SpawnExec? =
            createSpawnExecBuilder(
                com.google.common.collect.ImmutableList.of<String?>(),
                com.google.common.collect.ImmutableList.of<String?>()
            ).addCommandArgs("f").build()

        val l: MutableList<SpawnExec?> =
            testStableSort(com.google.common.collect.ImmutableList.of<SpawnExec?>(a, b, c, d, e, f))
        Truth.assertThat(l)
            .containsExactly( // sorted elements with actual outputs
                a,
                c,
                d,  // sorted elements without listed outputs
                b,
                e,
                f
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stableSort_ListedOutputs_reordered() {
        val a: SpawnExec? =
            createSpawnExecBuilder(
                com.google.common.collect.ImmutableList.of<String?>(),
                com.google.common.collect.ImmutableList.of<String?>("a")
            )
                .addCommandArgs("a")
                .build()
        val b: SpawnExec? =
            createSpawnExecBuilder(
                com.google.common.collect.ImmutableList.of<String?>(),
                com.google.common.collect.ImmutableList.of<String?>()
            ).addCommandArgs("b").build()
        val c: SpawnExec? =
            createSpawnExecBuilder(
                com.google.common.collect.ImmutableList.of<String?>(),
                com.google.common.collect.ImmutableList.of<String?>("c")
            )
                .addCommandArgs("c")
                .build()
        val d: SpawnExec? =
            createSpawnExecBuilder(
                com.google.common.collect.ImmutableList.of<String?>(),
                com.google.common.collect.ImmutableList.of<String?>("d")
            )
                .addCommandArgs("d")
                .build()
        val e: SpawnExec? =
            createSpawnExecBuilder(
                com.google.common.collect.ImmutableList.of<String?>(),
                com.google.common.collect.ImmutableList.of<String?>()
            ).addCommandArgs("e").build()
        val f: SpawnExec? =
            createSpawnExecBuilder(
                com.google.common.collect.ImmutableList.of<String?>(),
                com.google.common.collect.ImmutableList.of<String?>()
            ).addCommandArgs("f").build()

        // Reordering the input from the previous test does not change the resulting order
        val l: MutableList<SpawnExec?> =
            testStableSort(com.google.common.collect.ImmutableList.of<SpawnExec?>(f, e, d, c, b, a))
        Truth.assertThat(l)
            .containsExactly( // sorted elements with actual outputs
                a,
                c,
                d,  // sorted elements without listed outputs
                b,
                e,
                f
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stableSort_ListedOutputs_dependencies() {
        // Dependencies are respected
        val a: SpawnExec? =
            createSpawnExecBuilder(
                com.google.common.collect.ImmutableList.of<String?>("d"),
                com.google.common.collect.ImmutableList.of<String?>("a")
            )
                .addCommandArgs("a")
                .build()
        val b: SpawnExec? =
            createSpawnExecBuilder(
                com.google.common.collect.ImmutableList.of<String?>(),
                com.google.common.collect.ImmutableList.of<String?>()
            ).addCommandArgs("b").build()
        val c: SpawnExec? =
            createSpawnExecBuilder(
                com.google.common.collect.ImmutableList.of<String?>("d"),
                com.google.common.collect.ImmutableList.of<String?>("c")
            )
                .addCommandArgs("c")
                .build()
        val d: SpawnExec? =
            createSpawnExecBuilder(
                com.google.common.collect.ImmutableList.of<String?>(),
                com.google.common.collect.ImmutableList.of<String?>("d")
            )
                .addCommandArgs("d")
                .build()
        val e: SpawnExec? =
            createSpawnExecBuilder(
                com.google.common.collect.ImmutableList.of<String?>(),
                com.google.common.collect.ImmutableList.of<String?>()
            ).addCommandArgs("e").build()
        val f: SpawnExec? =
            createSpawnExecBuilder(
                com.google.common.collect.ImmutableList.of<String?>(),
                com.google.common.collect.ImmutableList.of<String?>()
            ).addCommandArgs("f").build()

        val l: MutableList<SpawnExec?> =
            testStableSort(com.google.common.collect.ImmutableList.of<SpawnExec?>(f, e, d, c, b, a))
        Truth.assertThat(l).containsExactly(d, a, c, b, e, f).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stableSort_execsWithDuplicateOutputs() {
        val a: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("a"),
            com.google.common.collect.ImmutableList.of<String?>("c")
        )
        val b: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("b"),
            com.google.common.collect.ImmutableList.of<String?>("c")
        )
        val c: SpawnExec = createSpawnExec(
            com.google.common.collect.ImmutableList.of<String?>("c"),
            com.google.common.collect.ImmutableList.of<String?>("d")
        )

        val l: MutableList<SpawnExec?> = testStableSort(com.google.common.collect.ImmutableList.of<SpawnExec?>(a, b, c))
        Truth.assertThat(l).containsExactly(a, b, c).inOrder()
    }

    companion object {
        private fun createSpawnExecBuilder(
            inputs: MutableList<String?>, outputs: MutableList<String?>
        ): SpawnExec.Builder {
            val e: SpawnExec.Builder = SpawnExec.newBuilder()
            for (output in outputs) {
                e.addActualOutputsBuilder().setPath(output)
                e.addListedOutputs(output)
            }
            for (s in inputs) {
                e.addInputs(File.newBuilder().setPath(s).build())
            }
            return e
        }

        private fun createSpawnExec(inputs: MutableList<String?>, outputs: MutableList<String?>): SpawnExec {
            return createSpawnExecBuilder(inputs, outputs).build()
        }
    }
}

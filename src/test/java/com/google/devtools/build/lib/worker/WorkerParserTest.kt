// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.worker

import com.google.devtools.build.lib.actions.ExecutionRequirements.SUPPORTS_MULTIPLEX_SANDBOXING

/** Tests for [WorkerParser].  */
@RunWith(JUnit4::class)
class WorkerParserTest {
    val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)

    @org.junit.Test
    fun workerKeyComputationCheck() {
        val keyNomultiNoSandboxedNoDynamic: WorkerKey =
            WorkerTestUtils.createWorkerKey(fs, false, false, false)
        assertThat(keyNomultiNoSandboxedNoDynamic.isMultiplex()).isFalse()
        assertThat(keyNomultiNoSandboxedNoDynamic.isSandboxed()).isFalse()
        assertThat(keyNomultiNoSandboxedNoDynamic.getWorkerTypeName()).isEqualTo("worker")

        val keyMultiNoSandboxedNoDynamic: WorkerKey =
            WorkerTestUtils.createWorkerKey(fs, true, false, false)
        assertThat(keyMultiNoSandboxedNoDynamic.isMultiplex()).isTrue()
        assertThat(keyMultiNoSandboxedNoDynamic.isSandboxed()).isFalse()
        assertThat(keyMultiNoSandboxedNoDynamic.getWorkerTypeName()).isEqualTo("multiplex-worker")

        val keyNomultiSandboxedNoDynamic: WorkerKey =
            WorkerTestUtils.createWorkerKey(fs, false, true, false)
        assertThat(keyNomultiSandboxedNoDynamic.isMultiplex()).isFalse()
        assertThat(keyNomultiSandboxedNoDynamic.isSandboxed()).isTrue()
        assertThat(keyNomultiSandboxedNoDynamic.getWorkerTypeName()).isEqualTo("worker")

        val keyMultiSandboxedNoDynamic: WorkerKey = WorkerTestUtils.createWorkerKey(fs, true, true, false)
        assertThat(keyMultiSandboxedNoDynamic.isMultiplex()).isTrue()
        assertThat(keyMultiSandboxedNoDynamic.isSandboxed()).isFalse()
        assertThat(keyMultiSandboxedNoDynamic.getWorkerTypeName()).isEqualTo("multiplex-worker")

        val keyNomultiNoSandboxedDynamic: WorkerKey =
            WorkerTestUtils.createWorkerKey(fs, false, false, true)
        assertThat(keyNomultiNoSandboxedDynamic.isMultiplex()).isFalse()
        assertThat(keyNomultiNoSandboxedDynamic.isSandboxed()).isTrue()
        assertThat(keyNomultiNoSandboxedDynamic.getWorkerTypeName()).isEqualTo("worker")

        val keyMultiNoSandboxedDynamic: WorkerKey = WorkerTestUtils.createWorkerKey(fs, true, false, true)
        assertThat(keyMultiNoSandboxedDynamic.isMultiplex()).isFalse()
        assertThat(keyMultiNoSandboxedDynamic.isSandboxed()).isTrue()
        assertThat(keyMultiNoSandboxedDynamic.getWorkerTypeName()).isEqualTo("worker")

        val keyNomultiSandboxedDynamic: WorkerKey = WorkerTestUtils.createWorkerKey(fs, false, true, true)
        assertThat(keyNomultiSandboxedDynamic.isMultiplex()).isFalse()
        assertThat(keyNomultiSandboxedDynamic.isSandboxed()).isTrue()
        assertThat(keyNomultiSandboxedDynamic.getWorkerTypeName()).isEqualTo("worker")

        val keyMultiSandboxedDynamic: WorkerKey = WorkerTestUtils.createWorkerKey(fs, true, true, true)
        assertThat(keyMultiSandboxedDynamic.isMultiplex()).isFalse()
        assertThat(keyMultiSandboxedDynamic.isSandboxed()).isTrue()
        assertThat(keyMultiSandboxedDynamic.getWorkerTypeName()).isEqualTo("worker")
    }

    @org.junit.Test
    fun createWorkerKey_understandsMultiplexSandboxing() {
        val options: WorkerOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(WorkerOptions::class.java)
        options.multiplexSandboxing = false
        options.workerMultiplex = true

        val keyNoMultiplexSandboxing: WorkerKey =
            WorkerTestUtils.createWorkerKeyWithRequirements(
                fs.getPath("/outputbase"), options, "Nom", false
            )
        assertThat(keyNoMultiplexSandboxing.isMultiplex()).isTrue()
        assertThat(keyNoMultiplexSandboxing.isSandboxed()).isFalse()
        assertThat(keyNoMultiplexSandboxing.getWorkerTypeName()).isEqualTo("multiplex-worker")

        val keyForcedSandboxedDynamic: WorkerKey =
            WorkerTestUtils.createWorkerKeyWithRequirements(
                fs.getPath("/outputbase"), options, "Nom", true
            )
        assertThat(keyForcedSandboxedDynamic.isMultiplex()).isFalse()
        assertThat(keyForcedSandboxedDynamic.isSandboxed()).isTrue()
        assertThat(keyForcedSandboxedDynamic.getWorkerTypeName()).isEqualTo("worker")

        val keyForcedeMultiplexSandboxing: WorkerKey =
            createWorkerKeyWithRequirements(
                fs.getPath("/outputbase"), options, "Nom", true, SUPPORTS_MULTIPLEX_SANDBOXING
            )
        assertThat(keyForcedeMultiplexSandboxing.isMultiplex()).isFalse()
        assertThat(keyForcedeMultiplexSandboxing.isSandboxed()).isTrue()
        assertThat(keyForcedeMultiplexSandboxing.getWorkerTypeName()).isEqualTo("worker")

        options.multiplexSandboxing = true

        val keyBaseMultiplexNoSandbox: WorkerKey =
            WorkerTestUtils.createWorkerKeyWithRequirements(
                fs.getPath("/outputbase"), options, "Nom", false
            )
        assertThat(keyBaseMultiplexNoSandbox.isMultiplex()).isTrue()
        assertThat(keyBaseMultiplexNoSandbox.isSandboxed()).isFalse()
        assertThat(keyBaseMultiplexNoSandbox.getWorkerTypeName()).isEqualTo("multiplex-worker")

        val keyBaseMultiplexSandboxing: WorkerKey =
            createWorkerKeyWithRequirements(
                fs.getPath("/outputbase"), options, "Nom", false, SUPPORTS_MULTIPLEX_SANDBOXING
            )
        assertThat(keyBaseMultiplexSandboxing.isMultiplex()).isTrue()
        assertThat(keyBaseMultiplexSandboxing.isSandboxed()).isTrue()
        assertThat(keyBaseMultiplexSandboxing.getWorkerTypeName()).isEqualTo("multiplex-worker")

        val keyDynamicMultiplexSandboxing: WorkerKey =
            createWorkerKeyWithRequirements(
                fs.getPath("/outputbase"), options, "Nom", true, SUPPORTS_MULTIPLEX_SANDBOXING
            )
        assertThat(keyDynamicMultiplexSandboxing.isMultiplex()).isTrue()
        assertThat(keyDynamicMultiplexSandboxing.isSandboxed()).isTrue()
        assertThat(keyDynamicMultiplexSandboxing.getWorkerTypeName()).isEqualTo("multiplex-worker")
    }

    @org.junit.Test
    @Throws(UserExecException::class)
    fun splitSpawnArgsIntoWorkerArgsAndFlagFiles_splitsArgsBasicCase() {
        val options: WorkerOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(WorkerOptions::class.java)
        options.setWorkerExtraFlags(com.google.common.collect.ImmutableList.of<E?>())
        val parser: WorkerParser = WorkerParser(null, options, null, null)

        val spawn: Spawn = WorkerTestUtils.createSpawn(
            com.google.common.collect.ImmutableList.of<String?>("--foo", "@bar"),
            com.google.common.collect.ImmutableMap.of<String?, String?>()
        )
        val flagFiles: MutableList<String?> = java.util.ArrayList<String?>()
        val args: com.google.common.collect.ImmutableList<String?>? =
            parser.splitSpawnArgsIntoWorkerArgsAndFlagFiles(spawn, flagFiles)
        Truth.assertThat(args).containsExactly("--foo", "--persistent_worker").inOrder()
        Truth.assertThat(flagFiles).containsExactly("@bar")
    }

    @org.junit.Test
    @Throws(UserExecException::class)
    fun splitSpawnArgsIntoWorkerArgsAndFlagFiles_addsExtras() {
        val options: WorkerOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(WorkerOptions::class.java)
        options.setWorkerExtraFlags(
            com.google.common.collect.ImmutableList.of<E?>(
                com.google.common.collect.Maps.immutableEntry<K?, V?>("Null", "--qux"),
                com.google.common.collect.Maps.immutableEntry<K?, V?>("Other action", "--should_not_appear"),
                com.google.common.collect.Maps.immutableEntry<K?, V?>("Null", "--quxify")
            )
        )
        val parser: WorkerParser = WorkerParser(null, options, null, null)
        val spawn: Spawn = WorkerTestUtils.createSpawn(
            com.google.common.collect.ImmutableList.of<String?>("--foo", "@bar"),
            com.google.common.collect.ImmutableMap.of<String?, String?>()
        )

        val flagFiles: MutableList<String?> = java.util.ArrayList<String?>()
        val args: com.google.common.collect.ImmutableList<String?>? =
            parser.splitSpawnArgsIntoWorkerArgsAndFlagFiles(spawn, flagFiles)

        Truth.assertThat(args).containsExactly("--foo", "--persistent_worker", "--qux", "--quxify").inOrder()
        Truth.assertThat(flagFiles).containsExactly("@bar")
    }

    @org.junit.Test
    @Throws(UserExecException::class)
    fun splitSpawnArgsIntoWorkerArgsAndFlagFiles_addsFlagFiles() {
        val options: WorkerOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(WorkerOptions::class.java)
        options.setWorkerExtraFlags(com.google.common.collect.ImmutableList.of<E?>())
        options.strictFlagfiles = false
        val parser: WorkerParser = WorkerParser(null, options, null, null)
        val spawn: Spawn =
            WorkerTestUtils.createSpawn(
                com.google.common.collect.ImmutableList.of<String?>(
                    "--foo",
                    "--flagfile=bar",
                    "@@escaped",
                    "@bar",
                    "@bartoo",
                    "--final"
                ),
                com.google.common.collect.ImmutableMap.of<String?, String?>()
            )

        val flagFiles: MutableList<String?> = java.util.ArrayList<String?>()
        val args: com.google.common.collect.ImmutableList<String?>? =
            parser.splitSpawnArgsIntoWorkerArgsAndFlagFiles(spawn, flagFiles)

        Truth.assertThat(args).containsExactly("--foo", "--final", "--persistent_worker").inOrder()
        // Yes, the legacy implementation allows multiple flagfiles and ignores escape sequences.
        Truth.assertThat(flagFiles)
            .containsExactly("--flagfile=bar", "@@escaped", "@bar", "@bartoo")
            .inOrder()
    }

    @org.junit.Test
    @Throws(UserExecException::class)
    fun splitSpawnArgsIntoWorkerArgsAndFlagFiles_addsFlagFilesStrict() {
        val options: WorkerOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(WorkerOptions::class.java)
        options.setWorkerExtraFlags(com.google.common.collect.ImmutableList.of<E?>())
        options.strictFlagfiles = true
        val parser: WorkerParser = WorkerParser(null, options, null, null)
        val spawn: Spawn =
            WorkerTestUtils.createSpawn(
                com.google.common.collect.ImmutableList.of<String?>("--foo", "@@escaped", "--final", "@bar"),
                com.google.common.collect.ImmutableMap.of<String?, String?>()
            )

        val flagFiles: MutableList<String?> = java.util.ArrayList<String?>()
        val args: com.google.common.collect.ImmutableList<String?>? =
            parser.splitSpawnArgsIntoWorkerArgsAndFlagFiles(spawn, flagFiles)

        Truth.assertThat(args)
            .containsExactly("--foo", "@@escaped", "--final", "--persistent_worker")
            .inOrder()
        Truth.assertThat(flagFiles).containsExactly("@bar")
    }

    @org.junit.Test
    @Throws(UserExecException::class)
    fun splitSpawnArgsIntoWorkerArgsAndFlagFiles_strictFlagFiles() {
        assertIllegalFlags("Must have args")
        assertIllegalFlags("Must have a flagfile", "--foo", "--final")
        assertIllegalFlags("Flagfile must be at the end", "@earlyFile", "--final")
        assertIllegalFlags("Only one flagfile allowed", "@earlyFile", "--final", "@lateFile")
        assertIllegalFlags(
            "Only one flagfile allowed, regardless of syntax",
            "--flagfile=foo",
            "--final",
            "@lateFile"
        )
    }

    private fun assertIllegalFlags(message: String?, vararg args: String?) {
        val options: WorkerOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(WorkerOptions::class.java)
        options.setWorkerExtraFlags(com.google.common.collect.ImmutableList.of<E?>())
        options.strictFlagfiles = true
        val parser: WorkerParser = WorkerParser(null, options, null, null)
        val spawn: Spawn = WorkerTestUtils.createSpawn(
            com.google.common.collect.ImmutableList.copyOf<String?>(args),
            com.google.common.collect.ImmutableMap.of<String?, String?>()
        )

        org.junit.Assert.assertThrows<T?>(
            message,
            UserExecException::class.java,
            org.junit.function.ThrowingRunnable {
                parser.splitSpawnArgsIntoWorkerArgsAndFlagFiles(
                    spawn,
                    java.util.ArrayList<E?>()
                )
            })
    }
}

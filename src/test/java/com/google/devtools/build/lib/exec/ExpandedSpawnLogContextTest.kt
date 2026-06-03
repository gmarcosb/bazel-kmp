// Copyright 2023 The Bazel Authors. All Rights Reserved.
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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.actions.Spawn

/** Tests for [ExpandedSpawnLogContext].  */
@RunWith(TestParameterInjector::class)
class ExpandedSpawnLogContextTest : SpawnLogContextTestBase() {
    private val logPath: Path = fs.getPath("/log")
    private val tempPath: Path? = fs.getPath("/temp")

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMnemonicFilter() {
        val spawn1: SpawnBuilder = SpawnLogContextTestBase.Companion.defaultSpawnBuilder().withMnemonic("Mnemonic1")
        val spawn2: SpawnBuilder = SpawnLogContextTestBase.Companion.defaultSpawnBuilder().withMnemonic("Mnemonic2")

        val context: SpawnLogContext =
            createSpawnLogContext(java.util.function.Predicate { spawn: Spawn? ->
                spawn.getMnemonic().equals("Mnemonic1")
            })

        context.logSpawn(
            spawn1.build(),
            SpawnLogContextTestBase.Companion.createInputMetadataProvider(),
            SpawnLogContextTestBase.Companion.createInputMap(),
            fs,
            SpawnLogContextTestBase.Companion.defaultTimeout(),
            SpawnLogContextTestBase.Companion.defaultSpawnResult()
        )
        context.logSpawn(
            spawn2.build(),
            SpawnLogContextTestBase.Companion.createInputMetadataProvider(),
            SpawnLogContextTestBase.Companion.createInputMap(),
            fs,
            SpawnLogContextTestBase.Companion.defaultTimeout(),
            SpawnLogContextTestBase.Companion.defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            SpawnLogContextTestBase.Companion.defaultSpawnExecBuilder().setMnemonic("Mnemonic1").build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStreaming() {
        val spawn: SpawnBuilder = SpawnLogContextTestBase.Companion.defaultSpawnBuilder().withMnemonic("Mnemonic1")

        val baos: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val out: BufferedOutputStream = BufferedOutputStream(baos)

        val context: SpawnLogContext =
            ExpandedSpawnLogContext(
                out,
                "stream",  /* outputPath= */
                null,
                tempPath,
                Encoding.BINARY,  /* sorted= */
                false,
                execRoot.asFragment(),
                com.google.devtools.common.options.Options.getDefaults<O?>(RemoteOptions::class.java),
                DigestHashFunction.SHA256,
                SyscallCache.NO_CACHE,  /* shouldPublish= */
                false,  /* logSpawnPredicate= */
                { s -> true })

        context.logSpawn(
            spawn.build(),
            SpawnLogContextTestBase.Companion.createInputMetadataProvider(),
            SpawnLogContextTestBase.Companion.createInputMap(),
            fs,
            SpawnLogContextTestBase.Companion.defaultTimeout(),
            SpawnLogContextTestBase.Companion.defaultSpawnResult()
        )

        context.close()

        val actual: java.util.ArrayList<SpawnExec?> = java.util.ArrayList<SpawnExec?>()
        ByteArrayInputStream(baos.toByteArray()).use { `in` ->
            var ex: SpawnExec?
            while ((SpawnExec.parseDelimitedFrom(`in`).also { ex = it }) != null) {
                actual.add(ex)
            }
        }
        Truth.assertThat(actual).containsExactly(
            SpawnLogContextTestBase.Companion.defaultSpawnExecBuilder().setMnemonic("Mnemonic1").build()
        )
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun createSpawnLogContext(platformProperties: com.google.common.collect.ImmutableMap<String?, String?>): SpawnLogContext {
        return createSpawnLogContext(
            platformProperties,  /* logSpawnPredicate= */
            java.util.function.Predicate { spawn: Spawn? -> true })
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun createSpawnLogContext(logSpawnPredicate: java.util.function.Predicate<Spawn?>?): SpawnLogContext {
        return createSpawnLogContext(com.google.common.collect.ImmutableMap.of<String?, String?>(), logSpawnPredicate)
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun createSpawnLogContext(
        platformProperties: com.google.common.collect.ImmutableMap<String?, String?>,
        logSpawnPredicate: java.util.function.Predicate<Spawn?>?
    ): SpawnLogContext {
        val remoteOptions: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        remoteOptions.setRemoteDefaultExecPropertiesField(platformProperties.entries.asList())

        return ExpandedSpawnLogContext(
            BufferedOutputStream(logPath.getOutputStream()),
            logPath.toString(),  /* outputPath= */
            null,
            tempPath,
            Encoding.BINARY,  /* sorted= */
            false,
            execRoot.asFragment(),
            remoteOptions,
            DigestHashFunction.SHA256,
            SyscallCache.NO_CACHE,  /* shouldPublish= */
            false,
            logSpawnPredicate
        )
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun closeAndAssertLog(context: SpawnLogContext, vararg expected: SpawnExec?) {
        context.close()

        val actual: java.util.ArrayList<SpawnExec?> = java.util.ArrayList<SpawnExec?>()
        logPath.getInputStream().use { `in` ->
            var ex: SpawnExec?
            while ((SpawnExec.parseDelimitedFrom(`in`).also { ex = it }) != null) {
                actual.add(ex)
            }
        }
        Truth.assertThat(actual).containsExactlyElementsIn(expected).inOrder()
    }
}

// Copyright 2026 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ActionLookupKey

/** Tests for [ActionLookupConflictFindingFunction].  */
@RunWith(JUnit4::class)
class ActionLookupConflictFindingFunctionTest {
    private class TestEnvironment : AbstractSkyFunctionEnvironmentForTesting() {
        private val values: MutableMap<SkyKey?, SkyValue?> = HashMap<SkyKey?, SkyValue?>()

        override fun getValueOrUntypedExceptions(
            depKeys: Iterable<out SkyKey?>
        ): com.google.common.collect.ImmutableMap<SkyKey?, ValueOrUntypedException?> {
            val builder: com.google.common.collect.ImmutableMap.Builder<SkyKey?, ValueOrUntypedException?> =
                com.google.common.collect.ImmutableMap.builder<SkyKey?, ValueOrUntypedException?>()
            for (key in depKeys) {
                val `val`: SkyValue? = values.get(key)
                if (`val` == null) {
                    this.valuesMissing = true
                }
                builder.put(key, ValueOrUntypedException.ofValueUntyped(`val`))
            }
            return builder.buildOrThrow()
        }

        val listener: ExtendedEventHandler?
            get() = Mockito.mock<ExtendedEventHandler?>(ExtendedEventHandler::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun compute_missingValue_isRetrievalEnabled_noBugReport() {
        val provider: RemoteAnalysisCacheReaderDepsProvider =
            Mockito.mock<RemoteAnalysisCacheReaderDepsProvider>(RemoteAnalysisCacheReaderDepsProvider::class.java)
        Mockito.`when`<T?>(provider.mode()).thenReturn(RemoteAnalysisCacheMode.DOWNLOAD)

        val function: ActionLookupConflictFindingFunction =
            ActionLookupConflictFindingFunction({ provider })

        val lookupKey: ActionLookupKey? =
            ConfiguredTargetKey.builder().setLabel(Label.parseCanonicalUnchecked("//foo:foo")).build()
        val key: SkyKey? = ActionLookupConflictFindingValue.key(lookupKey)

        val env = TestEnvironment()
        // ActionLookupConflictFindingFunction calls ACTION_CONFLICTS.get(env)
        env.values.put(
            ArtifactConflictFinder.ACTION_CONFLICTS.getKey(),
            PrecomputedValue(com.google.common.collect.ImmutableMap.of<K?, V?>())
        )

        val result: SkyValue? = function.compute(key, env)

        assertThat(result).isNull()
        assertThat(env.valuesMissing()).isTrue()
        // No exception thrown means no bug report sent.
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun compute_missingValue_isRetrievalDisabled_bugReport() {
        val provider: RemoteAnalysisCacheReaderDepsProvider =
            Mockito.mock<RemoteAnalysisCacheReaderDepsProvider>(RemoteAnalysisCacheReaderDepsProvider::class.java)
        Mockito.`when`<T?>(provider.mode()).thenReturn(RemoteAnalysisCacheMode.OFF)

        val function: ActionLookupConflictFindingFunction =
            ActionLookupConflictFindingFunction({ provider })

        val lookupKey: ActionLookupKey? =
            ConfiguredTargetKey.builder().setLabel(Label.parseCanonicalUnchecked("//bar:bar")).build()
        val key: SkyKey? = ActionLookupConflictFindingValue.key(lookupKey)

        val env = TestEnvironment()
        env.values.put(
            ArtifactConflictFinder.ACTION_CONFLICTS.getKey(),
            PrecomputedValue(com.google.common.collect.ImmutableMap.of<K?, V?>())
        )

        // BugReport.sendNonFatalBugReport throws IllegalStateException in tests.
        val thrown: java.lang.IllegalStateException? = org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable { function.compute(key, env) })
        Truth.assertThat(thrown)
            .hasMessageThat()
            .contains("Unexpected missing action lookup value during action conflict finding")
    }
}

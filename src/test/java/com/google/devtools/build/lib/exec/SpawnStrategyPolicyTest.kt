// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.runtime.proto.MnemonicPolicy

@RunWith(JUnit4::class)
class SpawnStrategyPolicyTest {
    @org.junit.Test
    fun applyEmptyPolicyListAllowsEverything() {
        val underTest: SpawnStrategyPolicy = SpawnStrategyPolicy.create(MnemonicPolicy.getDefaultInstance())

        val strategies: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("foo", "bar")
        assertThat(underTest.apply("mnemonic1", strategies))
            .containsExactlyElementsIn(strategies)
            .inOrder()
    }

    @org.junit.Test
    fun applyNonOverriddenMnemonicUsesDefaultAllowList() {
        val underTest: SpawnStrategyPolicy =
            SpawnStrategyPolicy.create(
                mnemonicPolicy(
                    com.google.common.collect.ImmutableList.of<StrategiesForMnemonic?>(
                        strategiesForMnemonic(
                            "mnemonic1",
                            "baz"
                        )
                    ),
                    com.google.common.collect.ImmutableList.of<String?>("foo", "bar")
                )
            )

        assertThat(
            underTest.apply(
                "not-mnemonic1",
                com.google.common.collect.ImmutableList.of<E?>("foo", "bar", "baz")
            )
        )
            .containsExactly("foo", "bar")
            .inOrder()
    }

    @org.junit.Test
    fun applyPerStrategyAllowListUsedToFilterStrategies() {
        val underTest: SpawnStrategyPolicy =
            SpawnStrategyPolicy.create(
                mnemonicPolicy(
                    com.google.common.collect.ImmutableList.of<StrategiesForMnemonic?>(
                        strategiesForMnemonic(
                            "mnemonic1",
                            "baz"
                        )
                    ),
                    com.google.common.collect.ImmutableList.of<String?>("foo", "bar")
                )
            )

        assertThat(underTest.apply("mnemonic1", com.google.common.collect.ImmutableList.of<E?>("foo", "bar", "baz")))
            .containsExactly("baz")
    }

    @org.junit.Test
    fun applyPerStrategyAllowListLastListPerMnemonicWins() {
        val underTest: SpawnStrategyPolicy =
            SpawnStrategyPolicy.create(
                mnemonicPolicy(
                    com.google.common.collect.ImmutableList.of<StrategiesForMnemonic?>(
                        strategiesForMnemonic("mnemonic1", "bar"),
                        strategiesForMnemonic("mnemonic1", "foo", "bar")
                    ),
                    com.google.common.collect.ImmutableList.of<String?>("boom")
                )
            )

        assertThat(underTest.apply("mnemonic1", com.google.common.collect.ImmutableList.of<E?>("foo", "bar", "baz")))
            .containsExactly("foo", "bar")
            .inOrder()
    }

    @org.junit.Test
    fun applyDefaultAllowList() {
        val underTest: SpawnStrategyPolicy =
            SpawnStrategyPolicy.create(
                mnemonicPolicy(
                    com.google.common.collect.ImmutableList.of<StrategiesForMnemonic?>(),
                    com.google.common.collect.ImmutableList.of<String?>("foo", "baz")
                )
            )

        assertThat(underTest.apply(com.google.common.collect.ImmutableList.of<E?>("foo", "bar", "baz")))
            .containsExactly("foo", "baz")
            .inOrder()
    }

    companion object {
        private fun mnemonicPolicy(
            strategyAllowList: MutableList<StrategiesForMnemonic?>?, defaultAllowlist: MutableList<String?>?
        ): MnemonicPolicy {
            return MnemonicPolicy.newBuilder()
                .addAllStrategyAllowlist(strategyAllowList)
                .addAllDefaultAllowlist(defaultAllowlist)
                .build()
        }

        private fun strategiesForMnemonic(
            mnemonic: String?, vararg strategies: String?
        ): StrategiesForMnemonic {
            return StrategiesForMnemonic.newBuilder()
                .setMnemonic(mnemonic)
                .addAllStrategy(com.google.common.collect.ImmutableList.< E > copyOf < E ? > (strategies))
                .build()
        }
    }
}

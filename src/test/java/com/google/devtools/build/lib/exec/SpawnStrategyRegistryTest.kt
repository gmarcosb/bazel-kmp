// Copyright 2018 The Bazel Authors. All Rights Reserved.
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

import com.google.devtools.build.lib.actions.ActionContext

/** Unit tests for SpawnStrategyRegistry.  */
@RunWith(JUnit4::class)
class SpawnStrategyRegistryTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegistration() {
        val strategy = NoopStrategy("")
        val strategyRegistry: SpawnStrategyRegistry =
            SpawnStrategyRegistry.builder()
                .registerStrategy(strategy, "foo")
                .setDefaultStrategies(com.google.common.collect.ImmutableList.of<E?>("foo"))
                .build()

        assertThat(
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("", ""),
                { event: com.google.devtools.build.lib.events.Event? -> noopEventHandler(event) })
        )
            .containsExactly(strategy)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMnemonicFilter() {
        val strategy1 = NoopStrategy("1")
        val strategy2 = NoopStrategy("2")
        val strategyRegistry: SpawnStrategyRegistry =
            SpawnStrategyRegistry.builder()
                .registerStrategy(strategy1, "foo")
                .registerStrategy(strategy2, "bar")
                .addMnemonicFilter("mnem", com.google.common.collect.ImmutableList.of<E?>("bar", "foo"))
                .build()

        assertThat(
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("mnem", ""),
                { event: com.google.devtools.build.lib.events.Event? -> noopEventHandler(event) })
        )
            .containsExactly(strategy2, strategy1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStrategyPolicyAppliedToPerMnemonicStrategies() {
        val strategy1 = NoopStrategy("1")
        val strategy2 = NoopStrategy("2")
        val strategyPolicyProto: StrategyPolicy? =
            StrategyPolicy.newBuilder()
                .setMnemonicPolicy(MnemonicPolicy.newBuilder().addDefaultAllowlist("foo"))
                .build()

        val strategyRegistry: SpawnStrategyRegistry =
            SpawnStrategyRegistry.builder(strategyPolicyProto)
                .registerStrategy(strategy1, "foo")
                .registerStrategy(strategy2, "bar")
                .addMnemonicFilter("some-mnemonic", com.google.common.collect.ImmutableList.of<E?>("bar", "foo"))
                .build()

        assertThat(
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("some-mnemonic", ""),
                { event: com.google.devtools.build.lib.events.Event? -> noopEventHandler(event) })
        )
            .containsExactly(strategy1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun strategyPolicyAppliedToPerDefaulttrategies() {
        val strategy1 = NoopStrategy("1")
        val strategy2 = NoopStrategy("2")
        val strategyPolicyProto: StrategyPolicy? =
            StrategyPolicy.newBuilder()
                .setMnemonicPolicy(MnemonicPolicy.newBuilder().addDefaultAllowlist("foo"))
                .build()
        val strategyRegistry: SpawnStrategyRegistry =
            SpawnStrategyRegistry.builder(strategyPolicyProto)
                .registerStrategy(strategy1, "foo")
                .registerStrategy(strategy2, "bar")
                .build()

        val strategies: MutableList<out SpawnStrategy?>? =
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("some-mnemonic", ""),
                { event: com.google.devtools.build.lib.events.Event? -> noopEventHandler(event) })

        Truth.assertThat(strategies).containsExactly(strategy1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun strategyPolicyAppliedToRegexpFilter_sanitizeStrategy() {
        val strategy1 = NoopStrategy("1")
        val strategy2 = NoopStrategy("2")
        val strategyPolicyProto: StrategyPolicy? =
            StrategyPolicy.newBuilder()
                .setMnemonicPolicy(MnemonicPolicy.newBuilder().addDefaultAllowlist("foo"))
                .build()
        val strategyRegistry: SpawnStrategyRegistry =
            SpawnStrategyRegistry.builder(strategyPolicyProto)
                .registerStrategy(strategy1, "foo")
                .registerStrategy(strategy2, "bar")
                .addDescriptionFilter(ELLO_MATCHER, com.google.common.collect.ImmutableList.of<E?>("foo", "bar"))
                .build()

        val strategies: MutableList<out SpawnStrategy?>? =
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("regex-mnemonic", "hello"),
                { event: com.google.devtools.build.lib.events.Event? -> noopEventHandler(event) })

        Truth.assertThat(strategies).containsExactly(strategy1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun strategyPolicyAppliedToRegexpFilter_fallbackToDefaultStrategy() {
        val strategy1 = NoopStrategy("1")
        val strategy2 = NoopStrategy("2")
        val strategy3 = NoopStrategy("3")
        val strategyPolicyProto: StrategyPolicy? =
            StrategyPolicy.newBuilder()
                .setMnemonicPolicy(
                    MnemonicPolicy.newBuilder()
                        .addAllDefaultAllowlist(com.google.common.collect.ImmutableList.of<E?>("foo", "baz"))
                )
                .build()
        val strategyRegistry: SpawnStrategyRegistry =
            SpawnStrategyRegistry.builder(strategyPolicyProto)
                .registerStrategy(strategy1, "foo")
                .registerStrategy(strategy2, "bar")
                .registerStrategy(strategy3, "baz")
                .addDescriptionFilter(ELLO_MATCHER, com.google.common.collect.ImmutableList.of<E?>("foo"))
                .addDescriptionFilter(LLO_MATCHER, com.google.common.collect.ImmutableList.of<E?>("bar"))
                .addMnemonicFilter("regex-mnemonic", com.google.common.collect.ImmutableList.of<E?>("baz"))
                .build()

        val strategies: MutableList<out SpawnStrategy?>? =
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("regex-mnemonic", "hello"),
                { event: com.google.devtools.build.lib.events.Event? -> noopEventHandler(event) })

        Truth.assertThat(strategies).containsExactly(strategy3)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLaterStrategyOverridesEarlier() {
        val strategy1 = NoopStrategy("1")
        val strategy2 = NoopStrategy("2")
        val strategyRegistry: SpawnStrategyRegistry =
            SpawnStrategyRegistry.builder()
                .registerStrategy(strategy1, "foo")
                .registerStrategy(strategy2, "foo")
                .addMnemonicFilter("mnem", com.google.common.collect.ImmutableList.of<E?>("foo"))
                .build()

        assertThat(
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("mnem", ""),
                { event: com.google.devtools.build.lib.events.Event? -> noopEventHandler(event) })
        )
            .containsExactly(strategy2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDescriptionFilter() {
        val strategy1 = NoopStrategy("1")
        val strategy2 = NoopStrategy("2")
        val strategyRegistry: SpawnStrategyRegistry =
            SpawnStrategyRegistry.builder()
                .registerStrategy(strategy1, "foo")
                .registerStrategy(strategy2, "bar")
                .addDescriptionFilter(ELLO_MATCHER, com.google.common.collect.ImmutableList.of<E?>("bar", "foo"))
                .build()

        assertThat(
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("", "hello"),
                { event: com.google.devtools.build.lib.events.Event? -> noopEventHandler(event) })
        )
            .containsExactly(strategy2, strategy1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDescriptionHasPrecedenceOverMnemonic() {
        val strategy1 = NoopStrategy("1")
        val strategy2 = NoopStrategy("2")
        val strategyRegistry: SpawnStrategyRegistry =
            SpawnStrategyRegistry.builder()
                .registerStrategy(strategy1, "foo")
                .registerStrategy(strategy2, "bar")
                .addMnemonicFilter("mnem", com.google.common.collect.ImmutableList.of<E?>("foo"))
                .addDescriptionFilter(ELLO_MATCHER, com.google.common.collect.ImmutableList.of<E?>("bar"))
                .build()

        assertThat(
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("mnem", "hello"),
                { event: com.google.devtools.build.lib.events.Event? -> noopEventHandler(event) })
        )
            .containsExactly(strategy2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleMnemonicFilter() {
        val strategy1 = NoopStrategy("1")
        val strategy2 = NoopStrategy("2")
        val strategyRegistry: SpawnStrategyRegistry =
            SpawnStrategyRegistry.builder()
                .registerStrategy(strategy1, "foo")
                .registerStrategy(strategy2, "bar")
                .addMnemonicFilter("mnem", com.google.common.collect.ImmutableList.of<E?>("foo"))
                .addMnemonicFilter("mnem", com.google.common.collect.ImmutableList.of<E?>("bar"))
                .build()

        assertThat(
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("mnem", ""),
                { event: com.google.devtools.build.lib.events.Event? -> noopEventHandler(event) })
        )
            .containsExactly(strategy2)
    }

    /** If an action matches multiple filters, the latter one gets the priority.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleDescriptionFilter() {
        val strategy1 = NoopStrategy("1")
        val strategy2 = NoopStrategy("2")
        val strategyRegistry: SpawnStrategyRegistry =
            SpawnStrategyRegistry.builder()
                .registerStrategy(strategy1, "foo")
                .registerStrategy(strategy2, "bar")
                .addDescriptionFilter(ELLO_MATCHER, com.google.common.collect.ImmutableList.of<E?>("foo"))
                .addDescriptionFilter(LLO_MATCHER, com.google.common.collect.ImmutableList.of<E?>("bar"))
                .build()

        assertThat(
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("", "hello"),
                { event: com.google.devtools.build.lib.events.Event? -> noopEventHandler(event) })
        )
            .containsExactly(strategy2)
    }

    /**
     * This demonstrate that the latter description filter overrides preceding one of same regexp.
     * filter=val_1 filter=val_2 is equivalent to filter=val_2
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDuplicatedDescriptionFilter() {
        val strategy1 = NoopStrategy("1")
        val strategy2 = NoopStrategy("2")
        val strategyRegistry: SpawnStrategyRegistry =
            SpawnStrategyRegistry.builder()
                .registerStrategy(strategy1, "foo")
                .registerStrategy(strategy2, "bar")
                .addDescriptionFilter(ELLO_MATCHER, com.google.common.collect.ImmutableList.of<E?>("foo"))
                .addDescriptionFilter(ELLO_MATCHER, com.google.common.collect.ImmutableList.of<E?>("bar"))
                .build()

        assertThat(
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("", "hello"),
                { event: com.google.devtools.build.lib.events.Event? -> noopEventHandler(event) })
        )
            .containsExactly(strategy2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPlatformFilter() {
        val strategy1 = NoopStrategy("1")
        val strategy2 = NoopStrategy("2")
        val strategyRegistry: SpawnStrategyRegistry =
            SpawnStrategyRegistry.builder()
                .registerStrategy(strategy1, "foo")
                .registerStrategy(strategy2, "bar")
                .addExecPlatformFilter(
                    PlatformInfo.EMPTY_PLATFORM_INFO.label(), com.google.common.collect.ImmutableList.of<E?>("foo")
                )
                .build()

        assertThat(
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("", ""),
                { event: com.google.devtools.build.lib.events.Event? -> noopEventHandler(event) })
        )
            .containsExactly(strategy1)
    }

    /** Tests that platform filters not affect the strategy ordering.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPlatformFilterOrder() {
        val strategy1 = NoopStrategy("1")
        val strategy2 = NoopStrategy("2")
        val strategyRegistry: SpawnStrategyRegistry =
            SpawnStrategyRegistry.builder()
                .registerStrategy(strategy1, "foo")
                .registerStrategy(strategy2, "bar")
                .addExecPlatformFilter(
                    PlatformInfo.EMPTY_PLATFORM_INFO.label(),
                    com.google.common.collect.ImmutableList.of<E?>("bar", "foo")
                )
                .build()

        assertThat(
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("", ""),
                { event: com.google.devtools.build.lib.events.Event? -> noopEventHandler(event) })
        )
            .containsExactly(strategy1, strategy2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleDefaultStrategies() {
        val strategy1 = NoopStrategy("1")
        val strategy2 = NoopStrategy("2")
        val strategy3 = NoopStrategy("3")
        val strategyRegistry: SpawnStrategyRegistry =
            SpawnStrategyRegistry.builder()
                .registerStrategy(strategy1, "foo")
                .registerStrategy(strategy2, "bar")
                .registerStrategy(strategy3, "baz")
                .setDefaultStrategies(com.google.common.collect.ImmutableList.of<E?>("foo", "baz"))
                .build()

        assertThat(
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("", ""),
                { event: com.google.devtools.build.lib.events.Event? -> noopEventHandler(event) })
        )
            .containsExactly(strategy1, strategy3)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefaultStrategiesIndependentOfFilters() {
        val strategy1 = NoopStrategy("1")
        val strategy2 = NoopStrategy("2")
        val strategy3 = NoopStrategy("3")
        val strategyRegistry: SpawnStrategyRegistry =
            SpawnStrategyRegistry.builder()
                .registerStrategy(strategy1, "foo")
                .registerStrategy(strategy2, "bar")
                .registerStrategy(strategy3, "baz")
                .addMnemonicFilter("mnem", com.google.common.collect.ImmutableList.of<E?>("bar"))
                .setDefaultStrategies(com.google.common.collect.ImmutableList.of<E?>("foo", "baz"))
                .build()

        assertThat(
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("", ""),
                { event: com.google.devtools.build.lib.events.Event? -> noopEventHandler(event) })
        )
            .containsExactly(strategy1, strategy3)

        assertThat(
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("mnem", ""),
                { event: com.google.devtools.build.lib.events.Event? -> noopEventHandler(event) })
        )
            .containsExactly(strategy2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImplicitDefault() {
        val strategy1 = NoopStrategy("1")
        val strategy2 = NoopStrategy("2")
        val strategyRegistry: SpawnStrategyRegistry =
            SpawnStrategyRegistry.builder()
                .registerStrategy(strategy1, "foo")
                .registerStrategy(strategy2, "bar")
                .build()

        assertThat(
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("", ""),
                { event: com.google.devtools.build.lib.events.Event? -> noopEventHandler(event) })
        )
            .containsExactly(strategy1, strategy2)
    }

    @org.junit.Test
    fun testMnemonicStrategyNotPresent() {
        val strategy1 = NoopStrategy("1")
        val exception: AbruptExitException? =
            org.junit.Assert.assertThrows<T?>(
                AbruptExitException::class.java,
                org.junit.function.ThrowingRunnable {
                    SpawnStrategyRegistry.builder()
                        .registerStrategy(strategy1, "foo")
                        .addMnemonicFilter("mnem", com.google.common.collect.ImmutableList.of<E?>("bar", "foo"))
                        .build()
                })

        assertThat(exception).hasMessageThat().containsMatch("bar.*Valid.*foo")
    }

    /** Don't throw an error if any of the replaced strategies was not registered.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDescriptionStrategyReplacedNotPresent() {
        val strategy1 = NoopStrategy("1")
        val strategyRegistry: SpawnStrategyRegistry =
            SpawnStrategyRegistry.builder()
                .registerStrategy(strategy1, "foo")
                .addDescriptionFilter(ELLO_MATCHER, com.google.common.collect.ImmutableList.of<E?>("bar", "foo"))
                .addDescriptionFilter(ELLO_MATCHER, com.google.common.collect.ImmutableList.of<E?>("foo"))
                .build()

        assertThat(
            strategyRegistry.getStrategies(
                createSpawnWithMnemonicAndDescription("", "hello"),
                { event: com.google.devtools.build.lib.events.Event? -> noopEventHandler(event) })
        )
            .containsExactly(strategy1)
    }

    /** Throw error when some of strategies were not registered.  */
    @org.junit.Test
    fun testDescriptionStrategyNotPresent() {
        val strategy1 = NoopStrategy("1")
        val exception: AbruptExitException? =
            org.junit.Assert.assertThrows<T?>(
                AbruptExitException::class.java,
                org.junit.function.ThrowingRunnable {
                    SpawnStrategyRegistry.builder()
                        .registerStrategy(strategy1, "foo")
                        .addDescriptionFilter(
                            ELLO_MATCHER,
                            com.google.common.collect.ImmutableList.of<E?>("bar", "foo")
                        )
                        .build()
                })

        assertThat(exception)
            .hasMessageThat()
            .containsMatch("'bar' was requested.*Valid values are: \\[foo\\]")
    }

    @org.junit.Test
    fun testDescriptionStrategyAllNotPresent() {
        val strategy1 = NoopStrategy("1")
        val exception: AbruptExitException? =
            org.junit.Assert.assertThrows<T?>(
                AbruptExitException::class.java,
                org.junit.function.ThrowingRunnable {
                    SpawnStrategyRegistry.builder()
                        .registerStrategy(strategy1, "foo")
                        .addDescriptionFilter(
                            ELLO_MATCHER,
                            com.google.common.collect.ImmutableList.of<E?>("bar", "food")
                        )
                        .build()
                })

        assertThat(exception).hasMessageThat().containsMatch("bar.*Valid.*foo")
    }

    @org.junit.Test
    fun testDefaultStrategyNotPresent() {
        val strategy1 = NoopStrategy("1")
        val exception: AbruptExitException? =
            org.junit.Assert.assertThrows<T?>(
                AbruptExitException::class.java,
                org.junit.function.ThrowingRunnable {
                    SpawnStrategyRegistry.builder()
                        .registerStrategy(strategy1, "foo")
                        .setDefaultStrategies(com.google.common.collect.ImmutableList.of<E?>("bar"))
                        .build()
                })

        assertThat(exception).hasMessageThat().containsMatch("bar.*Valid.*foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDynamicStrategies() {
        val strategy1: NoopStrategy = NoopSandboxedStrategy("1")
        val strategy2: NoopStrategy = NoopSandboxedStrategy("2")
        val strategyRegistry: SpawnStrategyRegistry =
            SpawnStrategyRegistry.builder()
                .registerStrategy(strategy1, "foo")
                .registerStrategy(strategy2, "bar")
                .addDynamicLocalStrategies(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "mnem",
                        com.google.common.collect.ImmutableList.of<E?>("bar")
                    )
                )
                .addDynamicRemoteStrategies(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "mnem",
                        com.google.common.collect.ImmutableList.of<E?>("foo")
                    )
                )
                .build()

        assertThat(
            strategyRegistry.getDynamicSpawnActionContexts(
                createSpawnWithMnemonicAndDescription("mnem", ""), DynamicMode.REMOTE
            )
        )
            .containsExactly(strategy1)
        assertThat(
            strategyRegistry.getDynamicSpawnActionContexts(
                createSpawnWithMnemonicAndDescription("mnem", ""), DynamicMode.LOCAL
            )
        )
            .containsExactly(strategy2)
    }

    @org.junit.Test
    fun testDynamicStrategyNotPresent() {
        val strategy1: NoopStrategy = NoopSandboxedStrategy("1")
        val exception: AbruptExitException? =
            org.junit.Assert.assertThrows<T?>(
                AbruptExitException::class.java,
                org.junit.function.ThrowingRunnable {
                    SpawnStrategyRegistry.builder()
                        .registerStrategy(strategy1, "foo")
                        .addDynamicLocalStrategies(
                            com.google.common.collect.ImmutableMap.of<K?, V?>(
                                "mnem",
                                com.google.common.collect.ImmutableList.of<E?>("bar")
                            )
                        )
                        .build()
                })

        assertThat(exception).hasMessageThat().containsMatch("bar.*Valid.*foo")
    }

    @org.junit.Test
    fun testDynamicStrategyNotSandboxed() {
        val strategy1 = NoopStrategy("1")
        val exception: AbruptExitException? =
            org.junit.Assert.assertThrows<T?>(
                AbruptExitException::class.java,
                org.junit.function.ThrowingRunnable {
                    SpawnStrategyRegistry.builder()
                        .registerStrategy(strategy1, "foo")
                        .addDynamicLocalStrategies(
                            com.google.common.collect.ImmutableMap.of<K?, V?>(
                                "mnem",
                                com.google.common.collect.ImmutableList.of<E?>("foo")
                            )
                        )
                        .build()
                })

        assertThat(exception).hasMessageThat().containsMatch("sandboxed strategy")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDynamicStrategiesHonorStrategyPolicy() {
        val remoteStrategy: NoopStrategy = NoopSandboxedStrategy("remote")
        val localStrategy: NoopStrategy = NoopSandboxedStrategy("local")
        val strategyRegistry: SpawnStrategyRegistry =
            SpawnStrategyRegistry.builder(
                StrategyPolicy.newBuilder()
                    .setDynamicRemotePolicy(
                        MnemonicPolicy.newBuilder().addDefaultAllowlist("remote")
                    )
                    .setDynamicLocalPolicy(MnemonicPolicy.newBuilder().addDefaultAllowlist("local"))
                    .build()
            )
                .registerStrategy(remoteStrategy, "remote")
                .registerStrategy(
                    localStrategy,
                    "local"
                ) // Pointlessly register both strategies in order to test that policy filters them.
                .addDynamicLocalStrategies(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "mnem",
                        com.google.common.collect.ImmutableList.of<E?>("remote", "local")
                    )
                )
                .addDynamicRemoteStrategies(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "mnem",
                        com.google.common.collect.ImmutableList.of<E?>("remote", "local")
                    )
                )
                .build()

        assertThat(
            strategyRegistry.getDynamicSpawnActionContexts(
                createSpawnWithMnemonicAndDescription("mnem", ""), DynamicMode.REMOTE
            )
        )
            .containsExactly(remoteStrategy)
        assertThat(
            strategyRegistry.getDynamicSpawnActionContexts(
                createSpawnWithMnemonicAndDescription("mnem", ""), DynamicMode.LOCAL
            )
        )
            .containsExactly(localStrategy)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRemoteLocalFallback() {
        val strategy1 = NoopAbstractStrategy("1")
        val strategy2 = NoopAbstractStrategy("2")
        val strategyRegistry: SpawnStrategyRegistry =
            SpawnStrategyRegistry.builder()
                .registerStrategy(strategy1, "foo")
                .registerStrategy(strategy2, "bar")
                .setRemoteLocalFallbackStrategyIdentifier("bar")
                .build()

        assertThat(
            strategyRegistry.getRemoteLocalFallbackStrategy(
                createSpawnWithMnemonicAndDescription("", "")
            )
        )
            .isEqualTo(strategy2)
    }

    @org.junit.Test
    fun testRemoteLocalFallbackNotPresent() {
        val strategy1 = NoopStrategy("1")
        val exception: AbruptExitException? =
            org.junit.Assert.assertThrows<T?>(
                AbruptExitException::class.java,
                org.junit.function.ThrowingRunnable {
                    SpawnStrategyRegistry.builder()
                        .registerStrategy(strategy1, "foo")
                        .setRemoteLocalFallbackStrategyIdentifier("bar")
                        .build()
                })

        assertThat(exception).hasMessageThat().containsMatch("bar.*Valid.*foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRemoteLocalFallbackNotRegistered() {
        val strategy1 = NoopStrategy("1")
        val strategyRegistry: SpawnStrategyRegistry =
            SpawnStrategyRegistry.builder().registerStrategy(strategy1, "foo").build()

        assertThat(
            strategyRegistry.getRemoteLocalFallbackStrategy(
                createSpawnWithMnemonicAndDescription("", "")
            )
        )
            .isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNotifyUsed() {
        val strategy1 = NoopStrategy("1")
        val strategy2 = NoopStrategy("2")
        val strategy3 = NoopStrategy("3")
        val strategy4 = NoopAbstractStrategy("4")
        val strategy5: NoopStrategy = NoopSandboxedStrategy("5")
        val strategy6: NoopStrategy = NoopSandboxedStrategy("6")
        val strategy7 = NoopStrategy("7")
        val strategy8 = NoopStrategy("8")
        val strategy9 = NoopStrategy("9")
        val strategyRegistry: SpawnStrategyRegistry =
            SpawnStrategyRegistry.builder()
                .registerStrategy(strategy1, "1")
                .registerStrategy(strategy2, "2")
                .registerStrategy(strategy3, "3")
                .registerStrategy(strategy7, "4") // no notification: identifier is overridden
                .registerStrategy(strategy4, "4")
                .registerStrategy(strategy5, "5") // no notification: dynamic strategies are separate
                .registerStrategy(strategy6, "6") // no notification: dynamic strategies are separate
                .registerStrategy(strategy8, "8") // no notification: never referenced
                .registerStrategy(strategy9, "9") // no notification: reference overridden
                .addMnemonicFilter("mnem", com.google.common.collect.ImmutableList.of<E?>("1"))
                .addDescriptionFilter(ELLO_MATCHER, com.google.common.collect.ImmutableList.of<E?>("2"))
                .setDefaultStrategies(com.google.common.collect.ImmutableList.of<E?>("9"))
                .setDefaultStrategies(com.google.common.collect.ImmutableList.of<E?>("3"))
                .setRemoteLocalFallbackStrategyIdentifier("4")
                .addDynamicLocalStrategies(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "oy",
                        com.google.common.collect.ImmutableList.of<E?>("5")
                    )
                )
                .addDynamicRemoteStrategies(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "oy",
                        com.google.common.collect.ImmutableList.of<E?>("6")
                    )
                )
                .build()

        strategyRegistry.notifyUsed(null)

        Truth.assertThat(strategy1.usedCalled).isEqualTo(1)
        Truth.assertThat(strategy2.usedCalled).isEqualTo(1)
        Truth.assertThat(strategy3.usedCalled).isEqualTo(1)
        Truth.assertThat(strategy4.usedCalled).isEqualTo(1)

        Truth.assertThat(strategy5.usedCalled).isEqualTo(0)
        Truth.assertThat(strategy6.usedCalled).isEqualTo(0)
        Truth.assertThat(strategy7.usedCalled).isEqualTo(0)
        Truth.assertThat(strategy8.usedCalled).isEqualTo(0)
        Truth.assertThat(strategy9.usedCalled).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNotifyUsedDynamic() {
        val strategy1 = NoopStrategy("1")
        val strategy2 = NoopStrategy("2")
        val strategy3 = NoopStrategy("3")
        val strategy4 = NoopAbstractStrategy("4")
        val strategy5: NoopStrategy = NoopSandboxedStrategy("5")
        val strategy6: NoopStrategy = NoopSandboxedStrategy("6")
        val strategy7 = NoopStrategy("7")
        val strategyRegistry: SpawnStrategyRegistry =
            SpawnStrategyRegistry.builder()
                .registerStrategy(strategy1, "1") // no notification: regular strategies are separate
                .registerStrategy(strategy2, "2") // no notification: regular strategies are separate
                .registerStrategy(strategy3, "3") // no notification: regular strategies are separate
                .registerStrategy(strategy4, "4") // no notification: regular strategies are separate
                .registerStrategy(strategy5, "5")
                .registerStrategy(strategy6, "6")
                .registerStrategy(strategy7, "7") // no notification: reference overridden
                .addMnemonicFilter("mnem", com.google.common.collect.ImmutableList.of<E?>("1"))
                .addDescriptionFilter(ELLO_MATCHER, com.google.common.collect.ImmutableList.of<E?>("2"))
                .setDefaultStrategies(com.google.common.collect.ImmutableList.of<E?>("3"))
                .setRemoteLocalFallbackStrategyIdentifier("4")
                .addDynamicLocalStrategies(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "oy",
                        com.google.common.collect.ImmutableList.of<E?>("7")
                    )
                )
                .addDynamicLocalStrategies(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "oy",
                        com.google.common.collect.ImmutableList.of<E?>("5")
                    )
                )
                .addDynamicRemoteStrategies(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "oy",
                        com.google.common.collect.ImmutableList.of<E?>("6")
                    )
                )
                .build()

        strategyRegistry.notifyUsedDynamic(null)

        Truth.assertThat(strategy1.usedCalled).isEqualTo(0)
        Truth.assertThat(strategy2.usedCalled).isEqualTo(0)
        Truth.assertThat(strategy3.usedCalled).isEqualTo(0)
        Truth.assertThat(strategy4.usedCalled).isEqualTo(0)

        Truth.assertThat(strategy5.usedCalled).isEqualTo(1)
        Truth.assertThat(strategy6.usedCalled).isEqualTo(1)

        Truth.assertThat(strategy7.usedCalled).isEqualTo(0)
    }

    private open class NoopStrategy(private val name: String) : SpawnStrategy {
        private var usedCalled = 0

        public override fun exec(
            spawn: Spawn?, actionExecutionContext: ActionExecutionContext?
        ): com.google.common.collect.ImmutableList<SpawnResult?>? {
            throw java.lang.UnsupportedOperationException()
        }

        public override fun canExec(
            spawn: Spawn?,
            actionContextRegistry: ActionContext.ActionContextRegistry?
        ): Boolean {
            return false
        }

        public override fun usedContext(actionContextRegistry: ActionContext.ActionContextRegistry?) {
            usedCalled++
        }

        override fun toString(): String {
            return "strategy" + name
        }
    }

    private class NoopSandboxedStrategy(name: String) : NoopStrategy(name), SandboxedSpawnStrategy {
        public override fun exec(
            spawn: Spawn?,
            actionExecutionContext: ActionExecutionContext?,
            stopConcurrentSpawns: SandboxedSpawnStrategy.StopConcurrentSpawns?
        ): com.google.common.collect.ImmutableList<SpawnResult?>? {
            throw java.lang.UnsupportedOperationException()
        }
    }

    private class NoopAbstractStrategy(private val name: String) : AbstractSpawnStrategy(null, null) {
        private var usedCalled = 0

        public override fun usedContext(actionContextRegistry: ActionContext.ActionContextRegistry?) {
            usedCalled++
        }

        override fun toString(): String {
            return "strategy" + name
        }
    }

    companion object {
        private val ELLO_MATCHER: RegexFilter = RegexFilter(
            com.google.common.collect.ImmutableList.of<E?>("ello"),
            com.google.common.collect.ImmutableList.of<E?>()
        )
        private val LLO_MATCHER: RegexFilter = RegexFilter(
            com.google.common.collect.ImmutableList.of<E?>("llo"),
            com.google.common.collect.ImmutableList.of<E?>()
        )

        private fun noopEventHandler(event: com.google.devtools.build.lib.events.Event?) {}

        private fun createSpawnWithMnemonicAndDescription(mnemonic: String?, description: String?): Spawn {
            return SimpleSpawn(
                FakeOwner(mnemonic, description, "//dummy:label"),
                com.google.common.collect.ImmutableList.of<E?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                com.google.common.collect.ImmutableSet.of<E?>(),
                ResourceSet.ZERO
            )
        }
    }
}

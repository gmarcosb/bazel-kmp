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

/** Policy for filtering spawn strategies.  */
interface SpawnStrategyPolicy {
    /** Returns result of applying policy to per-mnemonic strategies.  */
    fun apply(mnemonic: String?, strategies: MutableList<String?>?): com.google.common.collect.ImmutableList<String?>?

    /** Returns result of applying default policy to strategies.  */
    fun apply(strategies: MutableList<String?>?): com.google.common.collect.ImmutableList<String?>?

    /** Allows all strategies - effectively a no-op strategy.  */
    class AllowAllStrategiesPolicy private constructor() : SpawnStrategyPolicy {
        override fun apply(
            mnemonic: String?,
            strategies: MutableList<String?>
        ): com.google.common.collect.ImmutableList<String?> {
            return com.google.common.collect.ImmutableList.copyOf<String?>(strategies)
        }

        override fun apply(strategies: MutableList<String?>): com.google.common.collect.ImmutableList<String?> {
            return com.google.common.collect.ImmutableList.copyOf<String?>(strategies)
        }
    }

    /** Enforces a real strategy policy based on provided config.  */
    class SpawnStrategyPolicyImpl private constructor(
        perMnemonicAllowList: com.google.common.collect.ImmutableMap<String?, com.google.common.collect.ImmutableSet<String?>?>,
        defaultAllowList: com.google.common.collect.ImmutableSet<String?>
    ) : SpawnStrategyPolicy {
        private val perMnemonicAllowList: com.google.common.collect.ImmutableMap<String?, com.google.common.collect.ImmutableSet<String?>?>
        private val defaultAllowList: com.google.common.collect.ImmutableSet<String?>

        init {
            this.perMnemonicAllowList = perMnemonicAllowList
            this.defaultAllowList = defaultAllowList
        }

        override fun apply(
            mnemonic: String?,
            strategies: MutableList<String?>
        ): com.google.common.collect.ImmutableList<String?> {
            val allowList: com.google.common.collect.ImmutableSet<String?>? =
                perMnemonicAllowList.getOrDefault(mnemonic, defaultAllowList)
            return strategies.stream()
                .filter(java.util.function.Predicate { `object`: String? -> allowList.contains(`object`) })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())
        }

        override fun apply(strategies: MutableList<String?>): com.google.common.collect.ImmutableList<String?> {
            return strategies.stream()
                .filter(java.util.function.Predicate { `object`: String? -> defaultAllowList.contains(`object`) })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())
        }
    }

    companion object {
        /** Creates new policy from proto descriptor. Empty proto policy implies everything allowed.  */
        fun create(policy: MnemonicPolicy): SpawnStrategyPolicy {
            if (MnemonicPolicy.getDefaultInstance().equals(policy)) {
                return AllowAllStrategiesPolicy()
            }

            val perMnemonicAllowList: com.google.common.collect.ImmutableMap.Builder<String?, com.google.common.collect.ImmutableSet<String?>?> =
                com.google.common.collect.ImmutableMap.builder<String?, com.google.common.collect.ImmutableSet<String?>?>()
            for (strategiesForMnemonic in policy.getStrategyAllowlistList()) {
                perMnemonicAllowList.put(
                    strategiesForMnemonic.getMnemonic(),
                    com.google.common.collect.ImmutableSet.copyOf(strategiesForMnemonic.getStrategyList())
                )
            }
            return SpawnStrategyPolicyImpl(
                perMnemonicAllowList.buildKeepingLast(),
                com.google.common.collect.ImmutableSet.copyOf(policy.getDefaultAllowlistList())
            )
        }
    }
}

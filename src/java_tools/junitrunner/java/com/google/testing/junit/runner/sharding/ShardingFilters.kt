// Copyright 2010 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.runner.sharding

import com.google.testing.junit.runner.junit4.JUnit4Bazel.runner
import com.google.testing.junit.runner.sharding.HashBackedShardingFilter
import com.google.testing.junit.runner.sharding.RoundRobinShardingFilter
import com.google.testing.junit.runner.sharding.ShardingEnvironment
import com.google.testing.junit.runner.sharding.ShardingFilters
import com.google.testing.junit.runner.sharding.ShardingFilters.ShardingStrategy
import com.google.testing.junit.runner.sharding.api.ShardingFilterFactory
import com.google.testing.junit.runner.sharding.api.ShardingFilterFactory.createFilter
import net.starlark.java.syntax.TypeApplication.getConstructor

/**
 * A factory for test sharding filters.
 */
open class ShardingFilters @kotlin.jvm.JvmOverloads constructor(
    shardingEnvironment: ShardingEnvironment,
    defaultShardingStrategy: ShardingFilterFactory = DEFAULT_SHARDING_STRATEGY
) {
    /**
     * An enum of strategies for generating test sharding filters.
     */
    enum class ShardingStrategy : ShardingFilterFactory {
        /**
         * [com.google.testing.junit.runner.sharding.HashBackedShardingFilter]
         */
        HASH {
            override fun createFilter(
                testDescriptions: MutableCollection<org.junit.runner.Description?>?,
                shardIndex: Int, totalShards: Int
            ): org.junit.runner.manipulation.Filter? {
                return HashBackedShardingFilter(shardIndex, totalShards)
            }
        },

        /**
         * [com.google.testing.junit.runner.sharding.RoundRobinShardingFilter]
         */
        ROUND_ROBIN {
            override fun createFilter(
                testDescriptions: MutableCollection<org.junit.runner.Description?>?,
                shardIndex: Int, totalShards: Int
            ): org.junit.runner.manipulation.Filter? {
                return RoundRobinShardingFilter(testDescriptions, shardIndex, totalShards)
            }
        }
    }

    private val shardingEnvironment: ShardingEnvironment
    private val defaultShardingStrategy: ShardingFilterFactory

    /**
     * Creates a factory with the given sharding environment and sharding
     * strategy.
     */
    /**
     * Creates a factory with the given sharding environment and the
     * default sharding strategy.
     */
    init {
        this.shardingEnvironment = shardingEnvironment
        this.defaultShardingStrategy = defaultShardingStrategy
    }

    /**
     * Creates a sharding filter according to strategy specified by the
     * sharding environment.
     */
    open fun createShardingFilter(descriptions: MutableCollection<org.junit.runner.Description?>?): org.junit.runner.manipulation.Filter? {
        val factory: ShardingFilterFactory = this.shardingFilterFactory
        return factory.createFilter(
            descriptions, shardingEnvironment.getShardIndex(),
            shardingEnvironment.getTotalShards()
        )
    }

    private val shardingFilterFactory: ShardingFilterFactory
        get() {
            val strategy: String? = shardingEnvironment.getTestShardingStrategy()
            if (strategy == null) {
                return defaultShardingStrategy
            }
            var shardingFilterFactory: ShardingFilterFactory
            try {
                shardingFilterFactory =
                    com.google.testing.junit.runner.sharding.ShardingFilters.ShardingStrategy.valueOf(strategy.uppercase())
            } catch (e: java.lang.IllegalArgumentException) {
                try {
                    val classLoader: java.lang.ClassLoader = java.lang.Thread.currentThread().getContextClassLoader()
                    val strategyClass: java.lang.Class<out ShardingFilterFactory> =
                        classLoader.loadClass(strategy)
                            .asSubclass<ShardingFilterFactory>(ShardingFilterFactory::class.java)
                    shardingFilterFactory = strategyClass.getConstructor().newInstance()
                } catch (e2: java.lang.ReflectiveOperationException) {
                    throw java.lang.RuntimeException(
                        "Could not create custom sharding strategy class " + strategy, e2
                    )
                } catch (e2: java.lang.IllegalArgumentException) {
                    throw java.lang.RuntimeException(
                        "Could not create custom sharding strategy class " + strategy, e2
                    )
                }
            }
            return shardingFilterFactory
        }

    companion object {
        val DEFAULT_SHARDING_STRATEGY: ShardingFilterFactory = ShardingStrategy.ROUND_ROBIN
    }
}

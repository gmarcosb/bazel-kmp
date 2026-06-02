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
package com.google.devtools.build.lib.remote.grpc

import com.google.devtools.build.lib.remote.grpc.SharedConnectionFactory.SharedConnection
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.functions.Supplier
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.annotation.concurrent.GuardedBy
import kotlin.collections.ArrayList

/**
 * A [ConnectionPool] that creates new connection with given [ConnectionFactory] on
 * demand and applies rate limiting w.r.t `maxConcurrencyPerConnection` for one underlying
 * connection. It also uses Round-Robin algorithm to load balancing between underlying connections.
 * 
 * 
 * Connections must be closed with [Connection.close] in order to be reused later.
 */
class DynamicConnectionPool @kotlin.jvm.JvmOverloads constructor(
    private val connectionFactory: ConnectionFactory?,
    private val maxConcurrencyPerConnection: Int,
    private val maxConnections: Int = 0
) : ConnectionPool {
    private val closed = AtomicBoolean(false)

    @GuardedBy("this")
    private val factories: ArrayList<SharedConnectionFactory>

    @GuardedBy("this")
    private var indexTicker = 0

    init {
        this.factories = ArrayList<SharedConnectionFactory>()
    }

    fun isClosed(): Boolean {
        return closed.get()
    }

    @Throws(IOException::class)
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            synchronized(this) {
                for (factory in factories) {
                    factory.close()
                }
                factories.clear()
            }
        }
    }

    @GuardedBy("this")
    private fun nextFactory(): SharedConnectionFactory {
        val index: Int = Math.abs(indexTicker % factories.size())
        indexTicker += 1
        return factories.get(index)
    }

    /**
     * Performs a simple round robin on the list of [SharedConnectionFactory].
     * 
     * 
     * This will try to find a factory that has available connections at this moment. If no factory
     * has available connections, and the number of factories is less than [.maxConnections], it
     * will create a new [SharedConnectionFactory].
     */
    private fun nextAvailableFactory(): SharedConnectionFactory {
        check(!closed.get()) { "closed" }

        synchronized(this) {
            for (times in factories.indices) {
                val factory = nextFactory()
                if (factory.numAvailableConnections() > 0) {
                    return factory
                }
            }
            if (maxConnections <= 0 || factories.size() < maxConnections) {
                val factory =
                    SharedConnectionFactory(connectionFactory, maxConcurrencyPerConnection)
                factories.add(factory)
                return factory
            } else {
                return nextFactory()
            }
        }
    }

    override fun create(): Single<SharedConnection?>? {
        return Single.defer<SharedConnection?>(
            Supplier {
                val factory = nextAvailableFactory()
                factory.create()
            })
    }
}

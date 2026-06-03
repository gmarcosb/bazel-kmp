// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.concurrent

import com.google.devtools.build.lib.concurrent.ThreadSafety.ConditionallyThreadCompatible
import org.junit.Test

/**
 * This file just contains some examples of the use of
 * annotations for different categories of thread safety:
 * ThreadSafe
 * ThreadCompatible
 * ThreadHostile
 * Immutable ThreadSafe
 * Immutable ThreadHostile
 * 
 * It doesn't really test much -- just that this code
 * using those annotations compiles and runs.
 * 
 * The main class here is annotated as being both ConditionallyThreadSafe
 * and ConditionallyThreadCompatible, and accordingly we document here the
 * conditions under which it is thread-safe and thread-compatible:
 * - it is thread-safe if you only use the testThreadSafety() method,
 * the ThreadSafeCounter class, and/or ImmutableThreadSafeCounter class;
 * - it is thread-compatible if you use only those and/or the
 * ThreadCompatibleCounter and/or ImmutableThreadCompatibleCounter class;
 * - it is thread-hostile otherwise.
 */
@ConditionallyThreadSafe
@ConditionallyThreadCompatible
@RunWith(JUnit4::class)
class ThreadSafetyTest {
    @ThreadSafe
    class ThreadSafeCounter(value: Int) {
        // A ThreadSafe class can have public mutable fields,
        // provided they are atomic or volatile.
        @kotlin.concurrent.Volatile
        var myBool: Boolean = false
        var myInt: AtomicInteger? = null

        // A ThreadSafe class can have private mutable fields,
        // provided that access to them is synchronized.
        @get:kotlin.jvm.Synchronized
        var value: Int = 0
            private set

        @kotlin.jvm.Synchronized
        fun increment() {
            value++
        }

        // ... or non-static.
        @get:kotlin.jvm.Synchronized
        var numBars: Int = 0
            private set

        init {
            synchronized(this) { // is this needed?
                this.value = value
            }
        }

        @kotlin.jvm.Synchronized
        fun bar() {
            numBars++
        }

        companion object {
            // A ThreadSafe class can have private mutable members
            // provided that the methods of the class synchronize access
            // to them.
            // These members could be static...
            @get:kotlin.jvm.Synchronized
            var numFoos: Int = 0
                private set

            @kotlin.jvm.Synchronized
            fun foo() {
                numFoos++
            }
        }
    }

    @ThreadCompatible
    class ThreadCompatibleCounter(// A ThreadCompatible class can have public mutable fields.
        var value: Int
    ) {
        fun increment() {
            value++
        }

        companion object {
            // A ThreadCompatible class can have mutable static members
            // provided that the methods of the class synchronize access
            // to them.
            @get:kotlin.jvm.Synchronized
            var numFoos: Int = 0
                private set

            @kotlin.jvm.Synchronized
            fun foo() {
                numFoos++
            }
        }
    }

    @ThreadHostile
    class ThreadHostileCounter(// A ThreadHostile class can have public mutable fields.
        var value: Int
    ) {
        fun increment() {
            value++
        }

        companion object {
            // A ThreadHostile class can perform unsynchronized access
            // to mutable static data.
            var numFoos: Int = 0
                private set

            fun foo() {
                numFoos++
            }
        }
    }

    @Immutable
    @ThreadSafe
    class ImmutableThreadSafeCounter(// An Immutable ThreadSafe class can have public fields,
        // provided they are final and immutable.
        val value: Int
    ) {
        fun increment(): ImmutableThreadSafeCounter {
            return ImmutableThreadSafeCounter(value + 1)
        }

        // ... or non-static.
        private var incrementCache: ImmutableThreadSafeCounter? = null

        @kotlin.jvm.Synchronized
        fun incrementUsingCache(): ImmutableThreadSafeCounter {
            if (incrementCache == null) {
                incrementCache = ImmutableThreadSafeCounter(value + 1)
            }
            return incrementCache!!
        }

        fun choose(): Int {
            return random.nextInt(value)
        }

        companion object {
            // An Immutable ThreadSafe class can have immutable static members.
            const val NUM_STATIC_CACHE_ENTRIES: Int = 3
            private val staticCache = arrayOf<ImmutableThreadSafeCounter>(
                ImmutableThreadSafeCounter(0),
                ImmutableThreadSafeCounter(1),
                ImmutableThreadSafeCounter(2)
            )

            fun makeUsingStaticCache(value: Int): ImmutableThreadSafeCounter {
                if (value < NUM_STATIC_CACHE_ENTRIES) {
                    return staticCache[value]
                } else {
                    return ImmutableThreadSafeCounter(value)
                }
            }

            // An Immutable ThreadSafe class can have private mutable members
            // provided that the methods of the class synchronize access
            // to them.
            // These members could be static...
            private var cachedValue = 0
            private var cachedCounter = ImmutableThreadSafeCounter(0)

            @kotlin.jvm.Synchronized
            fun makeUsingDynamicCache(value: Int): ImmutableThreadSafeCounter {
                if (value != cachedValue) {
                    cachedValue = value
                    cachedCounter = ImmutableThreadSafeCounter(value)
                }
                return cachedCounter
            }

            // Methods of an Immutable class need not be deterministic.
            private val random: Random = Random()
        }
    }

    @Immutable
    @ThreadHostile
    class ImmutableThreadHostileCounter(// An Immutable ThreadHostile class can have public fields,
        // provided they are final and immutable.
        val value: Int
    ) {
        fun increment(): ImmutableThreadHostileCounter {
            return ImmutableThreadHostileCounter(value + 1)
        }

        // ... or non-static.
        private var incrementCache: ImmutableThreadHostileCounter? = null
        fun incrementUsingCache(): ImmutableThreadHostileCounter {
            if (incrementCache == null) {
                incrementCache = ImmutableThreadHostileCounter(value + 1)
            }
            return incrementCache!!
        }

        companion object {
            // An Immutable ThreadHostile class can have private mutable members,
            // and doesn't need to synchronize access to them.
            // These members could be static...
            private var cachedValue = 0
            private var cachedCounter = ImmutableThreadHostileCounter(0)
            fun makeUsingDynamicCache(value: Int): ImmutableThreadHostileCounter {
                if (value != cachedValue) {
                    cachedValue = value
                    cachedCounter = ImmutableThreadHostileCounter(value)
                }
                return cachedCounter
            }
        }
    }

    @Test
    @Throws(InterruptedException::class)
    fun threadSafety() {
        val threadSafeCounterArray: Array<ThreadSafeCounter?> =
            arrayOf<ThreadSafeCounter>(
                ThreadSafeCounter(1), ThreadSafeCounter(2), ThreadSafeCounter(3)
            )
        val threadCompatibleCounterArray: Array<ThreadCompatibleCounter?> =
            arrayOf<ThreadCompatibleCounter>(
                ThreadCompatibleCounter(1),
                ThreadCompatibleCounter(2),
                ThreadCompatibleCounter(3)
            )
        val threadHostileCounter =
            ThreadHostileCounter(1)

        class MyThread : Runnable {
            var threadCompatibleCounter: ThreadCompatibleCounter = ThreadCompatibleCounter(1)

            override fun run() {
                // ThreadSafe objects can be accessed with without synchronization

                for (counter in threadSafeCounterArray) {
                    counter!!.increment()
                }

                // ThreadCompatible objects can be accessed with without
                // synchronization if they are thread-local
                threadCompatibleCounter.increment()

                // Access to ThreadCompatible objects must be synchronized
                // if they could be concurrently accessed by other threads
                for (counter in threadCompatibleCounterArray) {
                    synchronized(counter) {
                        counter!!.increment()
                    }
                }

                // Access to ThreadHostile objects must be synchronized.
                synchronized(this.javaClass) {
                    threadHostileCounter.increment()
                }
            }
        }

        val thread1 = Thread(MyThread())
        val thread2 = Thread(MyThread())
        thread1.start()
        thread2.start()
        thread1.join()
        thread2.join()
    }
}

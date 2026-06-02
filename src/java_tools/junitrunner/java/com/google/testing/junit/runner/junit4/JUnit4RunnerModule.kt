// Copyright 2015 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.runner.junit4

import com.google.testing.junit.runner.internal.SignalHandlers
import java.io.OutputStream
import java.util.function.Supplier

/** Utility class for real test runs. This is a legacy Dagger module.  */
internal class JUnit4RunnerModule(private val options: JUnit4Options) {
    fun shardingEnvironment(): ShardingEnvironment? {
        return ShardingEnvironment()
    }

    fun shardingFilters(shardingEnvironment: ShardingEnvironment?): ShardingFilters? {
        return ShardingFilters(shardingEnvironment, ShardingFilters.DEFAULT_SHARDING_STRATEGY)
    }

    fun stdout(): PrintStream? {
        return System.out
    }

    fun config(): JUnit4Config {
        return JUnit4Config(
            options.getTestRunnerFailFast(),
            options.getTestIncludeFilter(),
            options.getTestExcludeFilter()
        )
    }

    fun clock(): TestClock {
        return TestClock.systemClock()
    }

    fun setOfRunListeners(
        config: JUnit4Config?,
        testSuiteModelSupplier: Supplier<TestSuiteModel?>?,
        cancellableRequestFactory: CancellableRequestFactory?
    ): MutableSet<RunListener?> {
        val listeners: MutableSet<RunListener?> = HashSet<RunListener?>()
        listeners.add(
            JUnit4TestXmlListener(
                testSuiteModelSupplier,
                cancellableRequestFactory,
                SignalHandlers(SignalHandlers.createRealHandlerInstaller()),
                ProvideXmlStreamFactory(Supplier { config }).get(),
                System.err
            )
        )
        listeners.add(JUnit4TestNameListener(provideCurrentRunningTest()))
        listeners.add(JUnit4RunnerBaseModule.provideTextListener(stdout()))
        return Collections.unmodifiableSet<RunListener?>(listeners)
    }

    fun cancellableRequestFactory(): CancellableRequestFactory {
        return CancellableRequestFactory()
    }

    companion object {
        fun provideXmlStream(config: JUnit4Config): OutputStream {
            val path = config.getXmlOutputPath()

            if (path != null) {
                try {
                    // TODO(bazel-team): Change the provider method to return ByteSink or CharSink
                    return FileOutputStream(path.toFile())
                } catch (e: FileNotFoundException) {
                    /*
         * We try to avoid throwing exceptions in the runner code. There is no
         * way to induce a test failure here, so the only thing we can do is
         * print a message and move on.
         */
                    e.printStackTrace()
                }
            }

            // Returns an OutputStream that discards everything written into it.
            return object : OutputStream() {
                override fun write(b: Int) {}

                override fun write(b: ByteArray) {
                    if (b == null) {
                        throw NullPointerException()
                    }
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (b == null) {
                        throw NullPointerException()
                    }
                }

                override fun toString(): String {
                    return "null OutputStream"
                }
            }
        }

        private fun provideCurrentRunningTest(): SettableCurrentRunningTest {
            return object : SettableCurrentRunningTest() {
                protected override fun setGlobalTestNameProvider(provider: TestNameProvider?) {
                    testNameProvider = provider
                }
            }
        }
    }
}

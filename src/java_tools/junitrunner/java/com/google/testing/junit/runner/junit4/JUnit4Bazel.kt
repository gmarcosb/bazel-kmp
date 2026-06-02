// Copyright 2016 The Bazel Authors. All Rights Reserved.
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

import com.google.errorprone.annotations.CanIgnoreReturnValue
import com.google.testing.junit.runner.model.TestSuiteModel
import com.google.testing.junit.runner.sharding.ShardingEnvironment
import org.junit.runner.Request
import java.util.function.Supplier

/**
 * Utility class to create a JUnit4Runner instance from a [Builder]. All required dependencies
 * are being injected automatically.
 */
class JUnit4Bazel internal constructor(builder: Builder<*>?) {
    private var request: Request? = null

    private var cancellableRequestFactory: CancellableRequestFactory? = null

    private var jUnit4TestModelBuilder: JUnit4TestModelBuilder? = null

    private var testSuiteModelSupplier: Supplier<TestSuiteModel?>? = null

    private var stdoutStream: PrintStream? = null

    private var config: JUnit4Config? = null

    private var setOfRunListeners: MutableSet<RunListener?>? = null

    init {
        initialize(checkNotNull(builder)!!)
    }

    private fun initialize(builder: Builder<*>) {
        val topLevelSuite = builder.suiteClass!!
        this.request = JUnit4RunnerBaseModule.provideRequest(topLevelSuite)
        this.cancellableRequestFactory = builder.module!!.cancellableRequestFactory()
        val topLevelSuiteName = topLevelSuite.getCanonicalName()
        val shardingEnvironment: ShardingEnvironment? = builder.module!!.shardingEnvironment()
        val shardingFilters: ShardingFilters? = builder.module!!.shardingFilters(shardingEnvironment)
        val resultWriter: XmlResultWriter = AntXmlResultWriter()
        val builder1 =
            TestSuiteModel.Builder(
                builder.module!!.clock(), shardingFilters, shardingEnvironment, resultWriter
            )
        this.jUnit4TestModelBuilder = JUnit4TestModelBuilder(request, topLevelSuiteName, builder1)
        this.testSuiteModelSupplier = MemoizingSupplier({ jUnit4TestModelBuilder!!.get() })
        this.stdoutStream = builder.module!!.stdout()
        this.config = builder.module!!.config()
        this.setOfRunListeners =
            builder.module!!.setOfRunListeners(config, testSuiteModelSupplier, cancellableRequestFactory)
    }

    fun runner(): JUnit4Runner {
        return JUnit4Runner(
            request,
            cancellableRequestFactory,
            testSuiteModelSupplier,
            stdoutStream,
            config,
            setOfRunListeners,
            mutableSetOf<JUnit4Runner.Initializer?>()
        )
    }

    /** A builder for instantiating [JUnit4Bazel].  */
    class Builder<B : Builder<B?>?> {
        private var suiteClass: Class<*>? = null
        private var config: JUnit4InstanceModules.Config? = null
        var module: JUnit4RunnerModule? = null

        fun build(): JUnit4Bazel {
            checkNotNull(suiteClass) { "suiteClass must be set" }
            if (module == null) {
                this.module = createModule()
            }
            return JUnit4Bazel(this)
        }

        private fun createModule(): JUnit4RunnerModule {
            checkNotNull(config) { JUnit4InstanceModules.Config::class.java.getCanonicalName() + " must be set" }
            return JUnit4RunnerModule(config!!.options())
        }

        @CanIgnoreReturnValue
        fun suiteClass(suiteClass: Class<*>?): B? {
            this.suiteClass = checkNotNull(suiteClass)
            return this as B
        }

        @CanIgnoreReturnValue
        fun config(config: JUnit4InstanceModules.Config?): B? {
            this.config = checkNotNull<JUnit4InstanceModules.Config>(config)
            return this as B
        }
    }

    companion object {
        fun builder(): Builder<*> {
            return JUnit4Bazel.Builder<B?>()
        }

        private fun <T> checkNotNull(reference: T?): T? {
            if (reference == null) {
                throw NullPointerException()
            }
            return reference
        }
    }
}

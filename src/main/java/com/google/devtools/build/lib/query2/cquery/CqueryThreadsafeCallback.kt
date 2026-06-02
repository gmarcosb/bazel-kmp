// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.cquery

import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue

/**
 * Parents class for cquery output callbacks. Handles names and outputting contents of result list
 * that is populated by child classes.
 * 
 * 
 * Human-readable cquery outputters should output short configuration IDs via [ ][.shortId] for easier reading. Machine-readable output, which are more
 * focused on completeness, should output full configuration checksums.
 */
abstract class CqueryThreadsafeCallback
internal constructor(
    eventHandler: ExtendedEventHandler?,
    options: CqueryOptions,
    out: java.io.OutputStream?,
    skyframeExecutor: SkyframeExecutor,
    accessor: TargetAccessor<CqueryNode?>?,
    uniquifyResults: Boolean
) : NamedThreadSafeOutputFormatterCallback<CqueryNode?>() {
    protected val eventHandler: ExtendedEventHandler?
    protected val options: CqueryOptions
    protected var outputStream: java.io.OutputStream? = null
    protected var printStream: java.io.Writer? = null

    // Skyframe calls incur a performance cost, even on cache hits. Consider this before exposing
    // direct executor access to child classes.
    private val skyframeExecutor: SkyframeExecutor
    private val configCache: MutableMap<BuildConfigurationKey?, BuildConfigurationValue?> =
        ConcurrentHashMap<BuildConfigurationKey?, BuildConfigurationValue?>()
    protected val accessor: ConfiguredTargetAccessor?

    @get:com.google.common.annotations.VisibleForTesting
    val result: MutableList<String?> = java.util.ArrayList<String?>()
    private val uniquifyResults: Boolean

    init {
        this.eventHandler = eventHandler
        this.options = options
        if (out != null) {
            this.outputStream = out
            // This code intentionally uses the platform default encoding.
            this.printStream = BufferedWriter(OutputStreamWriter(out))
        }
        this.skyframeExecutor = skyframeExecutor
        this.accessor = accessor as ConfiguredTargetAccessor?
        this.uniquifyResults = uniquifyResults
    }

    fun addResult(string: String?) {
        result.add(string)
    }

    @Throws(java.lang.InterruptedException::class, IOException::class)
    override fun close(failFast: Boolean) {
        if (!failFast && printStream != null) {
            val resultsToPrint =
                if (uniquifyResults) com.google.common.collect.ImmutableSet.copyOf<String?>(result).asList() else result
            for (s in resultsToPrint) {
                printStream.append(s).append(options.getLineTerminator())
            }
            printStream.flush()
        }
    }

    fun getConfiguration(configKey: BuildConfigurationKey?): BuildConfigurationValue? {
        // Experiments querying:
        //     cquery --output=graph "deps(//src:main/java/com/google/devtools/build/lib:runtime)"
        // 10 times on a warm Blaze instance show 7% less total query time when using this cache vs.
        // calling Skyframe directly (and relying on Skyframe's cache).
        if (configKey == null) {
            return null
        }
        return configCache.computeIfAbsent(
            configKey,
            java.util.function.Function { key: BuildConfigurationKey? ->
                skyframeExecutor.getConfiguration(
                    eventHandler,
                    key
                )
            })
    }

    companion object {
        /**
         * Returns a user-friendly configuration identifier, using special IDs for null configurations.
         */
        protected fun shortId(config: BuildConfigurationValue?): String? {
            return if (config == null) "null" else config.shortId()
        }
    }
}

// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import java.util.Collections

/**
 * Provides a piece of functionality in the Service Component (SC).
 * 
 * 
 * Each service must be structured as an interface type (which extends this interface) and an
 * implementation type (which implements the interface type). The interface type provides a stable
 * API through which the Logical Component (LC) can access the service, whose implementation is
 * provided by the SC.
 * 
 * 
 * The set of services is passed into [BlazeRuntime.main] and fixed for the lifetime of the
 * server. A service can be obtained by calling [BlazeRuntime.getBlazeService] with the
 * interface type as the argument.
 */
@com.google.devtools.build.lib.skybridge.SkybridgeInterface
interface BlazeService : com.google.devtools.build.lib.runtime.OptionsSupplier {
    val startupOptions: Iterable<java.lang.Class<out com.google.devtools.common.options.OptionsBase>>
        get() = Collections.emptyList<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>()

    val commonCommandOptions: Iterable<java.lang.Class<out com.google.devtools.common.options.OptionsBase>>
        get() = Collections.emptyList<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>()

    override fun getCommandOptions(commandName: String?): Iterable<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> {
        return Collections.emptyList<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>()
    }

    /**
     * Called at the beginning of Bazel startup, right before [BlazeModule.globalInit].
     * 
     * @param startupOptions the server's startup options
     * @param blazeServices the available services, including this service itself
     * @throws SerializedAbruptExitException to shut down the server immediately
     */
    @Throws(com.google.devtools.build.lib.util.SerializedAbruptExitException::class)
    fun globalInit(
        startupOptions: com.google.devtools.common.options.OptionsProvider?,
        blazeServices: Iterable<BlazeService?>?
    ) {
    }
}

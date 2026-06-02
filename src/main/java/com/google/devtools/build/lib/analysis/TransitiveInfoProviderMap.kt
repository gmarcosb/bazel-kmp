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
package com.google.devtools.build.lib.analysis

/**
 * Provides a mapping between an identifier for transitive information and its instance. (between
 * provider identifier and provider instance)
 * 
 * 
 * We have three kinds of provider identifiers:
 * 
 * 
 *  * Declared providers. They are exposed to Starlark and identified by [       ]. Provider instances are [       ]s.
 *  * Native providers. They are identified by their [Class] and their instances are
 * instances of that class. They should implement [TransitiveInfoProvider] marker
 * interface.
 *  * Legacy Starlark providers (deprecated). They are identified by simple strings, and their
 * instances are more-less random objects.
 * 
 */
@javax.annotation.concurrent.Immutable
interface TransitiveInfoProviderMap : ProviderCollection {
    /**
     * Returns a count of providers.
     * 
     * Upper bound for `index` in [.getProviderKeyAt]
     * and [.getProviderInstanceAt] }.
     * 
     * Low-level method, use with care.
     */
    @kotlin.jvm.JvmField
    val providerCount: Int

    /**
     * Return value is one of:
     * 
     * 
     *  * `Class<? extends TransitiveInfoProvider>`
     *  * String
     *  * [com.google.devtools.build.lib.packages.Provider.Key]
     * 
     * 
     * Low-level method, use with care.
     */
    fun getProviderKeyAt(index: Int): Any?

    fun getProviderInstanceAt(index: Int): Any?
}

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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.concurrent.BlazeInterners

/**
 * A wrapper around Starlark provider identifier, representing either a declared provider ({@see
 * * StarlarkProvider}) or a "legacy" string identifier.
 */
abstract class StarlarkProviderIdentifier {
    /** Returns a key identifying the declared provider (only for non-legacy providers).  */
    abstract fun getKey(): com.google.devtools.build.lib.packages.Provider.Key?

    abstract fun fingerprint(fp: Fingerprint?)

    /**
     * Returns the provider key name for a declared provider, or the legacy ID for a legacy provider.
     * 
     * 
     * Used for rendering human-readable descriptions, such as for a rule attribute's set of
     * required providers.
     */
    abstract override fun toString(): String

    @AutoCodec
    internal class KeyedIdentifier private constructor(key: com.google.devtools.build.lib.packages.Provider.Key) :
        StarlarkProviderIdentifier() {
        private val key: com.google.devtools.build.lib.packages.Provider.Key

        init {
            this.key = key
        }

        override fun getKey(): com.google.devtools.build.lib.packages.Provider.Key {
            return key
        }

        override fun fingerprint(fp: Fingerprint) {
            fp.addBoolean(false)
            key.fingerprint(fp)
        }

        override fun hashCode(): Int {
            return key.hashCode()
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj !is KeyedIdentifier) {
                return false
            }
            return key == obj.key
        }

        override fun toString(): String {
            return key.toString()
        }

        companion object {
            @AutoCodec.Interner
            fun intern(id: KeyedIdentifier?): KeyedIdentifier {
                return interner.intern(id) as KeyedIdentifier
            }
        }
    }

    companion object {
        private val interner: com.google.common.collect.Interner<StarlarkProviderIdentifier> =
            BlazeInterners.newWeakInterner()

        /** Creates an id for a declared provider with a given key ({@see StarlarkProvider}).  */
        fun forKey(key: com.google.devtools.build.lib.packages.Provider.Key): StarlarkProviderIdentifier {
            return interner.intern(KeyedIdentifier(key))
        }
    }
}

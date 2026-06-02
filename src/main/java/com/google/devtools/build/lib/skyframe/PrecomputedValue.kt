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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.packages.Package.ConfigSettingVisibilityPolicy

/**
 * A value that represents something computed outside of the skyframe framework. These values are
 * "precomputed" from skyframe's perspective and so the graph needs to be prepopulated with them
 * (e.g. via injection).
 */
class PrecomputedValue @com.google.common.annotations.VisibleForTesting constructor(value: Any?) : SkyValue {
    /**
     * An externally-injected precomputed value. Exists so that modules can inject precomputed values
     * into Skyframe's graph.
     * 
     * @see com.google.devtools.build.lib.runtime.BlazeModule.getPrecomputedValues
     */
    class Injected private constructor(
        private val precomputed: Precomputed<*>,
        supplier: com.google.common.base.Supplier<*>
    ) {
        private val supplier: com.google.common.base.Supplier<*>

        init {
            this.supplier = supplier
        }

        fun inject(injectable: Injectable) {
            injectable.inject(precomputed.key, Delta.justNew(PrecomputedValue(supplier.get())))
        }

        val key: SkyKey
            get() = precomputed.getKey()

        override fun toString(): String {
            return precomputed.toString() + ": " + supplier.get()
        }
    }

    private val value: Any

    init {
        this.value = com.google.common.base.Preconditions.checkNotNull<Any>(value)
    }

    /** Returns the value of the variable.  */
    fun get(): Any {
        return value
    }

    override fun hashCode(): Int {
        return value.hashCode()
    }

    override fun equals(obj: Any?): Boolean {
        if (obj !is PrecomputedValue) {
            return false
        }
        return value == obj.value
    }

    override fun toString(): String {
        return "<BuildVariable " + value + ">"
    }

    /**
     * A helper object corresponding to a variable in Skyframe.
     * 
     * 
     * Instances do not have internal state.
     */
    class Precomputed<T> private constructor(key: String?, shareable: Boolean) {
        private val key: SkyKey

        constructor(key: String?) : this(key,  /* shareable= */true)

        init {
            this.key =
                if (shareable) com.google.devtools.build.lib.skyframe.PrecomputedValue.Key.Companion.create(key) else UnshareableKey.Companion.create(
                    key
                )
        }

        fun getKey(): SkyKey {
            return key
        }

        /**
         * Retrieves the value of this variable from Skyframe.
         * 
         * 
         * If the value was not set, an exception will be raised.
         */
        @Throws(java.lang.InterruptedException::class)
        fun get(env: SkyFunction.Environment): T? {
            val value = env.getValue(key) as PrecomputedValue?
            if (value == null) {
                return null
            }
            return value.get() as T?
        }

        /** Injects a new variable value.  */
        fun set(injectable: Injectable, value: T?) {
            injectable.inject(key, Delta.justNew(PrecomputedValue(value)))
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("key", key)
                .add("shareable", key.valueIsShareable())
                .toString()
        }

        companion object {
            fun <T> createUnshareable(key: String?): Precomputed<T?> {
                return Precomputed<T?>(key,  /* shareable= */false)
            }
        }
    }

    /** [com.google.devtools.build.skyframe.SkyKey] for `PrecomputedValue`.  */
    @AutoCodec
    class Key private constructor(arg: String?) : AbstractSkyKey<String?>(arg) {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.PRECOMPUTED
        }

        val skyKeyInterner: SkyKeyInterner<Key?>
            get() = com.google.devtools.build.lib.skyframe.PrecomputedValue.Key.Companion.interner

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            fun create(arg: String?): Key {
                return com.google.devtools.build.lib.skyframe.PrecomputedValue.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.skyframe.PrecomputedValue.Key(
                        arg
                    )
                )
            }

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Interner
            fun intern(key: Key?): Key {
                return com.google.devtools.build.lib.skyframe.PrecomputedValue.Key.Companion.interner.intern(key)
            }
        }
    }

    /** Unshareable version of [Key].  */
    @AutoCodec
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    internal class UnshareableKey private constructor(arg: String?) : AbstractSkyKey<String?>(arg) {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.PRECOMPUTED
        }

        override fun valueIsShareable(): Boolean {
            return false
        }

        val skyKeyInterner: SkyKeyInterner<UnshareableKey?>
            get() = interner

        companion object {
            private val interner: SkyKeyInterner<UnshareableKey?> = SkyKey.newInterner<UnshareableKey?>()

            private fun create(arg: String?): UnshareableKey {
                return interner.intern(UnshareableKey(arg))
            }

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Interner
            fun intern(unshareableKey: UnshareableKey?): UnshareableKey {
                return interner.intern(unshareableKey)
            }
        }
    }

    companion object {
        fun <T> injected(precomputed: Precomputed<T?>, value: com.google.common.base.Supplier<T?>): Injected {
            return Injected(precomputed, value)
        }

        fun <T> injected(precomputed: Precomputed<T?>, value: T?): Injected {
            return Injected(precomputed, com.google.common.base.Suppliers.ofInstance<T?>(value))
        }

        @kotlin.jvm.JvmField
        val DEFAULT_VISIBILITY: Precomputed<RuleVisibility?> = Precomputed<RuleVisibility?>("default_visibility")

        @kotlin.jvm.JvmField
        val CONFIG_SETTING_VISIBILITY_POLICY: Precomputed<ConfigSettingVisibilityPolicy?> =
            Precomputed<ConfigSettingVisibilityPolicy?>("config_setting_visibility_policy")

        @kotlin.jvm.JvmField
        val STARLARK_SEMANTICS: Precomputed<net.starlark.java.eval.StarlarkSemantics?> =
            Precomputed<net.starlark.java.eval.StarlarkSemantics?>("starlark_semantics")

        @kotlin.jvm.JvmField
        val BUILD_ID: Precomputed<UUID?> = Precomputed.Companion.createUnshareable<UUID?>("build_id")

        @kotlin.jvm.JvmField
        val ACTION_ENV: Precomputed<MutableMap<String?, String?>?> =
            Precomputed<MutableMap<String?, String?>?>("action_env")

        @kotlin.jvm.JvmField
        val REPO_ENV: Precomputed<com.google.common.collect.ImmutableMap<String?, String?>?> =
            Precomputed<com.google.common.collect.ImmutableMap<String?, String?>?>("repo_env")

        @kotlin.jvm.JvmField
        val PATH_PACKAGE_LOCATOR: Precomputed<PathPackageLocator?> =
            Precomputed<PathPackageLocator?>("path_package_locator")

        @kotlin.jvm.JvmField
        val REMOTE_EXECUTION_ENABLED: Precomputed<Boolean?> = Precomputed<Boolean?>("remote_execution_enabled")

        @kotlin.jvm.JvmField
        val LAZY_MACRO_EXPANSION_PACKAGES: Precomputed<LazyMacroExpansionPackages?> =
            Precomputed<LazyMacroExpansionPackages?>("lazy_macro_expansion_packages")

        /**
         * A marker Skyframe dependency for a configured target that may behave differently due to `--stamp=true`, even if it does not own a stamped action.
         * 
         * 
         * Examples are:
         * 
         * 
         *  * When a starlark transition reads `//command_line_option:stamp` as an input.
         *  * When a starlark transition sets `//command_line_option:stamp` as an output (since
         * then dependencies may have a different output path in --stamp vs --nostamp).
         *  * A `config_setting` that matches on the value of `--stamp`.
         * 
         * 
         * 
         * The value is irrelevant. Its [Injected.getKey] is just a marker dependency.
         */
        @kotlin.jvm.JvmField
        val STAMP_SETTING_MARKER: Injected =
            injected<Boolean?>(Precomputed<Boolean?>("stamp_setting_marker"), java.lang.Boolean.TRUE)
    }
}

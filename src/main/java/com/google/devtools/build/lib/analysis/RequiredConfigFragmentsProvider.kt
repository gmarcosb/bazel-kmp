// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.config.Fragment

/**
 * Provides a user-friendly list of the [Fragment]s and [ ] required by this target and its
 * transitive dependencies.
 * 
 * 
 * See [RequiredFragmentsUtil] for details.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
@AutoCodec
class RequiredConfigFragmentsProvider(
    optionsClasses: com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?>?,
    fragmentClasses: com.google.common.collect.ImmutableSet<java.lang.Class<out Fragment?>?>?,
    defines: com.google.common.collect.ImmutableSet<String?>?,
    starlarkOptions: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?>?
) : com.google.devtools.build.lib.analysis.TransitiveInfoProvider {
    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(RequiredConfigFragmentsProvider::class.java)
            .add(
                "optionsClasses",
                com.google.common.collect.Collections2.transform<java.lang.Class<out FragmentOptions?>?, String?>(
                    this.optionsClasses,
                    com.google.common.base.Function { clazz: java.lang.Class<out FragmentOptions?>? ->
                        com.google.devtools.build.lib.util.ClassName.getSimpleNameWithOuter(clazz)
                    })
            )
            .add(
                "fragmentClasses",
                com.google.common.collect.Collections2.transform<java.lang.Class<out Fragment?>?, String?>(
                    this.fragmentClasses,
                    com.google.common.base.Function { clazz: java.lang.Class<out Fragment?>? ->
                        com.google.devtools.build.lib.util.ClassName.getSimpleNameWithOuter(clazz)
                    })
            )
            .add("defines", this.defines)
            .add("starlarkOptions", this.starlarkOptions)
            .toString()
    }

    /**
     * Builder for required config fragments.
     * 
     * 
     * The builder uses a merging strategy that favors reuse of [ImmutableSet] instances and
     * avoids copying data if possible (i.e. when adding elements that are already present). For this
     * reason, adding transitively required fragments *before* directly required fragments is
     * likely to result in better performance, as it promotes reuse of existing sets from
     * dependencies.
     */
    class Builder private constructor() {
        private var optionsClasses: MutableSet<java.lang.Class<out FragmentOptions?>?> =
            com.google.common.collect.ImmutableSet.of<java.lang.Class<out FragmentOptions?>?>()
        private var fragmentClasses: MutableSet<java.lang.Class<out Fragment?>?> =
            com.google.common.collect.ImmutableSet.of<java.lang.Class<out Fragment?>?>()
        private var defines: MutableSet<String?> = com.google.common.collect.ImmutableSet.of<String?>()
        private var starlarkOptions: MutableSet<com.google.devtools.build.lib.cmdline.Label?> =
            com.google.common.collect.ImmutableSet.of<com.google.devtools.build.lib.cmdline.Label?>()

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addOptionsClass(optionsClass: java.lang.Class<out FragmentOptions?>?): Builder {
            optionsClasses =
                com.google.devtools.build.lib.analysis.RequiredConfigFragmentsProvider.Builder.Companion.append<java.lang.Class<out FragmentOptions?>?>(
                    optionsClasses,
                    optionsClass
                )
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addOptionsClasses(optionsClasses: MutableCollection<java.lang.Class<out FragmentOptions?>?>?): Builder {
            this.optionsClasses =
                com.google.devtools.build.lib.analysis.RequiredConfigFragmentsProvider.Builder.Companion.appendAll<java.lang.Class<out FragmentOptions?>?>(
                    this.optionsClasses,
                    optionsClasses
                )
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addFragmentClasses(fragmentClasses: MutableCollection<java.lang.Class<out Fragment?>?>?): Builder {
            this.fragmentClasses =
                com.google.devtools.build.lib.analysis.RequiredConfigFragmentsProvider.Builder.Companion.appendAll<java.lang.Class<out Fragment?>?>(
                    this.fragmentClasses,
                    fragmentClasses
                )
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addDefine(define: String?): Builder {
            defines =
                com.google.devtools.build.lib.analysis.RequiredConfigFragmentsProvider.Builder.Companion.append<String?>(
                    defines,
                    define
                )
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addDefines(defines: MutableCollection<String?>?): Builder {
            this.defines =
                com.google.devtools.build.lib.analysis.RequiredConfigFragmentsProvider.Builder.Companion.appendAll<String?>(
                    this.defines,
                    defines
                )
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addStarlarkOption(starlarkOption: com.google.devtools.build.lib.cmdline.Label?): Builder {
            starlarkOptions =
                com.google.devtools.build.lib.analysis.RequiredConfigFragmentsProvider.Builder.Companion.append<com.google.devtools.build.lib.cmdline.Label?>(
                    starlarkOptions,
                    starlarkOption
                )
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addStarlarkOptions(starlarkOptions: MutableCollection<com.google.devtools.build.lib.cmdline.Label?>?): Builder {
            this.starlarkOptions =
                com.google.devtools.build.lib.analysis.RequiredConfigFragmentsProvider.Builder.Companion.appendAll<com.google.devtools.build.lib.cmdline.Label?>(
                    this.starlarkOptions,
                    starlarkOptions
                )
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun merge(provider: RequiredConfigFragmentsProvider?): Builder {
            if (provider != null) {
                optionsClasses =
                    com.google.devtools.build.lib.analysis.RequiredConfigFragmentsProvider.Builder.Companion.appendAll<java.lang.Class<out FragmentOptions?>?>(
                        optionsClasses,
                        provider.optionsClasses
                    )
                fragmentClasses =
                    com.google.devtools.build.lib.analysis.RequiredConfigFragmentsProvider.Builder.Companion.appendAll<java.lang.Class<out Fragment?>?>(
                        fragmentClasses,
                        provider.fragmentClasses
                    )
                defines =
                    com.google.devtools.build.lib.analysis.RequiredConfigFragmentsProvider.Builder.Companion.appendAll<String?>(
                        defines,
                        provider.defines
                    )
                starlarkOptions =
                    com.google.devtools.build.lib.analysis.RequiredConfigFragmentsProvider.Builder.Companion.appendAll<com.google.devtools.build.lib.cmdline.Label?>(
                        starlarkOptions,
                        provider.starlarkOptions
                    )
            }
            return this
        }

        fun build(): RequiredConfigFragmentsProvider {
            if (optionsClasses.isEmpty()
                && fragmentClasses.isEmpty()
                && defines.isEmpty()
                && starlarkOptions.isEmpty()
            ) {
                return EMPTY
            }
            return interner.intern(
                RequiredConfigFragmentsProvider(
                    com.google.common.collect.ImmutableSet.copyOf<java.lang.Class<out FragmentOptions?>?>(optionsClasses),
                    com.google.common.collect.ImmutableSet.copyOf<java.lang.Class<out Fragment?>?>(fragmentClasses),
                    com.google.common.collect.ImmutableSet.copyOf<String?>(defines),
                    com.google.common.collect.ImmutableSet.copyOf<com.google.devtools.build.lib.cmdline.Label?>(
                        starlarkOptions
                    )
                )
            )
        }

        companion object {
            private fun <T> append(set: MutableSet<T?>, t: T?): MutableSet<T?> {
                var set = set
                if (set is com.google.common.collect.ImmutableSet<*>) {
                    if (set.contains(t)) {
                        return set
                    }
                    set = HashSet<T?>(set)
                }
                set.add(t)
                return set
            }

            private fun <T> appendAll(set: MutableSet<T?>, ts: MutableCollection<T?>?): MutableSet<T?> {
                var set = set
                if (ts is MutableSet<*>) {
                    return com.google.devtools.build.lib.analysis.RequiredConfigFragmentsProvider.Builder.Companion.appendAll<T?>(
                        set,
                        ts as MutableSet<T?>
                    )
                }
                if (set is com.google.common.collect.ImmutableSet<*>) {
                    if (set.containsAll(ts!!)) {
                        return set
                    }
                    set = HashSet<T?>(set)
                }
                set.addAll(ts!!)
                return set
            }

            private fun <T> appendAll(set: MutableSet<T?>, ts: MutableSet<T?>): MutableSet<T?> {
                var set = set
                if (set.size > ts.size) {
                    if (set is com.google.common.collect.ImmutableSet<*> && set.containsAll(ts)) {
                        return set
                    }
                } else if (ts.size > set.size) {
                    if (ts is com.google.common.collect.ImmutableSet<*> && ts.containsAll(set)) {
                        return ts
                    }
                } else { // Sizes equal.
                    if (set is com.google.common.collect.ImmutableSet<*>) {
                        if (set == ts) {
                            return set
                        }
                    } else if (ts is com.google.common.collect.ImmutableSet<*> && ts == set) {
                        return ts
                    }
                }
                if (set is com.google.common.collect.ImmutableSet<*>) {
                    set = HashSet<T?>(set)
                }
                set.addAll(ts)
                return set
            }
        }
    }

    val optionsClasses: com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?>?
    val fragmentClasses: com.google.common.collect.ImmutableSet<java.lang.Class<out Fragment?>?>?
    val defines: com.google.common.collect.ImmutableSet<String?>?
    val starlarkOptions: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?>?

    init {
        this.starlarkOptions = starlarkOptions
        this.defines = defines
        this.fragmentClasses = fragmentClasses
        this.optionsClasses = optionsClasses
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?>?>(
            optionsClasses,
            "optionsClasses"
        )
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableSet<java.lang.Class<out Fragment?>?>?>(
            fragmentClasses,
            "fragmentClasses"
        )
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableSet<String?>?>(defines, "defines")
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?>?>(
            starlarkOptions,
            "starlarkOptions"
        )
    }

    companion object {
        private val interner: com.google.common.collect.Interner<RequiredConfigFragmentsProvider> =
            com.google.devtools.build.lib.concurrent.BlazeInterners.newWeakInterner<RequiredConfigFragmentsProvider?>()

        @SerializationConstant
        val EMPTY: RequiredConfigFragmentsProvider = RequiredConfigFragmentsProvider(
            com.google.common.collect.ImmutableSet.of<java.lang.Class<out FragmentOptions?>?>(),
            com.google.common.collect.ImmutableSet.of<java.lang.Class<out Fragment?>?>(),
            com.google.common.collect.ImmutableSet.of<String?>(),
            com.google.common.collect.ImmutableSet.of<com.google.devtools.build.lib.cmdline.Label?>()
        )

        /** Merges the values of one or more [RequiredConfigFragmentsProvider] instances.  */
        fun merge(
            providers: MutableList<RequiredConfigFragmentsProvider?>
        ): RequiredConfigFragmentsProvider? {
            if (providers.isEmpty()) {
                return EMPTY
            }
            var merged: Builder? = null
            var candidate: RequiredConfigFragmentsProvider? = EMPTY
            for (provider in providers) {
                if (provider === EMPTY) {
                    continue
                }
                if (merged != null) {
                    merged.merge(provider)
                } else if (candidate === EMPTY) {
                    candidate = provider
                } else {
                    merged = builder().merge(candidate).merge(provider)
                }
            }
            return if (merged == null) candidate else merged.build()
        }

        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return com.google.devtools.build.lib.analysis.RequiredConfigFragmentsProvider.Builder()
        }
    }
}

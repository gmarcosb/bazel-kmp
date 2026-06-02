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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant
import java.util.AbstractSet

/**
 * A wrapper class for an `ImmutableSortedSet<Class<? extends Fragment>>`. Interning these
 * objects allows us to do cheap reference equality checks when these sets are in frequently used
 * keys.
 */
@javax.annotation.concurrent.Immutable
class FragmentClassSet private constructor(
    fragments: com.google.common.collect.ImmutableSortedSet<java.lang.Class<out Fragment?>?>,
    hashCode: Int
) : AbstractSet<java.lang.Class<out Fragment?>?>() {
    private val fragments: com.google.common.collect.ImmutableSortedSet<java.lang.Class<out Fragment?>?>
    private val hashCode: Int

    init {
        this.fragments = fragments
        this.hashCode = hashCode
    }

    override fun size(): Int {
        return fragments.size
    }

    override fun contains(o: Any?): Boolean {
        return fragments.contains(o)
    }

    /** Returns a set of fragment classes identical to this one but without the given fragment.  */
    fun trim(fragment: java.lang.Class<out Fragment?>): FragmentClassSet? {
        if (!contains(fragment)) {
            return this
        }
        return of(
            com.google.common.collect.Sets.filter<java.lang.Class<out Fragment?>?>(
                fragments,
                com.google.common.base.Predicates.not<java.lang.Class<out Fragment?>?>(com.google.common.base.Predicate { obj: java.lang.Class<out Fragment?>? ->
                    fragment.equals(obj)
                })
            )
        )
    }

    override fun iterator(): MutableIterator<java.lang.Class<out Fragment?>?> {
        return fragments.iterator()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is FragmentClassSet) {
            return false
        }
        return hashCode == other.hashCode && fragments == other.fragments
    }

    override fun hashCode(): Int {
        return hashCode
    }

    override fun toString(): String {
        return com.google.common.collect.Collections2.transform<java.lang.Class<out Fragment?>?, String?>(
            fragments,
            com.google.common.base.Function { clazz: java.lang.Class<out Fragment?>? ->
                com.google.devtools.build.lib.util.ClassName.getSimpleNameWithOuter(clazz)
            }).toString()
    }

    companion object {
        /**
         * Sorts fragments by class name. This produces a stable order which, e.g., facilitates consistent
         * output from buildMnemonic.
         */
        @kotlin.jvm.JvmField
        @SerializationConstant
        val LEXICAL_FRAGMENT_SORTER: java.util.Comparator<java.lang.Class<out Fragment?>?> =
            java.util.Comparator.comparing<java.lang.Class<out Fragment?>?, String?>(java.util.function.Function { obj: java.lang.Class<out Fragment?>? -> obj.getName() })

        private val interner: com.google.common.collect.Interner<FragmentClassSet> =
            com.google.devtools.build.lib.concurrent.BlazeInterners.newWeakInterner<FragmentClassSet?>()

        fun of(fragments: MutableCollection<java.lang.Class<out Fragment?>?>): FragmentClassSet {
            val sortedFragments: com.google.common.collect.ImmutableSortedSet<java.lang.Class<out Fragment?>?> =
                com.google.common.collect.ImmutableSortedSet.copyOf<java.lang.Class<out Fragment?>?>(
                    LEXICAL_FRAGMENT_SORTER,
                    fragments
                )
            return interner.intern(FragmentClassSet(sortedFragments, sortedFragments.hashCode()))
        }
    }
}

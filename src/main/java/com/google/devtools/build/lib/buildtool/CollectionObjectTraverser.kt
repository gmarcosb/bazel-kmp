// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.collect.nestedset.NestedSet
import com.google.devtools.build.lib.util.MemoryAccountant.Measurer
import com.google.devtools.build.lib.util.ObjectGraphTraverser
import com.google.devtools.build.lib.util.ObjectGraphTraverser.DomainSpecificTraverser
import com.google.devtools.build.lib.util.ShallowObjectSizeComputer
import com.google.devtools.build.zip.ZipReader.entries

/** An object traverser that handles common collection classes.  */
class CollectionObjectTraverser

    : DomainSpecificTraverser, Measurer {
    override fun isInterned(o: Any?): Boolean {
        return false
    }

    override fun maybeGetShallowSize(o: Any): Long {
        return when (o) {
            -> ShallowObjectSizeComputer.getShallowSize(l) + ShallowObjectSizeComputer.getArraySize(
                l.size().toLong(),
                Any::class.java
            )

            -> ShallowObjectSizeComputer.getShallowSize(s) + ShallowObjectSizeComputer.getArraySize(
                s.size().toLong(),
                Any::class.java
            )

            ->  // 32 is an estimate for the per-entry overhead of Map and Multimap
                ShallowObjectSizeComputer.getShallowSize(m) + m.size() * 32L

            -> ShallowObjectSizeComputer.getShallowSize(mm) + mm.size() * 32L
            ->  // For CompactImmutableMap, we ignore OffsetTable: it's interned so it's difficult to
                // assign to any one SkyValue and there aren't supposed to be many of those anyway.
                ShallowObjectSizeComputer.getShallowSize(cim) + ShallowObjectSizeComputer.getArraySize(
                    cim.size().toLong(), Any::class.java
                )

            else -> -1
        }
    }

    override fun maybeTraverse(
        o: Any,
        traversal: com.google.devtools.build.lib.util.ObjectGraphTraverser.Traversal
    ): Boolean {
        when (o) {
            -> {
                traversal.objectFound(l, "List")
                for (m in l) {
                    traversal.edgeFound(m, null)
                }

                return true
            }

            -> {
                traversal.objectFound(s, "Set")
                for (m in s) {
                    traversal.edgeFound(m, null)
                }

                return true
            }

            -> {
                traversal.objectFound(m, "Map")
                for (e in m.entrySet()) {
                    traversal.edgeFound(e.getKey(), null)
                    traversal.edgeFound(e.getValue(), null)
                }

                return true
            }

            -> {
                traversal.objectFound(mm, "Multimap")
                for (e in mm.entries()) {
                    traversal.edgeFound(e.getKey(), null)
                    traversal.edgeFound(e.getValue(), null)
                }

                return true
            }

            -> {
                traversal.objectFound(cim, "CompactImmutableMap")
                for (k in cim) {
                    traversal.edgeFound(k, null)
                    traversal.edgeFound(cim.get(k), null)
                }
                return true
            }

            -> {
                traversal.objectFound(ns, "NestedSet")
                val children: Any?
                try {
                    children = NESTEDSET_CHILDREN.get(ns)
                } catch (e: java.lang.IllegalArgumentException) {
                    throw java.lang.IllegalStateException(e)
                } catch (e: java.lang.IllegalAccessException) {
                    throw java.lang.IllegalStateException(e)
                }

                if (children is Array<Any>) {
                    traversal.edgeFound(children, NESTEDSET_ARRAY)
                } else {
                    traversal.edgeFound(children, null)
                }

                return true
            }

            else -> {
                return false
            }
        }
    }

    override fun admit(o: Any?): Boolean {
        return true
    }

    override fun contextForArrayItem(from: Any?, fromContext: String?, to: Any?): String? {
        return null
    }

    override fun contextForField(from: Any?, fromContext: String?, field: java.lang.reflect.Field?, to: Any?): String? {
        return null
    }

    override fun ignoredFields(clazz: java.lang.Class<*>?): com.google.common.collect.ImmutableSet<String?>? {
        return null
    }

    companion object {
        private const val NESTEDSET_ARRAY = "Object[] NestedSet"
        private val NESTEDSET_CHILDREN: java.lang.reflect.Field

        init {
            try {
                NESTEDSET_CHILDREN = NestedSet::class.java.getDeclaredField("children")
                NESTEDSET_CHILDREN.setAccessible(true)
            } catch (e: java.lang.NoSuchFieldException) {
                throw java.lang.IllegalStateException(e)
            }
        }
    }
}

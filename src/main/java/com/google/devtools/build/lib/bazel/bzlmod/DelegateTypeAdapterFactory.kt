// Copyright 2023 The Bazel Authors. All rights reserved.
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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.io.IOException
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.SortedMap
import java.util.TreeMap

/**
 * Creates Gson type adapters for parameterized types by using a delegate parameterized type that
 * already has a registered type adapter factory.
 */
class DelegateTypeAdapterFactory<I, R : I?, D : I?>
private constructor(
    rawType: java.lang.Class<R?>?,
    intermediateToDelegateType: java.lang.Class<I?>?,
    delegateType: java.lang.Class<D?>,
    rawToDelegate: java.util.function.Function<R?, D?>,
    delegateToRaw: java.util.function.Function<D?, R?>
) : TypeAdapterFactory {
    private val rawType: java.lang.Class<R?>?
    private val intermediateToDelegateType: java.lang.Class<I?>?
    private val delegateType: java.lang.Class<D?>
    private val rawToDelegate: java.util.function.Function<R?, D?>
    private val delegateToRaw: java.util.function.Function<D?, R?>

    init {
        this.rawType = rawType
        this.intermediateToDelegateType = intermediateToDelegateType
        this.delegateType = delegateType
        this.rawToDelegate = rawToDelegate
        this.delegateToRaw = delegateToRaw
    }

    override fun <T> create(gson: Gson, typeToken: com.google.gson.reflect.TypeToken<T?>): TypeAdapter<T?>? {
        val type: java.lang.reflect.Type? = typeToken.getType()
        if (typeToken.getRawType() != rawType || type !is java.lang.reflect.ParameterizedType) {
            return null
        }

        val betterToken: com.google.common.reflect.TypeToken<*> =
            com.google.common.reflect.TypeToken.of(typeToken.getType())
        val delegateAdapter: TypeAdapter<Any?> =
            gson.getAdapter(
                com.google.gson.reflect.TypeToken.get(
                    betterToken
                        .getSupertype(intermediateToDelegateType as java.lang.Class<Any?>?)
                        .getSubtype(delegateType)
                        .getType()
                )
            ) as TypeAdapter<Any?>
        return object : TypeAdapter<T?>() {
            @Throws(IOException::class)
            override fun write(out: JsonWriter?, value: T?) {
                delegateAdapter.write(out, rawToDelegate.apply(value as R?))
            }

            @Throws(IOException::class)
            override fun read(`in`: JsonReader?): T? {
                return delegateToRaw.apply(delegateAdapter.read(`in`) as D?) as T?
            }
        }
    }

    companion object {
        val IMMUTABLE_MAP: TypeAdapterFactory =
            DelegateTypeAdapterFactory<MutableMap<*, *>?, com.google.common.collect.ImmutableMap<*, *>?, LinkedHashMap<*, *>?>(
                com.google.common.collect.ImmutableMap::class.java,
                MutableMap::class.java,
                LinkedHashMap::class.java,
                java.util.function.Function { raw: com.google.common.collect.ImmutableMap<*, *>? ->
                    LinkedHashMap<Any?, Any?>(
                        raw as MutableMap<*, *>?
                    )
                },
                java.util.function.Function { delegate: LinkedHashMap<*, *>? ->
                    com.google.common.collect.ImmutableMap.copyOf(
                        delegate as MutableMap<*, *>?
                    )
                })

        val IMMUTABLE_SORTED_MAP: TypeAdapterFactory =
            DelegateTypeAdapterFactory<SortedMap<*, *>?, com.google.common.collect.ImmutableSortedMap<*, *>?, TreeMap<*, *>?>(
                com.google.common.collect.ImmutableSortedMap::class.java,
                SortedMap::class.java,
                TreeMap::class.java,
                java.util.function.Function { raw: com.google.common.collect.ImmutableSortedMap<*, *>? ->
                    TreeMap<Any?, Any?>(
                        raw as SortedMap<*, *>?
                    )
                },
                java.util.function.Function { delegate: TreeMap<*, *>? ->
                    com.google.common.collect.ImmutableSortedMap.copyOf(
                        delegate as SortedMap<*, *>?
                    )
                })

        val IMMUTABLE_BIMAP: TypeAdapterFactory =
            DelegateTypeAdapterFactory<MutableMap<*, *>?, com.google.common.collect.ImmutableBiMap<*, *>?, LinkedHashMap<*, *>?>(
                com.google.common.collect.ImmutableBiMap::class.java,
                MutableMap::class.java,
                LinkedHashMap::class.java,
                java.util.function.Function { raw: com.google.common.collect.ImmutableBiMap<*, *>? ->
                    LinkedHashMap<Any?, Any?>(
                        raw as MutableMap<*, *>?
                    )
                },
                java.util.function.Function { delegate: LinkedHashMap<*, *>? ->
                    com.google.common.collect.ImmutableBiMap.copyOf(
                        delegate as MutableMap<*, *>?
                    )
                })

        val DICT: TypeAdapterFactory =
            DelegateTypeAdapterFactory<MutableMap<*, *>?, net.starlark.java.eval.Dict<*, *>?, LinkedHashMap<*, *>?>(
                net.starlark.java.eval.Dict::class.java,
                MutableMap::class.java,
                LinkedHashMap::class.java,
                java.util.function.Function { raw: net.starlark.java.eval.Dict<*, *>? -> LinkedHashMap<Any?, Any?>(raw as MutableMap<*, *>?) },
                java.util.function.Function { delegate: LinkedHashMap<*, *>? ->
                    net.starlark.java.eval.Dict.immutableCopyOf(
                        delegate as MutableMap<*, *>?
                    )
                })

        val IMMUTABLE_LIST: TypeAdapterFactory =
            DelegateTypeAdapterFactory<MutableList<*>?, com.google.common.collect.ImmutableList<*>?, java.util.ArrayList<*>?>(
                com.google.common.collect.ImmutableList::class.java,
                MutableList::class.java,
                java.util.ArrayList::class.java,
                java.util.function.Function { raw: com.google.common.collect.ImmutableList<*>? ->
                    java.util.ArrayList<Any?>(
                        raw as MutableList<*>?
                    )
                },
                java.util.function.Function { delegate: java.util.ArrayList<*>? ->
                    com.google.common.collect.ImmutableList.copyOf(
                        delegate as MutableList<*>?
                    )
                })

        val IMMUTABLE_SET: TypeAdapterFactory =
            DelegateTypeAdapterFactory<MutableSet<*>?, com.google.common.collect.ImmutableSet<*>?, LinkedHashSet<*>?>(
                com.google.common.collect.ImmutableSet::class.java,
                MutableSet::class.java,
                LinkedHashSet::class.java,
                java.util.function.Function { raw: com.google.common.collect.ImmutableSet<*>? -> LinkedHashSet<Any?>(raw as MutableSet<*>?) },
                java.util.function.Function { delegate: LinkedHashSet<*>? ->
                    com.google.common.collect.ImmutableSet.copyOf(
                        delegate as MutableSet<*>?
                    )
                })
    }
}

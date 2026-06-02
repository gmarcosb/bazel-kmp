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
package com.google.devtools.build.lib.skyframe.serialization.testutils

import java.util.concurrent.ConcurrentHashMap

/** A cache for [FieldInfo].  */
internal object FieldInfoCache {
    private val classInfoCache: ConcurrentHashMap<java.lang.Class<*>?, ClassInfo?> =
        ConcurrentHashMap<java.lang.Class<*>?, ClassInfo?>()

    private val CLOSED_CLASS_INFO = ClosedClassInfo()

    /**
     * Returns the [FieldInfo] list for the given `type`.
     * 
     * 
     * `type` must be in an accessible module or this will error.
     */
    fun getFieldInfo(type: java.lang.Class<*>?): com.google.common.collect.ImmutableList<FieldInfo?>? {
        return when (getClassInfo(type)) {
            -> fieldInfo
            -> throw java.lang.IllegalStateException("type in different, unopened module: " + type)
        }
    }

    fun getClassInfo(type: java.lang.Class<*>?): ClassInfo? {
        return classInfoCache.computeIfAbsent(
            type,
            java.util.function.Function { obj: java.lang.Class<*>? -> FieldInfoCache.getClassInfoUncached() })
    }

    private fun getClassInfoUncached(type: java.lang.Class<*>?): ClassInfo {
        val baseLookup: java.lang.invoke.MethodHandles.Lookup = java.lang.invoke.MethodHandles.lookup()

        val fieldInfo: com.google.common.collect.ImmutableList.Builder<FieldInfo?> =
            com.google.common.collect.ImmutableList.builder<FieldInfo?>()
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        return FieldInfoList(fieldInfo.build().reverse())
    }

    internal interface ClassInfo

    internal class FieldInfoList(fields: com.google.common.collect.ImmutableList<FieldInfo?>?) : ClassInfo {
        val fields: com.google.common.collect.ImmutableList<FieldInfo?>?

        init {
            this.fields = fields
        }
    }

    /** A class in a different module without add-opens where reflection is blocked.  */
    internal class ClosedClassInfo : ClassInfo

    internal interface FieldInfo

    private abstract class AbstractFieldInfo(
        field: java.lang.reflect.Field,
        privateLookup: java.lang.invoke.MethodHandles.Lookup
    ) {
        val name: String?
        val handle: java.lang.invoke.VarHandle

        init {
            this.name = field.getName()
            try {
                this.handle = privateLookup.unreflectVarHandle(field)
            } catch (e: java.lang.ReflectiveOperationException) {
                throw java.lang.IllegalStateException(e)
            }
        }

        fun name(): String? {
            return name
        }
    }

    internal class PrimitiveInfo private constructor(
        field: java.lang.reflect.Field,
        lookup: java.lang.invoke.MethodHandles.Lookup
    ) : AbstractFieldInfo(field, lookup), FieldInfo {
        fun getText(parent: Any?): String? {
            return handle.get(parent).toString()
        }
    }

    internal class ObjectInfo private constructor(
        field: java.lang.reflect.Field,
        privateLookup: java.lang.invoke.MethodHandles.Lookup
    ) : AbstractFieldInfo(field, privateLookup), FieldInfo {
        fun getFieldValue(parent: Any?): Any? {
            return handle.get(parent)
        }
    }
}

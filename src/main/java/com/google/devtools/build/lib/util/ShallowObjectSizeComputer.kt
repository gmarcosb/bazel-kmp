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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import com.sun.management.HotSpotDiagnosticMXBean
import java.util.concurrent.ConcurrentHashMap

/**
 * Utility class to calculate the shallow size of an object based on embedded knowledge about the
 * JVM.
 * 
 * 
 * "Shallow size" means that heap used by that given object, but not the ones it references. If
 * you want to know the "retained" size (i.e. the size of objects a given one transitively
 * references), you need to walk the object graph.
 */
object ShallowObjectSizeComputer {
    // "OOPS" stands for "Ordinary Object PointerS"
    private val COMPRESSED_OOPS = Layout(12, 8, 4, 4, 16)

    private val NO_COMPRESSED_OOPS = Layout(16, 8, 8, 8, 24)

    private val LAYOUT = Layout.Companion.currentLayout

    private val classSizeCache: ConcurrentHashMap<java.lang.Class<*>?, ClassSizes?> =
        ConcurrentHashMap<java.lang.Class<*>?, ClassSizes?>()

    /** Returns the size of a field containing the given type.  */
    private fun getStorageSize(clazz: java.lang.Class<*>): Long {
        if (!clazz.isPrimitive()) {
            return LAYOUT.referenceBytes
        } else if (clazz == Boolean::class.javaPrimitiveType) {
            return 1
        } else if (clazz == Byte::class.javaPrimitiveType) {
            return 1
        } else if (clazz == Char::class.javaPrimitiveType) {
            return java.lang.Character.BYTES.toLong()
        } else if (clazz == Short::class.javaPrimitiveType) {
            return java.lang.Short.BYTES.toLong()
        } else if (clazz == Int::class.javaPrimitiveType) {
            return java.lang.Integer.BYTES.toLong()
        } else if (clazz == Long::class.javaPrimitiveType) {
            return java.lang.Long.BYTES.toLong()
        } else if (clazz == Float::class.javaPrimitiveType) {
            return java.lang.Float.BYTES.toLong()
        } else if (clazz == Double::class.javaPrimitiveType) {
            return java.lang.Double.BYTES.toLong()
        } else {
            throw java.lang.IllegalStateException()
        }
    }

    private fun calculateClassSizes(clazz: java.lang.Class<*>): ClassSizes {
        var fieldsBytes: Long = 0
        for (f in clazz.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                fieldsBytes += getStorageSize(f.getType())
            }
        }

        val superClazz: java.lang.Class<*>? = clazz.getSuperclass()
        if (superClazz != null) {
            val superClazzSizes = getClassSizes(superClazz)
            fieldsBytes += roundUp(superClazzSizes.fieldsBytes, LAYOUT.superclassPaddingBytes)
        }

        return ClassSizes(
            fieldsBytes, roundUp(LAYOUT.objectHeaderBytes + fieldsBytes, LAYOUT.objectAlignment)
        )
    }

    /** Returns the size of an array of a given length containing the given type.  */
    fun getArraySize(length: Long, componentType: java.lang.Class<*>): Long {
        return roundUp(
            LAYOUT.arrayHeaderBytes + length * getStorageSize(componentType), LAYOUT.objectAlignment
        )
    }

    private fun getClassSizes(clazz: java.lang.Class<*>): ClassSizes {
        // computeIfAbsent() doesn't work because that cannot be called recursively and
        // calculateClassSizes() needs to call getClassSizes(). There is a race condition here, but it
        // is benign, since the result of the computation will always be the same, so the worst thing
        // that can happen is that we calculate the size of a class twice.
        var classSizes: ClassSizes? = classSizeCache.get(clazz)
        if (classSizes == null) {
            classSizes = calculateClassSizes(clazz)
            classSizeCache.putIfAbsent(clazz, classSizes)
        }

        return classSizes
    }

    /**
     * Returns the shallow size of objects of a given class.
     * 
     * 
     * Does not include memory used by static fields, memory used in metaspace, etc., only the
     * amount of memory used by instances of the given class.
     */
    fun getClassShallowSize(clazz: java.lang.Class<*>): Long {
        return getClassSizes(clazz).objectBytes
    }

    /** Returns the shallow size of an object.  */
    @kotlin.jvm.JvmStatic
    fun getShallowSize(o: Any): Long {
        val clazz: java.lang.Class<*> = o.javaClass
        if (!clazz.isArray()) {
            return getClassShallowSize(clazz)
        } else {
            return getArraySize(java.lang.reflect.Array.getLength(o).toLong(), clazz.getComponentType())
        }
    }

    private fun roundUp(x: Long, to: Long): Long {
        val ceil = (x + to - 1) / to
        return to * ceil
    }

    private class Layout(
        private val objectHeaderBytes: Long,
        private val objectAlignment: Long,
        private val referenceBytes: Long,
        private val superclassPaddingBytes: Long,
        private val arrayHeaderBytes: Long
    ) {
        companion object {
            val currentLayout: Layout
                get() {
                    check(
                        java.lang.System.getProperty("java.vm.name").startsWith("OpenJDK ")
                    ) { "Only OpenJDK is supported" }

                    check(java.lang.System.getProperty("sun.arch.data.model") == "64") { "Only 64-bit JVMs are supported" }

                    val diagnosticBean: HotSpotDiagnosticMXBean =
                        java.lang.management.ManagementFactory.getPlatformMXBean<HotSpotDiagnosticMXBean>(
                            HotSpotDiagnosticMXBean::class.java
                        )
                    val compressedOops: Boolean = diagnosticBean.getVMOption("UseCompressedOops").getValue().toBoolean()

                    return if (compressedOops) COMPRESSED_OOPS else NO_COMPRESSED_OOPS
                }
        }
    }

    private class ClassSizes(private val fieldsBytes: Long, private val objectBytes: Long)
}

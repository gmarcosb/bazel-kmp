// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.unsafe


/**
 * An accessor for Unsafe.
 * 
 * 
 * Used for serialization.
 */
// TODO: b/331765692 - clean this up
object UnsafeProvider {
    private val UNSAFE: sun.misc.Unsafe = unsafe

    @kotlin.jvm.JvmStatic
    fun unsafe(): sun.misc.Unsafe {
        return UNSAFE
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Throws(java.lang.NoSuchFieldException::class)
    fun getFieldOffset(type: java.lang.Class<*>, fieldName: String): Long {
        return UNSAFE.objectFieldOffset(type.getDeclaredField(fieldName))
    }

    private val unsafe: sun.misc.Unsafe
        /**
         * Gets a reference to [sun.misc.Unsafe] throwing an [AssertionError] on failure.
         * 
         * 
         * Failure is highly unlikely, but possible if the underlying VM stores unsafe in an unexpected
         * location.
         */
        get() {
            // sun.misc.Unsafe is intentionally difficult to get a hold of - it gives us the power to
            // do things like access raw memory and segfault the JVM.
            val unsafeClass: java.lang.Class<sun.misc.Unsafe> = sun.misc.Unsafe::class.java
            // Unsafe usually exists in the field 'theUnsafe', however check all fields
            // in case it's somewhere else in this VM's version of Unsafe.
            for (f in unsafeClass.getDeclaredFields()) {
                f.setAccessible(true)
                val fieldValue: Any?
                try {
                    fieldValue = f.get(null)
                } catch (e: java.lang.IllegalAccessException) {
                    throw java.lang.IllegalStateException(
                        "Failed to get value of %s even though it has been made accessible".formatted(f), e
                    )
                }
                if (unsafeClass.isInstance(fieldValue)) {
                    return unsafeClass.cast(fieldValue)
                }
            }
            throw java.lang.AssertionError("Failed to find sun.misc.Unsafe instance")
        }
}

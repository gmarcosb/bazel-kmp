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
package com.google.devtools.build.lib.util

/**
 * Helper library for hash codes that generates less garbage than [Objects.hash].
 * 
 * 
 * This uses the same underlying algorithm as the [Objects.hash] but explicitly overloads
 * for different argument counts instead of using varargs. The benefit is that it generates no
 * garbage.
 * 
 * 
 * [Object.hashCode] implementations tend to be garbage hotspots in Bazel, especially with
 * hashmaps.
 */
object HashCodes {
    /**
     * Value to use when composing two hash codes.
     * 
     * 
     * 31 is prime so modulo-multiplication is invertible (it spans the full range of values).
     * Multiplication by 31 also efficient because `31 * x` optimizes down to `(x << 5) - x`.
     */
    const val MULTIPLIER: Int = 31

    /** Returns the hashCode of a given object if it is non-null and 0 otherwise.  */
    @kotlin.jvm.JvmStatic
    fun hashObject(obj: Any?): Int {
        return if (obj == null) 0 else obj.hashCode()
    }

    @kotlin.jvm.JvmStatic
    fun hashObjects(o1: Any?, o2: Any?): Int {
        val h1 = hashObject(o1)
        val h2 = hashObject(o2)
        return h2 + (MULTIPLIER * (h1 + MULTIPLIER))
    }

    @kotlin.jvm.JvmStatic
    fun hashObjects(o1: Any?, o2: Any?, o3: Any?): Int {
        val h1 = hashObject(o1)
        val h2 = hashObject(o2)
        val h3 = hashObject(o3)
        return h3 + MULTIPLIER * (h2 + (MULTIPLIER * (h1 + MULTIPLIER)))
    }

    @kotlin.jvm.JvmStatic
    fun hashObjects(
        o1: Any?, o2: Any?, o3: Any?, o4: Any?
    ): Int {
        val h1 = hashObject(o1)
        val h2 = hashObject(o2)
        val h3 = hashObject(o3)
        val h4 = hashObject(o4)
        return h4 + MULTIPLIER * (h3 + MULTIPLIER * (h2 + (MULTIPLIER * (h1 + MULTIPLIER))))
    }

    @kotlin.jvm.JvmStatic
    fun hashObjects(
        o1: Any?,
        o2: Any?,
        o3: Any?,
        o4: Any?,
        o5: Any?
    ): Int {
        val h1 = hashObject(o1)
        val h2 = hashObject(o2)
        val h3 = hashObject(o3)
        val h4 = hashObject(o4)
        val h5 = hashObject(o5)
        return (h5
                + MULTIPLIER
                * (h4 + MULTIPLIER * (h3 + MULTIPLIER * (h2 + (MULTIPLIER * (h1 + MULTIPLIER))))))
    }

    @kotlin.jvm.JvmStatic
    fun hashObjects(
        o1: Any?,
        o2: Any?,
        o3: Any?,
        o4: Any?,
        o5: Any?,
        o6: Any?
    ): Int {
        val h1 = hashObject(o1)
        val h2 = hashObject(o2)
        val h3 = hashObject(o3)
        val h4 = hashObject(o4)
        val h5 = hashObject(o5)
        val h6 = hashObject(o6)
        return (h6
                + MULTIPLIER
                * (h5
                + MULTIPLIER
                * (h4
                + MULTIPLIER
                * (h3 + MULTIPLIER * (h2 + (MULTIPLIER * (h1 + MULTIPLIER)))))))
    }

    @kotlin.jvm.JvmStatic
    fun hashObjects(
        o1: Any?,
        o2: Any?,
        o3: Any?,
        o4: Any?,
        o5: Any?,
        o6: Any?,
        o7: Any?
    ): Int {
        val h1 = hashObject(o1)
        val h2 = hashObject(o2)
        val h3 = hashObject(o3)
        val h4 = hashObject(o4)
        val h5 = hashObject(o5)
        val h6 = hashObject(o6)
        val h7 = hashObject(o7)
        return (h7
                + MULTIPLIER
                * (h6
                + MULTIPLIER
                * (h5
                + MULTIPLIER
                * (h4
                + MULTIPLIER
                * (h3
                + MULTIPLIER * (h2 + (MULTIPLIER * (h1 + MULTIPLIER))))))))
    }

    @kotlin.jvm.JvmStatic
    fun hashObjects(
        o1: Any?,
        o2: Any?,
        o3: Any?,
        o4: Any?,
        o5: Any?,
        o6: Any?,
        o7: Any?,
        o8: Any?
    ): Int {
        val h1 = hashObject(o1)
        val h2 = hashObject(o2)
        val h3 = hashObject(o3)
        val h4 = hashObject(o4)
        val h5 = hashObject(o5)
        val h6 = hashObject(o6)
        val h7 = hashObject(o7)
        val h8 = hashObject(o8)
        return (h8
                + MULTIPLIER
                * (h7
                + MULTIPLIER
                * (h6
                + MULTIPLIER
                * (h5
                + MULTIPLIER
                * (h4
                + MULTIPLIER
                * (h3
                + MULTIPLIER
                * (h2 + (MULTIPLIER * (h1 + MULTIPLIER)))))))))
    }
}

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
package com.google.devtools.build.skyframe

import com.google.devtools.build.lib.concurrent.BlazeInterners

/** Versioning scheme based on integers.  */
class IntVersion private constructor(private val `val`: Long) : com.google.devtools.build.skyframe.Version {
    /** Returns the integer value as a long.  */
    fun getVal(): Long {
        return `val`
    }

    fun next(): IntVersion {
        return of(`val` + 1)
    }

    override fun atMost(other: com.google.devtools.build.skyframe.Version?): Boolean {
        if (other !is IntVersion) {
            return false
        }
        return `val` <= other.`val`
    }

    override fun hashCode(): Int {
        return java.lang.Long.hashCode(`val`)
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj !is IntVersion) {
            return false
        }
        return obj.`val` == `val`
    }

    override fun toString(): String {
        return "IntVersion: " + `val`
    }

    companion object {
        private val interner: com.google.common.collect.Interner<IntVersion> = BlazeInterners.newWeakInterner()

        @kotlin.jvm.JvmStatic
        fun of(`val`: Long): IntVersion {
            return interner.intern(IntVersion(`val`))
        }
    }
}

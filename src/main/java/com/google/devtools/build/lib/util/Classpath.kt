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
package com.google.devtools.build.lib.util

import java.io.IOException
import java.util.LinkedHashSet

/**
 * A helper class to find all classes on the current classpath. This is used to automatically create
 * JUnit 3 and 4 test suites.
 */
object Classpath {
    /** Finds all classes that live in or below the given package.  */
    @kotlin.jvm.JvmStatic
    @Throws(ClassPathException::class)
    fun findClasses(packageName: String?): MutableSet<java.lang.Class<*>?> {
        val result: MutableSet<java.lang.Class<*>?> = LinkedHashSet<java.lang.Class<*>?>()
        val packagePrefix: String? = (packageName + '.').replace('/', '.')
        try {
            for (ci in com.google.common.reflect.ClassPath.from(Classpath::class.java.getClassLoader())
                .getAllClasses()) {
                if (ci.getName().startsWith(packagePrefix)) {
                    try {
                        result.add(ci.load())
                    } catch (unused: java.lang.UnsatisfiedLinkError) {
                        // Ignore: we're most likely running on a different platform.
                    } catch (unused: java.lang.NoClassDefFoundError) {
                    }
                }
            }
        } catch (e: IOException) {
            throw ClassPathException(e.getMessage())
        }
        return result
    }

    /**
     * Base exception for any classpath related errors.
     */
    class ClassPathException(format: String, vararg args: Any?) :
        java.lang.Exception(java.lang.String.format(format, *args))
}

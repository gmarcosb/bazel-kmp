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

/**
 * A little utility to load resources (property files) from jars or the classpath. Recommended for
 * longer texts that do not fit nicely into a piece of Java code - e.g. a template for a lengthy
 * email.
 */
object ResourceFileLoader {
    fun resourceExists(relativeToClass: java.lang.Class<*>, resourceName: String): Boolean {
        try {
            getResourceAsStream(relativeToClass, resourceName).use { resourceStream ->
                return resourceStream != null
            }
        } catch (e: IOException) {
            return false
        }
    }

    /**
     * Loads a text resource that is located in a directory on the Java classpath that corresponds to
     * the package of `relativeToClass` using UTF8 encoding. E.g. `
     * loadResource(Class.forName("com.google.foo.Foo", "bar.txt"))` will look for `
     * com/google/foo/bar.txt` in the classpath.
     */
    @Throws(IOException::class)
    fun loadResource(relativeToClass: java.lang.Class<*>, resourceName: String): String {
        getResourceAsStream(relativeToClass, resourceName).use { stream ->
            if (stream == null) {
                throw IOException(resourceName + " not found.")
            }
            return String(com.google.common.io.ByteStreams.toByteArray(stream), java.nio.charset.StandardCharsets.UTF_8)
        }
    }

    private fun getResourceAsStream(relativeToClass: java.lang.Class<*>, resourceName: String): java.io.InputStream {
        val loader: java.lang.ClassLoader = relativeToClass.getClassLoader()
        val resource = resolveResource(relativeToClass, resourceName)
        return loader.getResourceAsStream(resource)
    }

    /**
     * Converts a relative resource name and Java class to a full resource path, using the same logic
     * as [.loadResource].
     */
    fun resolveResource(relativeToClass: java.lang.Class<*>, resourceName: String): String {
        // TODO(bazel-team): use relativeToClass.getPackage().getName().
        val className: String = relativeToClass.getName()
        val packageName: String = className.substring(0, className.lastIndexOf('.'))
        val path: String? = packageName.replace('.', '/')
        return path + '/' + resourceName
    }
}

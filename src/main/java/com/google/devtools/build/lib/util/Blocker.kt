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

/** Blocker wrapper for jdk.internal.misc.Blocker.  */
object Blocker {
    fun begin(): Any? {
        try {
            return com.google.devtools.build.lib.util.Blocker.BEGIN.invoke()
        } catch (e: Throwable) {
            throw java.lang.LinkageError(e.getMessage(), e)
        }
    }

    fun end(comp: Any?) {
        try {
            com.google.devtools.build.lib.util.Blocker.END.invoke(comp)
        } catch (e: Throwable) {
            throw java.lang.LinkageError(e.getMessage(), e)
        }
    }

    private val BEGIN: java.lang.invoke.MethodHandle = com.google.devtools.build.lib.util.Blocker.getBegin()

    private val END: java.lang.invoke.MethodHandle = com.google.devtools.build.lib.util.Blocker.getEnd()

    private fun blockerType(): java.lang.Class<*> {
        return if (java.lang.Runtime.version()
                .feature() >= 23
        ) Boolean::class.javaPrimitiveType else Long::class.javaPrimitiveType
    }

    private val end: java.lang.invoke.MethodHandle
        get() {
            try {
                return java.lang.invoke.MethodHandles.lookup()
                    .findStatic(
                        jdk.internal.misc.Blocker::class.java,
                        "end",
                        java.lang.invoke.MethodType.methodType(
                            Void.TYPE,
                            com.google.devtools.build.lib.util.Blocker.blockerType()
                        )
                    )
            } catch (e: java.lang.ReflectiveOperationException) {
                throw java.lang.LinkageError(e.getMessage(), e)
            }
        }

    private val begin: java.lang.invoke.MethodHandle
        get() {
            try {
                return java.lang.invoke.MethodHandles.lookup()
                    .findStatic(
                        jdk.internal.misc.Blocker::class.java,
                        "begin",
                        java.lang.invoke.MethodType.methodType(com.google.devtools.build.lib.util.Blocker.blockerType())
                    )
            } catch (e: java.lang.ReflectiveOperationException) {
                throw java.lang.LinkageError(e.getMessage(), e)
            }
        }
}

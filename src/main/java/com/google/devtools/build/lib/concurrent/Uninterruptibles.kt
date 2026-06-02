// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.concurrent

/**
 * Helper class for dealing with [InterruptedException].
 */
object Uninterruptibles {
    /**
     * Calls the given callable uninterruptibly.
     * 
     * 
     * If the callable throws [InterruptedException], calls it again, until the callable
     * returns a result. Sets the `currentThread().interrupted()` bit if the callable threw
     * [InterruptedException] at least once.
     */
    @Throws(java.lang.Exception::class)
    fun <T> callUninterruptibly(callable: java.util.concurrent.Callable<T?>): T? {
        var interrupted = false
        try {
            while (true) {
                try {
                    return callable.call()
                } catch (e: java.lang.InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) {
                java.lang.Thread.currentThread().interrupt()
            }
        }
    }
}


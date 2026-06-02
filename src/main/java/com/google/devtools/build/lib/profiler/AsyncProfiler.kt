// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.profiler

/**
 * A profiler that can be used to profile async operations.
 * 
 * 
 * This profiler is thread-compatible but not thread-safe. You should create one profiler per
 * task.
 */
@com.google.devtools.build.lib.skybridge.SkybridgeInterface
interface AsyncProfiler : com.google.devtools.build.lib.profiler.SilentCloseable {
    fun profile(
        type: com.google.devtools.build.lib.profiler.ProfilerTask?,
        description: String?
    ): com.google.devtools.build.lib.profiler.SilentCloseable?

    fun profile(description: String?): com.google.devtools.build.lib.profiler.SilentCloseable?

    fun <T> profileFuture(
        future: com.google.common.util.concurrent.ListenableFuture<T?>?,
        description: String?
    ): com.google.common.util.concurrent.ListenableFuture<T?>?

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun <T> profileFuture(
        future: com.google.common.util.concurrent.ListenableFuture<T?>?,
        type: com.google.devtools.build.lib.profiler.ProfilerTask?,
        description: String?
    ): com.google.common.util.concurrent.ListenableFuture<T?>?

    fun profileCallback(runnable: java.lang.Runnable?, description: String?): java.lang.Runnable?

    fun profileCallback(
        runnable: java.lang.Runnable?,
        type: com.google.devtools.build.lib.profiler.ProfilerTask?,
        description: String?
    ): java.lang.Runnable?

    fun <T> profileCallback(
        consumer: java.util.function.Consumer<T?>?,
        description: String?
    ): java.util.function.Consumer<T?>?

    fun <T> profileCallback(
        consumer: java.util.function.Consumer<T?>?,
        type: com.google.devtools.build.lib.profiler.ProfilerTask?,
        description: String?
    ): java.util.function.Consumer<T?>?
}

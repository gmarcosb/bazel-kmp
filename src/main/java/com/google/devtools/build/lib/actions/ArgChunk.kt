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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.actions.PathMapper

/**
 * Post-[expansion][CommandLine.expand] representation of command line arguments.
 * 
 * 
 * This differs from [CommandLine] in that consuming the arguments is guaranteed to be free
 * of [CommandLineExpansionException] and [InterruptedException].
 */
interface ArgChunk {
    /**
     * Returns the arguments.
     * 
     * 
     * The returned [Iterable] may lazily materialize strings during iteration, so consumers
     * should attempt to avoid iterating more times than necessary.
     */
    fun arguments(pathMapper: PathMapper?): Iterable<String?>?

    /**
     * Counts the total length of all arguments in this chunk.
     * 
     * 
     * Implementations that lazily materialize strings may be able to compute the total argument
     * length without actually materializing the arguments.
     */
    fun totalArgLength(pathMapper: PathMapper?): Int
}

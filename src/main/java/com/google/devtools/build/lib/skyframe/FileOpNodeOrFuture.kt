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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.concurrent.SettableFutureKeyedValue

/**
 * A possibly empty nested set of file system operations.
 * 
 * 
 * This value represents the set of file system operation dependencies of a given Skyframe entry,
 * computed by Skyframe graph traversal.
 */
// sealed hierarchy root
interface FileOpNodeOrFuture {
    /** A possibly empty set of file system dependencies.  */
    interface FileOpNodeOrEmpty : FileOpNodeOrFuture


    /** A non-empty set of filesystem operations.  */
    interface FileOpNode : FileOpNodeOrEmpty


    /** Empty set of filesystem dependencies.  */
    enum class EmptyFileOpNode : FileOpNodeOrEmpty {
        EMPTY_FILE_OP_NODE
    }

    /** The in-flight computation of a [FileOpNodeOrEmpty].  */
    class FutureFileOpNode
        (key: SkyKey?, consumer: java.util.function.BiConsumer<SkyKey?, FileOpNodeOrEmpty?>?) :
        SettableFutureKeyedValue<FutureFileOpNode?, SkyKey?, FileOpNodeOrEmpty?>(key, consumer), FileOpNodeOrFuture
}

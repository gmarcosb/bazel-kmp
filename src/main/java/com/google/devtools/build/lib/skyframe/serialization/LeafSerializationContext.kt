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
package com.google.devtools.build.lib.skyframe.serialization

import com.google.devtools.build.lib.skyframe.serialization.LeafObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.SerializationDependencyProvider
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/**
 * Context provided to [LeafObjectCodec] implementations.
 * 
 * 
 * This context permits delegation only to other [LeafObjectCodec] instances and dependency
 * lookups.
 */
interface LeafSerializationContext : SerializationDependencyProvider {
    /** Serializes `obj` using `codec` into `codedOut`.  */
    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun <T> serializeLeaf(
        obj: T?, codec: LeafObjectCodec<T?>?, codedOut: CodedOutputStream?
    )
}

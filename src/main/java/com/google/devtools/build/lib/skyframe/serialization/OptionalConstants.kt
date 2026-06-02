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
package com.google.devtools.build.lib.skyframe.serialization

import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant

/**
 * Declares serialization constants for empty optionals.
 * 
 * 
 * Upstream library constants cannot be annotated directly.
 */
@com.google.errorprone.annotations.Keep
internal object OptionalConstants {
    @SerializationConstant
    val EMPTY_JDK_OPTIONAL: java.util.Optional<*> = java.util.Optional.empty<Any?>()

    @SerializationConstant
    val EMPTY_GUAVA_OPTIONAL: com.google.common.base.Optional<*> = com.google.common.base.Optional.absent<Any?>()
}

// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.stringtemplate

import com.google.common.collect.ImmutableSet
import java.util.*

/**
 * Holds the result of expanding a string with make variables: both the new (expanded) string and
 * the set of variables that were expanded.
 */
@kotlin.jvm.JvmRecord
data class Expansion(val expansion: String?, val lookedUpVariables: ImmutableSet<String?>?) {
    init {
        Objects.requireNonNull<String?>(expansion, "expansion")
        Objects.requireNonNull<ImmutableSet<String?>?>(lookedUpVariables, "lookedUpVariables")
    }

    companion object {
        fun create(expansion: String?, lookedUpVariables: ImmutableSet<String?>?): Expansion {
            return Expansion(expansion, lookedUpVariables)
        }
    }
}

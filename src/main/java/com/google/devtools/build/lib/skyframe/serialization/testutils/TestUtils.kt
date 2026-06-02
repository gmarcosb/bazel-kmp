// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization.testutils

import com.google.common.truth.Truth
import com.google.devtools.build.lib.skyframe.serialization.AutoRegistry
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry

/** Helpers for serialization tests.  */
object TestUtils {
    /**
     * Asserts that two [Module]s have the same structure. Needed because [Module] doesn't
     * override [Object.equals].
     */
    fun assertModulesEqual(module1: net.starlark.java.eval.Module, module2: net.starlark.java.eval.Module) {
        Truth.assertThat(module1.getClientData()).isEqualTo(module2.getClientData())
        Truth.assertThat(module1.getGlobals()).containsExactlyEntriesIn(module2.getGlobals()).inOrder()
        Truth.assertThat(module1.getPredeclaredBindings())
            .containsExactlyEntriesIn(module2.getPredeclaredBindings())
            .inOrder()
    }

    fun getBuilderWithAdditionalCodecs(
        vararg codecs: ObjectCodec<*>?
    ): com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry.Builder {
        val builder: com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry.Builder =
            AutoRegistry.get().getBuilder()
        for (codec in codecs) {
            builder.add(codec)
        }
        return builder
    }
}

// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.vfs

import com.google.common.truth.Truth
import org.junit.runner.RunWith

/**
 * Tests different [DigestHashFunction] for consistency between the MessageDigests and the
 * HashFunctions that it exposes.
 */
@RunWith(org.junit.runners.Parameterized::class)
class DigestHashFunctionsTest {
    @org.junit.runners.Parameterized.Parameter
    var digestHashFunction: DigestHashFunction? = null

    private fun assertHashFunctionAndMessageDigestEquivalentForInput(input: ByteArray?) {
        val hashFunctionOutput: ByteArray? = digestHashFunction.getHashFunction().hashBytes(input).asBytes()
        val messageDigestOutput: ByteArray? = digestHashFunction.newMessageDigest().digest(input)
        Truth.assertThat(hashFunctionOutput).isEqualTo(messageDigestOutput)
    }

    @org.junit.Test
    fun emptyDigestIsConsistent() {
        assertHashFunctionAndMessageDigestEquivalentForInput(byteArrayOf())
    }

    @org.junit.Test
    fun shortDigestIsConsistent() {
        assertHashFunctionAndMessageDigestEquivalentForInput("Bazel".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
    }

    companion object {
        @org.junit.runners.Parameterized.Parameters(name = "{index}: digestHashFunction={0}")
        fun hashFunctions(): MutableCollection<Array<DigestHashFunction?>?> {
            // TODO(b/112537387): Remove the array-ification and return Collection<DigestHashFunction>. This
            // is possible in Junit4.12, but 4.11 requires the array. Bazel 0.18 will have Junit4.12, so
            // this can change then.
            return DigestHashFunction.getPossibleHashFunctions()
                .stream()
                .map({ dhf -> arrayOf<DigestHashFunction?>(dhf) })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        }
    }
}

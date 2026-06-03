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
package com.google.devtools.build.lib.concurrent

import com.google.devtools.build.lib.concurrent.PaddedAddresses.ALIGNMENT
import org.junit.Test
import sun.misc.Unsafe

@RunWith(JUnit4::class)
// TODO: b/359688989 - clean this up
class PaddedAddressesTest {
    // TODO: b/386384684 - remove Unsafe usage
    @Test
    fun createdAddresses_areAligned() {
        val address: Long = createPaddedBaseAddress(2)

        val first: Long = getAlignedAddress(address,  /* offset= */0)
        assertThat(first and (ALIGNMENT - 1)).isEqualTo(0)

        val second: Long = getAlignedAddress(address,  /* offset= */1)
        Truth.assertThat(second - first).isEqualTo(ALIGNMENT)

        UNSAFE.freeMemory(address)
    }

    companion object {
        private val UNSAFE: Unsafe = UnsafeProvider.unsafe()
    }
}

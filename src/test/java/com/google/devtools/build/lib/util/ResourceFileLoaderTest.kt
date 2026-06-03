// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util

import com.google.common.truth.Truth
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.IOException

/**
 * A test for [ResourceFileLoader].
 */
@RunWith(JUnit4::class)
class ResourceFileLoaderTest {
    @org.junit.Test
    @Throws(IOException::class)
    fun loader() {
        val message: String? = ResourceFileLoader.loadResource(
            ResourceFileLoaderTest::class.java, "ResourceFileLoaderTest.message"
        )
        Truth.assertThat(message).isEqualTo("Hello, world.")
    }

    @org.junit.Test
    fun resourceNotFound() {
        val e: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable {
                    ResourceFileLoader.loadResource(
                        ResourceFileLoaderTest::class.java, "does_not_exist.txt"
                    )
                })
        Truth.assertThat(e).hasMessageThat().isEqualTo("does_not_exist.txt not found.")
    }
}

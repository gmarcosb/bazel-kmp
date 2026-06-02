// Copyright 2022 The Bazel Authors. All rights reserved.
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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.LockfileMode

/** Tests for [RegistryFactory].  */
@RunWith(JUnit4::class)
class RegistryFactoryTest {
    @org.junit.Test
    fun badSchemes() {
        val registryFactory: RegistryFactory =
            RegistryFactoryImpl(com.google.common.base.Suppliers.ofInstance<T?>(com.google.common.collect.ImmutableMap.of<Any?, Any?>()))
        var exception: Throwable? =
            org.junit.Assert.assertThrows<URISyntaxException?>(
                URISyntaxException::class.java,
                org.junit.function.ThrowingRunnable {
                    registryFactory.createRegistry(
                        "/home/www",
                        LockfileMode.UPDATE,
                        com.google.common.collect.ImmutableMap.of<K?, V?>(),
                        com.google.common.collect.ImmutableMap.of<K?, V?>(),
                        java.util.Optional.empty<T?>(),
                        com.google.common.collect.ImmutableSet.of<E?>()
                    )
                })
        Truth.assertThat(exception).hasMessageThat().contains("Registry URL has no scheme")
        exception =
            org.junit.Assert.assertThrows<URISyntaxException?>(
                URISyntaxException::class.java,
                org.junit.function.ThrowingRunnable {
                    registryFactory.createRegistry(
                        "foo://bar",
                        LockfileMode.UPDATE,
                        com.google.common.collect.ImmutableMap.of<K?, V?>(),
                        com.google.common.collect.ImmutableMap.of<K?, V?>(),
                        java.util.Optional.empty<T?>(),
                        com.google.common.collect.ImmutableSet.of<E?>()
                    )
                })
        Truth.assertThat(exception).hasMessageThat().contains("Unrecognized registry URL protocol")
    }

    @org.junit.Test
    fun badPath() {
        val registryFactory: RegistryFactory =
            RegistryFactoryImpl(com.google.common.base.Suppliers.ofInstance<T?>(com.google.common.collect.ImmutableMap.of<Any?, Any?>()))
        val exception: Throwable? =
            org.junit.Assert.assertThrows<URISyntaxException?>(
                URISyntaxException::class.java,
                org.junit.function.ThrowingRunnable {
                    registryFactory.createRegistry(
                        "file:c:/path/to/workspace/registry",
                        LockfileMode.UPDATE,
                        com.google.common.collect.ImmutableMap.of<K?, V?>(),
                        com.google.common.collect.ImmutableMap.of<K?, V?>(),
                        java.util.Optional.empty<T?>(),
                        com.google.common.collect.ImmutableSet.of<E?>()
                    )
                })
        Truth.assertThat(exception).hasMessageThat().contains("Registry URL path is not valid")
    }
}

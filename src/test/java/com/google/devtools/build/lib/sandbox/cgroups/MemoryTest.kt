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
package com.google.devtools.build.lib.sandbox.cgroups

import com.google.common.io.Files
import com.google.devtools.build.lib.sandbox.cgroups.controller.Controller.Memory
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

@RunWith(JUnit4::class)
class MemoryTest {
    private val scratch: FsApparatus = FsApparatus.newNative()

    @Test
    @Throws(IOException::class)
    fun setMemoryLimit_v1() {
        val limit: File = scratch.file("cgroup/memory/memory.limit_in_bytes", "0").getPathFile()
        val memory: Memory = LegacyMemory(scratch.path("cgroup/memory").getPathFile().toPath())
        memory.maxBytes = 1000
        Truth.assertThat(Files.asCharSource(limit, StandardCharsets.UTF_8).read()).isEqualTo("1000")
    }

    @Test
    @Throws(IOException::class)
    fun getMemoryLimit_v1() {
        scratch.file("cgroup/memory/memory.limit_in_bytes", "100")
        val memory: Memory = LegacyMemory(scratch.path("cgroup/memory").getPathFile().toPath())
        assertThat(memory.maxBytes).isEqualTo(100)
    }

    @Test
    @Throws(IOException::class)
    fun getMemoryUsage_v1() {
        scratch.file("cgroup/memory/memory.usage_in_bytes", "2000")
        val memory: Memory = LegacyMemory(scratch.path("cgroup/memory").getPathFile().toPath())
        assertThat(memory.usageInBytes).isEqualTo(2000)
    }

    @Test
    @Throws(IOException::class)
    fun setMemoryLimit_v2() {
        val limit: File = scratch.file("cgroup/memory/memory.max", "0").getPathFile()
        val swap: File = scratch.file("cgroup/memory/memory.swap.max", "0").getPathFile()
        val memory: Memory = UnifiedMemory(scratch.path("cgroup/memory").getPathFile().toPath())
        memory.maxBytes = 1000
        Truth.assertThat(Files.asCharSource(limit, StandardCharsets.UTF_8).read()).isEqualTo("1000")
        Truth.assertThat(Files.asCharSource(swap, StandardCharsets.UTF_8).read()).isEqualTo("0")
    }

    @Test
    @Throws(IOException::class)
    fun getMemoryLimit_v2() {
        scratch.file("cgroup/memory/memory.max", "100")
        val memory: Memory = UnifiedMemory(scratch.path("cgroup/memory").getPathFile().toPath())
        assertThat(memory.maxBytes).isEqualTo(100)
    }

    @Test
    @Throws(IOException::class)
    fun getMemoryUsage_v2() {
        scratch.file("cgroup/memory/memory.current", "2000")
        val memory: Memory = UnifiedMemory(scratch.path("cgroup/memory").getPathFile().toPath())
        assertThat(memory.usageInBytes).isEqualTo(2000)
    }
}

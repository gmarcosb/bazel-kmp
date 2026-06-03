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
import com.google.devtools.build.lib.sandbox.cgroups.controller.Controller.Cpu
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

@RunWith(JUnit4::class)
class CpuTest {
    private val scratch: FsApparatus = FsApparatus.newNative()

    @Test
    @Throws(IOException::class)
    fun setCpuLimit_v1() {
        val quota: File = scratch.file("cgroup/cpu/cpu.cfs_quota_us", "-1").getPathFile()
        scratch.file("cgroup/cpu/cpu.cfs_period_us", "1000")
        val cpu: Cpu = LegacyCpu(scratch.path("cgroup/cpu").getPathFile().toPath())
        cpu.setCpus(3)
        Truth.assertThat(Files.asCharSource(quota, StandardCharsets.UTF_8).read()).isEqualTo("3000")
    }

    @Test
    @Throws(IOException::class)
    fun getCpuLimit_v1() {
        scratch.file("cgroup/cpu/cpu.cfs_quota_us", "4000")
        scratch.file("cgroup/cpu/cpu.cfs_period_us", "1000")
        val cpu: Cpu = LegacyCpu(scratch.path("cgroup/cpu").getPathFile().toPath())
        assertThat(cpu.cpus).isEqualTo(4)
    }

    @Test
    @Throws(IOException::class)
    fun setCpuLimit_v2() {
        val limit: File = scratch.file("cgroup/cpu/cpu.max", "-1 100000").getPathFile()
        val cpu: Cpu = UnifiedCpu(scratch.path("cgroup/cpu").getPathFile().toPath())
        cpu.setCpus(5)
        Truth.assertThat(Files.asCharSource(limit, StandardCharsets.UTF_8).read()).isEqualTo("500000 100000")
    }

    @Test
    @Throws(IOException::class)
    fun setCpuLimitNewLine_v2() {
        val limit: File = scratch.file("cgroup/cpu/cpu.max", "-1 100000\n").getPathFile()
        val cpu: Cpu = UnifiedCpu(scratch.path("cgroup/cpu").getPathFile().toPath())
        cpu.setCpus(5)
        Truth.assertThat(Files.asCharSource(limit, StandardCharsets.UTF_8).read()).isEqualTo("500000 100000")
    }

    @Test
    @Throws(IOException::class)
    fun getCpuLimit_v2() {
        scratch.file("cgroup/cpu/cpu.max", "6000 1000")
        val cpu: Cpu = UnifiedCpu(scratch.path("cgroup/cpu").getPathFile().toPath())
        assertThat(cpu.cpus).isEqualTo(6)
    }

    @Test
    @Throws(IOException::class)
    fun getCpuLimitNewLine_v2() {
        scratch.file("cgroup/cpu/cpu.max", "6000 1000\n")
        val cpu: Cpu = UnifiedCpu(scratch.path("cgroup/cpu").getPathFile().toPath())
        assertThat(cpu.cpus).isEqualTo(6)
    }

    @Test
    @Throws(IOException::class)
    fun getCpuLimitMax_v2() {
        scratch.file("cgroup/cpu/cpu.max", "max 1000\n")
        val cpu: Cpu = UnifiedCpu(scratch.path("cgroup/cpu").getPathFile().toPath())
        assertThat(cpu.cpus).isEqualTo(Runtime.getRuntime().availableProcessors())
    }
}

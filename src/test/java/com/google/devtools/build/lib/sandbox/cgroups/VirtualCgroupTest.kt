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
import com.google.devtools.build.lib.sandbox.cgroups.proto.CgroupsInfoProtos.CgroupControllerInfo
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

@RunWith(JUnit4::class)
class VirtualCgroupTest {
    private val scratch: FsApparatus = FsApparatus.newNative()

    @Throws(IOException::class)
    private fun updateV1(path: String?) {
        scratch.file(path + "/cgroup.procs")
    }

    @Throws(IOException::class)
    private fun createV1(): VirtualCgroup {
        val mounts: File? =
            scratch
                .file(
                    "proc/self/mounts",
                    "sysfs /sys sysfs rw,nosuid,nodev,noexec,relatime 0 0",
                    String.format(
                        "cgroup %s/dev/cgroup/memory cgroup rw,memory,hugetlb 0 0", scratch.path("")
                    ),
                    String.format("cgroup %s/dev/cgroup/cpuset cgroup rw,cpuset 0 0", scratch.path("")),
                    String.format(
                        "cgroup %s/dev/cgroup/cpu cgroup rw,cpu,cpuacct 0 0", scratch.path("")
                    ),
                    String.format("cgroup %s/dev/cgroup/blkio cgroup rw,blkio 0 0", scratch.path("")),
                    "proc /proc proc rw,nosuid,nodev,noexec,relatime 0 0"
                )
                .getPathFile()
        val hierarchies: File? =
            scratch
                .file(
                    "proc/self/cgroup",
                    "4:memory,hugetlb:/user.slice",
                    "3:cpuset:/",
                    "2:cpu,cpuacct:/user.slice",
                    "1:blkio:/user.slice"
                )
                .getPathFile()
        updateV1("dev/cgroup/memory/user.slice")
        updateV1("dev/cgroup/cpuset")
        updateV1("dev/cgroup/cpu/user.slice")
        updateV1("dev/cgroup/blkio/user.slice")
        return VirtualCgroup.createRoot(mounts, hierarchies)
    }

    @Throws(IOException::class)
    private fun updateV2(path: String?, controllers: String?) {
        scratch.file(path + "/cgroup.procs")
        scratch.file(path + "/cgroup.controllers", controllers)
        scratch.file(path + "/cgroup.subtree_control")
    }

    @Throws(IOException::class)
    private fun createV2(): VirtualCgroup {
        val mounts: File? =
            scratch
                .file(
                    "proc/self/mounts",
                    "sysfs /sys sysfs rw,nosuid,nodev,noexec,relatime 0 0",
                    String.format("cgroup %s/dev/cgroup/unified cgroup2 ro 0 0", scratch.path("")),
                    "proc /proc proc rw,nosuid,nodev,noexec,relatime 0 0"
                )
                .getPathFile()
        val hierarchies: File? = scratch.file("proc/self/cgroup", "0::/user.slice").getPathFile()
        updateV2("dev/cgroup/unified", "memory cpu")
        updateV2("dev/cgroup/unified/user.slice", "memory cpu")
        return VirtualCgroup.createRoot(mounts, hierarchies)
    }

    @Throws(IOException::class)
    private fun createHybrid(): VirtualCgroup {
        val mounts: File? =
            scratch
                .file(
                    "proc/self/mounts",
                    "sysfs /sys sysfs rw,nosuid,nodev,noexec,relatime 0 0",
                    String.format("cgroup %s/dev/cgroup/cpuset cgroup rw,cpuset 0 0", scratch.path("")),
                    String.format(
                        "cgroup %s/dev/cgroup/cpu cgroup rw,cpu,cpuacct 0 0", scratch.path("")
                    ),
                    String.format("cgroup %s/dev/cgroup/blkio cgroup rw,blkio 0 0", scratch.path("")),
                    String.format("cgroup %s/dev/cgroup/unified cgroup2 ro 0 0", scratch.path("")),
                    "proc /proc proc rw,nosuid,nodev,noexec,relatime 0 0"
                )
                .getPathFile()
        val hierarchies: File? =
            scratch
                .file(
                    "proc/self/cgroup",
                    "3:cpuset:/",
                    "2:cpu,cpuacct:/user.slice",
                    "1:blkio:/user.slice",
                    "0::/user.slice"
                )
                .getPathFile()
        updateV1("dev/cgroup/cpuset")
        updateV1("dev/cgroup/cpu/user.slice")
        updateV1("dev/cgroup/blkio/user.slice")

        updateV2("dev/cgroup/unified", "memory pids")
        updateV2("dev/cgroup/unified/user.slice", "memory pids")
        return VirtualCgroup.createRoot(mounts, hierarchies)
    }

    @Test
    @Throws(IOException::class)
    fun testGetRootCgroup_v1() {
        val vcg: VirtualCgroup = createV1()
        assertThat(vcg.cpu()).isNotNull()
        assertThat(vcg.memory()).isNotNull()
        assertThat(vcg.cpu().isLegacy).isTrue()
        assertThat(vcg.memory().isLegacy).isTrue()
        assertThat(vcg.cpu().path)
            .isEqualTo(scratch.path("dev/cgroup/cpu/user.slice").getPathFile().toPath())
        assertThat(vcg.memory().path)
            .isEqualTo(scratch.path("dev/cgroup/memory/user.slice").getPathFile().toPath())
    }

    @Test
    @Throws(IOException::class)
    fun testGetRootCgroup_v2() {
        val vcg: VirtualCgroup = createV2()
        assertThat(vcg.cpu()).isNotNull()
        assertThat(vcg.memory()).isNotNull()
        assertThat(vcg.cpu().isLegacy).isFalse()
        assertThat(vcg.memory().isLegacy).isFalse()
        assertThat(vcg.cpu().path)
            .isEqualTo(scratch.path("dev/cgroup/unified").getPathFile().toPath())
        assertThat(vcg.memory().path)
            .isEqualTo(scratch.path("dev/cgroup/unified").getPathFile().toPath())
    }

    @Test
    @Throws(IOException::class)
    fun testGetRootCgroup_mixed() {
        val vcg: VirtualCgroup = createHybrid()
        assertThat(vcg.cpu()).isNotNull()
        assertThat(vcg.memory()).isNotNull()
        assertThat(vcg.cpu().isLegacy).isTrue()
        assertThat(vcg.memory().isLegacy).isFalse()
        assertThat(vcg.cpu().path)
            .isEqualTo(scratch.path("dev/cgroup/cpu/user.slice").getPathFile().toPath())
        assertThat(vcg.memory().path)
            .isEqualTo(scratch.path("dev/cgroup/unified").getPathFile().toPath())
    }

    @Test
    @Throws(IOException::class)
    fun testCreateChild() {
        val vcg: VirtualCgroup = createHybrid()
        val child: VirtualCgroup = vcg.createChild("foo")
        assertThat(child.cpu()).isNotNull()
        assertThat(child.memory()).isNotNull()
        assertThat(child.memory().path)
            .isEqualTo(scratch.path("dev/cgroup/unified/foo").getPathFile().toPath())
        assertThat(child.cpu().path)
            .isEqualTo(scratch.path("dev/cgroup/cpu/user.slice/foo").getPathFile().toPath())
        val subtree: File = vcg.memory().path.resolve("cgroup.subtree_control").toFile()
        Truth.assertThat(Files.asCharSource(subtree, StandardCharsets.UTF_8).read()).isEqualTo("+memory +pids ")
    }

    @Test
    @Throws(IOException::class)
    fun testNullCgroupCreatesNullChild() {
        val child: VirtualCgroup = VirtualCgroup.NULL.createChild("foo")
        assertThat(child.cpu()).isNull()
        assertThat(child.cpu()).isNull()
    }

    @Test
    @Throws(IOException::class)
    fun testAddProcess_v1() {
        val vcg: VirtualCgroup = createV1()
        vcg.addProcess(1234)
        Truth.assertThat(
            Files.asCharSource(vcg.cpu().path.resolve("cgroup.procs").toFile(), StandardCharsets.UTF_8).read()
        )
            .isEqualTo("1234")
        Truth.assertThat(
            Files.asCharSource(vcg.memory().path.resolve("cgroup.procs").toFile(), StandardCharsets.UTF_8)
                .read()
        )
            .isEqualTo("1234")
    }

    @Test
    @Throws(IOException::class)
    fun testAddProcess_v2() {
        val vcg: VirtualCgroup = createV2()
        vcg.addProcess(1234)
        Truth.assertThat(
            Files.asCharSource(vcg.cpu().path.resolve("cgroup.procs").toFile(), StandardCharsets.UTF_8).read()
        )
            .isEqualTo("1234")
        Truth.assertThat(
            Files.asCharSource(vcg.memory().path.resolve("cgroup.procs").toFile(), StandardCharsets.UTF_8)
                .read()
        )
            .isEqualTo("1234")
    }

    @Test
    @Throws(IOException::class)
    fun testCgroupInvalidMounts() {
        val mounts: File? = scratch.file("proc/self/mounts").getPathFile()
        val hierarchies: File? = scratch.file("proc/self/cgroup", "0::/user.slice").getPathFile()

        val vcg: VirtualCgroup = VirtualCgroup.createRoot(mounts, hierarchies)
        assertThat(vcg.cpu()).isNull()
        assertThat(vcg.memory()).isNull()
    }

    @Test
    @Throws(IOException::class)
    fun testCgroupInvalidHierarchies() {
        val mounts: File? =
            scratch
                .file(
                    "proc/self/mounts",
                    String.format("cgroup %s/dev/cgroup/unified cgroup2 ro 0 0", scratch.path(""))
                )
                .getPathFile()
        val hierarchies: File? = scratch.file("proc/self/cgroup").getPathFile()

        val vcg: VirtualCgroup = VirtualCgroup.createRoot(mounts, hierarchies)
        assertThat(vcg.cpu()).isNull()
        assertThat(vcg.memory()).isNull()
    }

    @Test
    @Throws(IOException::class)
    fun testCgroupOnlyMemory() {
        val mounts: File? =
            scratch
                .file(
                    "proc/self/mounts",
                    String.format(
                        "cgroup %s/dev/cgroup/memory cgroup rw,memory,hugetlb 0 0", scratch.path("")
                    ),
                    String.format("cgroup %s/dev/cgroup/cpuset cgroup rw,cpuset 0 0", scratch.path(""))
                )
                .getPathFile()
        val hierarchies: File? =
            scratch
                .file("proc/self/cgroup", "2:cpuset:/user.slice", "1:memory,hugetlb:/user.slice")
                .getPathFile()
        scratch.file("dev/cgroup/memory/user.slice/cgroup.procs")
        scratch.file("dev/cgroup/cpuset/user.slice/cgroup.procs")

        val vcg: VirtualCgroup = VirtualCgroup.createRoot(mounts, hierarchies)
        assertThat(vcg.cpu()).isNull()
        assertThat(vcg.memory()).isNotNull()
    }

    @Test
    @Throws(IOException::class)
    fun testCgroupOnlyCpu() {
        val mounts: File? =
            scratch
                .file(
                    "proc/self/mounts",
                    String.format("cgroup %s/dev/cgroup/cpu cgroup rw,cpu 0 0", scratch.path("")),
                    String.format("cgroup %s/dev/cgroup/cpuset cgroup rw,cpuset 0 0", scratch.path(""))
                )
                .getPathFile()
        val hierarchies: File? =
            scratch.file("proc/self/cgroup", "2:cpuset:/user.slice", "1:cpu:/user.slice").getPathFile()
        scratch.file("dev/cgroup/cpu/user.slice/cgroup.procs")
        scratch.file("dev/cgroup/cpuset/user.slice/cgroup.procs")

        val vcg: VirtualCgroup = VirtualCgroup.createRoot(mounts, hierarchies)
        assertThat(vcg.cpu()).isNotNull()
        assertThat(vcg.memory()).isNull()
    }

    @Test
    @Throws(IOException::class)
    fun validCgroupV1_cgroupsInfo_returnsCorrectInfo() {
        val vcg: VirtualCgroup = createV1()
        assertThat(vcg.cgroupsInfo())
            .isEqualTo(
                CgroupsInfo.newBuilder()
                    .addCgroupControllers(
                        CgroupControllerInfo.newBuilder()
                            .setPath(
                                scratch.path("dev/cgroup/memory/user.slice").getPathFile().toString()
                            )
                            .setIsWritable(true)
                            .setVersion(CgroupControllerInfo.Version.V1)
                            .build()
                    )
                    .addCgroupControllers(
                        CgroupControllerInfo.newBuilder()
                            .setPath(
                                scratch.path("dev/cgroup/memory/user.slice").getPathFile().toString()
                            )
                            .setIsWritable(true)
                            .setVersion(CgroupControllerInfo.Version.V1)
                            .build()
                    )
                    .addCgroupControllers(
                        CgroupControllerInfo.newBuilder()
                            .setPath(scratch.path("dev/cgroup/cpuset").getPathFile().toString())
                            .setIsWritable(true)
                            .setVersion(CgroupControllerInfo.Version.V1)
                            .build()
                    )
                    .addCgroupControllers(
                        CgroupControllerInfo.newBuilder()
                            .setPath(scratch.path("dev/cgroup/cpu/user.slice").getPathFile().toString())
                            .setIsWritable(true)
                            .setVersion(CgroupControllerInfo.Version.V1)
                            .build()
                    )
                    .addCgroupControllers(
                        CgroupControllerInfo.newBuilder()
                            .setPath(scratch.path("dev/cgroup/cpu/user.slice").getPathFile().toString())
                            .setIsWritable(true)
                            .setVersion(CgroupControllerInfo.Version.V1)
                            .build()
                    )
                    .build()
            )
    }

    @Test
    @Throws(IOException::class)
    fun validCgroupV2_cgroupsInfo_returnsCorrectInfo() {
        val vcg: VirtualCgroup = createV2()
        assertThat(vcg.cgroupsInfo())
            .isEqualTo(
                CgroupsInfo.newBuilder()
                    .addCgroupControllers(
                        CgroupControllerInfo.newBuilder()
                            .setPath(scratch.path("dev/cgroup/unified").getPathFile().toString())
                            .setIsWritable(true)
                            .setVersion(CgroupControllerInfo.Version.V2)
                            .build()
                    )
                    .build()
            )
    }
}

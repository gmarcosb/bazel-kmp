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
package com.google.devtools.build.lib.sandbox

import com.google.devtools.build.lib.sandbox.CgroupsInfo.InvalidCgroupsInfo

/** Tests for [CgroupsInfo], [CgroupsInfoV1], [CgroupsInfoV2].  */
@RunWith(JUnit4::class)
class CgroupsInfoTest {
    private val scratch: FsApparatus = FsApparatus.newNative()

    /** We use this pseudo-root to get around not being able to replace absolute paths.  */
    private var root: String? = null

    @Before
    @Throws(IOException::class)
    fun setUp() {
        root = scratch.dir("fake_root").getPathString()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testGetRootCgroup_v1() {
        val pathString =
            createFakeAbsoluteFile(
                "/proc/self/mounts",
                "sysfs /sys sysfs rw,nosuid,nodev,noexec,relatime 0 0",
                "cgroup /dev/cgroup/cpu cgroup rw,cpu,cpuacct 0 0",
                "cgroup /dev/cgroup/io cgroup rw,io 0 0",
                "cgroup /dev/cgroup/job cgroup rw,job 0 0",
                "cgroup /dev/cgroup/memory cgroup rw,memory,hugetlb 0 0",
                "proc /proc proc rw,nosuid,nodev,noexec,relatime 0 0"
            )

        val cgroup: CgroupsInfo = CgroupsInfo.getRootCgroup(java.io.File(pathString))

        assertThat(cgroup.type).isEqualTo(CgroupsInfo.Type.ROOT)
        assertThat(cgroup.version).isEqualTo(CgroupsInfo.Version.V1)
        assertThat(cgroup.getCgroupDir().getPath()).isEqualTo("/dev/cgroup/memory")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testGetRootCgroup_v2() {
        val pathString =
            createFakeAbsoluteFile(
                "/proc/self/mounts",
                "sysfs /sys sysfs rw,nosuid,nodev,noexec,relatime 0 0",
                "cgroup2 /sys/fs/cgroup cgroup2 rw,memory_recursiveprot 0 0",
                "proc /proc proc rw,nosuid,nodev,noexec,relatime 0 0"
            )

        val cgroup: CgroupsInfo = CgroupsInfo.getRootCgroup(java.io.File(pathString))

        assertThat(cgroup.type).isEqualTo(CgroupsInfo.Type.ROOT)
        assertThat(cgroup.version).isEqualTo(CgroupsInfo.Version.V2)
        assertThat(cgroup.getCgroupDir().getPath()).isEqualTo("/sys/fs/cgroup")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testGetRootCgroup_mixed_v1_has_memory() {
        val pathString =
            createFakeAbsoluteFile(
                "/proc/self/mounts",
                "sysfs /sys sysfs rw,nosuid,nodev,noexec,relatime 0 0",
                "cgroup2 /sys/fs/cgroup cgroup2 rw,memory_recursiveprot 0 0",
                "cgroup /dev/cgroup/job cgroup rw,job 0 0",
                "cgroup /dev/cgroup/memory cgroup rw,memory,hugetlb 0 0",
                "proc /proc proc rw,nosuid,nodev,noexec,relatime 0 0"
            )

        val cgroup: CgroupsInfo = CgroupsInfo.getRootCgroup(java.io.File(pathString))

        assertThat(cgroup.type).isEqualTo(CgroupsInfo.Type.ROOT)
        assertThat(cgroup.version).isEqualTo(CgroupsInfo.Version.V1)
        assertThat(cgroup.getCgroupDir().getPath()).isEqualTo("/dev/cgroup/memory")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testGetRootCgroup_mixed_v2_has_memory() {
        val pathString =
            createFakeAbsoluteFile(
                "/proc/self/mount",
                "sysfs /sys sysfs rw,nosuid,nodev,noexec,relatime 0 0",
                "cgroup2 /sys/fs/cgroup cgroup2 rw,memory_recursiveprot 0 0",
                "cgroup /dev/cgroup/job cgroup rw,job 0 0",
                "cgroup /dev/cgroup/io cgroup rw,io 0 0",
                "proc /proc proc rw,nosuid,nodev,noexec,relatime 0 0"
            )

        val cgroup: CgroupsInfo = CgroupsInfo.getRootCgroup(java.io.File(pathString))

        assertThat(cgroup.type).isEqualTo(CgroupsInfo.Type.ROOT)
        assertThat(cgroup.version).isEqualTo(CgroupsInfo.Version.V2)
        assertThat(cgroup.getCgroupDir().getPath()).isEqualTo("/sys/fs/cgroup")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testCreateBlazeSpawnsCgroup_v1() {
        val mountPath = root + "/dev/cgroup/memory"
        val rootCgroup: CgroupsInfo =
            CgroupsInfoV1(CgroupsInfo.Type.ROOT,  /* cgroupDir= */java.io.File(mountPath))
        val procSelfCgroupPath =
            createFakeAbsoluteFile(
                "/proc/self/cgroup",
                "8:net:/netdir/action-6",
                "7:memory,hugetlb:/memdir/action-6",
                "6:job:/jobdir/action-16",
                "5:io:/iodir/action-1"
            )
        scratch.dir(root + "/dev/cgroup/memory/memdir/action-6").createDirectoryAndParents()
        val blazeSpawnsPath =
            (root
                    + "/dev/cgroup/memory/memdir/action-6/blaze_"
                    + java.lang.ProcessHandle.current().pid()
                    + "_spawns")
        scratch.dir(blazeSpawnsPath).createDirectoryAndParents()

        val cgroup: CgroupsInfo = rootCgroup.createBlazeSpawnsCgroup(procSelfCgroupPath)

        assertThat(cgroup.getCgroupDir().exists()).isTrue()
        assertThat(cgroup.getCgroupDir().getPath()).isEqualTo(blazeSpawnsPath)
        assertThat(cgroup.type).isEqualTo(CgroupsInfo.Type.BLAZE_SPAWNS)
        assertThat(cgroup.version).isEqualTo(CgroupsInfo.Version.V1)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testCreateBlazeSpawnsCgroup_v2() {
        val mountPath = root + "/sys/fs/cgroup"
        val rootCgroup: CgroupsInfo =
            CgroupsInfoV2(CgroupsInfo.Type.ROOT,  /* cgroupDir= */java.io.File(mountPath))
        val procSelfCgroupPath =
            createFakeAbsoluteFile("/proc/self/cgroup", "0::/user.slice/session.scope")
        // In v2, the blaze spawns cgroup is created one step up from where the blaze process is defined
        // in the /proc/self/cgroup file (defined above). Specifically, here it is in the
        // ".../user.slice".
        val blazeSpawnsPath =
            mountPath + "/user.slice/blaze_" + java.lang.ProcessHandle.current().pid() + "_spawns.slice"
        // Even though the blaze spawn's cgroup directory is meant to be created in the method call,
        // we create it here so that we can prepare the controller files that are expected beforehand.
        scratch.dir(blazeSpawnsPath).createDirectoryAndParents()
        // Since this controllers file is missing `pids`, we expect that to be written to it.
        scratch.file(blazeSpawnsPath + "/cgroup.controllers", "memory pids")
        scratch.file(blazeSpawnsPath + "/cgroup.subtree_control", "memory")

        val cgroup: CgroupsInfo = rootCgroup.createBlazeSpawnsCgroup(procSelfCgroupPath)

        assertThat(cgroup.version).isEqualTo(CgroupsInfo.Version.V2)
        assertThat(cgroup.getCgroupDir().exists()).isTrue()
        assertThat(cgroup.getCgroupDir().getPath()).isEqualTo(blazeSpawnsPath)
        assertThat(cgroup.type).isEqualTo(CgroupsInfo.Type.BLAZE_SPAWNS)
        assertThat(cgroup.version).isEqualTo(CgroupsInfo.Version.V2)
        // This is not what an actual cgroups v2 file would contain, but it's what we expect to write to
        // it to enable subtree control.
        Truth.assertThat(
            com.google.common.io.Files.readLines(
                java.io.File(blazeSpawnsPath + "/cgroup.subtree_control"),
                java.nio.charset.StandardCharsets.UTF_8
            )
        )
            .containsExactly("+memory +pids")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testCreateIndividualSpawnCgroup_withLimit_v1() {
        val blazeSpawnsPath = root + "/dev/cgroup/memory/memdir/action-6/blaze_1234_spawns"
        scratch.dir(blazeSpawnsPath).createDirectoryAndParents()
        val blazeSpawnsCgroup: CgroupsInfo =
            CgroupsInfoV1(CgroupsInfo.Type.BLAZE_SPAWNS, java.io.File(blazeSpawnsPath))

        val cgroup: CgroupsInfo = blazeSpawnsCgroup.createIndividualSpawnCgroup("spawn_1", 100)

        assertThat(cgroup.exists()).isTrue()
        assertThat(cgroup.getCgroupDir().getPath()).isEqualTo(blazeSpawnsPath + "/spawn_1")
        assertThat(cgroup.type).isEqualTo(CgroupsInfo.Type.SPAWN)
        assertThat(cgroup.version).isEqualTo(CgroupsInfo.Version.V1)
        Truth.assertThat(
            com.google.common.io.Files.readLines(
                java.io.File(blazeSpawnsPath + "/spawn_1/memory.limit_in_bytes"),
                java.nio.charset.StandardCharsets.UTF_8
            )
        )
            .containsExactly("104857600")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testCreateIndividualSpawnCgroup_noLimit_v1() {
        val blazeSpawnsPath = root + "/dev/cgroup/memory/memdir/action-6/blaze_1234_spawns"
        scratch.dir(blazeSpawnsPath).createDirectoryAndParents()
        val blazeSpawnsCgroup: CgroupsInfo =
            CgroupsInfoV1(CgroupsInfo.Type.BLAZE_SPAWNS, java.io.File(blazeSpawnsPath))

        val cgroup: CgroupsInfo = blazeSpawnsCgroup.createIndividualSpawnCgroup("spawn_1", 0)

        assertThat(cgroup.exists()).isTrue()
        assertThat(cgroup.getCgroupDir().getPath()).isEqualTo(blazeSpawnsPath + "/spawn_1")
        assertThat(cgroup.type).isEqualTo(CgroupsInfo.Type.SPAWN)
        assertThat(cgroup.version).isEqualTo(CgroupsInfo.Version.V1)
        // In reality, cgroups should still create this file automatically, but since we don't have
        // that in our tests, the memory limits file should not have been created since it isn't written
        // to.
        Truth.assertThat(java.io.File(blazeSpawnsPath + "/spawn_1/memory.limit_in_bytes").exists()).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testCreateIndividualSpawnCgroup_withLimit_v2() {
        val blazeSpawnsPath = root + "/sys/fs/cgroup/user.slice/blaze_1234_spawns.slice"
        scratch.dir(blazeSpawnsPath).createDirectoryAndParents()
        val blazeSpawnsCgroup: CgroupsInfo =
            CgroupsInfoV2(CgroupsInfo.Type.BLAZE_SPAWNS, java.io.File(blazeSpawnsPath))

        val cgroup: CgroupsInfo = blazeSpawnsCgroup.createIndividualSpawnCgroup("spawn_1", 100)

        assertThat(cgroup.exists()).isTrue()
        assertThat(cgroup.getCgroupDir().getPath()).isEqualTo(blazeSpawnsPath + "/spawn_1.scope")
        assertThat(cgroup.type).isEqualTo(CgroupsInfo.Type.SPAWN)
        assertThat(cgroup.version).isEqualTo(CgroupsInfo.Version.V2)
        Truth.assertThat(
            com.google.common.io.Files.readLines(
                java.io.File(blazeSpawnsPath + "/spawn_1.scope/memory.oom.group"),
                java.nio.charset.StandardCharsets.UTF_8
            )
        )
            .containsExactly("1")
        Truth.assertThat(
            com.google.common.io.Files.readLines(
                java.io.File(blazeSpawnsPath + "/spawn_1.scope/memory.max"),
                java.nio.charset.StandardCharsets.UTF_8
            )
        )
            .containsExactly("104857600")
        Truth.assertThat(
            com.google.common.io.Files.readLines(
                java.io.File(blazeSpawnsPath + "/spawn_1.scope/memory.swap.max"),
                java.nio.charset.StandardCharsets.UTF_8
            )
        )
            .containsExactly("0")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testCreateIndividualSpawnCgroup_noLimit_v2() {
        val blazeSpawnsPath = root + "/sys/fs/cgroup/user.slice/blaze_1234_spawns.slice"
        scratch.dir(blazeSpawnsPath).createDirectoryAndParents()
        val blazeSpawnsCgroup: CgroupsInfo =
            CgroupsInfoV2(CgroupsInfo.Type.BLAZE_SPAWNS, java.io.File(blazeSpawnsPath))

        val cgroup: CgroupsInfo = blazeSpawnsCgroup.createIndividualSpawnCgroup("spawn_1", 0)

        assertThat(cgroup.exists()).isTrue()
        assertThat(cgroup.getCgroupDir().getPath()).isEqualTo(blazeSpawnsPath + "/spawn_1.scope")
        assertThat(cgroup.type).isEqualTo(CgroupsInfo.Type.SPAWN)
        assertThat(cgroup.version).isEqualTo(CgroupsInfo.Version.V2)
        // In reality, cgroups should still create this file automatically, but since we don't have
        // that in our tests, the memory limits files should not have been created since they aren't
        // written to.
        Truth.assertThat(java.io.File(blazeSpawnsPath + "/spawn_1.scope/memory.oom.group").exists()).isFalse()
        Truth.assertThat(java.io.File(blazeSpawnsPath + "/spawn_1.scope/memory.max").exists()).isFalse()
        Truth.assertThat(java.io.File(blazeSpawnsPath + "/spawn_1.scope/memory.swap.max").exists()).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testGetMemoryUsageInKb_v1() {
        val cgroupPath = root + "/dev/cgroup/memory/memdir/action-1"
        scratch.dir(cgroupPath).createDirectoryAndParents()
        val cgroupsInfo: CgroupsInfo =
            CgroupsInfoV1(CgroupsInfo.Type.SPAWN,  /* cgroupDir= */java.io.File(cgroupPath))

        // It should return 0 if the /path/to/cgroup/memory.usage_in_bytes does not exist.
        assertThat(cgroupsInfo.getMemoryUsageInKb()).isEqualTo(0)

        scratch.file(cgroupPath + "/memory.usage_in_bytes", "1024000")

        assertThat(cgroupsInfo.getMemoryUsageInKb()).isEqualTo(1000)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testGetMemoryUsageInKb_v2() {
        val cgroupPath = root + "/sys/fs/cgroup/memdir/action-1"
        scratch.dir(cgroupPath).createDirectoryAndParents()
        val cgroupsInfo: CgroupsInfo =
            CgroupsInfoV2(CgroupsInfo.Type.SPAWN,  /* cgroupDir= */java.io.File(cgroupPath))

        // It should return 0 if the /path/to/cgroup/memory.current does not exist.
        assertThat(cgroupsInfo.getMemoryUsageInKb()).isEqualTo(0)

        scratch.file(cgroupPath + "/memory.current", "1024000")

        // Divided by 1024.
        assertThat(cgroupsInfo.getMemoryUsageInKb()).isEqualTo(1000)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testAddProcess_v1() {
        val cgroupPath = root + "/dev/cgroup/memory/memdir/action-1"
        scratch.dir(cgroupPath).createDirectoryAndParents()
        val cgroupsInfo: CgroupsInfo =
            CgroupsInfoV1(CgroupsInfo.Type.SPAWN,  /* cgroupDir= */java.io.File(cgroupPath))

        cgroupsInfo.addProcess(1234)

        Truth.assertThat(
            com.google.common.io.Files.readLines(
                java.io.File(cgroupsInfo.getCgroupDir(), "cgroup.procs"),
                java.nio.charset.StandardCharsets.UTF_8
            )
        )
            .containsExactly("1234")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testAddProcess_v2() {
        val cgroupPath = root + "/sys/fs/cgroup/memdir/action-1"
        scratch.dir(cgroupPath).createDirectoryAndParents()
        val cgroupsInfo: CgroupsInfo =
            CgroupsInfoV2(CgroupsInfo.Type.SPAWN,  /* cgroupDir= */java.io.File(cgroupPath))

        cgroupsInfo.addProcess(1234)

        Truth.assertThat(
            com.google.common.io.Files.readLines(
                java.io.File(cgroupsInfo.getCgroupDir(), "cgroup.procs"),
                java.nio.charset.StandardCharsets.UTF_8
            )
        )
            .containsExactly("1234")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testGetRootCgroup_returnsInvalidCgroup_whenMountNotFound() {
        val pathString = createFakeAbsoluteFile("/proc/self/mounts", "")

        val cgroup: CgroupsInfo = CgroupsInfo.getRootCgroup(java.io.File(pathString))

        assertThat(cgroup.getClass()).isEqualTo(InvalidCgroupsInfo::class.java)
        assertThat(cgroup.type).isEqualTo(CgroupsInfo.Type.ROOT)
    }

    @org.junit.Test
    fun testCreateCgroupFromInvalidCgroup_returnsInvalidCgroup() {
        val errorMessage = "Some error message"
        val invalidRootCgroup: CgroupsInfo =
            InvalidCgroupsInfo(CgroupsInfo.Type.ROOT, CgroupsInfo.Version.V1, errorMessage)
        val invalidBlazeSpawnsCgroup: CgroupsInfo =
            InvalidCgroupsInfo(CgroupsInfo.Type.BLAZE_SPAWNS, CgroupsInfo.Version.V2, errorMessage)

        val createdBlazeSpawnsCgroup: CgroupsInfo = invalidRootCgroup.createBlazeSpawnsCgroup("")
        val createdSpawnCgroup: CgroupsInfo =
            invalidBlazeSpawnsCgroup.createIndividualSpawnCgroup("spawn_1", 1)

        assertThat(createdBlazeSpawnsCgroup.getClass()).isEqualTo(InvalidCgroupsInfo::class.java)
        // Should still have the same version as the parent cgroup that attempted to create it.
        assertThat(createdBlazeSpawnsCgroup.version).isEqualTo(CgroupsInfo.Version.V1)
        assertThat(createdBlazeSpawnsCgroup.type).isEqualTo(CgroupsInfo.Type.BLAZE_SPAWNS)

        assertThat(createdSpawnCgroup.getClass()).isEqualTo(InvalidCgroupsInfo::class.java)
        // Should still have the same version as the parent cgroup that attempted to create it.
        assertThat(createdSpawnCgroup.version).isEqualTo(CgroupsInfo.Version.V2)
        assertThat(createdSpawnCgroup.type).isEqualTo(CgroupsInfo.Type.SPAWN)
    }

    @Throws(IOException::class)
    private fun createFakeAbsoluteFile(fileName: String?, vararg contents: String?): String {
        return scratch.file(root + fileName, *contents).getPathString()
    }
}

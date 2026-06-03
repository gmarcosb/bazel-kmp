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

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.truth.Truth
import com.google.devtools.build.lib.analysis.util.ConfigurationTestCase.create
import com.google.devtools.build.lib.analysis.util.MockRuleDefaults.DefaultConfiguredTargetFactory.create
import com.google.devtools.build.lib.runtime.commands.info.InfoItemHandler.InfoItemHandlerFactoryImpl.create
import com.google.devtools.build.lib.vfs.util.FsApparatus
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.IOException

@RunWith(JUnit4::class)
class VirtualCgroupFactoryTest {
    private val scratch: FsApparatus = FsApparatus.newNative()

    private var root: VirtualCgroup? = null

    @Before
    @Throws(Exception::class)
    fun setup() {
        scratch.dir("cpu/cpu")
        scratch.dir("mem/mem")
        val mounts: ImmutableList<Mount?> =
            ImmutableList.of<E?>(
                Mount.create(
                    scratch.path("cpu").getPathFile().toPath(), "cgroup", ImmutableList.of<E?>("cpu")
                ),
                Mount.create(
                    scratch.path("mem").getPathFile().toPath(), "cgroup", ImmutableList.of<E?>("memory")
                )
            )
        val hierarchies: ImmutableList<Hierarchy?> =
            ImmutableList.of<E?>(
                Hierarchy.create(
                    1, ImmutableList.of<E?>("cpu"), scratch.path("/cpu").getPathFile().toPath()
                ),
                Hierarchy.create(
                    2, ImmutableList.of<E?>("memory"), scratch.path("/mem").getPathFile().toPath()
                )
            )

        root = VirtualCgroup.createRoot(mounts, hierarchies)
        assertThat(root.cpu()).isNotNull()
        assertThat(root.memory()).isNotNull()
    }

    @Test
    @Throws(IOException::class)
    fun testCreateNoLimits() {
        val defaults = ImmutableMap.of<String?, Double?>()
        val factory: VirtualCgroupFactory = VirtualCgroupFactory("nolimits", root, defaults, false)

        val vcg: VirtualCgroup = factory.create(1, ImmutableMap.of<K?, V?>())

        assertThat(vcg.paths()).isEmpty()
    }

    @Test
    @Throws(IOException::class)
    fun testForceCreateNoLimits() {
        val defaults = ImmutableMap.of<String?, Double?>()
        val factory: VirtualCgroupFactory = VirtualCgroupFactory("nolimits", root, defaults, true)

        val vcg: VirtualCgroup = factory.create(1, ImmutableMap.of<K?, V?>())

        assertThat(vcg.paths()).isNotEmpty()
        assertThat(vcg.cpu()).isNotNull()
        assertThat(vcg.memory()).isNotNull()
    }

    @Test
    @Throws(IOException::class)
    fun testCreateWithDefaultLimits() {
        val defaults = ImmutableMap.of<String?, Double?>("memory", 100.0)
        val factory: VirtualCgroupFactory = VirtualCgroupFactory("defaults", root, defaults, false)

        val vcg: VirtualCgroup = factory.create(1, ImmutableMap.of<K?, V?>())

        assertThat(vcg.paths()).isNotEmpty()
        assertThat(vcg.cpu()).isNotNull()
        assertThat(vcg.memory()).isNotNull()
        assertThat(vcg.memory().maxBytes).isEqualTo(100 * 1024 * 1024)
    }

    @Test
    @Throws(IOException::class)
    fun testCreateWithCustomLimits() {
        scratch.file("cpu/cpu/custom1.scope/cpu.cfs_period_us", "1000")
        val defaults = ImmutableMap.of<String?, Double?>("memory", 100.0, "cpu", 1.0)
        val factory: VirtualCgroupFactory = VirtualCgroupFactory("custom", root, defaults, false)

        val vcg: VirtualCgroup = factory.create(1, ImmutableMap.of<K?, V?>("memory", 200.0))

        assertThat(vcg.paths()).isNotEmpty()
        assertThat(vcg.cpu()).isNotNull()
        assertThat(vcg.memory()).isNotNull()
        assertThat(vcg.cpu().cpus).isEqualTo(1)
        assertThat(vcg.memory().maxBytes).isEqualTo(200 * 1024 * 1024)
    }

    @Test
    @Throws(IOException::class)
    fun testCreateNull() {
        val defaults = ImmutableMap.of<String?, Double?>("memory", 100.0, "cpu", 1.0)
        val factory: VirtualCgroupFactory =
            VirtualCgroupFactory("null", VirtualCgroup.NULL, defaults, false)

        val vcg: VirtualCgroup = factory.create(1, ImmutableMap.of<K?, V?>())

        assertThat(vcg.paths()).isEmpty()
        assertThat(vcg.cpu()).isNull()
        assertThat(vcg.memory()).isNull()
    }

    @Test
    @Throws(IOException::class)
    fun testGet() {
        val defaults = ImmutableMap.of<String?, Double?>("memory", 100.0)
        val factory: VirtualCgroupFactory = VirtualCgroupFactory("get", root, defaults, false)

        val vcg: VirtualCgroup? = factory.create(1, ImmutableMap.of<K?, V?>())

        assertThat(factory.get(1)).isEqualTo(vcg)
    }

    @Test
    @Throws(IOException::class)
    fun testRemove() {
        val defaults = ImmutableMap.of<String?, Double?>()
        val factory: VirtualCgroupFactory = VirtualCgroupFactory("get", root, defaults, true)

        val vcg: VirtualCgroup = factory.create(1, ImmutableMap.of<K?, V?>())

        assertThat(factory.remove(1)).isEqualTo(vcg)
        for (p in vcg.paths()) {
            Truth.assertThat(p.toFile().exists()).isFalse()
        }
    }
}

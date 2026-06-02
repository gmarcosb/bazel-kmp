// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.platform

import build.bazel.remote.execution.v2.Platform
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.devtools.common.options.Options
import org.junit.Test

/** Tests for [PlatformUtils]  */
@RunWith(JUnit4::class)
class PlatformUtilsTest {
    @Test
    @Throws(Exception::class)
    fun testParsePlatformSortsProperties() {
        val expected: Platform? =
            Platform.newBuilder()
                .addProperties(Platform.Property.newBuilder().setName("a").setValue("1"))
                .addProperties(Platform.Property.newBuilder().setName("b").setValue("2"))
                .build()
        val s: Spawn = SpawnBuilder("dummy").build()
        assertThat(PlatformUtils.getPlatformProto(s, remoteOptions())).isEqualTo(expected)
    }

    @Test
    @Throws(Exception::class)
    fun testParsePlatformHandlesNull() {
        val s: Spawn = SpawnBuilder("dummy").build()
        assertThat(PlatformUtils.getPlatformProto(s, null)).isEqualTo(null)
    }

    @Test
    @Throws(Exception::class)
    fun testParsePlatformSortsProperties_execProperties() {
        // execProperties are chosen even if there are remoteOptions
        val map = ImmutableMap.of<String?, String?>("aa", "99", "zz", "66", "dd", "11")
        val s: Spawn = SpawnBuilder("dummy").withCombinedExecProperties(map).build()

        val expected: Platform? =
            Platform.newBuilder()
                .addProperties(Platform.Property.newBuilder().setName("aa").setValue("99"))
                .addProperties(Platform.Property.newBuilder().setName("dd").setValue("11"))
                .addProperties(Platform.Property.newBuilder().setName("zz").setValue("66"))
                .build()
        // execProperties are sorted by key
        assertThat(PlatformUtils.getPlatformProto(s, null)).isEqualTo(expected)
    }

    @Test
    @Throws(Exception::class)
    fun getPlatformProto_mergeTargetExecPropertiesWithPlatform() {
        val spawn: Spawn =
            SpawnBuilder("dummy").withCombinedExecProperties(ImmutableMap.of<String?, String?>("c", "3")).build()
        val expected: Platform? =
            Platform.newBuilder()
                .addProperties(Platform.Property.newBuilder().setName("a").setValue("1"))
                .addProperties(Platform.Property.newBuilder().setName("b").setValue("2"))
                .addProperties(Platform.Property.newBuilder().setName("c").setValue("3"))
                .build()
        assertThat(PlatformUtils.getPlatformProto(spawn, remoteOptions())).isEqualTo(expected)
    }

    @Test
    @Throws(Exception::class)
    fun getPlatformProto_targetExecPropertiesConflictWithPlatform_override() {
        val spawn: Spawn =
            SpawnBuilder("dummy").withCombinedExecProperties(ImmutableMap.of<String?, String?>("b", "3")).build()
        val expected: Platform? =
            Platform.newBuilder()
                .addProperties(Platform.Property.newBuilder().setName("a").setValue("1"))
                .addProperties(Platform.Property.newBuilder().setName("b").setValue("3"))
                .build()
        assertThat(PlatformUtils.getPlatformProto(spawn, remoteOptions())).isEqualTo(expected)
    }

    companion object {
        private fun remoteOptions(): RemoteOptions {
            val remoteOptions: RemoteOptions = Options.getDefaults<O>(RemoteOptions::class.java)
            remoteOptions.setRemoteDefaultExecPropertiesField(
                ImmutableList.of<MutableMap.MutableEntry<String?, String?>?>(
                    AbstractMap.SimpleEntry<String?, String?>("b", "2"),
                    AbstractMap.SimpleEntry<String?, String?>("a", "1")
                )
            )
            return remoteOptions
        }
    }
}

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
package com.google.devtools.build.lib.remote.options

import com.google.common.collect.ImmutableSortedMap
import com.google.common.collect.Maps
import com.google.devtools.build.lib.vfs.PathFragment
import com.google.devtools.common.options.Options
import org.junit.Assert
import org.junit.Test
import java.time.Duration
import java.util.*

/** Tests for RemoteOptions.  */
@RunWith(TestParameterInjector::class)
class RemoteOptionsTest {
    @Test
    @Throws(Exception::class)
    fun testDefaultValueOfExecProperties() {
        val options: RemoteOptions = Options.getDefaults<O>(RemoteOptions::class.java)
        Truth.assertThat(options.remoteDefaultExecProperties).isEmpty()
    }

    @Test
    @Throws(Exception::class)
    fun testRemoteDefaultExecProperties() {
        val options: RemoteOptions = Options.getDefaults<O>(RemoteOptions::class.java)
        options.setRemoteDefaultExecPropertiesField(
            Arrays.asList<MutableMap.MutableEntry<String?, String?>?>(
                Maps.immutableEntry<String?, String?>("ISA", "x86-64"),
                Maps.immutableEntry<String?, String?>("OSFamily", "linux")
            )
        )

        val properties: SortedMap<String?, String?> = options.remoteDefaultExecProperties
        Truth.assertThat(properties)
            .isEqualTo(ImmutableSortedMap.of<String?, String?>("OSFamily", "linux", "ISA", "x86-64"))
    }

    @Test
    @Throws(Exception::class)
    fun testRemoteDefaultExecPropertiesWithDuplicates() {
        val options: RemoteOptions = Options.getDefaults<O>(RemoteOptions::class.java)
        options.setRemoteDefaultExecPropertiesField(
            Arrays.asList<MutableMap.MutableEntry<String?, String?>?>(
                Maps.immutableEntry<String?, String?>("foo", "bar"),
                Maps.immutableEntry<String?, String?>("qux", "quux"),
                Maps.immutableEntry<String?, String?>("foo", "baz")
            )
        )
        val properties: SortedMap<String?, String?> = options.remoteDefaultExecProperties
        Truth.assertThat(properties).isEqualTo(ImmutableSortedMap.of<String?, String?>("foo", "baz", "qux", "quux"))
    }

    @Test
    fun testRemoteTimeoutOptionsConverterWithoutUnit() {
        try {
            val seconds = 60
            val convert: Duration? =
                RemoteDurationConverter().convert(seconds.toString())
            Truth.assertThat<Duration?>(Duration.ofSeconds(seconds.toLong())).isEqualTo(convert)
        } catch (e: OptionsParsingException) {
            Assert.fail(e.message)
        }
    }

    @Test
    fun testRemoteTimeoutOptionsConverterWithUnit() {
        try {
            val milliseconds = 60
            val convert: Duration? =
                RemoteDurationConverter().convert(milliseconds.toString() + "ms")
            Truth.assertThat<Duration?>(Duration.ofMillis(milliseconds.toLong())).isEqualTo(convert)
        } catch (e: OptionsParsingException) {
            Assert.fail(e.message)
        }
    }

    @Test
    fun testRemoteMaximumOpenFilesDefault() {
        val options: RemoteOptions = Options.getDefaults<O>(RemoteOptions::class.java)
        val defaultMax = options.maximumOpenFiles
        Truth.assertThat(defaultMax).isEqualTo(-1)
    }

    @Test
    @Throws(Exception::class)
    fun testRemoteGrpcLogWithEmptyString() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(RemoteOptions::class.java).build()
        parser.parse("--remote_grpc_log=test.log", "--remote_grpc_log=")
        val options: RemoteOptions? = parser.getOptions<O?>(RemoteOptions::class.java)
        assertThat(options!!.remoteGrpcLog).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun diskCache_defaultValue_disables() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(RemoteOptions::class.java).build()
        parser.parse()
        val options: RemoteOptions? = parser.getOptions<O?>(RemoteOptions::class.java)
        assertThat(options!!.diskCache).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun diskCache_noValue_usesDefaultLocation() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(RemoteOptions::class.java).build()
        parser.parse("--disk_cache")
        val options: RemoteOptions? = parser.getOptions<O?>(RemoteOptions::class.java)
        assertThat(options!!.diskCache).isEqualTo(PathFragment.EMPTY_FRAGMENT)
    }

    @Test
    @Throws(Exception::class)
    fun diskCache_emptyValue_disables() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(RemoteOptions::class.java).build()
        parser.parse("--disk_cache=")
        val options: RemoteOptions? = parser.getOptions<O?>(RemoteOptions::class.java)
        assertThat(options!!.diskCache).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun diskCache_trueValue_usesDefaultLocation(
        @TestParameter("true", "1", "yes", "t", "y") arg: String?
    ) {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(RemoteOptions::class.java).build()
        parser.parse("--disk_cache=%s".formatted(arg))
        val options: RemoteOptions? = parser.getOptions<O?>(RemoteOptions::class.java)
        assertThat(options!!.diskCache).isEqualTo(PathFragment.EMPTY_FRAGMENT)
    }

    @Test
    @Throws(Exception::class)
    fun diskCache_falseValue_disables(
        @TestParameter("false", "0", "no", "f", "n") arg: String?
    ) {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(RemoteOptions::class.java).build()
        parser.parse("--disk_cache=%s".formatted(arg))
        val options: RemoteOptions? = parser.getOptions<O?>(RemoteOptions::class.java)
        assertThat(options!!.diskCache).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun diskCache_negatedForm_disables() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(RemoteOptions::class.java).build()
        parser.parse("--disk_cache", "--nodisk_cache")
        val options: RemoteOptions? = parser.getOptions<O?>(RemoteOptions::class.java)
        assertThat(options!!.diskCache).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun diskCache_explicitPath_usesExplicitPath() {
        val parser: OptionsParser = OptionsParser.builder().optionsClasses(RemoteOptions::class.java).build()
        parser.parse("--disk_cache=custom/cache/dir")
        val options: RemoteOptions? = parser.getOptions<O?>(RemoteOptions::class.java)
        assertThat(options!!.diskCache).isEqualTo(PathFragment.create("custom/cache/dir"))
    }
}

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
package com.google.devtools.build.lib.rules.proto

import com.google.devtools.build.lib.analysis.util.BuildViewTestCase.ActionExecutionContextBuilder.build
import com.google.devtools.build.lib.analysis.util.OptionsTestCase
import com.google.devtools.build.lib.analysis.util.OptionsTestCase.assertSame
import com.google.devtools.build.lib.analysis.util.OptionsTestCase.createWithPrefix
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.build.lib.exec.util.TestExecutorBuilder.build
import com.google.devtools.build.lib.packages.util.Crosstool.CcToolchainConfig.Builder.build
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ProtoConfigurationTest :
    OptionsTestCase<com.google.devtools.build.lib.rules.proto.ProtoConfiguration.Options?>() {
    val optionsClass: java.lang.Class<com.google.devtools.build.lib.rules.proto.ProtoConfiguration.Options?>
        get() = com.google.devtools.build.lib.rules.proto.ProtoConfiguration.Options::class.java

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHdrSuffixes_ordering() {
        val one: com.google.devtools.build.lib.rules.proto.ProtoConfiguration.Options? = createWithPrefix(
            HDR_SUFFIXES_PREFIX, ".one.h,.two.h"
        )
        val two: com.google.devtools.build.lib.rules.proto.ProtoConfiguration.Options? = createWithPrefix(
            HDR_SUFFIXES_PREFIX, ".two.h,.one.h"
        )
        assertSame(one, two)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHdrSuffixes_duplicates() {
        val one: com.google.devtools.build.lib.rules.proto.ProtoConfiguration.Options? = createWithPrefix(
            HDR_SUFFIXES_PREFIX, ".one.h,.one.h"
        )
        val two: com.google.devtools.build.lib.rules.proto.ProtoConfiguration.Options? = createWithPrefix(
            HDR_SUFFIXES_PREFIX, ".one.h"
        )
        assertSame(one, two)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSrcSuffixes_ordering() {
        val one: com.google.devtools.build.lib.rules.proto.ProtoConfiguration.Options? = createWithPrefix(
            SRC_SUFFIXES_PREFIX, ".one.cc,.two.cc"
        )
        val two: com.google.devtools.build.lib.rules.proto.ProtoConfiguration.Options? = createWithPrefix(
            SRC_SUFFIXES_PREFIX, ".two.cc,.one.cc"
        )
        assertSame(one, two)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSrcSuffixes_duplicates() {
        val one: com.google.devtools.build.lib.rules.proto.ProtoConfiguration.Options? = createWithPrefix(
            SRC_SUFFIXES_PREFIX, ".one.cc,.one.cc"
        )
        val two: com.google.devtools.build.lib.rules.proto.ProtoConfiguration.Options? = createWithPrefix(
            SRC_SUFFIXES_PREFIX, ".one.cc"
        )
        assertSame(one, two)
    }

    companion object {
        private const val HDR_SUFFIXES_PREFIX = "--cc_proto_library_header_suffixes="
        private const val SRC_SUFFIXES_PREFIX = "--cc_proto_library_source_suffixes="
    }
}

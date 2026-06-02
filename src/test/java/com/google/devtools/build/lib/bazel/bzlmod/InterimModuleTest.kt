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
// limitations under the License
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil
import com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.InterimModuleBuilder
import com.google.devtools.build.lib.bazel.repository.decompressor.DecompressorDescriptor.Builder.build
import com.google.devtools.build.lib.bazel.repository.downloader.HttpStream.Factory.create
import com.google.devtools.build.lib.bazel.repository.downloader.ProgressInputStream.Factory.create
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for [InterimModule].  */
@RunWith(JUnit4::class)
class InterimModuleTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun withDepsTransformed() {
        assertThat(
            InterimModuleBuilder.Companion.create("", "")
                .addDep("dep_foo", BzlmodTestUtil.createModuleKey("foo", "1.0"))
                .addDep("dep_bar", BzlmodTestUtil.createModuleKey("bar", "2.0"))
                .addNodepDep(BzlmodTestUtil.createModuleKey("quux", "3.0"))
                .build()
                .withDepsTransformed(
                    { key -> BzlmodTestUtil.createModuleKey(key.name + "_new", key.version().getNormalized() + ".1") })
        )
            .isEqualTo(
                InterimModuleBuilder.Companion.create("", "")
                    .addDep("dep_foo", BzlmodTestUtil.createModuleKey("foo_new", "1.0.1"))
                    .addOriginalDep("dep_foo", BzlmodTestUtil.createModuleKey("foo", "1.0"))
                    .addDep("dep_bar", BzlmodTestUtil.createModuleKey("bar_new", "2.0.1"))
                    .addOriginalDep("dep_bar", BzlmodTestUtil.createModuleKey("bar", "2.0"))
                    .addNodepDep(BzlmodTestUtil.createModuleKey("quux_new", "3.0.1"))
                    .build()
            )
    }
}

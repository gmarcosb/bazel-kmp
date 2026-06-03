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
package com.google.devtools.build.lib.rules.android

import com.google.devtools.build.lib.analysis.util.OptionsTestCase
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class AndroidConfigurationTest : OptionsTestCase<AndroidConfiguration.Options?>() {
    val optionsClass: Class<AndroidConfiguration.Options?>
        get() = AndroidConfiguration.Options::class.java

    @Test
    @Throws(Exception::class)
    fun testPlatforms_ordering() {
        // Order matters.
        val one: AndroidConfiguration.Options? = createWithPrefix(ANDROID_PLATFORMS_PREFIX, "//a:one,//b")
        val two: AndroidConfiguration.Options? = createWithPrefix(ANDROID_PLATFORMS_PREFIX, "//b,//a:one")
        assertDifferent(one, two)
    }

    @Test
    @Throws(Exception::class)
    fun testPlatforms_duplicates() {
        // If there are two copies, only the first one is kept.
        val one: AndroidConfiguration.Options? = createWithPrefix(ANDROID_PLATFORMS_PREFIX, "//a:a,//b,//a")
        val two: AndroidConfiguration.Options? = createWithPrefix(ANDROID_PLATFORMS_PREFIX, "//a,//b")
        assertSame(one, two)
    }

    companion object {
        private const val ANDROID_PLATFORMS_PREFIX = "--android_platforms="
    }
}

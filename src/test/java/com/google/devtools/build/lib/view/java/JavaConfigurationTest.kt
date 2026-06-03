// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.view.java

import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue
import org.junit.Test

/**
 * Tests for the Java-specific parts of [BuildConfigurationValue] creation, and the
 * Java-related configuration transitions.
 */
@RunWith(JUnit4::class)
class JavaConfigurationTest : ConfigurationTestCase() {
    @Test
    @Throws(Exception::class)
    fun testJavaLauncherConfiguration() {
        // Default value of --java_launcher: null.
        var config: BuildConfigurationValue = create()
        var cfg: JavaConfiguration = config.getFragment(JavaConfiguration::class.java)
        assertThat(cfg.getJavaLauncherLabel()).isNull()

        // Explicitly enabled launcher as default
        scratch.file(
            "foo/BUILD",
            """
        filegroup(name = "bar")

        filegroup(name = "baz")
        
        """.trimIndent()
        )
        config = create("--java_launcher=//foo:bar")
        cfg = config.getFragment(JavaConfiguration::class.java)
        assertThat(Label.parseCanonicalUnchecked("//foo:bar")).isEqualTo(cfg.getJavaLauncherLabel())
    }
}

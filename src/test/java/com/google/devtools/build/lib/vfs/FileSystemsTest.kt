// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.vfs

import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * This class handles the tests for the FileSystems class.
 */
@RunWith(JUnit4::class)
class FileSystemsTest {
    @org.junit.Test
    fun testFileSystems() {
        assertThat(com.google.devtools.build.lib.vfs.util.FileSystems.getJavaIoFileSystem())
            .isNotSameInstanceAs(com.google.devtools.build.lib.vfs.util.FileSystems.getNativeFileSystem())
    }
}

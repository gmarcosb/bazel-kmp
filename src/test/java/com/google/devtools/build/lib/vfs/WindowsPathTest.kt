// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.vfs.WindowsOsPathPolicy.ShortPathResolver

/** Tests windows-specific parts of [Path]  */
@RunWith(JUnit4::class)
class WindowsPathTest : PathAbstractTest() {
    private class MockShortPathResolver : ShortPathResolver {
        // Full path to resolved child mapping.
        private val resolutions: MutableMap<String?, String?> = HashMap<String?, String?>()

        public override fun resolveShortPath(path: String): String {
            val segments: Array<String?> =
                path.split("[\\\\/]+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            var result = ""
            var i = 0
            while (i < segments.size) {
                var segment = segments[i]
                val queryString: String? = (result + segment).lowercase(Locale.getDefault())
                segment = resolutions.getOrDefault(queryString, segment)
                result = result + segment
                ++i
                if (i != segments.size) {
                    result += "/"
                }
            }
            return result
        }
    }

    @org.junit.Test
    fun testEqualsAndHashcodeWindows() {
        EqualsTester()
            .addEqualityGroup(create("/a/b"))
            .addEqualityGroup(create("c:/a/b"))
            .addEqualityGroup(create("C:/something/else"))
            .testEquals()
    }

    @org.junit.Test
    fun testCaseIsPreserved() {
        assertThat(create("C:/a/B").getPathString()).isEqualTo("C:/a/B")
    }

    @org.junit.Test
    fun testNormalizeWindows() {
        assertThat(create("C:/")).isEqualTo(create("C:/"))
        assertThat(create("c:/")).isEqualTo(create("C:/"))
        assertThat(create("c:\\")).isEqualTo(create("C:/"))
        assertThat(create("c:\\foo\\..\\bar\\")).isEqualTo(create("C:/bar"))
    }

    @org.junit.Test
    fun testStartsWithWindows() {
        assertThat(create("C:/").startsWith(create("C:/"))).isTrue()
        assertThat(create("C:/foo").startsWith(create("C:/"))).isTrue()
        assertThat(create("C:/foo").startsWith(create("D:/"))).isFalse()
    }

    @org.junit.Test
    fun testStartsWithIgnoringCaseWindows() {
        assertThat(create("C:/").startsWithIgnoringCase(create("C:/"))).isTrue()
        assertThat(create("C:/").startsWithIgnoringCase(create("c:/"))).isTrue()
        assertThat(create("c:/").startsWithIgnoringCase(create("C:/"))).isTrue()
        assertThat(create("c:/").startsWithIgnoringCase(create("c:/"))).isTrue()

        assertThat(create("C:/foo").startsWithIgnoringCase(create("C:/"))).isTrue()
        assertThat(create("C:/foo").startsWithIgnoringCase(create("c:/"))).isTrue()
        assertThat(create("c:/foo").startsWithIgnoringCase(create("C:/"))).isTrue()
        assertThat(create("c:/foo").startsWithIgnoringCase(create("c:/"))).isTrue()

        assertThat(create("C:/foo").startsWithIgnoringCase(create("D:/"))).isFalse()
        assertThat(create("C:/foo").startsWithIgnoringCase(create("d:/"))).isFalse()
        assertThat(create("c:/foo").startsWithIgnoringCase(create("D:/"))).isFalse()
        assertThat(create("c:/foo").startsWithIgnoringCase(create("d:/"))).isFalse()
    }

    @org.junit.Test
    fun testGetParentDirectoryWindows() {
        assertThat(create("C:/foo").getParentDirectory()).isEqualTo(create("C:/"))
        assertThat(create("C:/").getParentDirectory()).isNull()
        assertThat(create("/").getParentDirectory()).isNull()
    }

    @org.junit.Test
    fun testParentOfRootIsRootWindows() {
        assertThat(create("C:/..")).isEqualTo(create("C:/"))
        assertThat(create("C:/../../../../../..")).isEqualTo(create("C:/"))
        assertThat(create("C:/../../../foo")).isEqualTo(create("C:/foo"))
    }

    @org.junit.Test
    fun testRelativeToWindows() {
        assertThat(create("C:/foo").relativeTo(create("C:/")).getPathString()).isEqualTo("foo")
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { create("D:/foo").relativeTo(create("C:/")) })
    }

    @org.junit.Test
    fun testResolvesShortenedPaths() {
        val shortPathResolver = MockShortPathResolver()
        val osPathPolicy: WindowsOsPathPolicy = WindowsOsPathPolicy(shortPathResolver)
        shortPathResolver.resolutions.put("d:/progra~1", "program files")
        shortPathResolver.resolutions.put("d:/program files/micros~1", "microsoft something")
        shortPathResolver.resolutions.put(
            "d:/program files/microsoft something/foo/~bar~1", "~bar_hello"
        )

        // Assert normal shortpath resolution.
        Truth.assertThat(normalize(osPathPolicy, "d:/progra~1/micros~1/foo/~bar~1/baz"))
            .isEqualTo("D:/program files/microsoft something/foo/~bar_hello/baz")
        Truth.assertThat(normalize(osPathPolicy, "d:/progra~1/micros~1/foo/will~1.exi/bar"))
            .isEqualTo("D:/program files/microsoft something/foo/will~1.exi/bar")

        Truth.assertThat(normalize(osPathPolicy, "d:/progra~1/micros~1"))
            .isEqualTo("D:/program files/microsoft something")

        // Pretend that a path we already failed to resolve once came into existence.
        shortPathResolver.resolutions.put(
            "d:/program files/microsoft something/foo/will~1.exi", "will.exist"
        )

        // Assert that this time we can resolve the previously non-existent path.
        // The path string has an upper-case drive letter because that's how path printing works.
        Truth.assertThat(normalize(osPathPolicy, "d:/progra~1/micros~1/foo/will~1.exi/bar"))
            .isEqualTo("D:/program files/microsoft something/foo/will.exist/bar")

        // Check needsToNormalized
        assertThat(osPathPolicy.needsToNormalize("d:/progra~1/micros~1/foo/will~1.exi/bar"))
            .isEqualTo(WindowsOsPathPolicy.NEEDS_SHORT_PATH_NORMALIZATION)
        assertThat(osPathPolicy.needsToNormalize("will~1.exi"))
            .isEqualTo(WindowsOsPathPolicy.NEEDS_SHORT_PATH_NORMALIZATION)
        assertThat(osPathPolicy.needsToNormalize("d:/no-normalization"))
            .isEqualTo(WindowsOsPathPolicy.NORMALIZED)
    }

    companion object {
        private fun normalize(osPathPolicy: OsPathPolicy, str: String?): String {
            return osPathPolicy.normalize(str, osPathPolicy.needsToNormalize(str))
        }
    }
}

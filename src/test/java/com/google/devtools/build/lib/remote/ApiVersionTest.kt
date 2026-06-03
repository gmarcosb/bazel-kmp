// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote

import build.bazel.semver.SemVer

// Tests for {@link ApiVersion}.
@RunWith(JUnit4::class)
class ApiVersionTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToString() {
        assertThat(ApiVersion(0, 0, 0, "v1test").toString()).isEqualTo("v1test")
        assertThat(ApiVersion(1, 2, 3, "v1test").toString()).isEqualTo("v1test")
        assertThat(ApiVersion(2, 0, 0, "").toString()).isEqualTo("2.0")
        assertThat(ApiVersion(2, 1, 0, "").toString()).isEqualTo("2.1")
        assertThat(ApiVersion(10, 0, 3, "").toString()).isEqualTo("10.0.3")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompareTo() {
        assertThat(ApiVersion(0, 0, 0, "v1test").compareTo(ApiVersion(0, 0, 0, "v1test")))
            .isEqualTo(0)
        assertThat(ApiVersion(0, 0, 0, "v1test").compareTo(ApiVersion(0, 1, 0, "")))
            .isLessThan(0)
        assertThat(ApiVersion(0, 0, 1, "").compareTo(ApiVersion(1, 0, 0, "v1test")))
            .isGreaterThan(0)
        assertThat(ApiVersion(1, 0, 0, "").compareTo(ApiVersion(2, 0, 0, ""))).isLessThan(0)
        assertThat(ApiVersion(2, 0, 0, "").compareTo(ApiVersion(1, 0, 0, ""))).isGreaterThan(0)
        assertThat(ApiVersion(2, 1, 0, "").compareTo(ApiVersion(2, 2, 0, ""))).isLessThan(0)
        assertThat(ApiVersion(2, 2, 0, "").compareTo(ApiVersion(2, 1, 0, ""))).isGreaterThan(0)
        assertThat(ApiVersion(2, 2, 1, "").compareTo(ApiVersion(2, 2, 2, ""))).isLessThan(0)
        assertThat(ApiVersion(2, 2, 2, "").compareTo(ApiVersion(2, 1, 1, ""))).isGreaterThan(0)
        assertThat(ApiVersion(2, 2, 2, "").compareTo(ApiVersion(2, 2, 2, ""))).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFromToSemver() {
        val semvers: Array<SemVer?> =
            arrayOf<SemVer?>(
                SemVer.newBuilder().setMajor(2).build(),
                SemVer.newBuilder().setMajor(2).setMinor(1).setPatch(3).build(),
                SemVer.newBuilder().setPrerelease("v1test").build(),
            )
        for (sm in semvers) {
            assertThat(ApiVersion(sm).toSemVer()).isEqualTo(sm)
        }
    }
}

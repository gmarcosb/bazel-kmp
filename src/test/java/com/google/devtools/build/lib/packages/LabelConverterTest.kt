// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.Label

/** Test of [LabelConverter].  */
@RunWith(JUnit4::class)
class LabelConverterTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun convertLabel() {
        val basePackage: PackageIdentifier? = PackageIdentifier.create("quux", PathFragment.create("baz"))
        val converter: LabelConverter =
            LabelConverter(
                basePackage,
                RepositoryMapping.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>("orig_repo", RepositoryName.create("new_repo")),
                    RepositoryName.MAIN
                )
            )
        assertThat(converter.convert("@orig_repo//foo:bar"))
            .isEqualTo(Label.parseCanonical("@new_repo//foo:bar"))
        assertThat(converter.convert("//foo:bar")).isEqualTo(Label.parseCanonical("@quux//foo:bar"))
        assertThat(converter.convert(":bar")).isEqualTo(Label.parseCanonical("@quux//baz:bar"))
    }
}

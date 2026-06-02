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
package com.google.devtools.build.docgen

import com.google.devtools.build.docgen.starlark.StarlarkDocExpander

/** A test class for StarlarkDocExpander.  */
@RunWith(JUnit4::class)
class StarlarkDocExpanderTest {
    @org.junit.Test
    fun testExpand() {
        DocgenConsts.starlarkDocsRoot = "/strlrk"
        val linkMap: DocLinkMap =
            DocLinkMap( /* beRoot= */
                "/be_root",
                com.google.common.collect.ImmutableMap.of<K?, V?>("foo", "foobar.html"),  /* sourceUrlRoot= */
                "",
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )
        val expander: StarlarkDocExpander = StarlarkDocExpander(RuleLinkExpander(false, linkMap))

        assertThat(expander.expand("\$STARLARK_DOCS_ROOT")).isEqualTo("/strlrk")
        assertThat(expander.expand("\$BE_ROOT")).isEqualTo("/be_root")
        assertThat(expander.expand("\${link foo}")).isEqualTo("foobar.html")
    }
}

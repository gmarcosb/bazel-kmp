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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.vfs.ModifiedFileSet

/** Integration tests for [LocationExpander].  */
@RunWith(JUnit4::class)
class LocationExpanderIntegrationTest : BuildViewTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun createFiles() {
        // Set up a rule to test expansion in.
        scratch.file("files/fileA")
        scratch.file("files/fileB")

        scratch.file(
            "files/BUILD",
            """
        filegroup(
            name = "files",
            srcs = [
                "fileA",
                "fileB",
            ],
        )

        filegroup(
            name = "lib",
            srcs = [":files"],
        )
        
        """.trimIndent()
        )
    }

    @Throws(java.lang.Exception::class)
    private fun makeExpander(label: String?): LocationExpander {
        val target: ConfiguredTarget? = getConfiguredTarget(label)
        val ruleContext: RuleContext? = getRuleContext(target)
        return LocationExpander.withRunfilesPaths(ruleContext, null)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLocations() {
        // Smoke test
        val expander: LocationExpander = makeExpander("//files:lib")
        val input = "foo $(locations :files) bar"
        val result: String? = expander.expand(input)

        Truth.assertThat(result).isEqualTo("foo files/fileA files/fileB bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLocationAlias() {
        scratch.file(
            "alias/BUILD",
            """
        alias(
            name = "files_alias",
            actual = "//files:files",
        )

        filegroup(
            name = "lib",
            srcs = [":files_alias"],
        )
        
        """.trimIndent()
        )

        val expander: LocationExpander = makeExpander("//alias:lib")

        // Verifies expansion of $(locations) is the same for target and its alias
        val locationTarget = "foo $(locations //files:files) bar"
        val locationAlias = "foo $(locations :files_alias) bar"

        assertThat(expander.expand(locationTarget)).isEqualTo("foo files/fileA files/fileB bar")
        assertThat(expander.expand(locationAlias)).isEqualTo("foo files/fileA files/fileB bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLocationAliasAlias() {
        scratch.file(
            "alias/BUILD",
            """
        alias(
            name = "files_alias",
            actual = "//files:files",
        )

        alias(
            name = "files_alias_alias",
            actual = ":files_alias",
        )

        filegroup(
            name = "lib",
            srcs = [":files_alias_alias"],
        )
        
        """.trimIndent()
        )

        val expander: LocationExpander = makeExpander("//alias:lib")

        // Verifies expansion of $(locations) is the same for target and alias of its alias
        val locationTarget = "foo $(locations //files:files) bar"
        val locationAliasAlias = "foo $(locations :files_alias_alias) bar"

        assertThat(expander.expand(locationTarget)).isEqualTo("foo files/fileA files/fileB bar")
        assertThat(expander.expand(locationAliasAlias)).isEqualTo("foo files/fileA files/fileB bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun locations_spaces() {
        scratch.file("spaces/file with space A")
        scratch.file("spaces/file with space B")
        scratch.file(
            "spaces/BUILD",
            """
        filegroup(
            name = "files",
            srcs = [
                "file with space A",
                "file with space B",
            ],
        )

        filegroup(
            name = "lib",
            srcs = [":files"],
        )
        
        """.trimIndent()
        )

        val expander: LocationExpander = makeExpander("//spaces:lib")
        val input = "foo $(locations :files) bar"
        val result: String? = expander.expand(input)

        Truth.assertThat(result).isEqualTo("foo 'spaces/file with space A' 'spaces/file with space B' bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun otherPathExpansion() {
        scratch.file(
            "expansion/BUILD",
            """
        genrule(
            name = "foo",
            outs = ["foo.txt"],
            cmd = "never executed",
        )

        filegroup(
            name = "lib",
            srcs = [":foo"],
        )
        
        """.trimIndent()
        )
        scratch.overwriteFile("MODULE.bazel", "module(name='workspace')")
        // Invalidate WORKSPACE to pick up the name.
        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter, ModifiedFileSet.EVERYTHING_MODIFIED, Root.fromPath(rootDirectory)
            )

        val expander: LocationExpander = makeExpander("//expansion:lib")
        assertThat(expander.expand("foo $(execpath :foo) bar"))
            .matches("foo .*-out/.*/expansion/foo\\.txt bar")
        assertThat(expander.expand("foo $(execpaths :foo) bar"))
            .matches("foo .*-out/.*/expansion/foo\\.txt bar")
        assertThat(expander.expand("foo $(rootpath :foo) bar"))
            .matches("foo expansion/foo.txt bar")
        assertThat(expander.expand("foo $(rootpaths :foo) bar"))
            .matches("foo expansion/foo.txt bar")
        assertThat(expander.expand("foo $(rlocationpath :foo) bar"))
            .isEqualTo("foo " + ruleClassProvider.getRunfilesPrefix() + "/expansion/foo.txt bar")
        assertThat(expander.expand("foo $(rlocationpaths :foo) bar"))
            .isEqualTo("foo " + ruleClassProvider.getRunfilesPrefix() + "/expansion/foo.txt bar")
        assertThat(expander.expand("foo $(rlocationpath //expansion:foo) bar"))
            .isEqualTo("foo " + ruleClassProvider.getRunfilesPrefix() + "/expansion/foo.txt bar")
        assertThat(expander.expand("foo $(rlocationpaths //expansion:foo) bar"))
            .isEqualTo("foo " + ruleClassProvider.getRunfilesPrefix() + "/expansion/foo.txt bar")
        assertThat(expander.expand("foo $(rlocationpath @//expansion:foo) bar"))
            .isEqualTo("foo " + ruleClassProvider.getRunfilesPrefix() + "/expansion/foo.txt bar")
        assertThat(expander.expand("foo $(rlocationpaths @//expansion:foo) bar"))
            .isEqualTo("foo " + ruleClassProvider.getRunfilesPrefix() + "/expansion/foo.txt bar")
        assertThat(expander.expand("foo $(rlocationpath @workspace//expansion:foo) bar"))
            .isEqualTo("foo " + ruleClassProvider.getRunfilesPrefix() + "/expansion/foo.txt bar")
        assertThat(expander.expand("foo $(rlocationpaths @workspace//expansion:foo) bar"))
            .isEqualTo("foo " + ruleClassProvider.getRunfilesPrefix() + "/expansion/foo.txt bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun otherPathExternalExpansion() {
        scratch.file("expansion/BUILD", "filegroup(name='lib', srcs=['@r//p:foo'])")
        scratch.appendFile(
            "MODULE.bazel",
            "bazel_dep(name = 'r')",
            "local_path_override(module_name = 'r', path = '/r')"
        )

        // Invalidate WORKSPACE so @r can be resolved.
        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter, ModifiedFileSet.EVERYTHING_MODIFIED, Root.fromPath(rootDirectory)
            )

        scratch.resolve("/foo/bar").createDirectoryAndParents()
        scratch.file("/r/MODULE.bazel", "module(name = 'r')")
        scratch.file("/r/p/BUILD", "genrule(name='foo', outs=['foo.txt'], cmd='never executed')")

        val expander: LocationExpander = makeExpander("//expansion:lib")
        assertThat(expander.expand("foo $(execpath @r//p:foo) bar"))
            .matches("foo .*-out/.*/external/r\\+/p/foo\\.txt bar")
        assertThat(expander.expand("foo $(execpaths @r//p:foo) bar"))
            .matches("foo .*-out/.*/external/r\\+/p/foo\\.txt bar")
        assertThat(expander.expand("foo $(rootpath @r//p:foo) bar"))
            .matches("foo ../r\\+/p/foo.txt bar")
        assertThat(expander.expand("foo $(rootpaths @r//p:foo) bar"))
            .matches("foo ../r\\+/p/foo.txt bar")
        assertThat(expander.expand("foo $(rlocationpath @r//p:foo) bar"))
            .isEqualTo("foo r+/p/foo.txt bar")
        assertThat(expander.expand("foo $(rlocationpath @r//p:foo) bar"))
            .isEqualTo("foo r+/p/foo.txt bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun otherPathExternalExpansionSiblingRepositoryLayout() {
        scratch.file("expansion/BUILD", "filegroup(name='lib', srcs=['@r//p:foo'])")
        scratch.appendFile(
            "MODULE.bazel",
            "bazel_dep(name = 'r')",
            "local_path_override(module_name = 'r', path = '/r')"
        )

        // Invalidate WORKSPACE so @r can be resolved.
        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter, ModifiedFileSet.EVERYTHING_MODIFIED, Root.fromPath(rootDirectory)
            )

        scratch.resolve("/foo/bar").createDirectoryAndParents()
        scratch.file("/r/MODULE.bazel", "module(name = 'r')")
        scratch.file("/r/p/BUILD", "genrule(name='foo', outs=['foo.txt'], cmd='never executed')")

        setBuildLanguageOptions("--experimental_sibling_repository_layout")
        val expander: LocationExpander = makeExpander("//expansion:lib")
        assertThat(expander.expand("foo $(execpath @r//p:foo) bar"))
            .matches("foo .*-out/r\\+/.*/p/foo\\.txt bar")
        assertThat(expander.expand("foo $(execpaths @r//p:foo) bar"))
            .matches("foo .*-out/r\\+/.*/p/foo\\.txt bar")
        assertThat(expander.expand("foo $(rootpath @r//p:foo) bar"))
            .matches("foo ../r\\+/p/foo.txt bar")
        assertThat(expander.expand("foo $(rootpaths @r//p:foo) bar"))
            .matches("foo ../r\\+/p/foo.txt bar")
        assertThat(expander.expand("foo $(rlocationpath @r//p:foo) bar"))
            .isEqualTo("foo r+/p/foo.txt bar")
        assertThat(expander.expand("foo $(rlocationpaths @r//p:foo) bar"))
            .isEqualTo("foo r+/p/foo.txt bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun otherPathMultiExpansion() {
        scratch.file(
            "expansion/BUILD",
            """
        genrule(
            name = "foo",
            outs = [
                "foo.txt",
                "bar.txt",
            ],
            cmd = "never executed",
        )

        filegroup(
            name = "lib",
            srcs = [":foo"],
        )
        
        """.trimIndent()
        )

        val expander: LocationExpander = makeExpander("//expansion:lib")
        assertThat(expander.expand("foo $(execpaths :foo) bar"))
            .matches("foo .*-out/.*/expansion/bar\\.txt .*-out/.*/expansion/foo\\.txt bar")
        assertThat(expander.expand("foo $(rootpaths :foo) bar"))
            .matches("foo expansion/bar.txt expansion/foo.txt bar")
        assertThat(expander.expand("foo $(rlocationpaths :foo) bar"))
            .isEqualTo(
                "foo __main__/expansion/bar.txt __main__/expansion/foo.txt bar"
                    .replace("__main__", ruleClassProvider.getRunfilesPrefix())
            )
    }
}

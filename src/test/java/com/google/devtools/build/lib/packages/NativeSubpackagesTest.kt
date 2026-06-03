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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.Label

/** Tests for `native.subpackages` function.  */
@RunWith(TestParameterInjector::class)
class NativeSubpackagesTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun subpackages_simple_subDir() {
        makeSubpackageFileGroup("test/starlark/BUILD", ALL_SUBDIRS, null, null)
        makeFilesSubPackage("test/starlark/sub")

        assertAttrLabelList(
            "//test/starlark:files",
            "srcs",
            com.google.common.collect.ImmutableList.of<String?>("//test/starlark/sub:files")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun subpackages_simple_include() {
        makeSubpackageFileGroup("test/starlark/BUILD", "sub1", null, null)

        makeFilesSubPackage("test/starlark/sub")
        makeFilesSubPackage("test/starlark/sub1")
        makeFilesSubPackage("test/starlark/sub2")

        assertAttrLabelList(
            "//test/starlark:files",
            "srcs",
            com.google.common.collect.ImmutableList.of<String?>("//test/starlark/sub1:files")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun subpackages_simple_exclude() {
        makeSubpackageFileGroup("test/starlark/BUILD", ALL_SUBDIRS, "['sub2/**']", null)

        makeFilesSubPackage("test/starlark/sub")
        makeFilesSubPackage("test/starlark/sub1")
        makeFilesSubPackage("test/starlark/sub2")
        makeFilesSubPackage("test/starlark/sub3")

        assertAttrLabelList(
            "//test/starlark:files",
            "srcs",
            com.google.common.collect.ImmutableList.of<String?>(
                "//test/starlark/sub:files",
                "//test/starlark/sub1:files",
                "//test/starlark/sub3:files"
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun subpackages_simple_empty_allow() {
        makeSubpackageFileGroup("test/starlark/BUILD", ALL_SUBDIRS, null, true)
        assertAttrLabelList("//test/starlark:files", "srcs", com.google.common.collect.ImmutableList.of<String?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun subpackages_simple_empty_disallow() {
        makeSubpackageFileGroup("test/starlark/BUILD", ALL_SUBDIRS, null, null)

        // force evaluation
        val e: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//test/starlark:files") })
        Truth.assertThat(e).hasMessageThat().contains("subpackages pattern '**' didn't match anything")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun subpackages_deeplyNested_withSubdirs() {
        makeSubpackageFileGroup("test/starlark/BUILD", ALL_SUBDIRS, null, true)

        // Setup a dir with 2 subdirs, 1 a package one not
        makeFilesSubPackage("test/starlark/sub")
        // Should be blocked by 'sub'
        makeFilesSubPackage("test/starlark/sub/sub2")

        makeFilesSubPackage("test/starlark/sub3")
        makeFilesSubPackage("test/starlark/not_sub/sub_is_pkg/eventually")

        scratch.file("test/starlark/not_sub/file1.txt")
        scratch.file("test/starlark/not_sub/double_not_sub/file.txt")

        assertAttrLabelList(
            "//test/starlark:files",
            "srcs",
            com.google.common.collect.ImmutableList.of<String?>(
                "//test/starlark/sub:files",
                "//test/starlark/sub3:files",
                "//test/starlark/not_sub/sub_is_pkg/eventually:files"
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun subpackages_incremental_addSubPkg() {
        makeSubpackageFileGroup("test/starlark/BUILD", ALL_SUBDIRS, null, null)

        // Setup a two subdirs one shallow and one deep
        makeFilesSubPackage("test/starlark/sub")
        makeFilesSubPackage("test/starlark/deep/1/2/3")

        assertAttrLabelList(
            "//test/starlark:files",
            "srcs",
            com.google.common.collect.ImmutableList.of<String?>(
                "//test/starlark/sub:files",
                "//test/starlark/deep/1/2/3:files"
            )
        )

        // Add a 2nd shallow and 2nd deep mid
        makeFilesSubPackage("test/starlark/sub2")

        // Poke Skyframe by invalidating the dirent and files that changed.
        invalidateSkyFrameFiles(
            "test/starlark/sub2", "test/starlark/sub2/BUILD", "test/starlark/sub2/file.txt"
        )

        // We should now be aware of the new one via Skyframe invalidation.
        assertAttrLabelList(
            "//test/starlark:files",
            "srcs",
            com.google.common.collect.ImmutableList.of<String?>(
                "//test/starlark/sub:files",
                "//test/starlark/sub2:files",
                "//test/starlark/deep/1/2/3:files"
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun subpackages_incremental_delSubPkg() {
        makeSubpackageFileGroup("test/starlark/BUILD", ALL_SUBDIRS, null, null)

        // Setup a single subdir
        makeFilesSubPackage("test/starlark/sub")
        makeFilesSubPackage("test/starlark/sub2")

        assertAttrLabelList(
            "//test/starlark:files",
            "srcs",
            com.google.common.collect.ImmutableList.of<String?>(
                "//test/starlark/sub:files",
                "//test/starlark/sub2:files"
            )
        )

        scratch.deleteFile("test/starlark/sub2/BUILD")
        scratch.deleteFile("test/starlark/sub2/file.txt")

        invalidateSkyFrameFiles("test/starlark/sub2/BUILD", "test/starlark/sub2/file.txt")

        // We should now be aware of the new one.
        assertAttrLabelList(
            "//test/starlark:files",
            "srcs",
            com.google.common.collect.ImmutableList.of<String?>("//test/starlark/sub:files")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun subpackages_incremental_convertSubDirToPkg() {
        makeSubpackageFileGroup("test/starlark/BUILD", ALL_SUBDIRS, null, null)

        // Setup both immediate and deeply nested sub-dirs with BUILD files.
        makeFilesSubPackage("test/starlark/sub")
        scratch.file("test/starlark/sub2/file2.txt")

        // Initially we have a subdir with 'sub/BUILD' and sub2/file2.txt"
        assertAttrLabelList(
            "//test/starlark:files",
            "srcs",
            com.google.common.collect.ImmutableList.of<String?>("//test/starlark/sub:files")
        )

        // Then we add a BUILD file to sub2 making it a package Skyframe should pick
        // that up once invalidated.
        makeFilesSubPackage("test/starlark/sub2")

        // Poke Skyframe by invalidating the dirent and files that changed.
        invalidateSkyFrameFiles("test/starlark/sub2/BUILD", "test/starlark/sub2/file.txt")

        assertAttrLabelList(
            "//test/starlark:files",
            "srcs",
            com.google.common.collect.ImmutableList.of<String?>(
                "//test/starlark/sub:files",
                "//test/starlark/sub2:files"
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidPositionalParams() {
        scratch.file("foo/subdir/BUILD")
        scratch.file("foo/BUILD", "[filegroup(name = p) for p in subpackages(['subdir'])]")

        val e: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTargetAndData("//foo:subdir") })
        Truth.assertThat(e).hasMessageThat().contains("got unexpected positional argument")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidMissingInclude() {
        scratch.file("foo/subdir/BUILD")
        scratch.file("foo/BUILD", "[filegroup(name = p) for p in subpackages()]")

        val e: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTargetAndData("//foo:subdir") })
        Truth.assertThat(e).hasMessageThat().contains("missing 1 required named argument: include")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun validNoWildCardInclude() {
        makeSubpackageFileGroup(
            "test/starlark/BUILD",  /*include=*/
            com.google.common.collect.ImmutableList.of<String?>("sub", "sub2/deep"),
            null,
            null
        )
        makeFilesSubPackage("test/starlark/sub")
        makeFilesSubPackage("test/starlark/sub2/deep")

        assertAttrLabelList(
            "//test/starlark:files",
            "srcs",
            com.google.common.collect.ImmutableList.of<String?>(
                "//test/starlark/sub:files",
                "//test/starlark/sub2/deep:files"
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun includeValidMatchSubdir() {
        scratch.file("foo/subdir/BUILD")
        scratch.file("foo/BUILD", "[filegroup(name = p) for p in subpackages(include = ['subdir'])]")
        getConfiguredTargetAndData("//foo:subdir")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun includeValidSubMatchSubdir(
        @TestParameter(
            "subdir/*/deeper", "subdir/sub*/deeper", "subdir/**", "subdir/*/deeper/**", "subdir/**/deeper/**"
        ) expression: String
    ) {
        makeFilesSubPackage("test/starlark/subdir/sub/deeper")
        makeFilesSubPackage("test/starlark/subdir/sub2/deeper")
        makeFilesSubPackage("test/starlark/subdir/sub3/deeper")

        makeSubpackageFileGroup("test/starlark/BUILD", expression, null, null)

        assertAttrLabelList(
            "//test/starlark:files",
            "srcs",
            com.google.common.collect.ImmutableList.of<String?>(
                "//test/starlark/subdir/sub/deeper:files",
                "//test/starlark/subdir/sub2/deeper:files",
                "//test/starlark/subdir/sub3/deeper:files"
            )
        )
    }

    /**
     * Constructs a BUILD file with a single filegroup target whose srcs attribute is the list of all
     * //p:files, where //p is a subpackage returned by a call to native.subpackages.
     */
    @Throws(IOException::class)
    private fun makeSubpackageFileGroup(
        buildPath: String?,
        include: com.google.common.collect.ImmutableList<String?>,
        exclude: String?,
        allowEmpty: Boolean?
    ) {
        val subpackages: java.lang.StringBuilder = java.lang.StringBuilder()
        subpackages.append("subpackages(include = [")
        subpackages.append(include.stream().map<String?> { i: String? -> "'" + i + "'" }
            .collect(Collectors.joining(", ")))
        subpackages.append("]")

        if (exclude != null) {
            subpackages.append(", exclude = ")
            subpackages.append(exclude)
        }

        if (allowEmpty != null) {
            subpackages.append(", allow_empty = ")
            subpackages.append(if (allowEmpty) "True" else "False")
        }
        subpackages.append(")")

        scratch.file(
            buildPath,
            "filegroup(",
            "   name = 'files',",
            "   srcs = [",
            "     '//%s/%s:files' % (package_name(), s) for s in " + subpackages,
            "   ],",
            ")"
        )
    }

    @Throws(IOException::class)
    private fun makeSubpackageFileGroup(
        buildPath: String?, include: String, exclude: String?, allowEmpty: Boolean?
    ) {
        makeSubpackageFileGroup(
            buildPath,
            com.google.common.collect.ImmutableList.of<String?>(include),
            exclude,
            allowEmpty
        )
    }

    /**
     * Creates a BUILD file and single file at the given packagePath, the BUILD file will contain a
     * single filegroup called 'files' which contains the created file.
     */
    @Throws(IOException::class)
    private fun makeFilesSubPackage(packagePath: String?) {
        scratch.file(packagePath + "/file.txt")
        scratch.file(
            packagePath + "/BUILD", "filegroup(", "   name = 'files',", "   srcs = glob(['*']),", ")"
        )
    }

    @Throws(java.lang.Exception::class)
    private fun assertAttrLabelList(target: String?, attrName: String?, expectedLabels: MutableList<String?>) {
        val cfgTarget: ConfiguredTargetAndData = getConfiguredTargetAndData(target)
        assertThat(cfgTarget).isNotNull()

        val labels: com.google.common.collect.ImmutableList<Label?> =
            expectedLabels.stream().map<Label?> { label: String? -> this.makeLabel(label) }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Label?>())

        val configuredAttributeMapper: ConfiguredAttributeMapper =
            getMapperFromConfiguredTargetAndTarget(cfgTarget)
        assertThat(configuredAttributeMapper.get(attrName, BuildType.LABEL_LIST))
            .containsExactlyElementsIn(labels)
    }

    private fun makeLabel(label: String?): Label? {
        try {
            return Label.parseCanonical(label)
        } catch (e: java.lang.Exception) {
            org.junit.Assert.fail("Unable to construct Label from " + label)
            return null
        }
    }

    @Throws(java.lang.Exception::class)
    private fun invalidateSkyFrameFiles(vararg files: String?) {
        val builder: ModifiedFileSet.Builder = ModifiedFileSet.builder()

        for (f in files) {
            builder.modify(PathFragment.create(f))
        }

        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter, builder.build(), Root.fromPath(rootDirectory)
            )
    }

    companion object {
        private const val ALL_SUBDIRS = "**"
    }
}

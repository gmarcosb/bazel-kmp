// Copyright 2024 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.skyframe.ProjectFilesLookupFunction.PROJECT_FILE_NAME

/** Tests for [Project].  */
@RunWith(JUnit4::class)
class ProjectTest : AnalysisTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun defineSimpleRule() {
        scratch.file(
            "foo/defs.bzl",
            """
        simple_rule = rule(
            implementation = lambda ctx: [],
            attrs = {},
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun singleTargetNoProjects() {
        scratch.file(
            "foo/bar/BUILD",
            """
        load("//foo:defs.bzl", "simple_rule")

        simple_rule(name = "s")
        
        """.trimIndent()
        )

        assertThat(
            Project.findProjectFiles(
                com.google.common.collect.ImmutableList.of<E?>(Label.parseCanonical("//foo/bar:s")),
                skyframeExecutor,
                reporter
            )
        )
            .isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun singleTargetProjectInDirectPackage() {
        scratch.file(
            "foo/bar/BUILD",
            """
        load("//foo:defs.bzl", "simple_rule")

        simple_rule(name = "s")
        
        """.trimIndent()
        )
        scratch.file("foo/bar/" + PROJECT_FILE_NAME)

        assertThat(
            Project.findProjectFiles(
                com.google.common.collect.ImmutableList.of<E?>(Label.parseCanonical("//foo/bar:s")),
                skyframeExecutor,
                reporter
            )
                .asMap()
        )
            .containsExactly(
                Label.parseCanonical("//foo/bar:s"),
                com.google.common.collect.ImmutableList.of<E?>(Label.parseCanonical("//foo/bar:" + PROJECT_FILE_NAME))
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun singleTargetProjectInParentPackage() {
        scratch.file(
            "foo/bar/BUILD",
            """
        load("//foo:defs.bzl", "simple_rule")

        simple_rule(name = "s")
        
        """.trimIndent()
        )
        scratch.file("foo/BUILD")
        scratch.file("foo/" + PROJECT_FILE_NAME)

        assertThat(
            Project.findProjectFiles(
                com.google.common.collect.ImmutableList.of<E?>(Label.parseCanonical("//foo/bar:s")),
                skyframeExecutor,
                reporter
            )
                .asMap()
        )
            .containsExactly(
                Label.parseCanonical("//foo/bar:s"),
                com.google.common.collect.ImmutableList.of<E?>(Label.parseCanonical("//foo:" + PROJECT_FILE_NAME))
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun singleTargetProjectInBothDirectAndParentPackages() {
        scratch.file(
            "foo/bar/BUILD",
            """
        load("//foo:defs.bzl", "simple_rule")

        simple_rule(name = "s")
        
        """.trimIndent()
        )
        scratch.file("foo/BUILD")
        scratch.file("foo/" + PROJECT_FILE_NAME)
        scratch.file("foo/bar/" + PROJECT_FILE_NAME)

        assertThat(
            Project.findProjectFiles(
                com.google.common.collect.ImmutableList.of<E?>(Label.parseCanonical("//foo/bar:s")),
                skyframeExecutor,
                reporter
            )
                .asMap()
        )
            .containsExactly(
                Label.parseCanonical("//foo/bar:s"),
                com.google.common.collect.ImmutableList.of<E?>(
                    Label.parseCanonical("//foo/bar:" + PROJECT_FILE_NAME),
                    Label.parseCanonical("//foo:" + PROJECT_FILE_NAME)
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun singleTargetProjectInNonPackageParentDir() {
        scratch.file(
            "foo/bar/BUILD",
            """
        load("//foo:defs.bzl", "simple_rule")

        simple_rule(name = "s")
        
        """.trimIndent()
        )
        scratch.file("foo/" + PROJECT_FILE_NAME)
        scratch.file("foo/bar/" + PROJECT_FILE_NAME)

        // Project files don't count if they're in directories without BUILD files.
        assertThat(
            Project.findProjectFiles(
                com.google.common.collect.ImmutableList.of<E?>(Label.parseCanonical("//foo/bar:s")),
                skyframeExecutor,
                reporter
            )
                .asMap()
        )
            .containsExactly(
                Label.parseCanonical("//foo/bar:s"),
                com.google.common.collect.ImmutableList.of<E?>(Label.parseCanonical("//foo/bar:" + PROJECT_FILE_NAME))
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun twoTargetsInIndependentPackages() {
        scratch.file(
            "foo/BUILD",
            """
        load("//foo:defs.bzl", "simple_rule")

        simple_rule(name = "s")
        
        """.trimIndent()
        )
        scratch.file(
            "baz/BUILD",
            """
        load("//foo:defs.bzl", "simple_rule")

        simple_rule(name = "t")
        
        """.trimIndent()
        )
        scratch.file("foo/" + PROJECT_FILE_NAME)
        scratch.file("baz/" + PROJECT_FILE_NAME)

        assertThat(
            Project.findProjectFiles(
                com.google.common.collect.ImmutableList.of<E?>(
                    Label.parseCanonical("//foo:s"), Label.parseCanonical("//baz:t")
                ),
                skyframeExecutor,
                reporter
            )
                .asMap()
        )
            .containsExactly(
                Label.parseCanonical("//foo:s"),
                com.google.common.collect.ImmutableList.of<E?>(Label.parseCanonical("//foo:" + PROJECT_FILE_NAME)),
                Label.parseCanonical("//baz:t"),
                com.google.common.collect.ImmutableList.of<E?>(Label.parseCanonical("//baz:" + PROJECT_FILE_NAME))
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun twoTargetsInSubPackagesHierarchy() {
        scratch.file(
            "foo/bar/BUILD",
            """
        load("//foo:defs.bzl", "simple_rule")

        simple_rule(name = "child")
        
        """.trimIndent()
        )
        scratch.file(
            "foo/BUILD",
            """
        load("//foo:defs.bzl", "simple_rule")

        simple_rule(name = "parent")
        
        """.trimIndent()
        )
        scratch.file("foo/bar/" + PROJECT_FILE_NAME)
        scratch.file("foo/" + PROJECT_FILE_NAME)

        assertThat(
            Project.findProjectFiles(
                com.google.common.collect.ImmutableList.of<E?>(
                    Label.parseCanonical("//foo:parent"),
                    Label.parseCanonical("//foo/bar:child")
                ),
                skyframeExecutor,
                reporter
            )
                .asMap()
        )
            .containsExactly(
                Label.parseCanonical("//foo:parent"),
                com.google.common.collect.ImmutableList.of<E?>(Label.parseCanonical("//foo:" + PROJECT_FILE_NAME)),
                Label.parseCanonical("//foo/bar:child"),
                com.google.common.collect.ImmutableList.of<E?>(
                    Label.parseCanonical("//foo/bar:" + PROJECT_FILE_NAME),
                    Label.parseCanonical("//foo:" + PROJECT_FILE_NAME)
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidProjectFile() {
        scratch.file(
            "foo/BUILD",
            """
        load("//foo:defs.bzl", "simple_rule")

        simple_rule(name = "myrule")
        
        """.trimIndent()
        )
        scratch.dir("foo/" + PROJECT_FILE_NAME)

        org.junit.Assert.assertThrows<T?>(
            ProjectResolutionException::class.java,
            org.junit.function.ThrowingRunnable {
                Project.findProjectFiles(
                    com.google.common.collect.ImmutableList.of<E?>(Label.parseCanonical("//foo:myrule")),
                    skyframeExecutor,
                    reporter
                )
            })
    }
}

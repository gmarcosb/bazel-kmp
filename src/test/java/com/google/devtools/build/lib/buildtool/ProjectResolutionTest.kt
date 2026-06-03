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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.skyframe.ProjectFilesLookupFunction.PROJECT_FILE_NAME

/**
 * Tests how Bazel finds the right [Project] for a build.
 * 
 * 
 * This is an integration test between [Project] and the build process. It specifically
 * tests how builds call [Project] and use the results meaningfully. For direct unit tests on
 * projects, use [ProjectTest].
 */
@RunWith(JUnit4::class)
class ProjectResolutionTest : BuildViewTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        setBuildLanguageOptions("--experimental_enable_scl_dialect=true")
        writeProjectSclDefinition("test/project_proto.scl")
        scratch.file("test/BUILD")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildWithNoProjectFiles() {
        scratch.file("pkg/BUILD", "genrule(name='f', cmd = '', srcs=[], outs=['a.out'])")

        assertThat(
            Project.getProjectFiles(
                com.google.common.collect.ImmutableList.of<E?>(Label.parseCanonical("//pkg:f")),
                getSkyframeExecutor(),
                reporter
            )
                .isEmpty()
        )
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildWithOneProjectFile() {
        scratch.file("pkg/BUILD", "genrule(name='f', cmd = '', srcs=[], outs=['a.out'])")
        scratch.file(
            "pkg/" + PROJECT_FILE_NAME,
            """
        load("//test:project_proto.scl", "project_pb2")
        project = project_pb2.Project.create()
        
        """.trimIndent()
        )

        val projectFiles: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Project.getProjectFiles(
                com.google.common.collect.ImmutableList.of<E?>(Label.parseCanonical("//pkg:f")),
                getSkyframeExecutor(),
                reporter
            )
        assertThat(projectFiles.projectFilesToTargetLabels().keySet())
            .containsExactly(Label.parseCanonical("//pkg:" + PROJECT_FILE_NAME))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildWithTwoProjectFiles() {
        scratch.file("foo/bar/BUILD", "genrule(name='f', cmd = '', srcs=[], outs=['a.out'])")
        scratch.file("foo/BUILD")
        scratch.file(
            "foo/" + PROJECT_FILE_NAME,
            """
        load("//test:project_proto.scl", "project_pb2")
        project = project_pb2.Project.create()
        
        """.trimIndent()
        )
        scratch.file(
            "foo/bar/" + PROJECT_FILE_NAME,
            """
        load("//test:project_proto.scl", "project_pb2")
        project = project_pb2.Project.create()
        
        """.trimIndent()
        )

        val projectFiles: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Project.getProjectFiles(
                com.google.common.collect.ImmutableList.of<E?>(Label.parseCanonical("//foo/bar:f")),
                getSkyframeExecutor(),
                reporter
            )

        assertThat(projectFiles.projectFilesToTargetLabels().keySet())
            .containsExactly(Label.parseCanonical("//foo/bar:" + PROJECT_FILE_NAME))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun twoTargetsSameProjectFile() {
        scratch.file("foo/bar/BUILD", "genrule(name='child', cmd = '', srcs=[], outs=['c.out'])")
        scratch.file("foo/BUILD", "genrule(name='parent', cmd = '', srcs=[], outs=['p.out'])")
        scratch.file(
            "foo/" + PROJECT_FILE_NAME,
            """
        load("//test:project_proto.scl", "project_pb2")
        project = project_pb2.Project.create()
        
        """.trimIndent()
        )

        val projectFiles: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Project.getProjectFiles(
                com.google.common.collect.ImmutableList.of<E?>(
                    Label.parseCanonical("//foo:parent"), Label.parseCanonical("//foo/bar:child")
                ),
                getSkyframeExecutor(),
                reporter
            )
        assertThat(projectFiles.projectFilesToTargetLabels().keySet())
            .containsExactly(Label.parseCanonical("//foo:" + PROJECT_FILE_NAME))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun twoTargetsDifferentProjectFiles() {
        scratch.file("foo/BUILD", "genrule(name='f', cmd = '', srcs=[], outs=['f.out'])")
        scratch.file("bar/BUILD", "genrule(name='g', cmd = '', srcs=[], outs=['g.out'])")
        scratch.file(
            "foo/" + PROJECT_FILE_NAME,
            """
        load("//test:project_proto.scl", "project_pb2")
        project = project_pb2.Project.create()
        
        """.trimIndent()
        )
        scratch.file(
            "bar/" + PROJECT_FILE_NAME,
            """
        load("//test:project_proto.scl", "project_pb2")
        project = project_pb2.Project.create()
        
        """.trimIndent()
        )

        val projectFiles: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Project.getProjectFiles(
                com.google.common.collect.ImmutableList.of<E?>(
                    Label.parseCanonical("//foo:f"),
                    Label.parseCanonical("//bar:g")
                ),
                getSkyframeExecutor(),
                reporter
            )
        assertThat(projectFiles.projectFilesToTargetLabels().keySet())
            .containsExactly(
                Label.parseCanonical("//foo:" + PROJECT_FILE_NAME),
                Label.parseCanonical("//bar:" + PROJECT_FILE_NAME)
            )
        com.google.common.truth.Subject.contains(
            """
Targets have different project settings:
  - //foo:f -> //foo:PROJECT.scl
  - //bar:g -> //bar:PROJECT.scl
  """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun twoTargetsOnlyOneHasProjectFile() {
        scratch.file("foo/BUILD", "genrule(name='f', cmd = '', srcs=[], outs=['f.out'])")
        scratch.file("bar/BUILD", "genrule(name='g', cmd = '', srcs=[], outs=['g.out'])")
        scratch.file(
            "foo/" + PROJECT_FILE_NAME,
            """
        load("//test:project_proto.scl", "project_pb2")
        project = project_pb2.Project.create()
        
        """.trimIndent()
        )

        val projectFiles: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Project.getProjectFiles(
                com.google.common.collect.ImmutableList.of<E?>(
                    Label.parseCanonical("//foo:f"),
                    Label.parseCanonical("//bar:g")
                ),
                getSkyframeExecutor(),
                reporter
            )
        assertThat(projectFiles.projectFilesToTargetLabels().keySet())
            .containsExactly(Label.parseCanonical("//foo:" + PROJECT_FILE_NAME))
        assertThat(projectFiles.differentProjectsDetails()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun innermostPackageIsAParentDirectory() {
        scratch.file("pkg/BUILD", "genrule(name='f', cmd = '', srcs=[], outs=['a.out'])")
        scratch.file(
            "pkg/" + PROJECT_FILE_NAME,
            """
        load("//test:project_proto.scl", "project_pb2")
        project = project_pb2.Project.create()
        
        """.trimIndent()
        )
        scratch.file("pkg/subdir/not_a_build_file")
        // Doesn't count because it's not colocated with a BUILD file:
        scratch.file(
            "pkg/subdir/" + PROJECT_FILE_NAME,
            """
        load("//test:project_proto.scl", "project_pb2")
        project = project_pb2.Project.create()
        
        """.trimIndent()
        )

        val projectFiles: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Project.getProjectFiles(
                com.google.common.collect.ImmutableList.of<E?>(Label.parseCanonical("//pkg/subdir:fake_target")),
                getSkyframeExecutor(),
                reporter
            )
        assertThat(projectFiles.projectFilesToTargetLabels().keySet())
            .containsExactly(Label.parseCanonical("//pkg:" + PROJECT_FILE_NAME))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aliasProjectFile() {
        scratch.file("pkg/BUILD", "genrule(name='f', cmd = '', srcs=[], outs=['a.out'])")
        scratch.file(
            "pkg/PROJECT.scl",
            """
        project = {
          "actual": "//canonical:PROJECT.scl",
        }
        
        """.trimIndent()
        )
        scratch.file("canonical/BUILD")
        scratch.file(
            "canonical/" + PROJECT_FILE_NAME,
            """
        load("//test:project_proto.scl", "project_pb2")
        project = project_pb2.Project.create()
        
        """.trimIndent()
        )

        val projectFiles: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Project.getProjectFiles(
                com.google.common.collect.ImmutableList.of<E?>(Label.parseCanonical("//pkg:f")),
                getSkyframeExecutor(),
                reporter
            )
        assertThat(projectFiles.projectFilesToTargetLabels().keySet())
            .containsExactly(Label.parseCanonical("//canonical:PROJECT.scl"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aliasActualAttributeWrongType() {
        scratch.file("pkg/BUILD", "genrule(name='f', cmd = '', srcs=[], outs=['a.out'])")
        scratch.file(
            "pkg/PROJECT.scl",
            """
        project = {
          "actual": ["//canonical:PROJECT.scl"],
        }
        
        """.trimIndent()
        )

        val thrown: T? =
            org.junit.Assert.assertThrows<T?>(
                ProjectResolutionException::class.java,
                org.junit.function.ThrowingRunnable {
                    Project.getProjectFiles(
                        com.google.common.collect.ImmutableList.of<E?>(Label.parseCanonical("//pkg:f")),
                        getSkyframeExecutor(),
                        reporter
                    )
                })
        assertThat(thrown)
            .hasMessageThat()
            .contains("project[\"actual\"]: expected string, got [\"//canonical:PROJECT.scl\"]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aliasWithExtraProjectData() {
        scratch.file("pkg/BUILD", "genrule(name='f', cmd = '', srcs=[], outs=['a.out'])")
        scratch.file(
            "pkg/PROJECT.scl",
            """
        project = {
          "actual": "//canonical:PROJECT.scl",
          "extra": "data",
        }
        
        """.trimIndent()
        )

        val thrown: T? =
            org.junit.Assert.assertThrows<T?>(
                ProjectResolutionException::class.java,
                org.junit.function.ThrowingRunnable {
                    Project.getProjectFiles(
                        com.google.common.collect.ImmutableList.of<E?>(Label.parseCanonical("//pkg:f")),
                        getSkyframeExecutor(),
                        reporter
                    )
                })
        assertThat(thrown)
            .hasMessageThat()
            .contains("project[\"actual\"] is present, but other keys are present as well")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aliasWithExtraGlobalSymbol() {
        scratch.file("pkg/BUILD", "genrule(name='f', cmd = '', srcs=[], outs=['a.out'])")
        scratch.file(
            "pkg/PROJECT.scl",
            """
        project = {
          "actual": "//canonical:PROJECT.scl",
        }
        other_global = {}
        
        """.trimIndent()
        )

        val thrown: T? =
            org.junit.Assert.assertThrows<T?>(
                ProjectResolutionException::class.java,
                org.junit.function.ThrowingRunnable {
                    Project.getProjectFiles(
                        com.google.common.collect.ImmutableList.of<E?>(Label.parseCanonical("//pkg:f")),
                        getSkyframeExecutor(),
                        reporter
                    )
                })
        // This isn't actually specific to aliases: no PROJECT.scl fine can define non-"project"
        // globals. Still want to check here since aliases have their own reason for this: make sure
        // they're pure aliases and nothing else.
        assertThat(thrown)
            .hasMessageThat()
            .contains("project global variable is present, but other globals are present as well")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aliasRefDoesntExist() {
        scratch.file("pkg/BUILD", "genrule(name='f', cmd = '', srcs=[], outs=['a.out'])")
        scratch.file(
            "pkg/PROJECT.scl",
            """
        project = {
          "actual": "//canonical:PROJECT.scl",
        }
        
        """.trimIndent()
        )
        scratch.file("canonical/BUILD")

        val thrown: T? =
            org.junit.Assert.assertThrows<T?>(
                ProjectResolutionException::class.java,
                org.junit.function.ThrowingRunnable {
                    Project.getProjectFiles(
                        com.google.common.collect.ImmutableList.of<E?>(Label.parseCanonical("//pkg:f")),
                        getSkyframeExecutor(),
                        reporter
                    )
                })
        // This isn't actually specific to aliases: no PROJECT.scl fine can define non-"project"
        // globals. Still want to check here since aliases have their own reason for this: make sure
        // they're pure aliases and nothing else.
        assertThat(thrown)
            .hasMessageThat()
            .contains("cannot load '//canonical:PROJECT.scl': no such file")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aliasToAlias() {
        scratch.file("pkg/BUILD", "genrule(name='f', cmd = '', srcs=[], outs=['a.out'])")
        scratch.file(
            "pkg/PROJECT.scl",
            """
        project = {
          "actual": "//pkg2:PROJECT.scl",
        }
        
        """.trimIndent()
        )
        scratch.file("pkg2/BUILD")
        scratch.file(
            "pkg2/PROJECT.scl",
            """
        project = {
          "actual": "//canonical:PROJECT.scl",
        }
        
        """.trimIndent()
        )
        scratch.file("canonical/BUILD")
        scratch.file(
            "canonical/" + PROJECT_FILE_NAME,
            """
        load("//test:project_proto.scl", "project_pb2")
        project = project_pb2.Project.create()
        
        """.trimIndent()
        )

        val projectFiles: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Project.getProjectFiles(
                com.google.common.collect.ImmutableList.of<E?>(Label.parseCanonical("//pkg:f")),
                getSkyframeExecutor(),
                reporter
            )
        assertThat(projectFiles.projectFilesToTargetLabels().keySet())
            .containsExactly(Label.parseCanonical("//canonical:PROJECT.scl"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sameProjectFileAfterAliasResolution() {
        scratch.file("pkg1/BUILD", "genrule(name='f', cmd = '', srcs=[], outs=['a.out'])")
        scratch.file(
            "pkg1/PROJECT.scl",
            """
        project = {
          "actual": "//canonical:PROJECT.scl",
        }
        
        """.trimIndent()
        )
        scratch.file("pkg2/BUILD", "genrule(name='g', cmd = '', srcs=[], outs=['a.out'])")
        scratch.file(
            "pkg2/PROJECT.scl",
            """
        project = {
          "actual": "//canonical:PROJECT.scl",
        }
        
        """.trimIndent()
        )
        scratch.file("canonical/BUILD")
        scratch.file(
            "canonical/" + PROJECT_FILE_NAME,
            """
        load("//test:project_proto.scl", "project_pb2")
        project = project_pb2.Project.create()
        
        """.trimIndent()
        )

        val projectFiles: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Project.getProjectFiles(
                com.google.common.collect.ImmutableList.of<E?>(
                    Label.parseCanonical("//pkg1:f"),
                    Label.parseCanonical("//pkg2:g")
                ),
                getSkyframeExecutor(),
                reporter
            )
        assertThat(projectFiles.projectFilesToTargetLabels().keySet())
            .containsExactly(Label.parseCanonical("//canonical:PROJECT.scl"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun differentProjectFilesAfterAliasResolution() {
        scratch.file("pkg1/BUILD", "genrule(name='f', cmd = '', srcs=[], outs=['a.out'])")
        scratch.file(
            "pkg1/PROJECT.scl",
            """
        project = {
          "actual": "//canonical1:PROJECT.scl",
        }
        
        """.trimIndent()
        )
        scratch.file("pkg2/BUILD", "genrule(name='g', cmd = '', srcs=[], outs=['a.out'])")
        scratch.file(
            "pkg2/PROJECT.scl",
            """
        project = {
          "actual": "//canonical2:PROJECT.scl",
        }
        
        """.trimIndent()
        )
        scratch.file("canonical1/BUILD")
        scratch.file(
            "canonical1/" + PROJECT_FILE_NAME,
            """
        load("//test:project_proto.scl", "project_pb2")
        project = project_pb2.Project.create()
        
        """.trimIndent()
        )
        scratch.file("canonical2/BUILD")
        scratch.file(
            "canonical2/" + PROJECT_FILE_NAME,
            """
        load("//test:project_proto.scl", "project_pb2")
        project = project_pb2.Project.create()
        
        """.trimIndent()
        )

        val projectFiles: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Project.getProjectFiles(
                com.google.common.collect.ImmutableList.of<E?>(
                    Label.parseCanonical("//pkg1:f"),
                    Label.parseCanonical("//pkg2:g")
                ),
                getSkyframeExecutor(),
                reporter
            )
        com.google.common.truth.Subject.contains(
            """
Targets have different project settings:
  - //pkg1:f -> //canonical1:PROJECT.scl
  - //pkg2:g -> //canonical2:PROJECT.scl
  """.trimIndent()
        )
    } // TODO: b/382265245 - handle aliases that self-reference or produce cycles.
}

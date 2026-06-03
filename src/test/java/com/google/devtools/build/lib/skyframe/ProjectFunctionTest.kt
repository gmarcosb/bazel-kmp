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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.Label

@RunWith(JUnit4::class)
class ProjectFunctionTest : BuildViewTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        setBuildLanguageOptions("--experimental_enable_scl_dialect=true")
        writeProjectSclDefinition("test/project_proto.scl")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun projectFunction_emptyFile_isValid() {
        scratch.file("test/PROJECT.scl", "project = {}")
        scratch.file("test/BUILD")
        val key: ProjectValue.Key = Key(Label.parseCanonical("//test:PROJECT.scl"))

        val result: EvaluationResult<ProjectValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(skyframeExecutor, key, false, reporter)
        assertThat(result.hasError()).isFalse()

        val value: ProjectValue = result.get(key)
        assertThat(value.getDefaultProjectDirectories()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun projectFunction_returnsActiveDirectories() {
        scratch.file(
            "test/PROJECT.scl",
            """
        project = {
          "active_directories": {'default': ['foo'], 'a': ['bar', '-bar/baz']},
        }
        
        """.trimIndent()
        )
        scratch.file("test/BUILD")
        val key: ProjectValue.Key = Key(Label.parseCanonical("//test:PROJECT.scl"))

        val result: EvaluationResult<ProjectValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(skyframeExecutor, key, false, reporter)
        assertThat(result.hasError()).isFalse()

        val value: ProjectValue = result.get(key)
        val trie: com.google.common.collect.ImmutableMap<String?, PathFragmentPrefixTrie?> =
            PathFragmentPrefixTrie.transformValues(value.getProjectDirectories())
        assertThat(trie.get("default").includes(PathFragment.create("foo"))).isTrue()
        assertThat(trie.get("default").includes(PathFragment.create("bar"))).isFalse()
        assertThat(trie.get("a").includes(PathFragment.create("bar"))).isTrue()
        assertThat(trie.get("a").includes(PathFragment.create("bar/baz"))).isFalse()
        assertThat(trie.get("a").includes(PathFragment.create("bar/qux"))).isTrue()
        assertThat(trie.get("b")).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun projectFunction_returnsDefaultActiveDirectories() {
        scratch.file(
            "test/PROJECT.scl",
            """
        project = {
          "active_directories": { 'default': ['a', 'b/c'] },
        }
        
        """.trimIndent()
        )
        scratch.file("test/BUILD")
        val key: ProjectValue.Key = Key(Label.parseCanonical("//test:PROJECT.scl"))

        val result: EvaluationResult<ProjectValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(skyframeExecutor, key, false, reporter)
        assertThat(result.hasError()).isFalse()

        val value: ProjectValue = result.get(key)
        val trie: PathFragmentPrefixTrie = PathFragmentPrefixTrie.of(value.getDefaultProjectDirectories())
        assertThat(trie.includes(PathFragment.create("a"))).isTrue()
        assertThat(trie.includes(PathFragment.create("b/c"))).isTrue()
        assertThat(trie.includes(PathFragment.create("d"))).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun projectFunction_returnsDefaultActiveDirectories_topLevelProjectSchema() {
        scratch.file(
            "test/PROJECT.scl",
            """
        project = {
          "active_directories": { "default": ["a", "b/c"] }
        }
        
        """.trimIndent()
        )
        scratch.file("test/BUILD")
        val key: ProjectValue.Key = Key(Label.parseCanonical("//test:PROJECT.scl"))

        val result: EvaluationResult<ProjectValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(skyframeExecutor, key, false, reporter)
        assertThat(result.hasError()).isFalse()

        val value: ProjectValue = result.get(key)
        val trie: PathFragmentPrefixTrie = PathFragmentPrefixTrie.of(value.getDefaultProjectDirectories())
        assertThat(trie.includes(PathFragment.create("a"))).isTrue()
        assertThat(trie.includes(PathFragment.create("b/c"))).isTrue()
        assertThat(trie.includes(PathFragment.create("d"))).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun projectFunction_nonEmptyActiveDirectoriesMustHaveADefault() {
        scratch.file(
            "test/PROJECT.scl",
            """
        project = {
          "active_directories": { 'foo': ['a', 'b/c'] },
        }
        
        """.trimIndent()
        )
        scratch.file("test/BUILD")
        val key: ProjectValue.Key = Key(Label.parseCanonical("//test:PROJECT.scl"))

        val result: EvaluationResult<ProjectValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(skyframeExecutor, key, false, reporter)
        assertThat(result.hasError()).isTrue()
        assertThat(result.getError().getException())
            .hasMessageThat()
            .contains("non-empty active_directories must contain the 'default' key")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun projectFunction_incorrectType() {
        scratch.file(
            "test/PROJECT.scl",
            """
        project = {
          "active_directories": 42,
        }
        
        """.trimIndent()
        )
        scratch.file("test/BUILD")
        val key: ProjectValue.Key = Key(Label.parseCanonical("//test:PROJECT.scl"))

        val result: EvaluationResult<ProjectValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(skyframeExecutor, key, false, reporter)
        assertThat(result.hasError()).isTrue()
        assertThat(result.getError().getException())
            .hasMessageThat()
            .matches("expected a map of string to list of strings, got .+Int32")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun projectFunction_incorrectType_inList() {
        scratch.file(
            "test/PROJECT.scl",
            """
        project = {
          "active_directories": { 'default': [42] },
        }
        
        """.trimIndent()
        )
        scratch.file("test/BUILD")
        val key: ProjectValue.Key = Key(Label.parseCanonical("//test:PROJECT.scl"))

        val result: EvaluationResult<ProjectValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(skyframeExecutor, key, false, reporter)
        assertThat(result.hasError()).isTrue()
        assertThat(result.getError().getException())
            .hasMessageThat()
            .matches("expected a list of strings, got element of .+Int32")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun projectFunction_incorrectProjectType() {
        scratch.file(
            "test/PROJECT.scl",
            """
        project = 1
        
        """.trimIndent()
        )

        scratch.file("test/BUILD")
        val key: ProjectValue.Key = Key(Label.parseCanonical("//test:PROJECT.scl"))

        val result: EvaluationResult<ProjectValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(skyframeExecutor, key, false, reporter)
        assertThat(result.hasError()).isTrue()
        assertThat(result.getError().getException())
            .hasMessageThat()
            .matches("project variable: expected a map of string to objects, got .+Int32")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun projectFunction_incorrectProjectKeyType() {
        scratch.file(
            "test/PROJECT.scl",
            """
        project = {1: [] }
        
        """.trimIndent()
        )

        scratch.file("test/BUILD")
        val key: ProjectValue.Key = Key(Label.parseCanonical("//test:PROJECT.scl"))

        val result: EvaluationResult<ProjectValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(skyframeExecutor, key, false, reporter)
        assertThat(result.hasError()).isTrue()
        assertThat(result.getError().getException())
            .hasMessageThat()
            .matches("project variable: expected string key, got element of .+Int32")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun projectFunction_buildableUnitsFormat() {
        scratch.file(
            "test/PROJECT.scl",
            """
        load(
            "//test:project_proto.scl",
            "buildable_unit_pb2",
            "project_pb2",
        )
        project = project_pb2.Project.create(
          name = "test",
          enforcement_policy = "warn",
          project_directories = [ "//test/..."],
          always_allowed_configs = ["--config=foo"],
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "default",
                  target_patterns = [
                      "//test/...",
                  ],
                  description = "default",
                  flags = ["--define=foo=bar"],
                  is_default = True,
              ),
              buildable_unit_pb2.BuildableUnit.create(
                  name = "non_default",
                  target_patterns = [
                      "//test/...",
                  ],
                  description = "non default",
                  flags = ["--define=bar=baz"],
                  is_default = False,
              ),
          ],
        )
        
        """.trimIndent()
        )

        scratch.file("test/BUILD")
        val key: ProjectValue.Key = Key(Label.parseCanonical("//test:PROJECT.scl"))

        val result: EvaluationResult<ProjectValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(skyframeExecutor, key, false, reporter)
        assertThat(result.hasError()).isFalse()
        val value: ProjectValue = result.get(key)
        assertThat(value.enforcementPolicy).isEqualTo(ProjectValue.EnforcementPolicy.WARN)
        assertThat(value.getAlwaysAllowedConfigs()).isEqualTo(com.google.common.collect.ImmutableList.of<String?>("--config=foo"))
        assertThat(value.getActualProjectFile()).isEqualTo(Label.parseCanonical("//test:PROJECT.scl"))
        assertThat(value.getBuildableUnits().get("default").isDefault).isTrue()
        assertThat(value.getBuildableUnits().get("non_default").isDefault).isFalse()
        assertThat(value.getProjectDirectories()).hasSize(1)
        assertThat(value.getProjectDirectories().get("default")).containsExactly("//test/...")

        assertThat(value.getBuildableUnits()).containsKey("default")
        assertThat(value.getBuildableUnits().get("default"))
            .isEqualTo(
                ProjectValue.BuildableUnit.create(
                    "default",
                    com.google.common.collect.ImmutableList.of<E?>("//test/..."),
                    "default",
                    com.google.common.collect.ImmutableList.of<E?>("--define=foo=bar"),
                    true
                )
            )

        assertThat(value.getBuildableUnits()).containsKey("non_default")

        assertThat(value.getBuildableUnits().get("non_default"))
            .isEqualTo(
                ProjectValue.BuildableUnit.create(
                    "non_default",
                    com.google.common.collect.ImmutableList.of<E?>("//test/..."),
                    "non default",
                    com.google.common.collect.ImmutableList.of<E?>("--define=bar=baz"),
                    false
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun duplicateBuildableUnitNames() {
        scratch.file(
            "test/PROJECT.scl",
            """
        load(
            "//test:project_proto.scl",
            "buildable_unit_pb2",
            "project_pb2",
        )
        project = project_pb2.Project.create(
          name = "test",
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(name = "foo"),
              buildable_unit_pb2.BuildableUnit.create(name = "foo"),
          ],
        )
        
        """.trimIndent()
        )

        scratch.file("test/BUILD")
        val key: ProjectValue.Key = Key(Label.parseCanonical("//test:PROJECT.scl"))
        val result: EvaluationResult<ProjectValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(skyframeExecutor, key, false, reporter)

        assertThat(result.hasError()).isTrue()
        assertThat(result.getError().getException())
            .hasMessageThat()
            .isEqualTo(
                "buildable_unit name='foo' is repeated. Buildable units must have unique names."
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildableUnitSchemaDefaults() {
        scratch.file(
            "test/PROJECT.scl",
            """
        load(
            "//test:project_proto.scl",
            "buildable_unit_pb2",
            "project_pb2",
        )
        project = project_pb2.Project.create(
          name = "test",
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(name = "foo"),
          ],
        )
        
        """.trimIndent()
        )

        scratch.file("test/BUILD")
        val key: ProjectValue.Key = Key(Label.parseCanonical("//test:PROJECT.scl"))
        val result: EvaluationResult<ProjectValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(skyframeExecutor, key, false, reporter)

        assertThat(result.hasError()).isFalse()

        // Project-wide defaults:
        assertThat(result.get(key).enforcementPolicy)
            .isEqualTo(ProjectValue.EnforcementPolicy.WARN)
        assertThat(result.get(key).getAlwaysAllowedConfigs()).isNull()
        assertThat(result.get(key).getProjectDirectories()).hasSize(1)
        assertThat(result.get(key).getProjectDirectories().get("default")).isEmpty()

        // Buildable unit defaults:
        assertThat(result.get(key).getBuildableUnits().get("foo").targetPatternMatcher().isEmpty())
            .isTrue()
        assertThat(result.get(key).getBuildableUnits().get("foo").flags()).isEmpty()
        assertThat(result.get(key).getBuildableUnits().get("foo").description()).isEqualTo("foo")
        assertThat(result.get(key).getBuildableUnits().get("foo").isDefault).isFalse()
    }

    /** Asserts that a PROJECT.scl with the given contexts fails with the given message.  */
    @Throws(java.lang.Exception::class)
    private fun assertParseError(projectFileContents: String?, expectedError: String?) {
        scratch.file("test/PROJECT.scl", projectFileContents)
        scratch.file("test/BUILD")

        val key: ProjectValue.Key = Key(Label.parseCanonical("//test:PROJECT.scl"))
        val result: EvaluationResult<ProjectValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(skyframeExecutor, key, false, reporter)

        assertThat(result.hasError()).isTrue()
        assertThat(result.getError().getException()).hasMessageThat().matches(expectedError)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun projectNameTypeError_notCurrentlyParsedSoNotYetAnError() {
        // TODO: b/415068036 - update when/if we start reading project(name = "foo") to catch type
        // errors.
        scratch.file(
            "test/PROJECT.scl",
            """
        load(
            "//test:project_proto.scl",
            "buildable_unit_pb2",
            "project_pb2",
        )
        project = project_pb2.Project.create(
          name = 123,
          buildable_units = [],
        )
        
        """.trimIndent()
        )

        scratch.file("test/BUILD")
        val key: ProjectValue.Key = Key(Label.parseCanonical("//test:PROJECT.scl"))
        val result: EvaluationResult<ProjectValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(skyframeExecutor, key, false, reporter)

        assertThat(result.hasError()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildableUnitFieldTypeError() {
        assertParseError(
            """
        load(
            "//test:project_proto.scl",
            "buildable_unit_pb2",
            "project_pb2",
        )
        project = project_pb2.Project.create(
          name = "test",
          buildable_units = "bad value",
        )
        
        """.trimIndent(),
            "buildable_units must be a list of buildable unit definitions, got .*String"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun builableUnitEntryTypeError() {
        assertParseError(
            """
        load(
            "//test:project_proto.scl",
            "buildable_unit_pb2",
            "project_pb2",
        )
        project = project_pb2.Project.create(
          name = "test",
          buildable_units = [
              "bad value",
          ],
        )
        
        """.trimIndent(),
            "buildable_units entries must be structured objects, got .*String"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildableUnitNameTypeError() {
        assertParseError(
            """
        load(
            "//test:project_proto.scl",
            "buildable_unit_pb2",
            "project_pb2",
        )
        project = project_pb2.Project.create(
          name = "test",
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = 2,
                  target_patterns = [
                      "//test/...",
                  ],
                  description = "default",
                  flags = ["--define=foo=bar"],
                  is_default = True,
              ),
          ],
        )
        
        """.trimIndent(),
            "buildable_unit names must be strings, got .*Int32"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildableUnitTargetPatternsFieldTypeError() {
        assertParseError(
            """
        load(
            "//test:project_proto.scl",
            "buildable_unit_pb2",
            "project_pb2",
        )
        project = project_pb2.Project.create(
          name = "test",
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "default",
                  target_patterns = 1,
                  description = "default",
                  flags = ["--define=foo=bar"],
                  is_default = True,
              ),
          ],
        )
        
        """.trimIndent(),
            "target_patterns must be a list of strings, got .*Int32"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildableUnitTargetPatternsEntryTypeError() {
        assertParseError(
            """
        load(
            "//test:project_proto.scl",
            "buildable_unit_pb2",
            "project_pb2",
        )
        project = project_pb2.Project.create(
          name = "test",
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "default",
                  target_patterns = [2, 3],
                  description = "default",
                  flags = ["--define=foo=bar"],
                  is_default = True,
              ),
          ],
        )
        
        """.trimIndent(),
            "target_patterns entries must be strings, got .*Int32"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildableUnitDescriptionTypeError() {
        assertParseError(
            """
        load(
            "//test:project_proto.scl",
            "buildable_unit_pb2",
            "project_pb2",
        )
        project = project_pb2.Project.create(
          name = "test",
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "default",
                  target_patterns = [
                      "//test/...",
                  ],
                  description = 123,
                  flags = ["--define=foo=bar"],
                  is_default = True,
              ),
          ],
        )
        
        """.trimIndent(),
            "buildable_unit descriptions must be strings, got .*Int32"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildableUnitFlagsFieldTypeError() {
        assertParseError(
            """
        load(
            "//test:project_proto.scl",
            "buildable_unit_pb2",
            "project_pb2",
        )
        project = project_pb2.Project.create(
          name = "test",
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "default",
                  target_patterns = [
                      "//test/...",
                  ],
                  description = "default",
                  flags = "bad value",
                  is_default = True,
              ),
          ],
        )
        
        """.trimIndent(),
            "flags must be a list of strings, got .*String"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildableUnitFlagsEntryTypeError() {
        assertParseError(
            """
        load(
            "//test:project_proto.scl",
            "buildable_unit_pb2",
            "project_pb2",
        )
        project = project_pb2.Project.create(
          name = "test",
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "default",
                  target_patterns = [
                      "//test/...",
                  ],
                  description = "default",
                  flags = [123],
                  is_default = True,
              ),
          ],
        )
        
        """.trimIndent(),
            "flags entries must be strings, got .*Int32"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildableUnitIsDefaultTypeError() {
        assertParseError(
            """
        load(
            "//test:project_proto.scl",
            "buildable_unit_pb2",
            "project_pb2",
        )
        project = project_pb2.Project.create(
          name = "test",
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "default",
                  target_patterns = [
                      "//test/...",
                  ],
                  description = "default",
                  flags = ["--define=foo=bar"],
                  is_default = "not valid",
              ),
          ],
        )
        
        """.trimIndent(),
            "is_default must be a boolean, got .*String"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unknownProjectFieldError() {
        reporter.removeHandler(failFastHandler) // expect errors
        assertParseError(
            """
        load(
            "//test:project_proto.scl",
            "buildable_unit_pb2",
            "project_pb2",
        )
        project = project_pb2.Project.create(
          name = "test",
          invalid_field = "not valid",
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "default",
              ),
          ],
        )
        
        """.trimIndent(),  // The direct exception doesn't explain the cause but actual builds fail with more context:
            // "Error: project_project_Project() got unexpected keyword argument: invalid_field"
            "initialization of module 'test/PROJECT.scl' failed"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unknownBuildableUntFieldError() {
        reporter.removeHandler(failFastHandler) // expect errors
        assertParseError(
            """
        load(
            "//test:project_proto.scl",
            "buildable_unit_pb2",
            "project_pb2",
        )
        project = project_pb2.Project.create(
          name = "test",
          buildable_units = [
              buildable_unit_pb2.BuildableUnit.create(
                  name = "default",
                  invalid_field = "not valid",
              ),
          ],
        )
        
        """.trimIndent(),  // The direct exception doesn't explain the cause but actual builds fail with more context:
            // "Error: project_project_Project() got unexpected keyword argument: invalid_field"
            "initialization of module 'test/PROJECT.scl' failed"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun projectFunction_catchSyntaxError() {
        scratch.file(
            "test/PROJECT.scl",
            """
        something_is_wrong =
        
        """.trimIndent()
        )
        scratch.file("test/BUILD")
        val key: ProjectValue.Key = Key(Label.parseCanonical("//test:PROJECT.scl"))

        val e: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable {
                    SkyframeExecutorTestUtils.evaluate<T?>(
                        skyframeExecutor,
                        key,
                        false,
                        reporter
                    )
                })
        Truth.assertThat(e).hasMessageThat().contains("syntax error at 'newline': expected expression")
    }
}

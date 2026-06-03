// Copyright 2024 The Bazel Authors. All Rights Reserved.
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
package com.google.devtools.build.lib.exec

import com.google.common.truth.Truth
import com.google.devtools.build.lib.dynamic.DynamicSpawnStrategy.exec
import com.google.devtools.build.lib.exec.SpawnLogReconstructorTest
import com.google.devtools.build.lib.testutil.TestConstants
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class SpawnLogReconstructorTest {
    @org.junit.Test
    fun extractRunfilesPathDefault() {
        Truth.assertThat(matchDefault("file.txt"))
            .isEqualTo(com.google.devtools.build.lib.exec.SpawnLogReconstructorTest.Result(null, "file.txt"))
        Truth.assertThat(matchDefault("pkg/file.txt"))
            .isEqualTo(com.google.devtools.build.lib.exec.SpawnLogReconstructorTest.Result(null, "pkg/file.txt"))
        Truth.assertThat(matchDefault("pkg/external/file.txt"))
            .isEqualTo(
                com.google.devtools.build.lib.exec.SpawnLogReconstructorTest.Result(
                    null,
                    "pkg/external/file.txt"
                )
            )
        Truth.assertThat(matchDefault("external/some_repo/pkg/file.txt"))
            .isEqualTo(com.google.devtools.build.lib.exec.SpawnLogReconstructorTest.Result("some_repo", "pkg/file.txt"))
        Truth.assertThat(matchDefault("external/some-repo+/pkg/file.txt"))
            .isEqualTo(
                com.google.devtools.build.lib.exec.SpawnLogReconstructorTest.Result(
                    "some-repo+",
                    "pkg/file.txt"
                )
            )
        Truth.assertThat(matchDefault(TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/pkg/file.txt"))
            .isEqualTo(com.google.devtools.build.lib.exec.SpawnLogReconstructorTest.Result(null, "pkg/file.txt"))
        Truth.assertThat(matchDefault(TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/pkg/external/file.txt"))
            .isEqualTo(
                com.google.devtools.build.lib.exec.SpawnLogReconstructorTest.Result(
                    null,
                    "pkg/external/file.txt"
                )
            )
        Truth.assertThat(matchDefault(TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/external/some_repo/pkg/file.txt"))
            .isEqualTo(com.google.devtools.build.lib.exec.SpawnLogReconstructorTest.Result("some_repo", "pkg/file.txt"))
        Truth.assertThat(
            matchDefault(TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/external/some-repo+/pkg/file.txt")
        )
            .isEqualTo(
                com.google.devtools.build.lib.exec.SpawnLogReconstructorTest.Result(
                    "some-repo+",
                    "pkg/file.txt"
                )
            )
    }

    @org.junit.Test
    fun extractRunfilesPathSibling() {
        Truth.assertThat(matchSibling("file.txt"))
            .isEqualTo(com.google.devtools.build.lib.exec.SpawnLogReconstructorTest.Result(null, "file.txt"))
        Truth.assertThat(matchSibling("pkg/file.txt"))
            .isEqualTo(com.google.devtools.build.lib.exec.SpawnLogReconstructorTest.Result(null, "pkg/file.txt"))
        Truth.assertThat(matchSibling("pkg/external/file.txt"))
            .isEqualTo(
                com.google.devtools.build.lib.exec.SpawnLogReconstructorTest.Result(
                    null,
                    "pkg/external/file.txt"
                )
            )
        Truth.assertThat(matchSibling("external/pkg/file.txt"))
            .isEqualTo(
                com.google.devtools.build.lib.exec.SpawnLogReconstructorTest.Result(
                    null,
                    "external/pkg/file.txt"
                )
            )
        Truth.assertThat(matchSibling("../some_repo/pkg/file.txt"))
            .isEqualTo(com.google.devtools.build.lib.exec.SpawnLogReconstructorTest.Result("some_repo", "pkg/file.txt"))
        Truth.assertThat(matchSibling("../some-repo+/pkg/file.txt"))
            .isEqualTo(
                com.google.devtools.build.lib.exec.SpawnLogReconstructorTest.Result(
                    "some-repo+",
                    "pkg/file.txt"
                )
            )
        Truth.assertThat(matchSibling(TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/pkg/file.txt"))
            .isEqualTo(com.google.devtools.build.lib.exec.SpawnLogReconstructorTest.Result(null, "pkg/file.txt"))
        Truth.assertThat(matchSibling(TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/pkg/external/file.txt"))
            .isEqualTo(
                com.google.devtools.build.lib.exec.SpawnLogReconstructorTest.Result(
                    null,
                    "pkg/external/file.txt"
                )
            )
        Truth.assertThat(matchSibling(TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/external/pkg/file.txt"))
            .isEqualTo(
                com.google.devtools.build.lib.exec.SpawnLogReconstructorTest.Result(
                    null,
                    "external/pkg/file.txt"
                )
            )
        Truth.assertThat(matchSibling(TestConstants.PRODUCT_NAME + "-out/some_repo/k8-fastbuild/bin/pkg/file.txt"))
            .isEqualTo(com.google.devtools.build.lib.exec.SpawnLogReconstructorTest.Result("some_repo", "pkg/file.txt"))
        Truth.assertThat(matchSibling(TestConstants.PRODUCT_NAME + "-out/some-repo+/k8-fastbuild/bin/pkg/file.txt"))
            .isEqualTo(
                com.google.devtools.build.lib.exec.SpawnLogReconstructorTest.Result(
                    "some-repo+",
                    "pkg/file.txt"
                )
            )
        Truth.assertThat(
            matchSibling(
                TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/coverage-metadata/bin/other/pkg/gen.txt"
            )
        )
            .isEqualTo(
                com.google.devtools.build.lib.exec.SpawnLogReconstructorTest.Result(
                    null,
                    "bin/other/pkg/gen.txt"
                )
            )
        Truth.assertThat(
            matchSibling(
                TestConstants.PRODUCT_NAME
                        + "-out/some_repo/k8-fastbuild/coverage-metadata/bin/other/pkg/gen.txt"
            )
        )
            .isEqualTo(
                com.google.devtools.build.lib.exec.SpawnLogReconstructorTest.Result(
                    "some_repo",
                    "bin/other/pkg/gen.txt"
                )
            )
    }

    @kotlin.jvm.JvmRecord
    private data class Result(val repo: String?, val path: String?)

    companion object {
        private fun matchDefault(path: String?): Result {
            val result: java.util.regex.MatchResult =
                SpawnLogReconstructor.extractRunfilesPath(path,  /* siblingRepositoryLayout= */false)
            return com.google.devtools.build.lib.exec.SpawnLogReconstructorTest.Result(
                result.group("repo"),
                result.group("path")
            )
        }

        private fun matchSibling(path: String?): Result {
            val result: java.util.regex.MatchResult =
                SpawnLogReconstructor.extractRunfilesPath(path,  /* siblingRepositoryLayout= */true)
            return com.google.devtools.build.lib.exec.SpawnLogReconstructorTest.Result(
                result.group("repo"),
                result.group("path")
            )
        }
    }
}

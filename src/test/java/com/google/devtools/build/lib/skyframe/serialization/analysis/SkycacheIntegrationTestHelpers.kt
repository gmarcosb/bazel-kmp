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
package com.google.devtools.build.lib.skyframe.serialization.analysis

import com.google.devtools.build.lib.buildtool.BuildResult

/** Mixin helpers for Skycache integration tests.  */
internal interface SkycacheIntegrationTestHelpers {
    fun addOptions(vararg args: String?)

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    fun write(relativePath: String?, vararg lines: String?): Path?

    @Throws(IOException::class)
    fun writeProjectSclDefinition(dest: String?, alsoWriteBuildFile: Boolean)

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(java.lang.Exception::class)
    fun buildTarget(vararg targets: String?): BuildResult?

    val commandEnvironment: CommandEnvironment?

    @Throws(java.lang.Exception::class)
    fun assertUploadSuccess(vararg targets: String?) {
        addOptions(UPLOAD_MODE_OPTION)
        buildTarget(*targets)
        Truth.assertWithMessage("expected to serialize at least one Skyframe node")
            .that(this.commandEnvironment.getRemoteAnalysisCachingEventListener().getSerializedKeys())
            .isNotEmpty()
        Truth.assertWithMessage("expected to not have any SerializationExceptions")
            .that(
                this.commandEnvironment
                    .getRemoteAnalysisCachingEventListener()
                    .getSerializationExceptionCounts()
            )
            .isEqualTo(0)
    }

    @Throws(java.lang.Exception::class)
    fun assertDownloadSuccess(vararg targets: String?) {
        addOptions(DOWNLOAD_MODE_OPTION)
        buildTarget(*targets)
        Truth.assertWithMessage("expected to deserialize at least one Skyframe node")
            .that(this.commandEnvironment.getRemoteAnalysisCachingEventListener().getCacheHits())
            .isNotEmpty()
        Truth.assertWithMessage("expected to not have any SerializationExceptions")
            .that(
                this.commandEnvironment
                    .getRemoteAnalysisCachingEventListener()
                    .getSerializationExceptionCounts()
            )
            .isEqualTo(0)
    }

    @Throws(IOException::class)
    fun writeProjectSclWithActiveDirs(path: String?, vararg activeDirs: String?) {
        writeProjectSclDefinition("test/project_proto.scl",  /* alsoWriteBuildFile= */true)
        val activeDirsString: String? = java.util.Arrays.stream<String?>(activeDirs)
            .map<String?>(java.util.function.Function { s: String? -> "\"" + s + "\"" })
            .collect(Collectors.joining(", "))
        write(
            path + "/PROJECT.scl",
            """
load("//test:project_proto.scl", "project_pb2")
project = project_pb2.Project.create(project_directories = [%s])

"""
                .trimIndent()
                .formatted(activeDirsString)
        )
    }

    @Throws(IOException::class)
    fun writeProjectSclWithActiveDirs(path: String) {
        // Overload for the common case where the path is the only active directory.
        writeProjectSclWithActiveDirs(path, path)
    }

    companion object {
        const val OFF_MODE_OPTION: String = "--experimental_remote_analysis_cache_mode=off"
        const val UPLOAD_MODE_OPTION: String = "--experimental_remote_analysis_cache_mode=upload"
        const val DOWNLOAD_MODE_OPTION: String = "--experimental_remote_analysis_cache_mode=download"
        const val DUMP_MANIFEST_MODE_OPTION: String =
            "--experimental_remote_analysis_cache_mode=dump_upload_manifest_only"
    }
}

// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.buildeventstream

/**
 * Class providing constants for naming files in associated with tests.
 * 
 * 
 * The file names associated with a test are indexed in the build-event protocol by a string in
 * order to allow extensions of bazel to add their own files. This class provides constants for the
 * names of the standard files associated with a test.
 */
object TestFileNameConstants {
    const val SPLIT_LOGS: String = "test.splitlogs"
    const val TEST_INFRASTRUCTURE_FAILURE: String = "test.infrastructure_failure"
    const val TEST_LOG: String = "test.log"
    const val TEST_STDERR: String = "test.stderr"
    const val TEST_WARNINGS: String = "test.warnings"
    const val TEST_XML: String = "test.xml"
    const val UNUSED_RUNFILES_LOG: String = "test.unused_runfiles_log"

    // Only present for the coverage command.
    const val TEST_COVERAGE: String = "test.lcov"

    // Present for both --zip_undeclared_outputs and --nozip_undeclared_outputs.
    const val UNDECLARED_OUTPUTS_ANNOTATIONS: String = "test.outputs_manifest__ANNOTATIONS"
    const val UNDECLARED_OUTPUTS_ANNOTATIONS_PB: String = "test.outputs_manifest__ANNOTATIONS.pb"
    const val UNDECLARED_OUTPUTS_MANIFEST: String = "test.outputs_manifest__MANIFEST"

    // Only present for --zip_undeclared_outputs.
    const val UNDECLARED_OUTPUTS_ZIP: String = "test.outputs__outputs.zip"

    // Only present for --nozip_undeclared_outputs.
    // This is a prefix; each file in the undeclared outputs directory is reported individually, e.g.
    // test.outputs/path/to/file.txt.
    const val UNDECLARED_OUTPUTS_DIR: String = "test.outputs"
}

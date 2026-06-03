// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.coverageoutputgenerator

/**
 * Stores markers used by the lcov tracefile and gcov intermediate format file. See [lcov documentation](http://ltp.sourceforge.net/coverage/lcov/geninfo.1.php) and the flag
 * `--intermediate-format` in [
 * gcov documentation](https://gcc.gnu.org/onlinedocs/gcc/Invoking-Gcov.html).
 */
internal object Constants {
    const val SF_MARKER: String = "SF:"
    const val FN_MARKER: String = "FN:"
    const val FNDA_MARKER: String = "FNDA:"
    const val FNF_MARKER: String = "FNF:"
    const val FNH_MARKER: String = "FNH:"
    const val BRDA_MARKER: String = "BRDA:"
    const val BA_MARKER: String = "BA:"
    const val BRF_MARKER: String = "BRF:"
    const val BRH_MARKER: String = "BRH:"
    const val DA_MARKER: String = "DA:"
    const val LH_MARKER: String = "LH:"
    const val LF_MARKER: String = "LF:"
    const val END_OF_RECORD_MARKER: String = "end_of_record"
    const val NEVER_EVALUATED: String = "-"
    const val TRACEFILE_EXTENSION: String = ".dat"
    const val GCOV_EXTENSION: String = ".gcov"
    const val GCOV_JSON_EXTENSION: String = ".gcov.json.gz"
    const val PROFDATA_EXTENSION: String = ".profdata"
    const val DELIMITER: String = ","
    const val GCOV_VERSION_MARKER: String = "version:"
    const val GCOV_CWD_MARKER: String = "cwd:"
    const val GCOV_FILE_MARKER: String = "file:"
    const val GCOV_FUNCTION_MARKER: String = "function:"
    const val GCOV_LINE_MARKER: String = "lcount:"
    const val GCOV_BRANCH_MARKER: String = "branch:"
    const val GCOV_BRANCH_NOTEXEC: String = "notexec"
    const val GCOV_BRANCH_NOTTAKEN: String = "nottaken"
    const val GCOV_BRANCH_TAKEN: String = "taken"
}

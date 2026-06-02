// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.runtime.TestSummary

/**
 * Used to notify interested parties of test results.
 */
interface TestResultNotifier {
    /**
     * @param summaries Summary of all targets that were supposed to be tested
     * (regardless whether they actually were executed).
     * @param numberOfExecutedTargets the number of targets that were actually run.
     * Must not exceed summaries.size().
     */
    fun notify(summaries: MutableSet<TestSummary?>?, numberOfExecutedTargets: Int)
}

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
package com.google.devtools.build.lib.runtime.commands

import com.google.devtools.build.lib.buildeventstream.BuildCompletingEvent

/** This event is posted by the [TestCommand] if no tests were found.  */
class NoTestsFound(exitCode: ExitCode?, finishTimeMillis: Long) : BuildCompletingEvent(
    exitCode,
    finishTimeMillis,
    com.google.common.collect.ImmutableList.of<E?>(BuildEventIdUtil.buildMetrics())
)

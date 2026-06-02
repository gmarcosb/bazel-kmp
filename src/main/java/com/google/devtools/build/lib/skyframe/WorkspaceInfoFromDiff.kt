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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.skyframe.serialization.analysis.ClientId

/** Information for a workspace computed at the time of collecting diff.  */
interface WorkspaceInfoFromDiff {
    val evaluatingVersion: IntVersion?
        get() =// TODO: b/367284400 - handle this for external version control systems.
            IntVersion.of(Long.Companion.MIN_VALUE)

    val snapshot: java.util.Optional<ClientId?>
        get() =// TODO: b/367284400 - handle this for external version control systems.
            java.util.Optional.empty<ClientId?>()
}

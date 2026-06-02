// Copyright 2020 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.AspectCollection.AspectCycleOnPathException

/**
 * Signals an inconsistency on an aspect path: an aspect occurs twice on the path and the second
 * occurrence sees a different set of aspects.
 * 
 * 
 * {@see AspectCycleOnPathException}
 */
class InconsistentAspectOrderException(
    targetLabel: com.google.devtools.build.lib.cmdline.Label?,
    location: net.starlark.java.syntax.Location?,
    e: AspectCycleOnPathException
) : java.lang.Exception(java.lang.String.format("%s (when propagating to %s)", e.getMessage(), targetLabel)) {
    private val location: net.starlark.java.syntax.Location?

    init {
        this.location = location
    }

    fun getLocation(): net.starlark.java.syntax.Location? {
        return location
    }
}

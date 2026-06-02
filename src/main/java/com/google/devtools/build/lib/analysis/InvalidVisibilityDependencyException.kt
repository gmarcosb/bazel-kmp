// Copyright 2023 The Bazel Authors. All rights reserved.
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

/** Indicates a visibility dependency on a [Target] that is not a [PackageGroup].  */
class InvalidVisibilityDependencyException(label: com.google.devtools.build.lib.cmdline.Label?) :
    java.lang.Exception() {
    private val label: com.google.devtools.build.lib.cmdline.Label?

    init {
        this.label = label
    }

    /** Label of [Target] that was expected to be a [PackageGroup].  */
    fun label(): com.google.devtools.build.lib.cmdline.Label? {
        return label
    }
}

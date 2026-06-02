// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.starlarkbuildapi

import com.google.devtools.build.lib.starlarkbuildapi.FileApi

/** Represents a directory that has been expanded at execution time.  */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "ExpandedDirectory",
    category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN,
    doc = "Represents an expanded directory that makes the files within the it directly accessible."
)
interface ExpandedDirectoryApi : net.starlark.java.eval.StarlarkValue {
    @get:net.starlark.java.annot.StarlarkMethod(
        name = "directory",
        structField = true,
        doc = "The input directory that was expanded."
    )
    val directory: FileApi?

    @net.starlark.java.annot.StarlarkMethod(
        name = "children",
        structField = true,
        doc = "Contains the files within the directory."
    )
    fun children(): net.starlark.java.eval.StarlarkList<FileApi?>?
}

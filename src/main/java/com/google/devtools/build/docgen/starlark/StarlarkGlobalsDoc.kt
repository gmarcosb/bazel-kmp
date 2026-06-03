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
package com.google.devtools.build.docgen.starlark

import com.google.devtools.build.docgen.annot.GlobalMethods.Environment.getDescription
import com.google.devtools.build.docgen.annot.GlobalMethods.Environment.getPath
import com.google.devtools.build.docgen.annot.GlobalMethods.Environment.getTitle
import com.google.devtools.build.docgen.starlark.StarlarkDocExpander
import com.google.devtools.build.docgen.starlark.StarlarkDocPage

/** A documentation page for a list of Starlark global methods in the same environment.  */
class StarlarkGlobalsDoc(
    environment: com.google.devtools.build.docgen.annot.GlobalMethods.Environment,
    expander: StarlarkDocExpander?
) : StarlarkDocPage(expander) {
    private val environment: com.google.devtools.build.docgen.annot.GlobalMethods.Environment

    init {
        this.environment = environment
    }

    override fun getName(): String {
        return environment.getPath()
    }

    override fun getRawDocumentation(): String? {
        return environment.getDescription()
    }

    override fun getTitle(): String? {
        return environment.getTitle()
    }

    override fun getSourceFile(): String {
        return "NONE"
    }
}

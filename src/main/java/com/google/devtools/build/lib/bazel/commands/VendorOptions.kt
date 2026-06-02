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
package com.google.devtools.build.lib.bazel.commands

import com.google.devtools.common.options.*

/** Defines the options specific to Bazel's vendor command.  */
@OptionsClass
abstract class VendorOptions : OptionsBase() {
    @Option(
        name = "repo",
        defaultValue = "null",
        allowMultiple = true,
        documentationCategory = OptionDocumentationCategory.BZLMOD,
        effectTags = [OptionEffectTag.CHANGES_INPUTS],
        help = """
          Only vendors the specified repository, which can be either `@apparent_repo_name` or
          `@@canonical_repo_name`. This option can be set multiple times.
          
          """.trimIndent()
    )
    abstract fun getRepos(): MutableList<String?>?
}

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
package com.google.devtools.build.lib.bazel.commands

import com.google.devtools.common.options.*

/** Defines the options specific to Bazel's fetch command  */
@OptionsClass
abstract class FetchOptions : OptionsBase() {
    @Option(
        name = "all",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
        effectTags = [OptionEffectTag.CHANGES_INPUTS],
        help = """
          Fetches all external repositories necessary for building any target or repository.
          This is the default if no other flags and arguments are provided. Only works
          when `--enable_bzlmod` is on.
          
          """.trimIndent()
    )
    abstract fun getAll(): Boolean

    @Option(
        name = "configure",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.BZLMOD,
        effectTags = [OptionEffectTag.CHANGES_INPUTS],
        help = """
          Only fetches repositories marked as `configure` for system-configuration purpose. Only
          works when `--enable_bzlmod` is on.
          
          """.trimIndent()
    )
    abstract fun getConfigure(): Boolean

    @Option(
        name = "repo",
        defaultValue = "null",
        allowMultiple = true,
        documentationCategory = OptionDocumentationCategory.BZLMOD,
        effectTags = [OptionEffectTag.CHANGES_INPUTS],
        help = """
          Only fetches the specified repository, which can be either `@apparent_repo_name` or
          `@@canonical_repo_name`. Only works when `--enable_bzlmod` is on.
          
          """.trimIndent()
    )
    abstract fun getRepos(): MutableList<String?>?

    @Option(
        name = "force",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.BZLMOD,
        effectTags = [OptionEffectTag.CHANGES_INPUTS],
        help = """
          Ignore existing repository if any and force fetch the repository again. Only works when
          `--enable_bzlmod` is on.
          
          """.trimIndent()
    )
    abstract fun getForce(): Boolean
}

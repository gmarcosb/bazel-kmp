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
package com.google.devtools.build.docgen

import com.google.devtools.common.options.OptionDocumentationCategory
import com.google.devtools.common.options.OptionEffectTag
import com.google.devtools.common.options.OptionsClass

/** Command line options for the Build Encyclopedia docgen.  */
@OptionsClass
abstract class BuildEncyclopediaOptions : com.google.devtools.build.docgen.CommonOptions() {
    @get:com.google.devtools.common.options.Option(
        name = "input_dir",
        abbrev = 'i',
        defaultValue = "null",
        allowMultiple = true,
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "An input directory to read Java source files"
    )
    abstract val inputJavaDirs: MutableList<String?>?

    @get:com.google.devtools.common.options.Option(
        name = "input_root",
        abbrev = 'r',
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "Directory of the source tree root"
    )
    abstract val inputRoot: String?

    @get:com.google.devtools.common.options.Option(
        name = "be_stardoc_proto",
        oldName = "input_stardoc_proto",
        defaultValue = "null",
        allowMultiple = true,
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("A stardoc_output.ModuleInfo binary proto file generated from a Build Encyclopedia entry"
                + " point .bzl file; documentation from rule_stardoc_proto takes precedence over"
                + " documentation from input_dir")
    )
    abstract val buildEncyclopediaStardocProtos: MutableList<String?>?

    @get:com.google.devtools.common.options.Option(
        name = "provider",
        abbrev = 'p',
        defaultValue = "",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "The name of the rule class provider"
    )
    abstract val provider: String?

    @get:com.google.devtools.common.options.Option(
        name = "output_file",
        abbrev = 'f',
        defaultValue = "",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "An output file."
    )
    abstract val outputFile: String?

    @get:com.google.devtools.common.options.Option(
        name = "output_dir",
        abbrev = 'o',
        defaultValue = ".",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "An output directory."
    )
    abstract val outputDir: String?

    @get:com.google.devtools.common.options.Option(
        name = "denylist",
        oldName = "blacklist",
        abbrev = 'b',
        defaultValue = "",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "A path to a file listing rules not to document."
    )
    abstract val denylist: String?

    @get:com.google.devtools.common.options.Option(
        name = "single_page",
        abbrev = '1',
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("Whether to generate the BE as a single HTML page or one page per rule family. Mutually"
                + " exclusive with --create_toc.")
    )
    abstract val singlePage: Boolean
}

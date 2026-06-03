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
package com.google.devtools.build.docgen

import com.google.devtools.build.lib.cmdline.Label

/**
 * Given a Build Encyclopedia generator tool's input filename or label, the mapper produces the URL
 * for the corresponding source file in the source code repository.
 */
internal class SourceUrlMapper(
    private val sourceUrlRoot: String,
    inputRoot: String,
    repoPathsRewrites: com.google.common.collect.ImmutableMap<String?, String?>
) {
    private val inputRoot: Path
    private val repoPathsRewrites: com.google.common.collect.ImmutableMap<String?, String?>

    /**
     * @param sourceUrlRoot root URL for the source code repository
     * @param inputRoot input directory corresponding to the source tree root
     * @param repoPathsRewrites an ordered map of repo path prefixes; A repo path is a string formed
     * by concatenation of @repo// and a path. This handles labels from @builtins as well as from
     * external repositories. A map entry of the form "foo" -> "bar" indicates that if a repo path
     * starts with "foo", that prefix should be replaced with "bar" to form a full url.
     */
    init {
        this.inputRoot = java.io.File(inputRoot).toPath()
        this.repoPathsRewrites = repoPathsRewrites
    }

    constructor(linkMap: DocLinkMap, inputRoot: String) : this(
        linkMap.sourceUrlRoot,
        inputRoot,
        linkMap.repoPathRewrites
    )

    /**
     * Returns the source code repository URL of a Java source file which was passed as an input to
     * the Build Encyclopedia generator tool.
     * 
     * @throws InvalidArgumentException if the URL could not be produced.
     */
    fun urlOfFile(file: java.io.File): String {
        val path: Path = file.toPath()
        com.google.common.base.Preconditions.checkArgument(
            path.startsWith(inputRoot), "File '%s' is expected to be under '%s'", path, inputRoot
        )
        return sourceUrlRoot + inputRoot.toUri().relativize(path.toUri())
    }

    /**
     * Returns the source code repository URL of a .bzl file label which was passed as an input to the
     * Build Encyclopedia generator tool.
     * 
     * 
     * A label is first rewritten via [repoPathsRewrites]: an entry of the form "foo" ->
     * "bar" means that if `labelString` starts with "foo", the "foo" prefix is replaced with
     * "bar". Rewrite rules in [repoPathsRewrites] are examined in order, and only the first
     * matching rewrite is applied.
     * 
     * 
     * If the result is a label in the main repo, the (possibly rewritten) label is transformed
     * into a URL.
     * 
     * @throws InvalidArgumentException if the URL could not be produced.
     */
    fun urlOfLabel(labelString: String?): String {
        val label: Label
        try {
            label = Label.parseCanonical(labelString)
        } catch (e: LabelSyntaxException) {
            val message: String? = String.format("Failed to parse label '%s'", labelString)
            throw java.lang.IllegalArgumentException(message, e)
        }
        return urlOfLabel(label)
    }

    private fun urlOfLabel(label: Label): String {
        val path =
            ("@"
                    + label.getPackageIdentifier().getRepository().getName()
                    + "//"
                    + label.toPathFragment().getPathString())
        for (entry in repoPathsRewrites.entries) {
            if (path.startsWith(entry.key)) {
                return entry.value + path.substring(entry.key.length)
            }
        }
        throw java.lang.IllegalStateException("Label URL left untransformed: " + path)
    }
}

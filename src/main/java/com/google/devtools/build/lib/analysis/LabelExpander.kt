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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.actions.Artifact

/**
 * Helper class encapsulating string scanning state used during "heuristic" expansion of labels
 * embedded within rules.
 */
object LabelExpander {
    /**
     * CharMatcher to determine if a given character is valid for labels.
     * 
     * 
     * The Build Concept Reference additionally allows '=' and ',' to appear in labels, but for the
     * purposes of the heuristic, this function does not, as it would cause "--foo=:rule1,:rule2" to
     * scan as a single possible label, instead of three ("--foo", ":rule1", ":rule2").
     */
    private val LABEL_CHAR_MATCHER: com.google.common.base.CharMatcher =
        com.google.common.base.CharMatcher.inRange('a', 'z')
            .or(com.google.common.base.CharMatcher.inRange('A', 'Z'))
            .or(com.google.common.base.CharMatcher.inRange('0', '9'))
            .or(com.google.common.base.CharMatcher.anyOf(":/_.-+" + PathFragment.SEPARATOR_CHAR))
            .precomputed()

    /**
     * Expands all references to labels embedded within a string using the provided expansion mapping
     * from labels to artifacts.
     * 
     * 
     * Since this pass is heuristic, references to non-existent labels (such as arbitrary words) or
     * invalid labels are simply ignored and are unchanged in the output. However, if the heuristic
     * discovers a label, which identifies an existing target producing zero or multiple files, an
     * error is reported.
     * 
     * @param expression the expression to expand.
     * @param labelMap the mapping from labels to artifacts, whose relative path is to be used as the
     * expansion.
     * @param labelResolver the `Label` that can resolve label strings to `Label` objects.
     * The resolved label is either relative to `labelResolver` or is a global label (i.e.
     * starts with "//").
     * @return the expansion of the string.
     * @throws NotUniqueExpansionException if a label that is present in the mapping expands to zero
     * or multiple files.
     */
    @Throws(NotUniqueExpansionException::class)
    fun <T : Iterable<Artifact?>?> expand(
        expression: String?,
        labelMap: MutableMap<com.google.devtools.build.lib.cmdline.Label?, T?>?,
        labelResolver: com.google.devtools.build.lib.cmdline.Label?
    ): String {
        if (com.google.common.base.Strings.isNullOrEmpty(expression)) {
            return ""
        }
        com.google.common.base.Preconditions.checkNotNull<MutableMap<com.google.devtools.build.lib.cmdline.Label?, T?>?>(
            labelMap
        )
        com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.cmdline.Label?>(labelResolver)

        var offset = 0
        val result: java.lang.StringBuilder = java.lang.StringBuilder()
        while (offset < expression!!.length) {
            val labelText = LabelExpander.scanLabel(expression, offset)
            if (labelText != null) {
                offset += labelText.length
                result.append(LabelExpander.tryResolvingLabelTextToArtifactPath<T?>(labelText, labelMap, labelResolver))
            } else {
                result.append(expression.get(offset))
                offset++
            }
        }
        return result.toString()
    }

    /**
     * Tries resolving a label text to a full label for the associated `Artifact`, using the
     * provided mapping.
     * 
     * 
     * The method succeeds if the label text can be resolved to a `Label` object, which is
     * present in the `labelMap` and maps to exactly one `Artifact`.
     * 
     * @param labelText the text to resolve.
     * @param labelMap the mapping from labels to artifacts, whose relative path is to be used as the
     * expansion.
     * @param labelResolver the `Label` that can resolve label strings to `Label` objects.
     * The resolved label is either relative to `labelResolver` or is a global label (i.e.
     * starts with "//").
     * @return an absolute label to an `Artifact` if the resolving was successful or the
     * original label text.
     * @throws NotUniqueExpansionException if a label that is present in the mapping expands to zero
     * or multiple files.
     */
    @Throws(NotUniqueExpansionException::class)
    private fun <T : Iterable<Artifact?>?> tryResolvingLabelTextToArtifactPath(
        labelText: String?,
        labelMap: MutableMap<com.google.devtools.build.lib.cmdline.Label?, T?>,
        labelResolver: com.google.devtools.build.lib.cmdline.Label
    ): String? {
        val resolvedLabel: com.google.devtools.build.lib.cmdline.Label? = resolveLabelText(labelText, labelResolver)
        if (resolvedLabel == null) {
            return labelText
        }
        val artifacts: Iterable<Artifact>? = labelMap.get(resolvedLabel)
        if (artifacts == null) {
            return labelText
        }
        // resolvedLabel identifies an existing target
        val locations: MutableList<String?> = java.util.ArrayList<String?>()
        for (artifact in artifacts) {
            if (!artifact.isRunfilesTree()) {
                locations.add(artifact.getExecPathString())
            }
        }
        val resultSetSize = locations.size
        if (resultSetSize == 1) {
            return com.google.common.collect.Iterables.getOnlyElement<String?>(locations) // success!
        } else {
            throw NotUniqueExpansionException(resultSetSize, labelText)
        }
    }

    /**
     * Resolves a string to a label text. Uses `labelResolver` to do so. The result is either
     * relative to `labelResolver` or is an absolute label. In case of an invalid label text,
     * the return value is null.
     */
    private fun resolveLabelText(
        labelText: String?,
        labelResolver: com.google.devtools.build.lib.cmdline.Label
    ): com.google.devtools.build.lib.cmdline.Label? {
        try {
            return com.google.devtools.build.lib.cmdline.Label.parseWithPackageContext(
                labelText,
                PackageContext.of(
                    labelResolver.getPackageIdentifier(),
                    com.google.devtools.build.lib.cmdline.RepositoryMapping.EMPTY
                )
            )
        } catch (e: LabelSyntaxException) {
            // It's a heuristic, so quietly ignore "errors".
            return null
        }
    }

    /**
     * Scans the argument string from a given start position until the name of a potential label has
     * been consumed, then returns the label text. If the expression contains no possible label
     * starting at the start position, the return value is null.
     */
    private fun scanLabel(expression: String, start: Int): String? {
        var offset = start
        while (offset < expression.length && LABEL_CHAR_MATCHER.matches(expression.get(offset))) {
            ++offset
        }
        if (offset > start) {
            return expression.substring(start, offset)
        } else {
            return null
        }
    }

    /**
     * An exception that is thrown when a label is expanded to zero or multiple files during
     * expansion.
     */
    class NotUniqueExpansionException(sizeOfResultSet: Int, labelText: String?) : java.lang.Exception(
        ("heuristic label expansion found '"
                + labelText
                + "', which expands to "
                + sizeOfResultSet
                + " files"
                + (if (sizeOfResultSet > 1) ", please use $(locations " + labelText + ") instead" else ""))
    )
}

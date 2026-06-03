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

import com.google.devtools.build.docgen.DocgenConsts
import com.google.devtools.build.docgen.RuleDocumentationAttribute
import java.io.IOException

/**
 * A class to contain the base definition of common BUILD rule attributes.
 */
object PredefinedAttributes {
    /**
     * List of documentation for common attributes of *_test rules, relative to [ ].
     */
    val TEST_ATTRIBUTES_DOCFILES: com.google.common.collect.ImmutableList<String> =
        com.google.common.collect.ImmutableList.of<String?>(
            "templates/attributes/test/args.html",
            "templates/attributes/test/env.html",
            "templates/attributes/test/env_inherit.html",
            "templates/attributes/test/size.html",
            "templates/attributes/test/timeout.html",
            "templates/attributes/test/flaky.html",
            "templates/attributes/test/shard_count.html",
            "templates/attributes/test/local.html"
        )

    /**
     * List of typical (defined by most rules, but not implicitly added to all rules) attributes
     * documentation, relative to [com.google.devtools.build.docgen].
     */
    val TYPICAL_ATTRIBUTES_DOCFILES: com.google.common.collect.ImmutableList<String> =
        com.google.common.collect.ImmutableList.of<String?>(
            "templates/attributes/typical/data.html",
            "templates/attributes/typical/deps.html",
            "templates/attributes/typical/licenses.html",
            "templates/attributes/typical/srcs.html"
        )

    /**
     * List of common (implicitly added to all rules) attributes documentation, relative to [ ].
     */
    val COMMON_ATTRIBUTES_DOCFILES: com.google.common.collect.ImmutableList<String> =
        com.google.common.collect.ImmutableList.of<String?>(
            "templates/attributes/common/aspect_hints.html",
            "templates/attributes/common/compatible_with.html",
            "templates/attributes/common/deprecation.html",
            "templates/attributes/common/exec_compatible_with.html",
            "templates/attributes/common/exec_group_compatible_with.html",
            "templates/attributes/common/exec_properties.html",
            "templates/attributes/common/features.html",
            "templates/attributes/common/package_metadata.html",
            "templates/attributes/common/restricted_to.html",
            "templates/attributes/common/tags.html",
            "templates/attributes/common/target_compatible_with.html",
            "templates/attributes/common/testonly.html",
            "templates/attributes/common/toolchains.html",
            "templates/attributes/common/visibility.html"
        )

    /**
     * List of documentation for common attributes of *_binary rules, relative to [ ].
     */
    val BINARY_ATTRIBUTES_DOCFILES: com.google.common.collect.ImmutableList<String> =
        com.google.common.collect.ImmutableList.of<String?>(
            "templates/attributes/binary/args.html",
            "templates/attributes/binary/env.html",
            "templates/attributes/binary/output_licenses.html"
        )

    private fun generateAttributeMap(
        commonType: String?, filenames: com.google.common.collect.ImmutableList<String>
    ): com.google.common.collect.ImmutableMap<String?, RuleDocumentationAttribute?> {
        val builder: com.google.common.collect.ImmutableMap.Builder<String?, RuleDocumentationAttribute?> =
            com.google.common.collect.ImmutableMap.builder<String?, RuleDocumentationAttribute?>()
        for (filename in filenames) {
            val name: String = com.google.common.io.Files.getNameWithoutExtension(filename)
            try {
                val stream: java.io.InputStream = PredefinedAttributes::class.java.getResourceAsStream(filename)
                checkNotNull(stream) { "Resource " + filename + " not found" }
                val content = String(
                    com.google.common.io.ByteStreams.toByteArray(stream),
                    java.nio.charset.StandardCharsets.UTF_8
                )
                builder.put(name, RuleDocumentationAttribute.Companion.createCommon(name, commonType, content))
            } catch (e: IOException) {
                throw java.lang.IllegalStateException("Exception while reading " + filename, e)
            }
        }
        return builder.buildOrThrow()
    }

    val TYPICAL_ATTRIBUTES: com.google.common.collect.ImmutableMap<String?, RuleDocumentationAttribute?> =
        generateAttributeMap(DocgenConsts.TYPICAL_ATTRIBUTES, TYPICAL_ATTRIBUTES_DOCFILES)

    val COMMON_ATTRIBUTES: com.google.common.collect.ImmutableMap<String?, RuleDocumentationAttribute?> =
        generateAttributeMap(DocgenConsts.COMMON_ATTRIBUTES, COMMON_ATTRIBUTES_DOCFILES)

    val BINARY_ATTRIBUTES: com.google.common.collect.ImmutableMap<String?, RuleDocumentationAttribute?> =
        generateAttributeMap(DocgenConsts.BINARY_ATTRIBUTES, BINARY_ATTRIBUTES_DOCFILES)

    val TEST_ATTRIBUTES: com.google.common.collect.ImmutableMap<String?, RuleDocumentationAttribute?> =
        generateAttributeMap(DocgenConsts.TEST_ATTRIBUTES, TEST_ATTRIBUTES_DOCFILES)
}

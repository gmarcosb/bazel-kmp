// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.common.options

import OptionFilters.OptionEffectTag
import OptionFilters.OptionMetadataTag
import com.google.common.truth.Truth
import com.google.devtools.common.options.OptionDocumentationCategory
import com.google.devtools.common.options.OptionEffectTag
import com.google.devtools.common.options.OptionFilterDescriptions
import com.google.devtools.common.options.OptionMetadataTag
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.add
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.addAll
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.Collections

/** Tests that we have descriptions for every option tag.  */
@RunWith(JUnit4::class)
class OptionFilterDescriptionsTest {
    @org.junit.Test
    fun documentationOrderIncludesAllDocumentedCategories() {
        // Expect the documentation order to include everything but the undocumented category.
        val docOrderPlusUndocumented: java.util.ArrayList<OptionDocumentationCategory?> =
            java.util.ArrayList<OptionDocumentationCategory?>()
        Collections.addAll<OptionDocumentationCategory?>(
            docOrderPlusUndocumented,
            *OptionFilterDescriptions.documentationOrder
        )
        docOrderPlusUndocumented.add(OptionDocumentationCategory.UNDOCUMENTED)

        Truth.assertThat<OptionDocumentationCategory?>(OptionDocumentationCategory.entries.toTypedArray())
            .asList()
            .containsExactlyElementsIn(docOrderPlusUndocumented)
    }

    @org.junit.Test
    fun optionDocumentationCategoryDescriptionsContainsAllCategories() {
        // Check that we have a description for all valid option categories.
        val optionCategoryDescriptions: com.google.common.collect.ImmutableMap<OptionDocumentationCategory?, String?> =
            OptionFilterDescriptions.getOptionCategoriesEnumDescription()

        Truth.assertThat<OptionDocumentationCategory?>(OptionDocumentationCategory.entries.toTypedArray())
            .asList()
            .containsExactlyElementsIn(optionCategoryDescriptions.keys)
    }

    @org.junit.Test
    fun optionEffectTagDescriptionsContainsAllTags() {
        // Check that we have a description for all valid option tags.
        val optionEffectTagDescription: com.google.common.collect.ImmutableMap<OptionEffectTag?, String?> =
            OptionFilterDescriptions.getOptionEffectTagDescription("blaze")

        Truth.assertThat<OptionEffectTag?>(OptionEffectTag.entries.toTypedArray())
            .asList()
            .containsExactlyElementsIn(optionEffectTagDescription.keys)
    }

    @org.junit.Test
    fun optionMetadataTagDescriptionsContainsAllTags() {
        // Check that we have a description for all valid option tags.
        val optionMetadataTagDescription: com.google.common.collect.ImmutableMap<OptionMetadataTag?, String?> =
            OptionFilterDescriptions.getOptionMetadataTagDescription("blaze")

        Truth.assertThat<OptionMetadataTag?>(OptionMetadataTag.entries.toTypedArray())
            .asList()
            .containsExactlyElementsIn(optionMetadataTagDescription.keys)
    }
}

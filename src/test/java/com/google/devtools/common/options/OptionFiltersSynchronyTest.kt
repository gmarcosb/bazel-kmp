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

import com.google.devtools.common.options.proto.OptionFilters

/**
 * This test makes sure that the two java filtering enums, OptionMetadataTag and OptionEffectTag,
 * are kept in sync with the matching proto.
 */
@RunWith(JUnit4::class)
class OptionFiltersSynchronyTest {
    @org.junit.Test
    fun optionEffectTags() {
        // Check that the number of tags are equal. The proto version automatically defines an
        // UNRECOGNIZED value at -1, the sizes should actually be offset by one.
        assertThat(OptionFilters.OptionEffectTag.values())
            .hasLength(OptionEffectTag.entries.size + 1)

        // Now go through each and check that the names are equal.
        for (javaTag in OptionEffectTag.entries) {
            val protoTag: OptionEffectTag =
                OptionFilters.OptionEffectTag.forNumber(javaTag.value)

            // First check that the tag exists with this value, then that the names are equal.
            Truth.assertWithMessage(
                "OptionEffectTag %s does not have a proto equivalent with the same value", javaTag
            )
                .that(protoTag)
                .isNotNull()
            Truth.assertWithMessage(
                "OptionEffectTag %s does not have the same name as the proto equivalent %s",
                javaTag, protoTag
            )
                .that(javaTag.name)
                .isEqualTo(protoTag.name())
        }
    }

    @org.junit.Test
    fun optionMetadataTags() {
        // Check that the number of tags are equal. The proto version automatically defines an
        // UNRECOGNIZED value at -1, the sizes should actually be offset by one.
        assertThat(OptionFilters.OptionMetadataTag.values())
            .hasLength(OptionMetadataTag.entries.size + 1)

        // Now go through each and check that the names are equal.
        for (javaTag in OptionMetadataTag.entries) {
            val protoTag: OptionMetadataTag =
                OptionFilters.OptionMetadataTag.forNumber(javaTag.value)

            // First check that the tag exists with this value, then that the names are equal.
            Truth.assertWithMessage(
                "OptionMetadataTag %s does not have a proto equivalent with the same value", javaTag
            )
                .that(protoTag)
                .isNotNull()
            Truth.assertWithMessage(
                "OptionMetadataTag %s does not have the same name as the proto equivalent %s",
                javaTag, protoTag
            )
                .that(javaTag.name)
                .isEqualTo(protoTag.name())
        }
    }
}

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
// limitations under the License
package com.google.devtools.build.lib.rules.config

import com.google.devtools.build.lib.analysis.config.FragmentOptions

/** The options fragment which defines options related to tagged trimming of feature flags.  */
@com.google.devtools.common.options.OptionsClass
abstract class ConfigFeatureFlagOptions : FragmentOptions() {
    @get:com.google.devtools.common.options.Option(
        name = "enforce_transitive_configs_for_config_feature_flag",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOSES_INCREMENTAL_STATE, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.BUILD_FILE_SEMANTICS, com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION, com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS
        ],
        defaultValue = "false"
    )
    abstract val enforceTransitiveConfigsForConfigFeatureFlag: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "all feature flag values are present (internal)",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOSES_INCREMENTAL_STATE, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.BUILD_FILE_SEMANTICS, com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION, com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS
        ],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INTERNAL],
        defaultValue = "true"
    )
    abstract var allFeatureFlagValuesArePresent: Boolean
}

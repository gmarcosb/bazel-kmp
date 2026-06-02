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
package com.google.devtools.build.lib.skyframe.toolchains

import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider

/** Helper class for [ConfigMatchingProvider] data.  */
object ConfigMatchingUtil {
    /**
     * Validates that the given [ConfigMatchingProvider] instances are all successful.
     * 
     * @param label the source of the instances, for error reporting
     * @param configSettings the instances to check
     * @param errorHandler if non-null, an error message will be generated for each non-successful
     * instance
     * @param settingsAttribute the name of the attribute defining the config settings, to be used for
     * error reporting
     * @return whether all instances are successful
     * @throws InvalidConfigurationException thrown if any instances are in an error state
     */
    @Throws(InvalidConfigurationException::class)
    fun validate(
        label: Label?,
        configSettings: com.google.common.collect.ImmutableList<ConfigMatchingProvider?>?,
        errorHandler: java.util.function.Consumer<String?>?,
        settingsAttribute: String?
    ): Boolean {
        // Make sure the target setting matches but watch out for resolution errors.
        val accumulateResults: AccumulateResults =
            ConfigMatchingProvider.accumulateMatchResults(configSettings)
        if (!accumulateResults.errors().isEmpty()) {
            // TODO(blaze-configurability-team): This should only be due to feature flag trimming. So,
            // would be better to just ensure toolchain resolution isn't transitively dependent on
            // feature flags at all.
            val message: String? =
                accumulateResults.errors().asMap().entrySet().stream()
                    .map(
                        { entry ->
                            java.lang.String.format(
                                "For config_setting %s: %s",
                                entry.getKey().getName(), java.lang.String.join(", ", entry.getValue())
                            )
                        })
                    .collect(Collectors.joining("; "))
            throw InvalidConfigurationException(
                ("Unrecoverable errors resolving config_setting associated with "
                        + label
                        + ": "
                        + message)
            )
        }
        if (accumulateResults.success()) {
            return true
        } else if (!accumulateResults.nonMatching().isEmpty() && errorHandler != null) {
            val nonMatchingList: String? =
                accumulateResults.nonMatching().stream()
                    .distinct()
                    .map(Label::getName)
                    .collect(Collectors.joining(", "))
            val message: String? = java.lang.String.format("mismatching %s: %s", settingsAttribute, nonMatchingList)
            errorHandler.accept(message)
        }
        return false
    }
}

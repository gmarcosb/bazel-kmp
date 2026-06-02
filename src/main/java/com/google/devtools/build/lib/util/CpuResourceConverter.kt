// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.util.ResourceConverter

/**
 * Converter for --local_resources=cpu=, which takes an integer greater than or equal to 1, or
 * "HOST_CPUS", optionally followed by [-|*]<float>.
</float> */
class CpuResourceConverter : com.google.devtools.build.lib.util.ResourceConverter.IntegerConverter( /* keywords= */
    com.google.common.collect.ImmutableMap.of<String?, java.util.function.Supplier<Int?>?>(
        ResourceConverter.HOST_CPUS_KEYWORD,
        ResourceConverter.HOST_CPUS_SUPPLIER
    ),  /* minValue= */
    0,  /* maxValue= */
    java.lang.Integer.MAX_VALUE
) {
    val typeDescription: String?
        get() = java.lang.String.format(
            "an integer, or \"%s\", optionally followed by [-|*]<float>.", ResourceConverter.HOST_CPUS_KEYWORD
        )
}

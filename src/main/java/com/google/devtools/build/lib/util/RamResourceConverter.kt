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
 * Converter for --local_resources=memory=, which takes an integer greater than or equal to 0, or
 * "HOST_RAM", optionally followed by [-|*]<float>.
</float> */
class RamResourceConverter : com.google.devtools.build.lib.util.ResourceConverter.IntegerConverter( /* keywords= */
    com.google.common.collect.ImmutableMap.of<String?, java.util.function.Supplier<Int?>?>(
        ResourceConverter.Companion.HOST_RAM_KEYWORD,
        ResourceConverter.Companion.HOST_RAM_SUPPLIER
    ),  /* minValue= */
    0,  /* maxValue= */
    Int.Companion.MAX_VALUE
) {
    val typeDescription: String?
        get() = String.format(
            "an integer number of MBs, or \"%s\", optionally followed by [-|*]<float>.",
            ResourceConverter.Companion.HOST_RAM_KEYWORD
        )
}

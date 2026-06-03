// Copyright 2026 The Bazel Authors. All rights reserved.
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
package com.google.devtools.common.options.processor.optiontestsources

import OptionFilters.OptionEffectTag
import com.google.devtools.common.options.OptionDocumentationCategory
import com.google.devtools.common.options.OptionEffectTag
import com.google.devtools.common.options.OptionsBase
import com.google.devtools.common.options.OptionsClass

@OptionsClass
abstract class NonAbstractGetter : OptionsBase() {
    @get:com.google.devtools.common.options.Option(
        name = "foo",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.AFFECTS_OUTPUTS],
        defaultValue = "42"
    )
    var foo: Int
        get() = 42
}

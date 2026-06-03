// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.docgen.starlark

import com.google.devtools.build.docgen.RuleLinkExpander
import com.google.devtools.build.docgen.starlark.StarlarkDocUtils
import com.google.devtools.build.docgen.starlark.TypeParser

/** A utility class for replacing variables in documentation strings with their actual values.  */
class StarlarkDocExpander(ruleExpander: RuleLinkExpander) {
    val ruleExpander: RuleLinkExpander

    // Set by setTypeParser().
    private var typeParser: TypeParser? = null

    init {
        this.ruleExpander = ruleExpander
    }

    fun setTypeParser(typeParser: TypeParser?) {
        this.typeParser = typeParser
    }

    fun getTypeParser(): TypeParser {
        return com.google.common.base.Preconditions.checkNotNull<TypeParser>(
            typeParser,
            "StarlarkDocExpander.setTypeParser() has not been called"
        )
    }

    fun expand(docString: String): String? {
        return ruleExpander.expand(
            StarlarkDocUtils.substituteVariables(docString, ruleExpander.beRoot())
        )
    }
}

// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.python

import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild

/** Instance of the provider type for the Python rules.  */
@com.google.common.annotations.VisibleForTesting
class PyInfo private constructor(info: StarlarkInfo) {
    private val info: StarlarkInfo

    init {
        this.info = info
    }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val transitiveSourcesSet: NestedSet<Artifact?>
        get() {
            val value: Any? = info.getValue("transitive_sources")
            return Depset.cast(value, Artifact::class.java, "transitive_sources")
        }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val usesSharedLibraries: Boolean
        get() = info.getValue("uses_shared_libraries", Boolean::class.java)

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val importsSet: NestedSet<String?>
        get() {
            val value: Any? = info.getValue("imports")
            return Depset.cast(value, String::class.java, "imports")
        }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val hasPy2OnlySources: Boolean
        get() = info.getValue("has_py2_only_sources", Boolean::class.java)

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val hasPy3OnlySources: Boolean
        get() = info.getValue("has_py3_only_sources", Boolean::class.java)

    /** The PyInfo provider type object for the rules_python provider.  */
    class RulesPythonPyInfoProvider private constructor() : StarlarkProviderWrapper<PyInfo?>(
        keyForBuild(Label.parseCanonicalUnchecked(TestConstants.PYINFO_BZL)),
        "PyInfo"
    ) {
        public override fun wrap(value: Info?): PyInfo {
            return PyInfo(value as StarlarkInfo?)
        }
    }

    companion object {
        private val RULES_PYTHON_PROVIDER = RulesPythonPyInfoProvider()

        @Throws(RuleErrorException::class)
        fun fromTarget(target: ConfiguredTarget): PyInfo {
            val provider: PyInfo? = target.get(RULES_PYTHON_PROVIDER)
            if (provider != null) {
                return provider
            }
            throw java.lang.IllegalStateException(String.format("Unable to find PyInfo provider in %s", target))
        }
    }
}

// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.rules.python

import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild

/**
 * Instance of the provider type that describes Python runtimes.
 * 
 * 
 * Invariant: Exactly one of [.interpreterPath] and [.interpreter] is non-null. The
 * former corresponds to a platform runtime, and the latter to an in-build runtime; these two cases
 * are mutually exclusive. In addition, [.files] is non-null if and only if [ ][.interpreter] is non-null; in other words files are only used for in-build runtimes. These
 * invariants mirror the user-visible API on [PyRuntimeInfoApi] except that `None` is
 * replaced by null.
 */
@com.google.common.annotations.VisibleForTesting
class PyRuntimeInfo private constructor(info: StarlarkInfo) {
    private val info: StarlarkInfo

    init {
        this.info = info
    }

    val interpreterPathString: String?
        get() {
            val value: Any? = info.getValue("interpreter_path")
            return if (value === Starlark.NONE) null else value as String?
        }

    val interpreter: Artifact?
        get() {
            val value: Any? = info.getValue("interpreter")
            return if (value === Starlark.NONE) null else value as Artifact?
        }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val files: NestedSet<Artifact?>?
        get() {
            val value: Any? = info.getValue("files")
            if (value === Starlark.NONE) {
                return null
            } else {
                return Depset.cast(value, Artifact::class.java, "files")
            }
        }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val pythonVersion: PythonVersion
        get() = PythonVersion.parseTargetValue(info.getValue("python_version", String::class.java))

    private open class BaseProvider(bzlKey: BzlLoadValue.Key?) :
        StarlarkProviderWrapper<PyRuntimeInfo?>(bzlKey, "PyRuntimeInfo") {
        public override fun wrap(value: Info?): PyRuntimeInfo {
            return PyRuntimeInfo(value as StarlarkInfo?)
        }
    }

    /** Provider instance for the rules_python PyRuntimeInfo provider.  */
    private class RulesPythonProvider :
        BaseProvider(keyForBuild(Label.parseCanonicalUnchecked(TestConstants.PYRUNTIMEINFO_BZL)))

    companion object {
        private val RULES_PYTHON_PROVIDER = RulesPythonProvider()

        @Throws(RuleErrorException::class)
        fun fromTarget(target: ConfiguredTarget): PyRuntimeInfo {
            val provider: PyRuntimeInfo = fromTargetNullable(target)!!
            checkNotNull(provider != null) { String.format("Unable to find PyRuntimeInfo provider in %s", target) }
            return provider
        }

        @Throws(RuleErrorException::class)
        fun fromTargetNullable(target: ConfiguredTarget): PyRuntimeInfo? {
            val provider: PyRuntimeInfo? = target.get(RULES_PYTHON_PROVIDER)
            if (provider != null) {
                return provider
            }
            return null
        }
    }
}

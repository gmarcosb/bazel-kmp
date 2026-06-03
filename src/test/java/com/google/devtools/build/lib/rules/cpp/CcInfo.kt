// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.analysis.ConfiguredTarget

/** Provider for C++ compilation and linking information.  */
class CcInfo private constructor(starlarkInfo: StarlarkInfo) {
    /** A wrapper around the Starlark provider.  */
    class CcInfoProvider : StarlarkProviderWrapper<CcInfo?>(
        BzlLoadValue.keyForBuild(
            Label.parseCanonicalUnchecked(
                TestConstants.RULES_CC_CANNONICAL + "/private:cc_info.bzl"
            )
        ),
        "CcInfo"
    ) {
        public override fun wrap(value: Info?): CcInfo {
            return CcInfo(value as StarlarkInfo?)
        }
    }

    class RulesCcCcInfoProvider : StarlarkProviderWrapper<CcInfo?>(
        BzlLoadValue.keyForBuild(
            Label.parseCanonicalUnchecked("@rules_cc+//cc/private:cc_info.bzl")
        ),
        "CcInfo"
    ) {
        public override fun wrap(value: Info?): CcInfo {
            return CcInfo(value as StarlarkInfo?)
        }
    }

    private val starlarkInfo: StarlarkInfo

    init {
        this.starlarkInfo = starlarkInfo
    }

    val ccCompilationContext: CcCompilationContext
        get() {
            try {
                return CcCompilationContext.of(
                    starlarkInfo.getValue("compilation_context", StarlarkInfo::class.java)
                )
            } catch (e: net.starlark.java.eval.EvalException) {
                throw java.lang.IllegalStateException(e)
            }
        }

    val ccLinkingContext: CcLinkingContext
        get() {
            try {
                return CcLinkingContext.Companion.of(
                    starlarkInfo.getValue(
                        "linking_context",
                        StarlarkInfo::class.java
                    )
                )
            } catch (e: net.starlark.java.eval.EvalException) {
                throw java.lang.IllegalStateException(e)
            }
        }

    val ccDebugInfoContext: StarlarkInfo
        get() {
            try {
                return starlarkInfo.getValue("_debug_context", StarlarkInfo::class.java)
            } catch (e: net.starlark.java.eval.EvalException) {
                throw java.lang.IllegalStateException(e)
            }
        }

    val transitiveCcNativeLibrariesForTests: NestedSet<LibraryToLink?>?
        get() {
            try {
                return wrap(
                    starlarkInfo
                        .getValue("_legacy_transitive_native_libraries", Depset::class.java)
                        .getSet(StarlarkInfo::class.java)
                )
            } catch (e: net.starlark.java.eval.EvalException) {
                throw java.lang.IllegalStateException(e)
            } catch (e: TypeException) {
                throw java.lang.IllegalStateException(e)
            }
        }

    companion object {
        val PROVIDER: CcInfoProvider = CcInfoProvider()
        val RULES_CC_PROVIDER: RulesCcCcInfoProvider = RulesCcCcInfoProvider()

        @Throws(RuleErrorException::class)
        fun get(target: ConfiguredTarget): CcInfo? {
            var ccInfo: CcInfo? = target.get(PROVIDER)
            if (ccInfo == null) {
                ccInfo = target.get(RULES_CC_PROVIDER)
            }
            return ccInfo
        }

        fun wrap(starlarkInfo: StarlarkInfo): CcInfo {
            return CcInfo(starlarkInfo)
        }
    }
}

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
package com.google.devtools.build.lib.rules.java

import com.github.benmanes.caffeine.cache.CacheLoader
import com.github.benmanes.caffeine.cache.LoadingCache
import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.concurrent.ThreadSafety
import com.google.devtools.build.lib.packages.BuildType.LABEL
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkInt
import java.lang.String
import kotlin.Int
import kotlin.plus

/** Information about the Java runtime used by the `java_*` rules.  */
@ThreadSafety.Immutable
class JavaRuntimeInfo private constructor(underlying: StarlarkInfo?) : StarlarkInfoWrapper(underlying) {
    /** All input artifacts in the javabase.  */
    @Throws(RuleErrorException::class)
    fun javaBaseInputs(): NestedSet<Artifact?>? {
        return getUnderlyingNestedSet<Artifact?>("files", Artifact::class.java)
    }

    /** The root directory of the Java installation.  */
    @Throws(RuleErrorException::class)
    fun javaHome(): String? {
        return getUnderlyingValue<String?>("java_home", String::class.java)
    }

    @Throws(RuleErrorException::class)
    fun javaBinaryExecPathFragment(): PathFragment? {
        return javaBinaryExecPathCache.get(
            getUnderlyingValue<String?>("java_executable_exec_path", String::class.java)
        )
    }

    @Throws(RuleErrorException::class)
    fun hermeticStaticLibs(): ImmutableList<StarlarkInfo?>? {
        return getUnderlyingSequence<StarlarkInfo?>("hermetic_static_libs", StarlarkInfo::class.java).getImmutableList()
    }

    @Throws(RuleErrorException::class)
    fun version(): Int {
        return getUnderlyingValue<StarlarkInt?>("version", StarlarkInt::class.java).toIntUnchecked()
    }

    private class RulesJavaProvider :
        Provider(BzlLoadValue.keyForBuild(Label.parseCanonicalUnchecked("//java/common/rules:java_runtime.bzl")))

    private open class Provider(
        key: BzlLoadValue.Key? = BzlLoadValue.keyForBuild(
            Label.parseCanonicalUnchecked(
                JavaSemantics.Companion.RULES_JAVA_PROVIDER_LABELS_PREFIX
                        + "java/common/rules:java_runtime.bzl"
            )
        )
    ) : StarlarkProviderWrapper<JavaRuntimeInfo?>(key, "JavaRuntimeInfo") {
        @Throws(RuleErrorException::class)
        public override fun wrap(value: Info): JavaRuntimeInfo {
            if (value is StarlarkInfoWithSchema
                && value.getProvider().getKey().equals(getKey())
            ) {
                return JavaRuntimeInfo(value as StarlarkInfo)
            } else {
                throw RuleErrorException(
                    "got value of type '" + Starlark.type(value) + "', want 'JavaRuntimeInfo'"
                )
            }
        }
    }

    companion object {
        val RULES_JAVA_PROVIDER: StarlarkProviderWrapper<JavaRuntimeInfo?> = RulesJavaProvider()
        val PROVIDER: StarlarkProviderWrapper<JavaRuntimeInfo?> = Provider()

        // Ensures that we use a canonical PathFragment instance per java binary exec path to save memory.
        private val javaBinaryExecPathCache: LoadingCache<String?, PathFragment?> =
            Caffeine.newBuilder().weakKeys().build<String?, PathFragment?>(
                CacheLoader { path: String? -> PathFragment.create(path) })

        // Helper methods to access an instance of JavaRuntimeInfo.
        @Throws(RuleErrorException::class)
        fun forHost(ruleContext: RuleContext): JavaRuntimeInfo? {
            return JavaToolchainProvider.Companion.from(ruleContext).getJavaRuntime()
        }

        fun from(ruleContext: RuleContext, javaRuntimeToolchainType: Label?): JavaRuntimeInfo? {
            val toolchainInfo: ToolchainInfo? = ruleContext.getToolchainInfo(javaRuntimeToolchainType)
            return Companion.from(ruleContext, toolchainInfo)
        }

        fun from(ruleContext: RuleContext, attributeName: String?): JavaRuntimeInfo? {
            if (!ruleContext.attributes().has(attributeName, LABEL)) {
                return null
            }
            val prerequisite: TransitiveInfoCollection? = ruleContext.getPrerequisite(attributeName)
            if (prerequisite == null) {
                return null
            }

            val toolchainInfo: ToolchainInfo? = prerequisite.get(ToolchainInfo.PROVIDER)
            return Companion.from(ruleContext, toolchainInfo)
        }

        private fun from(ruleContext: RuleContext, toolchainInfo: ToolchainInfo?): JavaRuntimeInfo? {
            if (toolchainInfo != null) {
                try {
                    val result: JavaRuntimeInfo? =
                        wrap(toolchainInfo.getValue("java_runtime", Info::class.java), "java_runtime")
                    if (result != null) {
                        return result
                    }
                } catch (e: EvalException) {
                    ruleContext.ruleError(String.format("There was an error reading the Java runtime: %s", e))
                    return null
                } catch (e: RuleErrorException) {
                    ruleContext.ruleError(String.format("There was an error reading the Java runtime: %s", e))
                    return null
                }
            }
            ruleContext.ruleError("The selected Java runtime is not a JavaRuntimeInfo")
            return null
        }

        @Throws(RuleErrorException::class)
        fun wrap(info: Info, what: kotlin.String?): JavaRuntimeInfo {
            if (info == null) {
                throw RuleErrorException("expected a JavaRuntimeInfo, but " + what + " was unset.")
            }
            val key: com.google.devtools.build.lib.packages.Provider.Key = info.getProvider().getKey()
            if (key.equals(PROVIDER.getKey())) {
                return PROVIDER.wrap(info)
            } else if (key.equals(RULES_JAVA_PROVIDER.getKey())) {
                return RULES_JAVA_PROVIDER.wrap(info)
            } else {
                throw RuleErrorException("expected JavaRuntimeInfo, got: " + key)
            }
        }
    }
}

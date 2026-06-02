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
// limitations under the License.
package com.google.devtools.build.lib.rules.java

import com.google.common.annotations.VisibleForTesting
import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.actions.Artifact
import com.google.devtools.build.lib.concurrent.ThreadSafety
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Sequence
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkValue

/** A provider for Java per-package configuration.  */
@ThreadSafety.Immutable
class JavaPackageConfigurationProvider private constructor(underlying: StructImpl) : StarlarkValue {
    private val underlying: StructImpl

    init {
        this.underlying = underlying
    }

    /** Package specifications for which the configuration should be applied.  */
    @Throws(RuleErrorException::class)
    private fun packageSpecifications(): ImmutableList<PackageSpecificationProvider>? {
        try {
            return Sequence.noneableCast<T?>(
                underlying.getValue("package_specs"),
                PackageSpecificationProvider::class.java,
                "package_specs"
            )
                .getImmutableList()
        } catch (e: EvalException) {
            throw RuleErrorException(e)
        }
    }

    @Throws(RuleErrorException::class)
    fun javacoptsAsList(): ImmutableList<String?>? {
        try {
            return tokenizeJavaOptions(
                Depset.noneableCast(underlying.getValue("javac_opts"), String::class.java, "javac_opts")
            )
        } catch (e: EvalException) {
            throw RuleErrorException(e)
        }
    }

    @Throws(RuleErrorException::class)
    fun data(): NestedSet<Artifact?> {
        try {
            return Depset.noneableCast(underlying.getValue("data"), Artifact::class.java, "data")
        } catch (e: EvalException) {
            throw RuleErrorException(e)
        }
    }

    /**
     * Returns true if this configuration matches the current label: that is, if the label's package
     * is contained by any of the [.packageSpecifications].
     */
    @Throws(RuleErrorException::class)
    fun matches(label: Label): Boolean {
        // Do not use streams here as they create excessive garbage.
        for (provider in packageSpecifications()!!) {
            for (specifications in provider.getPackageSpecifications().toList()) {
                if (specifications.containsPackage(label.getPackageIdentifier())) {
                    return true
                }
            }
        }
        return false
    }

    private class Provider(
        key: BzlLoadValue.Key? = BzlLoadValue.keyForBuild(
            Label.parseCanonicalUnchecked(
                JavaSemantics.Companion.RULES_JAVA_PROVIDER_LABELS_PREFIX
                        + "java/common/rules:java_package_configuration.bzl"
            )
        )
    ) : StarlarkProviderWrapper<JavaPackageConfigurationProvider?>(key, "JavaPackageConfigurationInfo") {
        @Throws(RuleErrorException::class)
        public override fun wrap(value: Info): JavaPackageConfigurationProvider {
            if (value is StructImpl) {
                return JavaPackageConfigurationProvider(value)
            } else {
                throw RuleErrorException(
                    "expected an instance of JavaPackageConfigurationProvider, got: "
                            + Starlark.type(value)
                )
            }
        }
    }

    companion object {
        private val PROVIDER: StarlarkProviderWrapper<JavaPackageConfigurationProvider?> = Provider()

        @VisibleForTesting
        @Throws(RuleErrorException::class)
        fun get(target: ConfiguredTarget): JavaPackageConfigurationProvider {
            return target.get(PROVIDER)
        }

        @Throws(RuleErrorException::class)
        fun wrapSequence(sequence: Sequence<StructImpl?>): ImmutableList<JavaPackageConfigurationProvider?> {
            val builder = ImmutableList.builder<JavaPackageConfigurationProvider?>()
            for (struct in sequence) {
                // this result isn't propagated back to Starlark so we just need any type
                builder.add(PROVIDER.wrap(struct))
            }
            return builder.build()
        }
    }
}

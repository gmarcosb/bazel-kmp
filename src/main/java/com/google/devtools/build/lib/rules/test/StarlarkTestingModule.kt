// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.test

import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment

/** A class that exposes testing infrastructure to Starlark.  */
class StarlarkTestingModule : TestingModuleApi {
    override fun executionInfo(): ExecutionInfo.ExecutionInfoProvider {
        return ExecutionInfo.PROVIDER
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun testEnvironment(
        environment: net.starlark.java.eval.Dict<*, *>?,  /* <String, String> */
        inheritedEnvironment: net.starlark.java.eval.Sequence<*>? /* <String> */
    ): RunEnvironmentInfo? {
        return RunEnvironmentInfo(
            net.starlark.java.eval.Dict.cast<K?, V?>(
                environment,
                String::class.java,
                String::class.java,
                "environment"
            ),
            net.starlark.java.eval.StarlarkList.immutableCopyOf<T?>(
                net.starlark.java.eval.Sequence.cast<String?>(
                    inheritedEnvironment,
                    String::class.java,
                    "inherited_environment"
                )
            ),  /* shouldErrorOnNonExecutableRule= */
            false
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    override fun analysisTest(
        name: String?,
        implementation: net.starlark.java.eval.StarlarkFunction?,
        attrs: net.starlark.java.eval.Dict<*, *>?,
        fragments: net.starlark.java.eval.Sequence<*>?,
        toolchains: net.starlark.java.eval.Sequence<*>?,
        attrValuesApi: Any?,
        thread: net.starlark.java.eval.StarlarkThread
    ) {
        val pkgBuilder: Package.Builder = Package.Builder.fromOrNull(thread)
        val ruleDefinitionEnvironment: RuleDefinitionEnvironment? =
            thread.getThreadLocal<RuleDefinitionEnvironment?>(RuleDefinitionEnvironment::class.java)
        // TODO(b/236456122): Refactor this check into a standard helper / error message
        if (pkgBuilder == null || ruleDefinitionEnvironment == null) {
            throw net.starlark.java.eval.Starlark.errorf("analysis_test can only be called in a BUILD thread")
        }

        if (!RULE_NAME_PATTERN.matcher(name).matches()) {
            throw net.starlark.java.eval.Starlark.errorf("'name' is limited to Starlark identifiers, got %s", name)
        }
        val attrValues: net.starlark.java.eval.Dict<String?, Any?> =
            net.starlark.java.eval.Dict.cast<String?, Any?>(
                attrValuesApi,
                String::class.java,
                Any::class.java,
                "attr_values"
            )
        if (attrValues.containsKey("name")) {
            throw net.starlark.java.eval.Starlark.errorf("'name' cannot be set or overridden in 'attr_values'")
        }

        val labelConverter: LabelConverter? = LabelConverter.forBzlEvaluatingThread(thread)

        // Each call to analysis_test defines a rule class (the code right below this comment here) and
        // then instantiates a *single* target of that rule class (the code at the end of this method).
        //
        // For normal Starlark-defined rule classes we're supposed to pass in the label of the bzl file
        // being initialized at the time the rule class is defined, as well as the transitive digest of
        // that bzl and all bzls it loads (for purposes of being sensitive to e.g. changes to the rule
        // class's implementation function).
        //
        // We used to use a constant digest for all calls to analysis_test. This caused issues due to
        // how the digest is used as part of the cache key of deserialized rule classes. To address
        // that, we now use the combo of the package name and the target name (this works since we don't
        // currently try to deserialize the same rule class produced at different source versions).
        // See http://b/291752414#comment6.
        val dummyBzlFile: Label? = Label.createUnvalidated(PackageIdentifier.EMPTY_PACKAGE_ID, "dummy_label")
        val fingerprint: Fingerprint = Fingerprint()
        fingerprint.addString(pkgBuilder.getMetadata().getName())
        fingerprint.addString(name)
        // TODO: b/291752414 - also include the BUILD file digest
        fingerprint.addBytes(pkgBuilder.getTransitiveBzlDigest())
        val transitiveDigestToUse: ByteArray? = fingerprint.digestAndReset()

        val starlarkRuleFunction: StarlarkRuleFunction =
            StarlarkRuleClassFunctions.createRule( // Contextual parameters.
                ruleDefinitionEnvironment,
                thread,
                dummyBzlFile,
                transitiveDigestToUse,
                labelConverter,  // rule() parameters.
                /* parent= */
                null,  /* extendableUnchecked= */
                false,
                implementation,  /* initializer= */
                null,  /* test= */
                true,
                attrs,  /* implicitOutputs= */
                net.starlark.java.eval.Starlark.NONE,  /* executable= */
                false,  /* outputToGenfiles= */
                false,  /* fragments= */
                fragments,  /* starlarkTestable= */
                false,  /* toolchains= */
                toolchains,  /* doc= */
                net.starlark.java.eval.Starlark.NONE,  /* providesArg= */
                net.starlark.java.eval.StarlarkList.empty<T?>(),  /* dependencyResolutionRule= */
                false,  /* isMaterializerRule= */
                false,  /* allowMaterializerRuleRealDeps= */
                false,  /* execCompatibleWith= */
                net.starlark.java.eval.StarlarkList.empty<T?>(),  /* analysisTest= */
                java.lang.Boolean.TRUE,  /* buildSetting= */
                net.starlark.java.eval.Starlark.NONE,  /* cfg= */
                net.starlark.java.eval.Starlark.NONE,  /* execGroups= */
                net.starlark.java.eval.Starlark.NONE,  /* subrulesUnchecked= */
                net.starlark.java.eval.StarlarkList.empty<T?>()
            )

        // Export the rule.
        //
        // Because exporting can raise multiple errors, we need to accumulate them here into a single
        // EvalException. This is a code smell because any non-ERROR events will be lost, and any
        // location information in the events will be overwritten by the location of this rule's
        // definition.
        //
        // However, this is currently fine because StarlarkRuleFunction#export only creates events that
        // are ERRORs and that have the rule definition as their location.
        //
        // TODO(brandjon): Instead of accumulating events here, consider registering the rule in the
        // BazelStarlarkContext (or the appropriate subclass), and exporting such rules after module
        // evaluation in BzlLoadFunction#execAndExport.
        val handler: StoredEventHandler = StoredEventHandler()
        starlarkRuleFunction.export(
            handler,
            pkgBuilder.getMetadata().buildFileLabel(),
            name + "_test",
            net.starlark.java.syntax.Location.fromFile(
                pkgBuilder.getMetadata().buildFilename().toString()
            )
        ) // export in BUILD thread
        if (handler.hasErrors()) {
            val errors: java.lang.StringBuilder =
                handler.getEvents().stream()
                    .filter(java.util.function.Predicate { e: com.google.devtools.build.lib.events.Event? -> e.getKind() == com.google.devtools.build.lib.events.EventKind.ERROR })
                    .reduce<java.lang.StringBuilder>(
                        java.lang.StringBuilder(),
                        java.util.function.BiFunction { sb: java.lang.StringBuilder, ev: com.google.devtools.build.lib.events.Event? ->
                            sb.append(
                                "\n"
                            ).append(ev.getMessage())
                        },
                        BinaryOperator { obj: java.lang.StringBuilder?, s: CharSequence? -> obj.append(s) })
            throw net.starlark.java.eval.Starlark.errorf("Errors in exporting %s: %s", name, errors.toString())
        }

        // Instantiate the target
        val args: net.starlark.java.eval.Dict.Builder<String?, Any?> =
            net.starlark.java.eval.Dict.builder<String?, Any?>()
        args.put("name", name)
        args.putAll(attrValues)
        starlarkRuleFunction.call(thread, net.starlark.java.eval.Tuple.of(), args.buildImmutable())
    }

    companion object {
        private val RULE_NAME_PATTERN: java.util.regex.Pattern =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*")
    }
}

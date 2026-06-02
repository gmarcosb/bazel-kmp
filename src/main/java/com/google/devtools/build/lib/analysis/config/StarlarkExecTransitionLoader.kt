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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.analysis.starlark.StarlarkAttributeTransitionProvider

/**
 * Utility class for loading a Starlark exec transition from source and making it available as an
 * [StarlarkAttributeTransitionProvider].
 */
object StarlarkExecTransitionLoader {
    /**
     * Loads the Starlark transition that implements execution transition logic according to [ ][CoreOptions.starlarkExecConfig].
     * 
     * @param options the current configured target's [BuildOptions]. This is used to find the
     * value for [CoreOptions.starlarkExecConfig].
     * @param bzlFileLoader caller-provided logic for loading [BzlLoadValue.Key] skyvalues.
     * @return null if Skyframe deps need loading. A filled [Optional] if this build implements
     * the exec transition with a Starlark transition. An empty [Optional] if this build
     * implements the exec transition with native logic.
     * @throws StarlarkExecTransitionLoadingException if the desired transition isn't a valid Starlark
     * exec transition.
     */
    @Throws(StarlarkExecTransitionLoadingException::class, java.lang.InterruptedException::class)
    fun loadStarlarkExecTransition(
        options: BuildOptions?, bzlFileLoader: BzlFileLoader
    ): java.util.Optional<StarlarkAttributeTransitionProvider?>? {
        if (options == null || options.equals(CommonOptions.EMPTY_OPTIONS)) {
            return java.util.Optional.empty<StarlarkAttributeTransitionProvider?>()
        }
        val userRef: String =
            com.google.common.base.Verify.verifyNotNull<T>(
                options.get(CoreOptions::class.java).getStarlarkExecConfig(),
                "Cannot apply the exec transition since no transition is defined for this build."
            )
        val flagName = "--experimental_exec_config"
        val parsedRef = TransitionReference.Companion.create(userRef, flagName)
        val bzlValue: BzlLoadValue?
        try {
            bzlValue =
                bzlFileLoader.getValue(
                    if (parsedRef.bzlFile.getRepository() == RepositoryName.BUILTINS)
                        BzlLoadValue.keyForBuiltins(parsedRef.bzlFile)
                    else
                        BzlLoadValue.keyForBuild(parsedRef.bzlFile)
                )
        } catch (e: BzlLoadFailedException) {
            throw StarlarkExecTransitionLoadingException(flagName, userRef, e.getMessage())
        }
        if (bzlValue == null) {
            return null
        }
        val transition: Any? = bzlValue.getModule().getGlobal(parsedRef.starlarkSymbolName)
        if (transition == null) {
            throw StarlarkExecTransitionLoadingException(
                flagName,
                userRef,
                java.lang.String.format("%s not found in %s", parsedRef.starlarkSymbolName, parsedRef.bzlFile)
            )
        } else if (transition !is StarlarkDefinedConfigTransition) {
            throw StarlarkExecTransitionLoadingException(
                flagName, userRef, parsedRef.starlarkSymbolName + " is not a Starlark transition"
            )
        }
        return java.util.Optional.of<T?>(
            StarlarkExecTransitionProvider(transition as StarlarkDefinedConfigTransition)
        )
    }

    /** Thrown when the Starlark transition failed to load.  */
    class StarlarkExecTransitionLoadingException : java.lang.Exception {
        constructor(context: String?, ref: String?, message: String?) : this(
            java.lang.String.format(
                "Bad Starlark transition reference from %s: %s. %s.", context, ref, message
            )
        )

        constructor(message: String?) : super(message)

        constructor(cause: Throwable?) : super(cause)
    }

    /** Caller-provided logic for Skyframe-evaluating [BzlLoadValue.Key]s.  */
    interface BzlFileLoader {
        /**
         * Loads the given [BzlLoadValue.Key]. Returns null if not all Skyframe deps are ready.
         */
        @Throws(
            BzlLoadFailedException::class,
            java.lang.InterruptedException::class,
            StarlarkExecTransitionLoadingException::class
        )
        fun getValue(key: com.google.devtools.build.lib.skyframe.BzlLoadValue.Key?): BzlLoadValue?
    }

    /** A marker class to distinguish the exec transition from other starlark transitions.  */
    internal class StarlarkExecTransitionProvider(execTransition: StarlarkDefinedConfigTransition?) :
        StarlarkAttributeTransitionProvider(execTransition) {
        public override fun allowImmutableFlagChanges(): Boolean {
            // The exec transition must be allowed to change otherwise immutable flags.
            return true
        }

        public override fun isExecTransitionProvider(): Boolean {
            return true
        }
    }

    /**
     * Structured form of a Starlark transition reference.
     * 
     * 
     * In other words, structured form of `//pkg:def.bzl%transition_name`
     * 
     * @param bzlFile The .bzl file where this transition is defined.
     * @param starlarkSymbolName The transition's Starlark symbol name.
     */
    @kotlin.jvm.JvmRecord
    internal data class TransitionReference(
        bzlFile: com.google.devtools.build.lib.cmdline.Label?,
        starlarkSymbolName: String?
    ) {
        val bzlFile: com.google.devtools.build.lib.cmdline.Label?
        val starlarkSymbolName: String?

        init {
            this.starlarkSymbolName = starlarkSymbolName
            this.bzlFile = bzlFile
            java.util.Objects.requireNonNull<com.google.devtools.build.lib.cmdline.Label?>(bzlFile, "bzlFile")
            java.util.Objects.requireNonNull<String?>(starlarkSymbolName, "starlarkSymbolName")
        }

        companion object {
            /**
             * Returns a structured form of a user-specified Starlark transition reference.
             * 
             * @throws StarlarkExecTransitionLoadingException on parsing errors.
             */
            @Throws(StarlarkExecTransitionLoadingException::class)
            fun create(userRef: String, context: String?): TransitionReference {
                val splitval: MutableList<String?> = com.google.common.base.Splitter.on('%').splitToList(userRef)
                if (splitval.size() < 2 || splitval.get(1).isEmpty()) {
                    throw StarlarkExecTransitionLoadingException(
                        context, userRef, "Doesn't match expected form //pkg:file.bzl%%symbol"
                    )
                }
                try {
                    return TransitionReference(
                        com.google.devtools.build.lib.cmdline.Label.parseCanonical(splitval.get(0)),
                        splitval.get(1)
                    )
                } catch (e: LabelSyntaxException) {
                    throw StarlarkExecTransitionLoadingException(
                        context, userRef, java.lang.String.format("Bad label %s: %s", splitval.get(0), e.getMessage())
                    )
                }
            }
        }
    }
}

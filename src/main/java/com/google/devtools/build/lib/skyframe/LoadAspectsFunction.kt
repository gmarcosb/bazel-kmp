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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.packages.Aspect

/** [SkyFunction] to load top level aspects and assign their parameters.  */
internal class LoadAspectsFunction : SkyFunction {
    @Throws(LoadAspectsFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val topLevelAspectsDetailsKey: LoadAspectsKey = skyKey.argument() as LoadAspectsKey

        val topLevelAspects: com.google.common.collect.ImmutableList<Aspect?>? =
            getTopLevelAspects(
                env,
                topLevelAspectsDetailsKey.getTopLevelAspectsClasses(),
                topLevelAspectsDetailsKey.getTopLevelAspectsParameters()
            )

        if (topLevelAspects == null) {
            return null // some aspects are not loaded
        }

        return LoadAspectsValue(topLevelAspects)
    }

    private class LoadAspectsFunctionException(cause: TopLevelAspectsDetailsBuildFailedException?) :
        SkyFunctionException(cause, Transience.PERSISTENT)

    companion object {
        @Throws(java.lang.InterruptedException::class, LoadAspectsFunctionException::class)
        private fun loadStarlarkAspect(
            env: SkyFunction.Environment, aspectClass: StarlarkAspectClass
        ): StarlarkDefinedAspect? {
            val starlarkAspect: StarlarkDefinedAspect?
            try {
                val bzlLoadValue: BzlLoadValue? =
                    env.getValueOrThrow<E?>(
                        AspectFunction.Companion.bzlLoadKeyForStarlarkAspect(aspectClass),
                        BzlLoadFailedException::class.java
                    ) as BzlLoadValue?
                if (bzlLoadValue == null) {
                    return null
                }
                starlarkAspect = AspectFunction.Companion.loadAspectFromBzl(aspectClass, bzlLoadValue)
            } catch (e: BzlLoadFailedException) {
                env.getListener().handle(com.google.devtools.build.lib.events.Event.error(e.getMessage()))
                throw LoadAspectsFunctionException(
                    TopLevelAspectsDetailsBuildFailedException(
                        e.getMessage(), Code.ASPECT_CREATION_FAILED
                    )
                )
            } catch (e: AspectCreationException) {
                env.getListener().handle(com.google.devtools.build.lib.events.Event.error(e.getMessage()))
                throw LoadAspectsFunctionException(
                    TopLevelAspectsDetailsBuildFailedException(
                        e.getMessage(), Code.ASPECT_CREATION_FAILED
                    )
                )
            }
            return starlarkAspect
        }

        @Throws(java.lang.InterruptedException::class, LoadAspectsFunctionException::class)
        private fun getTopLevelAspects(
            env: SkyFunction.Environment,
            topLevelAspectsClasses: com.google.common.collect.ImmutableList<AspectClass?>,
            topLevelAspectsParameters: com.google.common.collect.ImmutableMap<String?, String?>?
        ): com.google.common.collect.ImmutableList<Aspect?>? {
            val builder: AspectsList.Builder = Builder()

            for (aspectClass in topLevelAspectsClasses) {
                if (aspectClass is StarlarkAspectClass) {
                    val starlarkAspect: StarlarkAspect? = loadStarlarkAspect(env, aspectClass)
                    if (starlarkAspect == null) {
                        return null
                    }
                    try {
                        builder.addAspect(starlarkAspect)
                    } catch (e: net.starlark.java.eval.EvalException) {
                        env.getListener().handle(
                            com.google.devtools.build.lib.events.Event.error(
                                e.getInnermostLocation(),
                                e.getMessageWithStack()
                            )
                        )
                        throw LoadAspectsFunctionException(
                            TopLevelAspectsDetailsBuildFailedException(
                                e.getMessage(), Code.ASPECT_CREATION_FAILED
                            )
                        )
                    }
                } else {
                    try {
                        builder.addAspect(aspectClass as NativeAspectClass?)
                    } catch (e: java.lang.AssertionError) {
                        env.getListener().handle(com.google.devtools.build.lib.events.Event.error(e.getMessage()))
                        throw LoadAspectsFunctionException(
                            TopLevelAspectsDetailsBuildFailedException(
                                e.getMessage(), Code.ASPECT_CREATION_FAILED
                            )
                        )
                    }
                }
            }

            val aspectsList: AspectsList = builder.build()
            try {
                aspectsList.validateTopLevelAspectsParameters(topLevelAspectsParameters)
                return aspectsList.buildAspects(topLevelAspectsParameters)
            } catch (e: net.starlark.java.eval.EvalException) {
                env.getListener().handle(
                    com.google.devtools.build.lib.events.Event.error(
                        e.getInnermostLocation(),
                        e.getMessageWithStack()
                    )
                )
                throw LoadAspectsFunctionException(
                    TopLevelAspectsDetailsBuildFailedException(
                        e.getMessage(), Code.ASPECT_CREATION_FAILED
                    )
                )
            }
        }
    }
}

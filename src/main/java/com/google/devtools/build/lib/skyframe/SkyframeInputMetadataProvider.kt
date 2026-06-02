// Copyright 2025 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ActionInput

/**
 * An [InputMetadataProvider] implementation that requests the metadata of derived artifacts
 * from Skyframe and that of source artifacts from the per-build metadata cache.
 * 
 * 
 * During input discovery, the action may well legally read scheduling dependencies that are not
 * also inputs. Those are not in the regular input metadata provider (doing so would be a
 * performance issue), so we need to ask those from Skyframe instead. It's not that problematic
 * because they are known to be transitive Skyframe deps, so we can rely on them being present, with
 * one exception (see below) that can be handled without much ceremony.
 * 
 * 
 * In theory, this would also work for source artifacts. However, the performance ramifications
 * of doing that are unknown.
 */
internal class SkyframeInputMetadataProvider(
    evaluator: MemoizingEvaluator,
    perBuild: InputMetadataProvider,
    relativeOutputPath: String?
) : InputMetadataProvider {
    // Not static since it uses "env" and "envMonitor". This works out because "env" is always
    // updated to the Environment instance from the last restart and SkyframeLookup closes over
    // SkyframeInputMetadataProvider and not "env".
    private inner class SkyframeLookup(key: SkyKey?) {
        private val key: SkyKey?

        @kotlin.concurrent.Volatile
        private var value: SkyValue?

        init {
            this.key = key
            this.value = null
        }

        @Throws(java.lang.InterruptedException::class, MissingDepExecException::class)
        fun tryLookup(): SkyValue? {
            if (value != null) {
                return value
            }

            // We reuse envMonitor to guard SkyframeLookup.value. It's the simplest: we need a lock for
            // "env" that ensures that only one thread calls methods on it and thus a simple
            // synchronized" block won't work so if we wanted to guard "value" separately, we'd have to
            // have two nested "synchronized" blocks which sounds like more trouble than it's worth.
            synchronized(envMonitor) {
                // We use .getExistingValue() to spare a Skyframe edge. This is correct because these are
                // always transitive dependencies (it's not a property that's inherently true, though, it's
                // just how actions that discover inputs happen to be implemented)
                var localValue: SkyValue? = evaluator.getExistingValue(key)
                if (localValue == null) {
                    // This can only happen if a transitive dependency was rewound but the re-evaluation
                    // resulted in an error or the rewinding is in progress.
                    //
                    // env is set to null once any missing values are detected. This is a work-around for a
                    // semi-bug in include scanning where the include scanner might continue processing after
                    // the action has already ended. This is problematic because lookups against an
                    // environment crash once the associated action is done. Instead, any subsequent lookups
                    // throw MissingDepExecException without adding a dependency edge. At worst, this can only
                    // result in a superfluous restart.
                    if (env == null) {
                        throw MissingDepExecException()
                    }
                    localValue = env.getValue(key)
                    if (localValue == null) {
                        env = null
                        throw MissingDepExecException()
                    }
                    // This can happen if the evaluation of "value" finished between the getExistingValue()
                    // call and the getValue() one. In this case, "value" is good. We move on.
                }

                value = localValue
                return localValue
            }
        }
    }

    private val evaluator: MemoizingEvaluator

    // Non-null while skyframe lookups are being allowed during input discovery.
    private var env: SkyFunction.Environment? = null

    private val envMonitor: Any
    private val perBuild: InputMetadataProvider
    private val relativeOutputPath: PathFragment?

    private val seen: ConcurrentHashMap<PathFragment?, ActionInput?>

    /**
     * A cache so that we don't need to look up any SkyValue twice.
     * 
     * 
     * This is necessary because action rewinding means that even though a `getValue()` call
     * returned the appropriate value alright, subsequent calls with the same `SkyKey` may not
     * do so. So theoretically, every call to [.getInputMetadata] should be
     * prepared to handle a `MissingDepExecException`.
     * 
     * 
     * Sadly, that's not the case and the invariant we have is that the **first** call over the
     * course of the evaluation of an action with any given `ActionInput` handles that case, the
     * subsequent ones not necessarily. This cache is there to make sure that that's alright.
     */
    private val skyframeLookups: ConcurrentHashMap<SkyKey?, SkyframeLookup>

    private var allowSkyframe: Boolean

    init {
        this.evaluator = evaluator
        this.envMonitor = Any()
        this.perBuild = perBuild
        this.relativeOutputPath = PathFragment.create(relativeOutputPath)
        this.seen = ConcurrentHashMap<PathFragment?, ActionInput?>()
        this.skyframeLookups = ConcurrentHashMap<SkyKey?, SkyframeLookup>()
        this.allowSkyframe = false
    }

    /**
     * Allow Skyframe access while the returned closeable is open.
     * 
     * 
     * This should only happen during input discovery, so we disallow it everywhere else.
     */
    // TODO: b/416449869 - Add test coverage for a new env being set after a skyframe restart.
    fun withSkyframeAllowed(env: SkyFunction.Environment?): SilentCloseable {
        // No need to synchronize with envMonitor here. This is called before input discovery begins,
        // and the closeable is called after input discovery ends, so there are no concurrent calls to
        // getInputMetadataChecked.
        allowSkyframe = true
        this.env = env
        return SilentCloseable {
            allowSkyframe = false
            this.env = null
        }
    }

    @Throws(java.lang.InterruptedException::class, IOException::class, MissingDepExecException::class)
    public override fun getInputMetadataChecked(input: ActionInput): FileArtifactValue? {
        if (input !is Artifact) {
            if (!input.getExecPath().startsWith(relativeOutputPath)) {
                return perBuild.getInputMetadataChecked(input)
            } else {
                return null
            }
        }

        if (input.isSourceArtifact()) {
            return perBuild.getInputMetadataChecked(input)
        }

        if (!allowSkyframe) {
            return null
        }

        if (input is SpecialArtifact) {
            return null
        }

        val key: SkyKey? = Artifact.key(input)
        val lookup: SkyframeLookup =
            skyframeLookups.computeIfAbsent(key, java.util.function.Function { key: SkyKey? -> SkyframeLookup(key) })
        val value: SkyValue? = lookup.tryLookup()
        seen.put(input.getExecPath(), input)
        val actionExecutionValue: ActionExecutionValue = value as ActionExecutionValue
        return actionExecutionValue.getExistingFileArtifactValue(input)
    }

    public override fun getTreeMetadata(input: ActionInput?): TreeArtifactValue? {
        return null
    }

    public override fun getEnclosingTreeMetadata(execPath: PathFragment?): TreeArtifactValue? {
        return null
    }

    public override fun getFileset(input: ActionInput?): FilesetOutputTree? {
        return null
    }

    val filesets: MutableMap<Artifact, FilesetOutputTree>
        get() = com.google.common.collect.ImmutableMap.of<Artifact?, FilesetOutputTree?>()

    public override fun getRunfilesMetadata(input: ActionInput?): RunfilesArtifactValue? {
        return null
    }

    val runfilesTrees: com.google.common.collect.ImmutableList<RunfilesTree?>
        get() = com.google.common.collect.ImmutableList.of<RunfilesTree?>()

    public override fun getInput(execPath: PathFragment?): ActionInput? {
        var result: ActionInput? = seen.get(execPath)
        if (result == null) {
            result = perBuild.getInput(execPath)
        }

        return result
    }
}

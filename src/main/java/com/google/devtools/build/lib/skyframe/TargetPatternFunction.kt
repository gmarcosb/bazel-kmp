// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.BatchCallback.SafeBatchCallback

/**
 * TargetPatternFunction translates a target pattern (eg, "foo/...") into a set of resolved Targets.
 */
class TargetPatternFunction : SkyFunction {
    @Throws(TargetPatternFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(key: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val patternKey: TargetPatternKey =
            (key.argument() as TargetPatternKey)
        val parsedPattern: TargetPattern = patternKey.getParsedPattern()

        val ignoredSubdirectories: IgnoredSubdirectoriesValue? =
            env.getValue(IgnoredSubdirectoriesValue.key(parsedPattern.repository)) as IgnoredSubdirectoriesValue?
        if (ignoredSubdirectories == null) {
            return null
        }

        val resolvedTargets: ResolvedTargets<Target?>?
        val provider: EnvironmentBackedRecursivePackageProvider =
            EnvironmentBackedRecursivePackageProvider(env)
        try {
            val resolver: RecursivePackageProviderBackedTargetPatternResolver =
                RecursivePackageProviderBackedTargetPatternResolver(
                    provider,
                    env.getListener(),
                    patternKey.getPolicy(),
                    MultisetSemaphore.unbounded<T?>(),  /* maxConcurrentGetTargetsTasks= */
                    java.util.Optional.empty<T?>(),
                    { SimplePackageIdentifierBatchingCallback() })
            val excludedSubdirectories: com.google.common.collect.ImmutableSet<PathFragment?>? =
                patternKey.getExcludedSubdirectories()
            val resolvedTargetsBuilder: ResolvedTargets.Builder<Target?> = ResolvedTargets.builder()
            val callback: SafeBatchCallback<Target?> =
                SafeBatchCallback { partialResult ->
                    for (target in partialResult) {
                        // TODO(b/156899726): This will go away as soon as we remove implicit outputs from
                        //  cc_library completely. The only downside to doing this is that implicit outputs
                        //  won't be listed when doing somepackage:* for the handful of cases still on the
                        //  allowlist. This is only a Google-internal problem and the scale of it is
                        //  acceptable in the short term while cleaning up the allowlist.
                        if (target is OutputFile
                            && target.getGeneratingRule().getRuleClass().equals("cc_library")
                        ) {
                            continue
                        }
                        resolvedTargetsBuilder.add(target)
                    }
                }
            try {
                parsedPattern.eval(
                    resolver,
                    { ignoredSubdirectories.asIgnoredSubdirectories() },
                    excludedSubdirectories,
                    callback,
                    MarkerRuntimeException::class.java
                )
            } catch (e: ProcessPackageDirectoryException) {
                throw TargetPatternFunctionException(e)
            } catch (e: InconsistentFilesystemException) {
                throw TargetPatternFunctionException(e)
            }
            if (provider.encounteredPackageErrors()) {
                resolvedTargetsBuilder.setError()
            }
            resolvedTargets = resolvedTargetsBuilder.build()
        } catch (e: TargetParsingException) {
            env.getListener().post(ParsingFailedEvent(patternKey.getPattern(), e.getMessage()))
            throw TargetPatternFunctionException(e)
        } catch (e: MissingDepException) {
            // If there is at least one missing dependency, we should eagerly throw any exception we might
            // have encountered: if we are in error bubbling, this could be our last chance.
            maybeThrowEncounteredException(parsedPattern, provider)
            // The EnvironmentBackedRecursivePackageProvider constructed above might throw
            // MissingDepException to signal when it has a dependency on a missing Environment value.
            // Note that MissingDepException extends RuntimeException because the methods called
            // on EnvironmentBackedRecursivePackageProvider all belong to an interface shared with other
            // implementations that are unconcerned with MissingDepExceptions.
            return null
        } catch (e: java.lang.InterruptedException) {
            if (env.inErrorBubbling()) {
                maybeThrowEncounteredException(parsedPattern, provider)
            }
            throw e
        }
        if (env.inErrorBubbling()) {
            maybeThrowEncounteredException(parsedPattern, provider)
        }
        com.google.common.base.Preconditions.checkNotNull<Any?>(resolvedTargets, key)
        val resolvedLabelsBuilder: ResolvedTargets.Builder<Label?> = ResolvedTargets.builder()
        if (resolvedTargets.hasError()) {
            resolvedLabelsBuilder.setError()
        }
        for (target in resolvedTargets.getTargets()) {
            resolvedLabelsBuilder.add(target.getLabel())
        }
        for (target in resolvedTargets.getFilteredTargets()) {
            resolvedLabelsBuilder.remove(target.getLabel())
        }
        return TargetPatternValue(resolvedLabelsBuilder.build())
    }

    private class TargetPatternFunctionException : SkyFunctionException {
        val isCatastrophic: Boolean

        internal constructor(e: TargetParsingException?) : super(e, Transience.PERSISTENT) {
            this.isCatastrophic = false
        }

        internal constructor(e: InconsistentFilesystemException?) : super(
            TargetParsingException(e),
            Transience.PERSISTENT
        ) {
            this.isCatastrophic = true
        }

        internal constructor(e: ProcessPackageDirectoryException) : super(
            TargetParsingException(e.getMessage(), e, e.getDetailedExitCode()),
            Transience.PERSISTENT
        ) {
            this.isCatastrophic = true
        }
    }

    companion object {
        @Throws(TargetPatternFunctionException::class)
        private fun maybeThrowEncounteredException(
            pattern: TargetPattern, provider: EnvironmentBackedRecursivePackageProvider
        ) {
            val e: NoSuchPackageException? = provider.maybeGetNoSuchPackageException()
            if (e != null) {
                throw TargetPatternFunctionException(
                    TargetParsingException(
                        "Error evaluating '" + pattern.originalPattern + "': " + e.getMessage(),
                        e,
                        FailureDetails.TargetPatterns.Code.PACKAGE_NOT_FOUND
                    )
                )
            }
        }
    }
}

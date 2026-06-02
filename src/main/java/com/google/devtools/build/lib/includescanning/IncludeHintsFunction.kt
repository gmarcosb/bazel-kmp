// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.includescanning

import com.google.devtools.build.lib.actions.EnvironmentalExecException
import java.lang.String

/**
 * Creates a [IncludeParser.HintsRules] object. Done in Skyframe to track dependence on
 * INCLUDE_HINTS file.
 */
class IncludeHintsFunction(hintsFile: PathFragment) : SkyFunction {
    private val hintsFile: PathFragment

    init {
        this.hintsFile = hintsFile
    }

    @Throws(IncludeHintsFunctionException::class, InterruptedException::class)
    override fun compute(skyKey: SkyKey?, env: SkyFunction.Environment): HintsRules? {
        val hintsPackageRoot: Root
        try {
            val hintsLookupValue: ContainingPackageLookupValue? =
                env.getValueOrThrow<IOException?, BuildFileNotFoundException?>(
                    ContainingPackageLookupValue.key(
                        PackageIdentifier.createInMainRepo(hintsFile.getParentDirectory())
                    ),
                    IOException::class.java, BuildFileNotFoundException::class.java
                ) as ContainingPackageLookupValue?
            if (env.valuesMissing()) {
                return null
            }
            if (!hintsLookupValue.hasContainingPackage()) {
                val reasonForNoContainingPackage: String? = hintsLookupValue.getReasonForNoContainingPackage()
                val message =
                    String.format(
                        "INCLUDE_HINTS file %s was not in a package%s",
                        hintsFile,
                        if (reasonForNoContainingPackage != null) ": " + reasonForNoContainingPackage else ""
                    )
                throw IncludeHintsFunctionException(
                    EnvironmentalExecException(
                        createFailureDetail(message, Code.INCLUDE_HINTS_FILE_NOT_IN_PACKAGE)
                    )
                )
            }
            hintsPackageRoot = hintsLookupValue.getContainingPackageRoot()
            env.getValueOrThrow<E?>(
                FileValue.key(RootedPath.toRootedPath(hintsPackageRoot, hintsFile)),
                IOException::class.java
            )
        } catch (e: IOException) {
            throw IncludeHintsFunctionException(
                EnvironmentalExecException(
                    e,
                    createFailureDetail(
                        "could not read INCLUDE_HINTS file", Code.INCLUDE_HINTS_READ_FAILURE
                    )
                )
            )
        } catch (e: BuildFileNotFoundException) {
            throw IncludeHintsFunctionException(
                EnvironmentalExecException(
                    e,
                    createFailureDetail(
                        "could not read INCLUDE_HINTS file", Code.INCLUDE_HINTS_READ_FAILURE
                    )
                )
            )
        }
        if (env.valuesMissing()) {
            return null
        }
        try {
            return Hints.Companion.getRules(hintsPackageRoot.getRelative(hintsFile))
        } catch (e: IOException) {
            throw IncludeHintsFunctionException(
                EnvironmentalExecException(
                    e,
                    createFailureDetail(
                        "could not read INCLUDE_HINTS file", Code.INCLUDE_HINTS_READ_FAILURE
                    )
                )
            )
        }
    }

    /**
     * Used to declare the exception type that can be wrapped in the exception thrown by
     * [IncludeHintsFunction.compute].
     */
    private class IncludeHintsFunctionException(e: EnvironmentalExecException?) :
        SkyFunctionException(e, Transience.PERSISTENT)

    companion object {
        // TODO(b/111722810): the action cache is not sensitive to changes in the INCLUDE_HINTS file, so
        //  even though Skyframe handles changes, we may still not re-execute an affected action.
        @SerializationConstant
        val INCLUDE_HINTS_KEY: SkyKey = SkyKey { IncludeScanningSkyFunctions.INCLUDE_HINTS } as SkyKey

        private fun createFailureDetail(message: kotlin.String?, detailedCode: Code?): FailureDetail {
            return FailureDetail.newBuilder()
                .setMessage(message)
                .setIncludeScanning(IncludeScanning.newBuilder().setCode(detailedCode))
                .build()
        }
    }
}

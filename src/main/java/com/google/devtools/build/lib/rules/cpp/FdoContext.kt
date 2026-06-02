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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.actions.Artifact

/**
 * Describes how C++ FDO compilation should be done.
 * 
 * 
 * A POJO encapsulating the branch profiling configuration. For implementation see
 * fdo_context.bzl.
 * 
 * 
 * **The `fdoProfilePath` member was a mistake. DO NOT USE IT FOR ANYTHING!**
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class FdoContext(fdoContextStruct: StructImpl) {
    private val fdoContextStruct: StructImpl

    /** A POJO encapsulating the branch profiling configuration.  */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    class BranchFdoProfile(branchFdoProfile: StructImpl) {
        private val branchFdoProfile: StructImpl

        init {
            this.branchFdoProfile = branchFdoProfile
        }

        @get:Throws(net.starlark.java.eval.EvalException::class)
        val isAutoFdo: Boolean
            get() = this.branchFdoMode == "auto_fdo"

        @get:Throws(net.starlark.java.eval.EvalException::class)
        val isAutoXBinaryFdo: Boolean
            get() = this.branchFdoMode == "xbinary_fdo"

        @get:Throws(net.starlark.java.eval.EvalException::class)
        val isLlvmFdo: Boolean
            get() = this.branchFdoMode == "llvm_fdo"

        @get:Throws(net.starlark.java.eval.EvalException::class)
        val isLlvmCSFdo: Boolean
            get() = this.branchFdoMode == "llvm_cs_fdo"

        @get:Throws(net.starlark.java.eval.EvalException::class)
        val profileArtifact: Artifact?
            get() = branchFdoProfile.getNoneableValue("profile_artifact", Artifact::class.java)

        @get:Throws(net.starlark.java.eval.EvalException::class)
        private val branchFdoMode: String
            get() = branchFdoProfile.getValue("branch_fdo_mode", String::class.java)
    }

    init {
        this.fdoContextStruct = fdoContextStruct
    }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val branchFdoProfile: BranchFdoProfile?
        get() {
            val branchFdoProfile: StructImpl? =
                fdoContextStruct.getNoneableValue("branch_fdo_profile", StructImpl::class.java)
            if (branchFdoProfile == null) {
                return null
            }
            return BranchFdoProfile(branchFdoProfile)
        }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val prefetchHintsArtifact: Artifact
        get() = fdoContextStruct.getNoneableValue("prefetch_hints_artifact", Artifact::class.java)

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val propellerOptimizeInputFile: PropellerOptimizeInputFile?
        get() {
            val inputFile: StructImpl? =
                fdoContextStruct.getNoneableValue("propeller_optimize_info", StructImpl::class.java)
            if (inputFile == null) {
                return null
            }
            return PropellerOptimizeInputFile(inputFile)
        }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val memProfProfileArtifact: Artifact
        get() = fdoContextStruct.getNoneableValue("memprof_profile_artifact", Artifact::class.java)

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val protoProfileArtifact: Artifact?
        get() = fdoContextStruct.getNoneableValue("proto_profile_artifact", Artifact::class.java)

    @Throws(net.starlark.java.eval.EvalException::class)
    fun hasArtifacts(): Boolean {
        return this.branchFdoProfile != null || this.prefetchHintsArtifact != null || this.propellerOptimizeInputFile != null || this.memProfProfileArtifact != null
    }
}

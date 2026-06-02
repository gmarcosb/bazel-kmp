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
package com.google.devtools.build.lib.bazel

import com.google.devtools.build.lib.server.FailureDetails.FailureDetail

/**
 * Module to provide a [com.google.devtools.build.lib.vfs.FileSystem] instance that uses
 * `SHA256` as the default hash function, or else what's specified by `-Dbazel.DigestFunction`.
 * 
 * 
 * Because of Blaze/Bazel divergence we can't make the [ ] class use `SHA256` by default.
 */
class BazelFileSystemModule : BlazeModule() {
    private var nativePosixFilesService: NativePosixFilesService? = null

    override fun globalInit(
        startupOptions: com.google.devtools.common.options.OptionsParsingResult?,
        blazeServices: Iterable<com.google.devtools.build.lib.runtime.BlazeService?>
    ) {
        for (blazeService in blazeServices) {
            if (blazeService is NativePosixFilesService) {
                this.nativePosixFilesService = blazeService
                break
            }
        }
        com.google.common.base.Preconditions.checkNotNull<NativePosixFilesService?>(
            nativePosixFilesService,
            "expected NativePosixFilesService to be available"
        )
    }

    @Throws(AbruptExitException::class)
    override fun getFileSystem(startupOptions: com.google.devtools.common.options.OptionsParsingResult): ModuleFileSystem? {
        val options: BlazeServerStartupOptions =
            com.google.common.base.Preconditions.checkNotNull<BlazeServerStartupOptions>(
                startupOptions.getOptions<BlazeServerStartupOptions?>(
                    BlazeServerStartupOptions::class.java
                )
            )
        var digestHashFunction: DigestHashFunction? = options.getDigestHashFunction()
        if (digestHashFunction == null) {
            val value: String? = java.lang.System.getProperty("bazel.DigestFunction", "SHA256")
            try {
                digestHashFunction = DigestFunctionConverter().convert(value)
            } catch (e: com.google.devtools.common.options.OptionsParsingException) {
                throw AbruptExitException(
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(com.google.common.base.Strings.nullToEmpty(e.getMessage()))
                            .setFilesystem(
                                Filesystem.newBuilder()
                                    .setCode(Code.DEFAULT_DIGEST_HASH_FUNCTION_INVALID_VALUE)
                            )
                            .build()
                    ),
                    e
                )
            }
        }

        val fs: com.google.devtools.build.lib.vfs.FileSystem =
            when (com.google.devtools.build.lib.util.OS.getCurrent()) {
                com.google.devtools.build.lib.util.OS.WINDOWS -> com.google.devtools.build.lib.windows.WindowsFileSystem(
                    digestHashFunction,
                    options.getEnableWindowsSymlinks()
                )

                else -> UnixFileSystem(
                    digestHashFunction,
                    options.getUnixDigestHashAttributeName(),
                    nativePosixFilesService
                )
            }

        return ModuleFileSystem.create(fs)
    }

    companion object {
        init {
            BazelHashFunctions.ensureRegistered()
        }
    }
}

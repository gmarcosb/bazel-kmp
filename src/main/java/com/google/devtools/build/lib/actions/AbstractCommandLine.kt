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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.util.Fingerprint

/**
 * Partial implementation of a [CommandLine] suitable for when expansion eagerly materializes
 * strings.
 */
abstract class AbstractCommandLine : com.google.devtools.build.lib.actions.CommandLine() {
    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    override fun expand(): ArgChunk {
        return SimpleArgChunk(arguments())
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    override fun expand(inputMetadataProvider: InputMetadataProvider?, pathMapper: PathMapper?): ArgChunk {
        return SimpleArgChunk(arguments(inputMetadataProvider, pathMapper))
    }

    /**
     * Returns the expanded command line with enclosed artifacts expanded by an `InputMetadataProvider` at execution time.
     * 
     * 
     * By default, this method just delegates to [.arguments], without performing any
     * artifact expansion. Subclasses should override this method if they contain tree artifacts and
     * need to expand them for proper argument evaluation.
     */
    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    override fun arguments(
        inputMetadataProvider: InputMetadataProvider?, pathMapper: PathMapper?
    ): Iterable<String?>? {
        return arguments()
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    override fun addToFingerprint(
        actionKeyContext: ActionKeyContext?,
        inputMetadataProvider: InputMetadataProvider?,
        effectiveOutputPathsMode: OutputPathsMode?,
        fingerprint: Fingerprint
    ) {
        for (s in arguments( /* inputMetadataProvider= */
            null, PathMapper.Companion.forActionKey(effectiveOutputPathsMode)
        )!!) {
            fingerprint.addString(s)
        }
    }
}

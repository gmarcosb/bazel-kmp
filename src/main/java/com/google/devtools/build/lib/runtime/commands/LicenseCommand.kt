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
package com.google.devtools.build.lib.runtime.commands

import com.google.devtools.build.lib.runtime.Command.BuildPhase.NONE

/** A command that prints an embedded license text.  */
@Command(
    name = "license",
    buildPhase = NONE,
    allowResidue = true,
    mustRunInWorkspace = false,
    shortDescription = "Prints the license of this software.",
    help = "Prints the license of this software.\n\n%{options}"
)
class LicenseCommand : BlazeCommand {
    public override fun exec(
        env: CommandEnvironment,
        options: com.google.devtools.common.options.OptionsParsingResult?
    ): BlazeCommandResult {
        env.getEventBus().post(NoBuildEvent())
        val outErr: OutErr = env.getReporter().getOutErr()

        outErr.printOutLn("Licenses of all components included in this binary:\n")

        try {
            outErr.printOutLn(ResourceFileLoader.loadResource(this.javaClass, BAZEL_LICENSE))
        } catch (e: IOException) {
            throw java.lang.IllegalStateException(
                "I/O error while trying to print 'LICENSE' resource: " + e.message, e
            )
        }

        val bundledJdk: java.nio.file.Path =
            env.getDirectories()
                .getEmbeddedBinariesRoot()
                .getRelative("embedded_tools/jdk")
                .getPathFile()
                .toPath()
        if (java.nio.file.Files.exists(bundledJdk)) {
            outErr.printOutLn(
                "This binary comes with a bundled JDK, which contains the following license files:\n"
            )
            printJavaLicenseFiles(outErr, bundledJdk)
        }

        val bundledJre: java.nio.file.Path =
            env.getDirectories()
                .getEmbeddedBinariesRoot()
                .getRelative("embedded_tools/jre")
                .getPathFile()
                .toPath()
        if (java.nio.file.Files.exists(bundledJre)) {
            outErr.printOutLn(
                "This binary comes with a bundled JRE, which contains the following license files:\n"
            )
            printJavaLicenseFiles(outErr, bundledJre)
        }

        return BlazeCommandResult.success()
    }

    companion object {
        private val JAVA_LICENSE_FILES: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>(
                "ASSEMBLY_EXCEPTION",
                "DISCLAIMER",
                "LICENSE",
                "THIRD_PARTY_README"
            )

        private const val BAZEL_LICENSE = "license/LICENSE"

        val isSupported: Boolean
            get() = ResourceFileLoader.resourceExists(
                LicenseCommand::class.java,
                BAZEL_LICENSE
            )

        private fun printJavaLicenseFiles(outErr: OutErr, bundledJdkOrJre: java.nio.file.Path?) {
            try {
                java.nio.file.Files.walkFileTree(
                    bundledJdkOrJre,
                    object : SimpleFileVisitor<java.nio.file.Path?>() {
                        @Throws(IOException::class)
                        override fun visitFile(
                            path: java.nio.file.Path,
                            basicFileAttributes: BasicFileAttributes?
                        ): FileVisitResult? {
                            if (JAVA_LICENSE_FILES.contains(path.getFileName().toString())) {
                                outErr.printOutLn(path.toString() + ":\n")
                                java.nio.file.Files.copy(path, outErr.getOutputStream())
                                outErr.printOutLn("\n")
                            }
                            return super.visitFile(path, basicFileAttributes)
                        }
                    })
            } catch (e: IOException) {
                throw UncheckedIOException(
                    "I/O error while trying to print license file of bundled JDK or JRE: " + e.message,
                    e
                )
            }
        }
    }
}

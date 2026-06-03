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
package com.google.devtools.build.remote.worker

import com.google.devtools.build.lib.util.StringEncoding.unicodeToInternal

/** Options for remote worker.  */
@OptionsClass
abstract class RemoteWorkerOptions : OptionsBase() {
    private class PathFragmentConverter : Contextless<PathFragment?>() {
        override fun convert(value: String?): PathFragment {
            return PathFragment.create(unicodeToInternal(value))
        }

        val typeDescription: String
            get() = "a path"
    }

    @get:com.google.devtools.common.options.Option(
        name = "listen_port",
        defaultValue = "8080",
        category = "build_worker",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "Listening port for the netty server."
    )
    abstract val listenPort: Int

    @get:com.google.devtools.common.options.Option(
        name = "work_path",
        defaultValue = "null",
        converter = PathFragmentConverter::class,
        category = "build_worker",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "A directory for the build worker to do work."
    )
    abstract val workPath: PathFragment?

    @get:com.google.devtools.common.options.Option(
        name = "cas_path",
        defaultValue = "null",
        converter = PathFragmentConverter::class,
        category = "build_worker",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("A directory for the build worker to store it's files in. If left unset, and if no "
                + "other store is set, the worker falls back to an in-memory store.")
    )
    abstract val casPath: PathFragment?

    @get:com.google.devtools.common.options.Option(
        name = "debug",
        defaultValue = "false",
        category = "build_worker",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("Turn this on for debugging remote job failures. There will be extra messages and the "
                + "work directory will be preserved in the case of failure.")
    )
    abstract val debug: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "legacy_api",
        defaultValue = "false",
        category = "build_worker",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "Restrict worker to RemoteApi version 2.0 capabilities"
    )
    abstract val legacyApi: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "pid_file",
        defaultValue = "null",
        converter = PathFragmentConverter::class,
        category = "build_worker",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "File for writing the process id for this worker when it is fully started."
    )
    abstract val pidFile: PathFragment?

    @get:com.google.devtools.common.options.Option(
        name = "sandboxing",
        defaultValue = "false",
        category = "build_worker",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "If supported on this platform, use sandboxing for increased hermeticity."
    )
    abstract val sandboxing: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "sandboxing_writable_path",
        defaultValue = "null",
        category = "build_worker",
        converter = PathFragmentConverter::class,
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        allowMultiple = true,
        help = "When using sandboxing, allow running actions to write to this path."
    )
    abstract val sandboxingWritablePaths: MutableList<PathFragment>?

    @get:com.google.devtools.common.options.Option(
        name = "sandboxing_tmpfs_dir",
        defaultValue = "null",
        category = "build_worker",
        converter = PathFragmentConverter::class,
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        allowMultiple = true,
        help = "When using sandboxing, mount an empty tmpfs onto this path for each running action."
    )
    abstract val sandboxingTmpfsDirs: MutableList<PathFragment>?

    @get:com.google.devtools.common.options.Option(
        name = "sandboxing_block_network",
        defaultValue = "false",
        category = "build_worker",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "When using sandboxing, block network access for running actions."
    )
    abstract val sandboxingBlockNetwork: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "jobs",
        defaultValue = "auto",
        converter = JobsConverter::class,
        category = "build_worker",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("The maximum number of concurrent jobs to run. Takes "
                + ResourceConverter.FLAG_SYNTAX
                + ". \"auto\" means to use a reasonable value"
                + " derived from the machine's hardware profile (e.g. the number of processors)."
                + " Values less than 1 or above "
                + MAX_JOBS
                + " are not allowed.")
    )
    abstract val jobs: Int

    @get:com.google.devtools.common.options.Option(
        name = "http_listen_port",
        defaultValue = "0",
        category = "build_worker",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("Starts an embedded HTTP REST server on the given port. The server will simply store PUT "
                + "requests in memory and return them again on GET requests. This is useful for "
                + "testing only.")
    )
    abstract val httpListenPort: Int

    @get:com.google.devtools.common.options.Option(
        name = "tls_certificate",
        defaultValue = "null",
        converter = PathFragmentConverter::class,
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "Specify the TLS server certificate to use."
    )
    abstract val tlsCertificate: PathFragment?

    @get:com.google.devtools.common.options.Option(
        name = "tls_private_key",
        defaultValue = "null",
        converter = PathFragmentConverter::class,
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "Specify the TLS private key to be used."
    )
    abstract val tlsPrivateKey: PathFragment?

    @get:com.google.devtools.common.options.Option(
        name = "tls_ca_certificate",
        defaultValue = "null",
        converter = PathFragmentConverter::class,
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("Specify a CA certificate to use for authenticating clients; setting this implicitly "
                + "requires client authentication (aka mTLS).")
    )
    abstract val tlsCaCertificate: PathFragment?

    @get:com.google.devtools.common.options.Option(
        name = "expected_authorization_token",
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("The authorization token expected to be present in every request. This is useful for"
                + " testing only.")
    )
    abstract val expectedAuthorizationToken: String?

    @get:com.google.devtools.common.options.Option(
        name = "unavailable",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("If true, all gRPC services, except Capabilities, return UNAVAILABLE. This is useful for"
                + " testing only.")
    )
    abstract val unavailable: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "error_on_duplicate_downloads",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("If true, each individual digest is allowed to be downloaded at most once per tool"
                + " invocation id. This is useful for testing only.")
    )
    abstract val errorOnDuplicateDownloads: Boolean

    /**
     * Converter for jobs. Takes {@value FLAG_SYNTAX}. Values must be between 1 and {@value MAX_JOBS}.
     * Values higher than {@value MAX_JOBS} will be set to {@value MAX_JOBS}.
     */
    class JobsConverter :
        ResourceConverter.IntegerConverter( /* auto= */HOST_CPUS_SUPPLIER,  /* minValue= */1,  /* maxValue= */
            MAX_JOBS
        ) {
        @Throws(OptionsParsingException::class)
        public override fun checkAndLimit(value: Int?): Int? {
            var value = value
            if (value < minValue) {
                throw OptionsParsingException(
                    java.lang.String.format("Value '(%d)' must be at least %d.", value, minValue)
                )
            }
            if (value > maxValue) {
                logger.atWarning().log(
                    ("Flag remoteWorker \"jobs\" ('%d') was set too high. "
                            + "This is a result of passing large values to --jobs. "
                            + "Using '%d' jobs"),
                    value, maxValue
                )
                value = maxValue
            }
            return value
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private const val MAX_JOBS = 16384
    }
}

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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.util.OptionsUtils
import com.google.devtools.build.lib.vfs.DigestHashFunction
import com.google.devtools.build.lib.vfs.DigestHashFunction.DigestFunctionConverter
import com.google.devtools.build.lib.vfs.PathFragment

/**
 * Options that will be evaluated by the blaze client startup code and passed to the blaze server
 * upon startup.
 * 
 * <h4>IMPORTANT</h4>
 * 
 * These options and their defaults must be kept in sync with those in the source of the launcher.
 * The latter define the actual default values, most startup options are passed every time,
 * regardless of whether a value was set explicitly or if the default was used. Some options are
 * omitted by default, though this should only be true for options where "omitted" is a distinct
 * value.
 * 
 * 
 * The same relationship holds between [HostJvmStartupOptions] and the launcher.
 */
@com.google.devtools.common.options.OptionsClass
abstract class BlazeServerStartupOptions : com.google.devtools.common.options.OptionsBase() {
    /**
     * Converter for the `option_sources` option. Takes a string in the form of
     * "option_name1:source1:option_name2:source2:.." and converts it into an option name to source
     * map.
     */
    class OptionSourcesConverter :
        com.google.devtools.common.options.Converter.Contextless<MutableMap<String?, String?>?>() {
        private fun unescape(input: String): String? {
            return input.replace("_C", ":").replace("_U", "_")
        }

        override fun convert(input: String): MutableMap<String?, String?> {
            val builder: com.google.common.collect.ImmutableMap.Builder<String?, String?> =
                com.google.common.collect.ImmutableMap.builder<String?, String?>()
            if (input.isEmpty()) {
                return builder.buildOrThrow()
            }

            val elements: Array<String> = input.split(":")
            for (i in 0..<(elements.size + 1) / 2) {
                val name = elements[i * 2]
                var value = ""
                if (elements.size > i * 2 + 1) {
                    value = elements[i * 2 + 1]
                }
                builder.put(unescape(name), unescape(value))
            }
            return builder.buildOrThrow()
        }

        val typeDescription: String
            get() = "a list of option-source pairs"
    }

    @get:com.google.devtools.common.options.Option(
        name = "install_base",
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.CHANGES_INPUTS, com.google.devtools.common.options.OptionEffectTag.LOSES_INCREMENTAL_STATE],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
        converter = OptionsUtils.PathFragmentConverter::class,
        help = "This launcher option is intended for use only by tests."
    )
    abstract val installBase: PathFragment?

    @get:com.google.devtools.common.options.Option(
        name = "install_md5",
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOSES_INCREMENTAL_STATE, com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
        help = "This launcher option is intended for use only by tests."
    )
    abstract val installMD5: String?

    @get:com.google.devtools.common.options.Option(
        name = "lock_install_base",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
        help = ("Whether the server should hold a lock on the install base while running, to prevent"
                + " another server from attempting to garbage collect it.")
    )
    abstract val lockInstallBase: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "output_base",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.LOSES_INCREMENTAL_STATE],
        converter = OptionsUtils.PathFragmentConverter::class,
        valueHelp = "<path>",
        help = ("If set, specifies the output location to which all build output will be written. "
                + "Otherwise, the location will be "
                + "\${OUTPUT_ROOT}/_blaze_\${USER}/\${MD5_OF_WORKSPACE_ROOT}. Note: If you specify a "
                + "different option from one to the next Bazel invocation for this value, you'll "
                + "likely start up a new, additional Bazel server. Bazel starts exactly one server "
                + "per specified output base. Typically there is one output base per workspace - "
                + "however, with this option you may have multiple output bases per workspace and "
                + "thereby run multiple builds for the same client on the same machine concurrently. "
                + "See 'bazel help shutdown' on how to shutdown a Bazel server.")
    )
    abstract val outputBase: PathFragment?

    @get:com.google.devtools.common.options.Option(
        name = "output_user_root",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.LOSES_INCREMENTAL_STATE],
        converter = OptionsUtils.PathFragmentConverter::class,
        valueHelp = "<path>",
        help = ("The user-specific directory beneath which all build outputs are written; by default, "
                + "this is a function of \$USER, but by specifying a constant, build outputs can be "
                + "shared between collaborating users.")
    )
    abstract val outputUserRoot: PathFragment?

    @get:com.google.devtools.common.options.Option(
        name = "server_jvm_out",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.LOSES_INCREMENTAL_STATE],
        converter = OptionsUtils.PathFragmentConverter::class,
        valueHelp = "<path>",
        help = ("The location to write the server's JVM's output. If unset then defaults to a location "
                + "in output_base.")
    )
    abstract val serverJvmOut: PathFragment?

    @get:com.google.devtools.common.options.Option(
        name = "failure_detail_out",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.LOSES_INCREMENTAL_STATE],
        converter = OptionsUtils.PathFragmentConverter::class,
        valueHelp = "<path>",
        help = ("If set, specifies a location to write a failure_detail protobuf message if the server"
                + " experiences a failure and cannot report it via gRPC, as normal. Otherwise, the"
                + " location will be \${OUTPUT_BASE}/failure_detail.rawproto.")
    )
    abstract val failureDetailOut: PathFragment?

    @get:com.google.devtools.common.options.Option(
        name = "workspace_directory",
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.CHANGES_INPUTS, com.google.devtools.common.options.OptionEffectTag.LOSES_INCREMENTAL_STATE],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
        converter = OptionsUtils.PathFragmentConverter::class,
        help = ("The root of the workspace, that is, the directory that Bazel uses as the root of the "
                + "build. This flag is only to be set by the bazel client.")
    )
    abstract val workspaceDirectory: PathFragment?

    @get:com.google.devtools.common.options.Option(
        name = "default_system_javabase",
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.CHANGES_INPUTS, com.google.devtools.common.options.OptionEffectTag.LOSES_INCREMENTAL_STATE],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
        converter = OptionsUtils.PathFragmentConverter::class,
        help = ("The root of the user's local JDK install, to be used as the default target javabase"
                + " and as a fall-back host_javabase. This is not the embedded JDK.")
    )
    abstract val defaultSystemJavabase: PathFragment?

    @get:com.google.devtools.common.options.Option(
        name = "max_idle_secs",
        defaultValue = "" + (3 * 3600),
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EAGERNESS_TO_EXIT, com.google.devtools.common.options.OptionEffectTag.LOSES_INCREMENTAL_STATE],
        valueHelp = "<integer>",
        help = ("The number of seconds the build server will wait idling before shutting down. Zero"
                + " means that the server will never shutdown. This is only read on server-startup,"
                + " changing this option will not cause the server to restart.")
    )
    abstract val maxIdleSeconds: Int

    @get:com.google.devtools.common.options.Option(
        name = "shutdown_on_low_sys_mem",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EAGERNESS_TO_EXIT, com.google.devtools.common.options.OptionEffectTag.LOSES_INCREMENTAL_STATE],
        help = ("If max_idle_secs is set and the build server has been idle for a while, shut down the "
                + "server when the system is low on free RAM. Linux and MacOS only.")
    )
    abstract val shutdownOnLowSysMem: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "batch",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOSES_INCREMENTAL_STATE, com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION
        ],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
        help = ("If set, Bazel will be run as just a client process without a server, instead of in "
                + "the standard client/server mode. This is deprecated and will be removed, please "
                + "prefer shutting down the server explicitly if you wish to avoid lingering "
                + "servers.")
    )
    @get:Deprecated("")
    abstract val batch: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "block_for_lock",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EAGERNESS_TO_EXIT],
        help = ("When --noblock_for_lock is passed, Bazel does not wait for a running command to "
                + "complete, but instead exits immediately.")
    )
    abstract val blockForLock: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "io_nice_level",
        defaultValue = "-1",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        valueHelp = "{-1,0,1,2,3,4,5,6,7}",
        help = ("Only on Linux; set a level from 0-7 for best-effort IO scheduling using the "
                + "sys_ioprio_set system call. 0 is highest priority, 7 is lowest. The anticipatory "
                + "scheduler may only honor up to priority 4. If set to a negative value, then Bazel "
                + "does not perform a system call.")
    )
    abstract val ioNiceLevel: Int

    @get:com.google.devtools.common.options.Option(
        name = "batch_cpu_scheduling",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS],
        help = ("Only on Linux; use 'batch' CPU scheduling for Blaze. This policy is useful for "
                + "workloads that are non-interactive, but do not want to lower their nice value. "
                + "See 'man 2 sched_setscheduler'. If false, then Bazel does not perform a system "
                + "call.")
    )
    abstract val batchCpuScheduling: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "ignore_all_rc_files",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.CHANGES_INPUTS],
        help = ("Disables all rc files, regardless of the values of other rc-modifying flags, even if "
                + "these flags come later in the list of startup options.")
    )
    abstract val ignoreAllRcFiles: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "fatal_event_bus_exceptions",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EAGERNESS_TO_EXIT, com.google.devtools.common.options.OptionEffectTag.LOSES_INCREMENTAL_STATE],
        deprecationWarning = "Will be enabled by default and removed soon",
        help = ("Whether or not to exit if an exception is thrown by an internal EventBus handler. No-op"
                + " if --fatal_async_exceptions_exclusions is available; that flag's behavior is"
                + " preferentially used.")
    )
    abstract val fatalEventBusExceptions: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "option_sources",
        converter = OptionSourcesConverter::class,
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
        help = ""
    )
    abstract val optionSources: MutableMap<String?, String?>?

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "invocation_policy",
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.CHANGES_INPUTS],
        help = ("A base64-encoded-binary-serialized or text-formatted "
                + "invocation_policy.InvocationPolicy proto. Unlike other options, it is an error to "
                + "specify --invocation_policy multiple times.")
    )
    abstract val invocationPolicy: String?

    @get:com.google.devtools.common.options.Option(
        name = "command_port",
        defaultValue = "0",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOSES_INCREMENTAL_STATE, com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION
        ],
        help = "Port to start up the gRPC command server on. If 0, let the kernel choose."
    )
    abstract val commandPort: Int

    @get:com.google.devtools.common.options.Option(
        name = "product_name",
        defaultValue = "bazel",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOSES_INCREMENTAL_STATE, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING
        ],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
        help = ("The name of the build system. It is used as part of the name of the generated "
                + "directories (e.g. productName-bin for binaries) as well as for printing error "
                + "messages and logging")
    )
    abstract val productName: String?

    @get:com.google.devtools.common.options.Option(
        name = "write_command_log",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.LOSES_INCREMENTAL_STATE],
        help = ("WARNING: This option is deprecated and will be removed soon. Please use the command"
                + " option instead.")
    )
    abstract val writeCommandLog: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "client_debug",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        help = ("If true, log debug information from the client to stderr. Changing this option will not "
                + "cause the server to restart.")
    )
    abstract val clientDebug: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "quiet",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING],
        help = ("If true, no informational messages are emitted on the console, only errors. Changing "
                + "this option will not cause the server to restart.")
    )
    abstract val quiet: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "preemptible",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EAGERNESS_TO_EXIT],
        help = "If true, the command can be preempted if another command is started."
    )
    abstract val preemptible: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "connect_timeout_secs",
        defaultValue = "30",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        help = "The amount of time the client waits for each attempt to connect to the server"
    )
    abstract val connectTimeoutSecs: Int

    @get:com.google.devtools.common.options.Option(
        name = "local_startup_timeout_secs",
        defaultValue = "120",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        help = "The maximum amount of time the client waits to connect to the server"
    )
    abstract val localStartupTimeoutSecs: Int

    @get:com.google.devtools.common.options.Option(
        name = "digest_function",
        defaultValue = "null",
        converter = DigestFunctionConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOSES_INCREMENTAL_STATE, com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION
        ],
        help = "The hash function to use when computing file digests."
    )
    abstract val digestHashFunction: DigestHashFunction?

    @get:com.google.devtools.common.options.Option(
        name = "idle_server_tasks",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOSES_INCREMENTAL_STATE, com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS
        ],
        help = "Run System.gc() when the server is idle"
    )
    abstract val idleServerTasks: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "unlimit_coredumps",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION
        ],
        help = ("Raises the soft coredump limit to the hard limit to make coredumps of the server"
                + " (including the JVM) and the client possible under common conditions. Stick this"
                + " flag in your bazelrc once and forget about it so that you get coredumps when you"
                + " actually encounter a condition that triggers them.")
    )
    abstract val unlimitCoredumps: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "macos_qos_class",
        defaultValue = "default",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS
        ],
        help = ("Sets the QoS service class of the %{product} server when running on macOS. This "
                + "flag has no effect on all other platforms but is supported to ensure rc files "
                + "can be shared among them without changes. Possible values are: user-interactive, "
                + "user-initiated, default, utility, and background.")
    )
    abstract val macosQosClass: String?

    @get:com.google.devtools.common.options.Option(
        name = "windows_enable_symlinks",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        help = ("If true, real symbolic links will be created on Windows instead of file copying. "
                + "Requires Windows developer mode to be enabled and Windows 10 version 1703 or "
                + "greater.")
    )
    abstract val enableWindowsSymlinks: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "unix_digest_hash_attribute_name",
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.CHANGES_INPUTS, com.google.devtools.common.options.OptionEffectTag.LOSES_INCREMENTAL_STATE],
        help = ("The name of an extended attribute that can be placed on files to store a precomputed "
                + "copy of the file's hash, corresponding with --digest_function. This option "
                + "can be used to reduce disk I/O and CPU load caused by hash computation. This "
                + "extended attribute is checked on all source files and output files, meaning "
                + "that it causes a significant number of invocations of the getxattr() system call.")
    )
    abstract val unixDigestHashAttributeName: String?

    @get:com.google.devtools.common.options.Option(
        name = "autodetect_server_javabase",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.LOSES_INCREMENTAL_STATE],
        help = ("When --noautodetect_server_javabase is passed, Bazel does not fall back to the local "
                + "JDK for running the bazel server and instead exits.")
    )
    abstract val autodetectServerJavabase: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_cgroup_parent",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING, com.google.devtools.common.options.OptionEffectTag.EXECUTION
        ],
        valueHelp = "<path>",
        help = ("The cgroup where to start the bazel server as an absolute path. The server "
                + "process will be started in the specified cgroup for each supported controller. "
                + "For example, if the value of this flag is /build/bazel and the cpu and memory "
                + "controllers are mounted respectively on /sys/fs/cgroup/cpu and "
                + "/sys/fs/cgroup/memory, the server will be started in the cgroups "
                + "/sys/fs/cgroup/cpu/build/bazel and /sys/fs/cgroup/memory/build/bazel."
                + "It is not an error if the specified cgroup is not writable for one or more "
                + "of the controllers. This options does not have any effect on "
                + "platforms that do not support cgroups.")
    )
    abstract val cgroupParent: String?

    @get:com.google.devtools.common.options.Option(
        name = "experimental_run_in_user_cgroup",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_MONITORING, com.google.devtools.common.options.OptionEffectTag.EXECUTION
        ],
        help = ("If true, the Bazel server will be run with systemd-run, and the user will own the"
                + " cgroup. This flag only takes effect on Linux.")
    )
    abstract val runInUserCgroup: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "extra_classpath",
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION
        ],
        help = ("A colon-separated list of classpath entries to be added to the classpath of the Bazel"
                + " server.")
    )
    abstract val extraClasspath: String?
}

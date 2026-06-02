// Copyright 2024 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.sandbox.cgroups

import com.google.devtools.build.lib.sandbox.cgroups.proto.CgroupsInfoProtos.CgroupControllerInfo

/**
 * This class creates and exposes a virtual cgroup for the Bazel process and allows creating child
 * cgroups. Resources are exposed as [Controller]s, each representing a subsystem within the
 * virtual cgroup and that could in theory belong to different real cgroups.
 */
@AutoValue
abstract class VirtualCgroup : Cgroup {
    abstract fun cpu(): Cpu?

    abstract fun memory(): com.google.devtools.build.lib.sandbox.cgroups.controller.Controller.Memory?

    abstract override fun paths(): com.google.common.collect.ImmutableSet<java.nio.file.Path>?

    abstract fun cgroupsInfo(): CgroupsInfo?

    private val children: java.util.Queue<VirtualCgroup?> = ConcurrentLinkedQueue<VirtualCgroup?>()

    override fun destroy() {
        this.children.forEach(java.util.function.Consumer { obj: VirtualCgroup? -> obj!!.destroy() })
        this.paths().stream().map<java.io.File?> { obj: java.nio.file.Path? -> obj.toFile() }
            .filter { obj: java.io.File? -> obj.exists() }.forEach { obj: java.io.File? -> obj.delete() }
    }

    @Throws(IOException::class)
    fun createChild(name: String?): VirtualCgroup {
        return createChild(name, null)
    }

    @Throws(IOException::class)
    private fun createChild(name: String?, cgroupsInfo: CgroupsInfo?): VirtualCgroup {
        var cpu: Cpu? = null
        var memory: com.google.devtools.build.lib.sandbox.cgroups.controller.Controller.Memory? = null
        val paths: com.google.common.collect.ImmutableSet.Builder<java.nio.file.Path?> =
            com.google.common.collect.ImmutableSet.builder<java.nio.file.Path?>()
        if (memory() != null) {
            memory = memory().child(name)
            paths.add(memory.getPath())
        }
        if (cpu() != null) {
            cpu = cpu().child(name)
            paths.add(cpu.getPath())
        }
        // We don't create the CgroupsInfo for the child cgroups. Theoretically, when the root cgroup is
        // user-writable, then the child cgroups are also user-writable. We can revisit this if we
        // observe strange behaviors in the future.
        val child: VirtualCgroup = AutoValue_VirtualCgroup(cpu, memory, paths.build(), cgroupsInfo)
        this.children.add(child)
        return child
    }

    @Throws(IOException::class)
    override fun addProcess(pid: Long) {
        val pidStr = pid.toString()
        for (path in paths()) {
            val procs: java.io.File = path.resolve("cgroup.procs").toFile()
            val sink: com.google.common.io.CharSink =
                com.google.common.io.Files.asCharSink(procs, java.nio.charset.StandardCharsets.UTF_8)
            sink.write(pidStr)
        }
    }

    val memoryUsageInKb: Int
        get() {
            try {
                return if (memory() == null) 0 else (memory().getUsageInBytes() / 1024).toInt()
            } catch (e: IOException) {
                return 0
            }
        }

    override fun exists(): Boolean {
        return memory() != null && memory().exists()
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
        private val PROC_SELF_MOUNTS_PATH: java.io.File = java.io.File("/proc/self/mounts")
        private val PROC_SELF_CGROUP_PATH: java.io.File = java.io.File("/proc/self/cgroup")

        private val instanceSupplier: java.util.function.Supplier<VirtualCgroup?> =
            com.google.common.base.Suppliers.memoize<VirtualCgroup?>(com.google.common.base.Supplier { createInstance() })

        @kotlin.jvm.JvmStatic
        val instance: VirtualCgroup?
            get() = instanceSupplier.get()

        private fun createInstance(): VirtualCgroup {
            if (com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.LINUX) {
                // Cgroups are only supported on Linux.
                return NULL
            }
            try {
                val cgroupRoot = createRoot()
                // In our implementation, only the root cgroup holds the cgroupsInfo object. If we want to
                // create a node to be the new root of our cgroup, we need to pass down the cgroupsInfo,
                // otherwise it'll be null.
                val instance =
                    cgroupRoot.createChild(
                        "blaze_" + java.lang.ProcessHandle.current().pid() + "_spawns.slice", cgroupRoot.cgroupsInfo()
                    )
                java.lang.Runtime.getRuntime()
                    .addShutdownHook(java.lang.Thread(java.lang.Runnable { deleteInstance() }))
                return instance
            } catch (e: IOException) {
                logger.atInfo().withCause(e).log("Failed to create root cgroup")
                return NULL
            }
        }

        fun deleteInstance() {
            instance!!.destroy()
        }

        @kotlin.jvm.JvmField
        val NULL: VirtualCgroup =
            AutoValue_VirtualCgroup(null, null, com.google.common.collect.ImmutableSet.of<E?>(), null)

        @kotlin.jvm.JvmOverloads
        @Throws(IOException::class)
        fun createRoot(
            procMounts: java.io.File? = PROC_SELF_MOUNTS_PATH,
            procCgroup: java.io.File? = PROC_SELF_CGROUP_PATH
        ): VirtualCgroup {
            return Companion.createRoot(
                Mount.Companion.parse(procMounts),
                com.google.devtools.build.lib.sandbox.cgroups.Hierarchy.Companion.parse(procCgroup)
            )
        }

        @Throws(IOException::class)
        fun createRoot(
            mounts: MutableList<Mount>,
            hierarchies: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.sandbox.cgroups.Hierarchy?>
        ): VirtualCgroup {
            return Companion.createRoot(
                mounts,
                hierarchies.stream()
                    .flatMap<MutableMap.MutableEntry<String?, com.google.devtools.build.lib.sandbox.cgroups.Hierarchy?>?> { h: com.google.devtools.build.lib.sandbox.cgroups.Hierarchy? ->
                        h.controllers.stream()
                            .map<MutableMap.MutableEntry<String?, com.google.devtools.build.lib.sandbox.cgroups.Hierarchy?>?> { c: String? ->
                                java.util.Map.entry<String?, com.google.devtools.build.lib.sandbox.cgroups.Hierarchy?>(
                                    c,
                                    h
                                )
                            }
                    }  // For cgroup v2, there are no controllers specified in the proc/pid/cgroup file
                    // So the keep will be empty and unique. For cgroup v1, there could potentially
                    // be multiple mounting points for the same controller, but they represent a
                    // "view of the same hierarchy" so it is ok to just keep one.
                    // Ref. https://man7.org/linux/man-pages/man7/cgroups.7.html
                    .collect(
                        com.google.common.collect.ImmutableMap.toImmutableMap<MutableMap.MutableEntry<String?, com.google.devtools.build.lib.sandbox.cgroups.Hierarchy?>?, String?, com.google.devtools.build.lib.sandbox.cgroups.Hierarchy?>(
                            java.util.function.Function { java.util.Map.Entry.key },
                            java.util.function.Function { java.util.Map.Entry.value })
                    )
            )
        }

        @Throws(IOException::class)
        private fun createRoot(
            mounts: MutableList<Mount>,
            hierarchies: MutableMap<String?, com.google.devtools.build.lib.sandbox.cgroups.Hierarchy?>
        ): VirtualCgroup {
            var memory: com.google.devtools.build.lib.sandbox.cgroups.controller.Controller.Memory? = null
            var cpu: Cpu? = null
            val paths: com.google.common.collect.ImmutableSet.Builder<java.nio.file.Path?> =
                com.google.common.collect.ImmutableSet.builder<java.nio.file.Path?>()
            val cgroupsInfo: CgroupsInfo.Builder = CgroupsInfo.newBuilder()

            for (m in mounts) {
                if (memory != null && cpu != null) {
                    break
                }

                if (m.isV2()) {
                    val h: com.google.devtools.build.lib.sandbox.cgroups.Hierarchy? = hierarchies.get("")
                    if (h == null) {
                        continue
                    }
                    var cgroup: java.nio.file.Path = m.path.resolve(java.nio.file.Path.of("/").relativize(h.path))
                    logger.atInfo().log("Found cgroup v2 at %s", cgroup)
                    if (cgroup != m.path) {
                        // Because of the "no internal processes" rule, it is not possible to
                        // create a non-empty child cgroups on non-root cgroups with member processes
                        // Instead, we go up one level in the hierarchy and declare a sibling.
                        cgroup = cgroup.getParent()
                        logger.atInfo().log(
                            "Due to the no internal processes rule, using cgroup %s instead.", cgroup
                        )
                    }
                    val isCgroupWritable: Boolean = cgroup.toFile().canWrite()
                    cgroupsInfo.addCgroupControllers(
                        CgroupControllerInfo.newBuilder()
                            .setPath(cgroup.toString())
                            .setIsWritable(isCgroupWritable)
                            .setVersion(CgroupControllerInfo.Version.V2)
                            .build()
                    )
                    if (!isCgroupWritable) {
                        logger.atInfo().log("Found non-writable cgroup v2 at %s", cgroup)
                        continue
                    }
                    paths.add(cgroup)

                    java.util.Scanner(
                        cgroup.resolve("cgroup.controllers").toFile(),
                        java.nio.charset.StandardCharsets.UTF_8
                    ).use { scanner ->
                        while (scanner.hasNext()) {
                            when (scanner.next()) {
                                "memory" -> {
                                    if (memory != null) {
                                        continue
                                    }
                                    logger.atFine().log("Found v2 memory controller at %s", cgroup)
                                    memory = UnifiedMemory(cgroup)
                                }

                                "cpu" -> {
                                    if (cpu != null) {
                                        continue
                                    }
                                    logger.atFine().log("Found v2 cpu controller at %s", cgroup)
                                    cpu = UnifiedCpu(cgroup)
                                }

                                else -> {}
                            }
                        }
                    }
                } else {
                    for (opt in m.opts) {
                        val h: com.google.devtools.build.lib.sandbox.cgroups.Hierarchy? = hierarchies.get(opt)
                        if (h == null) {
                            continue
                        }
                        val cgroup: java.nio.file.Path = m.path.resolve(java.nio.file.Path.of("/").relativize(h.path))
                        val isCgroupWritable: Boolean = cgroup.toFile().canWrite()
                        cgroupsInfo.addCgroupControllers(
                            CgroupControllerInfo.newBuilder()
                                .setPath(cgroup.toString())
                                .setIsWritable(isCgroupWritable)
                                .setVersion(CgroupControllerInfo.Version.V1)
                                .build()
                        )
                        if (!isCgroupWritable) {
                            logger.atInfo().log("Found non-writable cgroup v1 at %s", cgroup)
                            continue
                        }
                        paths.add(cgroup)

                        when (opt) {
                            "memory" -> {
                                if (memory != null) {
                                    continue
                                }
                                logger.atFine().log("Found v1 memory controller at %s", cgroup)
                                memory = LegacyMemory(cgroup)
                            }

                            "cpu" -> {
                                if (cpu != null) {
                                    continue
                                }
                                logger.atFine().log("Found v1 cpu controller at %s", cgroup)
                                cpu = LegacyCpu(cgroup)
                            }

                            else -> {}
                        }
                    }
                }
            }

            return AutoValue_VirtualCgroup(cpu, memory, paths.build(), cgroupsInfo.build())
        }

        @com.google.common.annotations.VisibleForTesting
        fun create(
            cpu: Cpu?,
            memory: com.google.devtools.build.lib.sandbox.cgroups.controller.Controller.Memory?,
            paths: com.google.common.collect.ImmutableSet<java.nio.file.Path?>?,
            cgroupsInfo: CgroupsInfo?
        ): VirtualCgroup {
            return AutoValue_VirtualCgroup(cpu, memory, paths, cgroupsInfo)
        }
    }
}

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
package com.google.devtools.build.lib.bazel.debug

import com.google.devtools.build.lib.bazel.debug.proto.WorkspaceLogProtos

/** An event to record events happening during workspace rule resolution  */
class WorkspaceRuleEvent private constructor(event: WorkspaceLogProtos.WorkspaceEvent) :
    com.google.devtools.build.lib.events.ExtendedEventHandler.Postable {
    var event: WorkspaceLogProtos.WorkspaceEvent

    fun getLogEvent(): WorkspaceLogProtos.WorkspaceEvent {
        return event
    }

    init {
        this.event = event
    }

    /**
     * @return a message to log for this event
     */
    fun logMessage(): String {
        return event.toString()
    }

    companion object {
        /** Creates a new WorkspaceRuleEvent for an execution event.  */
        fun newExecuteEvent(
            args: Iterable<String?>,
            timeout: Int,
            commonEnvironment: MutableMap<String?, String?>?,
            customEnvironment: MutableMap<String?, String?>?,
            outputDirectory: String?,
            quiet: Boolean,
            context: String?,
            location: net.starlark.java.syntax.Location?
        ): WorkspaceRuleEvent {
            var e: WorkspaceLogProtos.ExecuteEvent.Builder =
                WorkspaceLogProtos.ExecuteEvent.newBuilder()
                    .setTimeoutSeconds(timeout)
                    .setOutputDirectory(outputDirectory)
                    .setQuiet(quiet)
            if (commonEnvironment != null) {
                e = e.putAllEnvironment(commonEnvironment)
            }
            if (customEnvironment != null) {
                e = e.putAllEnvironment(customEnvironment)
            }

            for (a in args) {
                e.addArguments(a)
            }

            var result: WorkspaceLogProtos.WorkspaceEvent.Builder =
                WorkspaceLogProtos.WorkspaceEvent.newBuilder()
            result = result.setExecuteEvent(e.build())
            if (location != null) {
                result = result.setLocation(location.toString())
            }
            if (context != null) {
                result = result.setContext(context)
            }
            return WorkspaceRuleEvent(result.build())
        }

        /** Creates a new WorkspaceRuleEvent for a download event.  */
        fun newDownloadEvent(
            urls: MutableList<java.net.URI>,
            output: String?,
            sha256: String?,
            integrity: String?,
            executable: Boolean?,
            context: String?,
            location: net.starlark.java.syntax.Location?
        ): WorkspaceRuleEvent {
            val e: WorkspaceLogProtos.DownloadEvent.Builder =
                WorkspaceLogProtos.DownloadEvent.newBuilder()
                    .setOutput(output)
                    .setSha256(sha256)
                    .setIntegrity(integrity)
                    .setExecutable(executable)
            for (u in urls) {
                e.addUrl(u.toString())
            }

            var result: WorkspaceLogProtos.WorkspaceEvent.Builder =
                WorkspaceLogProtos.WorkspaceEvent.newBuilder()
            result = result.setDownloadEvent(e.build())
            if (location != null) {
                result = result.setLocation(location.toString())
            }
            if (context != null) {
                result = result.setContext(context)
            }
            return WorkspaceRuleEvent(result.build())
        }

        /** Creates a new WorkspaceRuleEvent for an extract event.  */
        fun newExtractEvent(
            archive: String?,
            output: String?,
            stripPrefix: String?,
            renameFiles: MutableMap<String?, String?>?,
            context: String?,
            location: net.starlark.java.syntax.Location?
        ): WorkspaceRuleEvent {
            val e: ExtractEvent? =
                WorkspaceLogProtos.ExtractEvent.newBuilder()
                    .setArchive(archive)
                    .setOutput(output)
                    .setStripPrefix(stripPrefix)
                    .putAllRenameFiles(renameFiles)
                    .build()

            var result: WorkspaceLogProtos.WorkspaceEvent.Builder =
                WorkspaceLogProtos.WorkspaceEvent.newBuilder()
            result = result.setExtractEvent(e)
            if (location != null) {
                result = result.setLocation(location.toString())
            }
            if (context != null) {
                result = result.setContext(context)
            }
            return WorkspaceRuleEvent(result.build())
        }

        /** Creates a new WorkspaceRuleEvent for a download and extract event.  */
        fun newDownloadAndExtractEvent(
            urls: MutableList<java.net.URI>,
            output: String?,
            sha256: String?,
            integrity: String?,
            type: String?,
            stripPrefix: String?,
            renameFiles: MutableMap<String?, String?>?,
            context: String?,
            location: net.starlark.java.syntax.Location?
        ): WorkspaceRuleEvent {
            val e: WorkspaceLogProtos.DownloadAndExtractEvent.Builder =
                WorkspaceLogProtos.DownloadAndExtractEvent.newBuilder()
                    .setOutput(output)
                    .setSha256(sha256)
                    .setIntegrity(integrity)
                    .setType(type)
                    .setStripPrefix(stripPrefix)
                    .putAllRenameFiles(renameFiles)
            for (u in urls) {
                e.addUrl(u.toString())
            }

            var result: WorkspaceLogProtos.WorkspaceEvent.Builder =
                WorkspaceLogProtos.WorkspaceEvent.newBuilder()
            result = result.setDownloadAndExtractEvent(e.build())
            if (location != null) {
                result = result.setLocation(location.toString())
            }
            if (context != null) {
                result = result.setContext(context)
            }
            return WorkspaceRuleEvent(result.build())
        }

        /** Creates a new WorkspaceRuleEvent for a file event.  */
        fun newFileEvent(
            path: String?,
            content: String?,
            executable: Boolean,
            context: String?,
            location: net.starlark.java.syntax.Location?
        ): WorkspaceRuleEvent {
            val e: FileEvent? =
                WorkspaceLogProtos.FileEvent.newBuilder()
                    .setPath(path)
                    .setContent(content)
                    .setExecutable(executable)
                    .build()

            var result: WorkspaceLogProtos.WorkspaceEvent.Builder =
                WorkspaceLogProtos.WorkspaceEvent.newBuilder()
            result = result.setFileEvent(e)
            if (location != null) {
                result = result.setLocation(location.toString())
            }
            if (context != null) {
                result = result.setContext(context)
            }
            return WorkspaceRuleEvent(result.build())
        }

        /** Creates a new WorkspaceRuleEvent for a file read event.  */
        fun newReadEvent(
            path: String?,
            context: String?,
            location: net.starlark.java.syntax.Location?
        ): WorkspaceRuleEvent {
            val e: WorkspaceLogProtos.ReadEvent? =
                WorkspaceLogProtos.ReadEvent.newBuilder().setPath(path).build()

            var result: WorkspaceLogProtos.WorkspaceEvent.Builder =
                WorkspaceLogProtos.WorkspaceEvent.newBuilder()
            result = result.setReadEvent(e)
            if (location != null) {
                result = result.setLocation(location.toString())
            }
            if (context != null) {
                result = result.setContext(context)
            }
            return WorkspaceRuleEvent(result.build())
        }

        /** Creates a new WorkspaceRuleEvent for a file read event.  */
        fun newDeleteEvent(
            path: String?,
            context: String?,
            location: net.starlark.java.syntax.Location?
        ): WorkspaceRuleEvent {
            val e: WorkspaceLogProtos.DeleteEvent? =
                WorkspaceLogProtos.DeleteEvent.newBuilder().setPath(path).build()

            var result: WorkspaceLogProtos.WorkspaceEvent.Builder =
                WorkspaceLogProtos.WorkspaceEvent.newBuilder()
            result = result.setDeleteEvent(e)
            if (location != null) {
                result = result.setLocation(location.toString())
            }
            if (context != null) {
                result = result.setContext(context)
            }
            return WorkspaceRuleEvent(result.build())
        }

        /** Creates a new WorkspaceRuleEvent for a patch event.  */
        fun newPatchEvent(
            patchFile: String?, strip: Int, context: String?, location: net.starlark.java.syntax.Location?
        ): WorkspaceRuleEvent {
            val e: WorkspaceLogProtos.PatchEvent? =
                WorkspaceLogProtos.PatchEvent.newBuilder().setPatchFile(patchFile).setStrip(strip).build()

            var result: WorkspaceLogProtos.WorkspaceEvent.Builder =
                WorkspaceLogProtos.WorkspaceEvent.newBuilder()
            result = result.setPatchEvent(e)
            if (location != null) {
                result = result.setLocation(location.toString())
            }
            if (context != null) {
                result = result.setContext(context)
            }
            return WorkspaceRuleEvent(result.build())
        }

        /** Creates a new WorkspaceRuleEvent for an os event.  */
        fun newOsEvent(context: String?, location: net.starlark.java.syntax.Location?): WorkspaceRuleEvent {
            val e: OsEvent? = WorkspaceLogProtos.OsEvent.getDefaultInstance()

            var result: WorkspaceLogProtos.WorkspaceEvent.Builder =
                WorkspaceLogProtos.WorkspaceEvent.newBuilder()
            result = result.setOsEvent(e)
            if (location != null) {
                result = result.setLocation(location.toString())
            }
            if (context != null) {
                result = result.setContext(context)
            }
            return WorkspaceRuleEvent(result.build())
        }

        /** Creates a new WorkspaceRuleEvent for a rename event.  */
        fun newRenameEvent(
            src: String?, dst: String?, context: String?, location: net.starlark.java.syntax.Location?
        ): WorkspaceRuleEvent {
            val e: RenameEvent? = WorkspaceLogProtos.RenameEvent.newBuilder().setSrc(src).setDst(dst).build()

            var result: WorkspaceLogProtos.WorkspaceEvent.Builder =
                WorkspaceLogProtos.WorkspaceEvent.newBuilder()
            result = result.setRenameEvent(e)
            if (location != null) {
                result = result.setLocation(location.toString())
            }
            if (context != null) {
                result = result.setContext(context)
            }
            return WorkspaceRuleEvent(result.build())
        }

        /** Creates a new WorkspaceRuleEvent for a symlink event.  */
        fun newSymlinkEvent(
            from: String?, to: String?, context: String?, location: net.starlark.java.syntax.Location?
        ): WorkspaceRuleEvent {
            val e: SymlinkEvent? =
                WorkspaceLogProtos.SymlinkEvent.newBuilder().setTarget(from).setPath(to).build()

            var result: WorkspaceLogProtos.WorkspaceEvent.Builder =
                WorkspaceLogProtos.WorkspaceEvent.newBuilder()
            result = result.setSymlinkEvent(e)
            if (location != null) {
                result = result.setLocation(location.toString())
            }
            if (context != null) {
                result = result.setContext(context)
            }
            return WorkspaceRuleEvent(result.build())
        }

        /** Creates a new WorkspaceRuleEvent for a template event.  */
        fun newTemplateEvent(
            path: String?,
            template: String?,
            substitutions: MutableMap<String?, String?>?,
            executable: Boolean,
            context: String?,
            location: net.starlark.java.syntax.Location?
        ): WorkspaceRuleEvent {
            val e: TemplateEvent? =
                WorkspaceLogProtos.TemplateEvent.newBuilder()
                    .setPath(path)
                    .setTemplate(template)
                    .putAllSubstitutions(substitutions)
                    .setExecutable(executable)
                    .build()

            var result: WorkspaceLogProtos.WorkspaceEvent.Builder =
                WorkspaceLogProtos.WorkspaceEvent.newBuilder()
            result = result.setTemplateEvent(e)
            if (location != null) {
                result = result.setLocation(location.toString())
            }
            if (context != null) {
                result = result.setContext(context)
            }
            return WorkspaceRuleEvent(result.build())
        }

        /** Creates a new WorkspaceRuleEvent for a which event.  */
        fun newWhichEvent(
            program: String?, context: String?, location: net.starlark.java.syntax.Location?
        ): WorkspaceRuleEvent {
            val e: WhichEvent? = WorkspaceLogProtos.WhichEvent.newBuilder().setProgram(program).build()

            var result: WorkspaceLogProtos.WorkspaceEvent.Builder =
                WorkspaceLogProtos.WorkspaceEvent.newBuilder()
            result = result.setWhichEvent(e)
            if (location != null) {
                result = result.setLocation(location.toString())
            }
            if (context != null) {
                result = result.setContext(context)
            }
            return WorkspaceRuleEvent(result.build())
        }

        fun newLoadWasmEvent(
            modulePath: String?,
            compile: Boolean,
            allocateFn: String?,
            context: String?,
            location: net.starlark.java.syntax.Location?
        ): WorkspaceRuleEvent {
            val e: LoadWasmEvent? =
                WorkspaceLogProtos.LoadWasmEvent.newBuilder()
                    .setModulePath(modulePath)
                    .setCompile(compile)
                    .setAllocateFn(allocateFn)
                    .build()
            var result: WorkspaceLogProtos.WorkspaceEvent.Builder =
                WorkspaceLogProtos.WorkspaceEvent.newBuilder()
            result = result.setLoadWasmEvent(e)
            if (location != null) {
                result = result.setLocation(location.toString())
            }
            if (context != null) {
                result = result.setContext(context)
            }
            return WorkspaceRuleEvent(result.build())
        }

        fun newExecuteWasmEvent(
            modulePath: String?,
            function: String?,
            input: ByteArray,
            timeout: Int,
            memoryLimit: Long,
            context: String?,
            location: net.starlark.java.syntax.Location?
        ): WorkspaceRuleEvent {
            val e: ExecuteWasmEvent? =
                WorkspaceLogProtos.ExecuteWasmEvent.newBuilder()
                    .setModulePath(modulePath)
                    .setFunction(function)
                    .setInput(ByteString.copyFrom(input))
                    .setTimeoutSeconds(timeout)
                    .setMemoryLimitBytes(memoryLimit)
                    .build()
            var result: WorkspaceLogProtos.WorkspaceEvent.Builder =
                WorkspaceLogProtos.WorkspaceEvent.newBuilder()
            result = result.setExecuteWasmEvent(e)
            if (location != null) {
                result = result.setLocation(location.toString())
            }
            if (context != null) {
                result = result.setContext(context)
            }
            return WorkspaceRuleEvent(result.build())
        }
    }
}

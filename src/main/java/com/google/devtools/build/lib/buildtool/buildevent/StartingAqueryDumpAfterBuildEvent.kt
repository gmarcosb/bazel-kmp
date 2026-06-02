// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildtool.buildevent

import com.google.devtools.build.lib.buildeventstream.BuildEventArtifactUploader.UploadContext
import com.google.devtools.build.lib.events.ExtendedEventHandler
import com.google.devtools.build.lib.vfs.Path

/** Fired before the start of the aquery dump after a build.  */
class StartingAqueryDumpAfterBuildEvent : ExtendedEventHandler.Postable {
    var streamingContext: UploadContext? = null
        private set
    var localAqueryDumpPath: Path? = null
        private set
    val aqueryDumpName: String?

    constructor(streamingContext: UploadContext?, aqueryDumpName: String?) {
        this.streamingContext = streamingContext
        this.aqueryDumpName = aqueryDumpName
    }

    constructor(localAqueryDumpPath: Path?, aqueryDumpName: String?) {
        this.localAqueryDumpPath = localAqueryDumpPath
        this.aqueryDumpName = aqueryDumpName
    }
}

// Copyright 2019 The Bazel Authors. All rights reserved.
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
//
package com.google.devtools.build.lib.vfs

import com.google.devtools.build.lib.vfs.PathFragment
import com.google.devtools.build.lib.vfs.PathTransformingDelegateFileSystem

/**
 * A file system that delegates all operations to `delegateFs` under the hood.
 * 
 * 
 * This is helpful when wanting to implement a file system on top of another file system as it
 * allows code patterns like: `
 * @Override
 * protected long getFileSize(PathFragment path, boolean followSymlinks) throws IOException {
 * if (!someCondition) {
 * return super.getFileSize(path, followSymlinks);
 * }
 * return rpc.getFileSize(path, followSymlinks);
 * }
` * 
 * 
 * 
 * The implementation uses [PathTransformingDelegateFileSystem] with identity path
 * transformations ([ toDelegatePath][PathTransformingDelegateFileSystem.toDelegatePath] and [ fromDelegatePath][PathTransformingDelegateFileSystem.fromDelegatePath]).
 */
abstract class DelegateFileSystem(delegateFs: com.google.devtools.build.lib.vfs.FileSystem) :
    PathTransformingDelegateFileSystem(delegateFs) {
    override fun toDelegatePath(path: PathFragment?): PathFragment? {
        return path
    }

    override fun fromDelegatePath(delegatePath: PathFragment?): PathFragment? {
        return delegatePath
    }
}

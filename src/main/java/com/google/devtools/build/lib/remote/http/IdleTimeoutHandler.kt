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
package com.google.devtools.build.lib.remote.http

import io.netty.channel.ChannelHandlerContext
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import io.netty.handler.timeout.TimeoutException
import java.util.concurrent.TimeUnit

/**
 * Triggers [IdleState.ALL_IDLE] events, when no reads or writes were performed for a period
 * of time.
 */
class IdleTimeoutHandler(timeoutSeconds: Long, private val timeoutException: TimeoutException?) :
    IdleStateHandler( /* readerIdleTime= */0,  /* writerIdleTime= */0, timeoutSeconds, TimeUnit.SECONDS) {
    private var closed = false

    @Throws(Exception::class)
    override fun channelIdle(ctx: ChannelHandlerContext, evt: IdleStateEvent) {
        assert(evt.state() == IdleState.ALL_IDLE)
        if (!closed) {
            ctx.fireExceptionCaught(timeoutException)
            ctx.close()
            closed = true
        }
    }
}

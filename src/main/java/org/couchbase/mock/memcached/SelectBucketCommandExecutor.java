/*
 * Copyright 2017 Couchbase, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package org.couchbase.mock.memcached;

import org.couchbase.mock.memcached.protocol.BinaryCommand;
import org.couchbase.mock.memcached.protocol.BinaryResponse;
import org.couchbase.mock.memcached.protocol.BinarySelectBucketCommand;
import org.couchbase.mock.memcached.protocol.ErrorCode;

/**
 * Created by mnunberg on 3/3/17.
 */
public class SelectBucketCommandExecutor implements CommandExecutor {
    @Override
    public void execute(BinaryCommand cmdBase, MemcachedServer server, MemcachedConnection client) {
        BinarySelectBucketCommand cmd = (BinarySelectBucketCommand)cmdBase;
        if (!cmd.getKey().equals(server.getBucket().getName())) {
            client.sendResponse(new BinaryResponse(cmd, ErrorCode.EACCESS));
        } else {
            client.sendResponse(new BinaryResponse(cmd, ErrorCode.SUCCESS));
        }
    }
}

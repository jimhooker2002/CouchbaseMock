/*
 * Copyright 2015 Couchbase, Inc.
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

import org.couchbase.mock.memcached.SubdocCommandExecutor.ResultInfo;
import org.couchbase.mock.memcached.client.CommandBuilder;
import org.couchbase.mock.memcached.protocol.*;
import org.couchbase.mock.subdoc.Operation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class SubdocMultiCommandExecutor implements CommandExecutor {
    static class SpecResult {
        final int index;
        final ErrorCode ec;
        final String value;
        SpecResult(int index, ErrorCode ec) {
            this.index = index;
            this.ec = ec;
            this.value = null;
        }
        SpecResult(int index, String value) {
            this.index = index;
            this.value = value;
            this.ec = ErrorCode.SUCCESS;
        }
    }

    static class ExecutorContext {
        final List<SpecResult> results;
        final List<BinarySubdocMultiCommand.MultiSpec> specs;
        final BinarySubdocMultiCommand command;
        final MemcachedConnection client;
        final Item existing;
        final VBucketStore cache;

        // True if we've encountered at least *ONE* extended attribute in the spec to create.
        // This tells us whether if the xattribute is simply "{}" to write it or not.
        boolean hasXattrSpec;
        boolean needCreate;
        String currentDoc;
        String currentAttrs;

        boolean isMutator() {
            return command.getComCode() == CommandCode.SUBDOC_MULTI_MUTATION;
        }

        ExecutorContext(
                BinaryCommand cmd, MemcachedConnection client, Item existing, VBucketStore cache, boolean needCreate) {
            this.existing = existing;
            currentDoc = new String(existing.getValue());
            currentAttrs = new String(existing.getValue() == null ? "{}".getBytes() : existing.getValue());
            this.command = (BinarySubdocMultiCommand)cmd;
            this.client = client;
            this.specs = command.getLookupSpecs();
            this.cache = cache;
            this.needCreate = needCreate;
            this.hasXattrSpec = false;
            results = new ArrayList<SpecResult>();
        }

        private boolean handleLookupSpec(BinarySubdocMultiCommand.MultiSpec spec, int index) {
            Operation op = spec.getOp();
            if (op == null) {
                results.add(new SpecResult(index, ErrorCode.UNKNOWN_COMMAND));
                return true;
            }
            if (!op.isLookup()) {
                client.sendResponse(new BinaryResponse(command, ErrorCode.SUBDOC_INVALID_COMBO));
                return false;
            }
            ResultInfo rsi = SubdocCommandExecutor.executeSubdocLookup(op, currentDoc, spec.getPath());
            switch (rsi.getStatus()) {
                case SUCCESS:
                    if (op.returnsMatch()) {
                        results.add(new SpecResult(index, rsi.getMatchString()));
                    } else {
                        results.add(new SpecResult(index, ErrorCode.SUCCESS));
                    }
                    return true;
                case SUBDOC_DOC_NOTJSON:
                case SUBDOC_DOC_E2DEEP:
                    client.sendResponse(new BinaryResponse(command, rsi.getStatus()));
                    return false;
                default:
                    results.add(new SpecResult(index, rsi.getStatus()));
                    return true;
            }
        }

        private boolean sendMutationError(ErrorCode ec, int index) {
            ByteBuffer bb = ByteBuffer.allocate(3);
            bb.put((byte)index);
            bb.putShort(ec.value());
            ErrorCode topLevelRc;
            if (ec == ErrorCode.SUBDOC_INVALID_COMBO) {
                topLevelRc = ec;
            } else {
                topLevelRc = ErrorCode.SUBDOC_MULTI_FAILURE;
            }
            BinaryResponse br = BinaryResponse.createWithValue(topLevelRc, command, bb.array(), 0);
            client.sendResponse(br);
            return false;
        }

        private class MutationError extends Exception {
            ErrorCode code;
            MutationError(ErrorCode ec) {
                code = ec;
            }
        }

        private ResultInfo handleMutationSpecInner(Operation op, String input,
                                                   BinarySubdocMultiMutationCommand.MultiSpec spec)
                throws MutationError {

            ResultInfo rsi = SubdocCommandExecutor.executeSubdocOperation(op, input, spec.getPath(),
                    spec.getValue(), spec.getFlags());
            if (rsi.getStatus() != ErrorCode.SUCCESS) {
                throw new MutationError(rsi.getStatus());
            }
            return rsi;
        }

        private boolean handleMutationSpec(BinarySubdocMultiCommand.MultiSpec spec, int index) {
            Operation op = spec.getOp();

            if (op == null) {
                return sendMutationError(ErrorCode.UNKNOWN_COMMAND, index);
            }

            if (!op.isMutator()) {
                return sendMutationError(ErrorCode.SUBDOC_INVALID_COMBO, index);
            }

            boolean isXattr = (spec.getFlags() & BinarySubdocCommand.FLAG_XATTR_PATH) != 0;
            ResultInfo rsi;
            try {
                if (isXattr) {
                    rsi = handleMutationSpecInner(op, currentAttrs, spec);
                    currentAttrs = rsi.getNewDocString();
                    hasXattrSpec = true;
                } else {
                    rsi = handleMutationSpecInner(op, currentDoc, spec);
                    currentDoc = rsi.getNewDocString();
                }
            } catch (MutationError ex) {
                return sendMutationError(ex.code, index);
            }

            if (op.returnsMatch()) {
                results.add(new SpecResult(index, rsi.getMatchString()));
            }

            return true;
        }

        void execute() {
            for (int i = 0; i < specs.size(); i++) {
                BinarySubdocMultiCommand.MultiSpec spec = specs.get(i);
                boolean result;
                if (isMutator()) {
                    result = handleMutationSpec(spec, i);
                } else {
                    result = handleLookupSpec(spec, i);
                }
                if (!result) {
                    // Assume response was sent.
                    return;
                }
            }

            if (isMutator()) {
                MutationInfoWriter miw = client.getMutinfoWriter();
                byte[] newXattrs;
                if (hasXattrSpec) {
                    newXattrs = currentAttrs.getBytes();
                } else if (needCreate) {
                    newXattrs = null;
                } else {
                    newXattrs = existing.getXattr();
                }
                Item newItem = new Item(
                        existing.getKeySpec(),
                        existing.getFlags(),
                        command.getNewExpiry(existing.getExpiryTime()),
                        currentDoc.getBytes(),
                        newXattrs,
                        command.getCas());

                MutationStatus ms;
                if (needCreate) {
                    needCreate = false;
                    ms = cache.add(newItem);
                    if (ms.getStatus() == ErrorCode.KEY_EEXISTS) {
                        results.clear();
                        execute();
                        return;
                    }
                } else {
                    ms = cache.replace(newItem);
                }

                ByteArrayOutputStream bao = new ByteArrayOutputStream();
                for (SpecResult result : results) {
                    int specLen = 3;

                    if (result.ec == ErrorCode.SUCCESS) {
                        specLen += 4;
                        specLen += result.value.length();
                    }

                    ByteBuffer bb = ByteBuffer.allocate(specLen);
                    bb.put((byte)result.index);
                    bb.putShort(result.ec.value());
                    if (result.ec == ErrorCode.SUCCESS) {
                        bb.putInt(result.value.length());
                        bb.put(result.value.getBytes());
                    }
                    try {
                        bao.write(bb.array());
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                }
                client.sendResponse(new BinaryResponse(command, ms, miw, newItem.getCas(), bao.toByteArray()));
            } else {
                ByteArrayOutputStream bao = new ByteArrayOutputStream();
                boolean hasError = false;
                for (SpecResult result : results) {
                    String value = result.value;

                    if (value == null) {
                        value = "";
                    }

                    ByteBuffer bb = ByteBuffer.allocate(6 + value.length());
                    bb.putShort(result.ec.value());
                    bb.putInt(value.length());
                    bb.put(value.getBytes());
                    if (result.ec != ErrorCode.SUCCESS) {
                        hasError = true;
                    }
                    try {
                        bao.write(bb.array());
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                }
                byte[] multiPayload  = bao.toByteArray();
                ErrorCode finalEc = hasError ? ErrorCode.SUBDOC_MULTI_FAILURE : ErrorCode.SUCCESS;
                BinaryResponse resp = BinaryResponse.createWithValue(finalEc, command, multiPayload, existing.getCas());
                client.sendResponse(resp);
            }
        }
    }

    @Override
    public void execute(BinaryCommand cmd, MemcachedServer server, MemcachedConnection client) {
        VBucketStore cache = server.getCache(cmd);
        Item existing = cache.get(cmd.getKeySpec());
        ExecutorContext cx;

        if (existing == null) {
            // Not a mutation. No point in making fake documents
            if (! (cmd instanceof  BinarySubdocMultiMutationCommand)) {
                client.sendResponse(new BinaryResponse(cmd, ErrorCode.KEY_ENOENT));
                return;
            }

            BinarySubdocMultiMutationCommand mcmd = (BinarySubdocMultiMutationCommand)cmd;
            if (!mcmd.hasMkdocFlag()) {
                client.sendResponse(new BinaryResponse(cmd, ErrorCode.KEY_ENOENT));
                return;
            }

            String rootString = mcmd.getRootType();
            if (rootString == null) {
                client.sendResponse(new BinaryResponse(cmd, ErrorCode.KEY_ENOENT));
                return;
            }

            Item newItem = new Item(cmd.getKeySpec(), 0, 0, rootString.getBytes(), "{}".getBytes(), 0);
            cx = new ExecutorContext(cmd, client, newItem, cache, true);

        } else {
            cx = new ExecutorContext(cmd, client, existing, cache, false);
        }

        cx.execute();
    }
}

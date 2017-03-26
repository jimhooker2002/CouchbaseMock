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

package org.couchbase.mock;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import org.couchbase.mock.control.CommandStatus;
import org.couchbase.mock.control.MockCommand;
import org.couchbase.mock.control.handlers.GetMCPortsHandler;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * This class supports running CouchbaseMock in "embedded" mode. CouchbaseMock
 * was originally designed to run in a seperate process from the code under test.
 * Embedded mode supports running CouchbaseMock within the same process (JVM) as
 * the code under test.
 *
 * CouchbaseMockEmbedded simplifies interaction with CouchbaseMock (removes the
 * remote interaction over a socket) at the cost of having CouchbaseMock and it's
 * dependencies within the same JVM.
 */
public class CouchbaseMockEmbedded extends CouchbaseMock {

    // In embedded mode, we *could* continue to have the OOB commands
    // go over via MockClient/HarikiriMonitor. But.....
    //
    // When running in a seperate process, the client makes perfect sense.
    // When running in "embedded" mode however, it feels way more natural to just work
    // with the CouchbaseMockEmbedded object instead.

    public List<Integer> getMcPorts(String bucket) {
        List<Integer> ports = new ArrayList<Integer>();
        JsonObject request = new JsonObject();
        if (null != bucket) {
            request.addProperty("bucket", bucket);
        }
        CommandStatus response = new GetMCPortsHandler()
                .execute(this, MockCommand.Command.GET_MCPORTS, request);
        JsonObject responseJson
                = new JsonParser().parse(response.toString()).getAsJsonObject();
        if (responseJson.get("status").toString().equals("ok")) {
            JsonArray payload = responseJson.getAsJsonArray("payload");
            Type type = new TypeToken<List<Integer>>() {}.getType();
            ports = new Gson().fromJson(payload, type);
        } else {
            // FIXME - error handling goes here.
        }
        return ports;
    }

    // Methods to support the other MockClient/OOB requests
    // go here......

}

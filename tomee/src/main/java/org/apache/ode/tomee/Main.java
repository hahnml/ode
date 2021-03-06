/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ode.tomee;

import java.io.File;

public class Main {
    public static void main(String[] args) {
        String home = System.getProperty("ode.server.home");

        if(home != null) {
            final TomeeLauncher launcher = new TomeeLauncher(home);
            try {
                launcher.initialize();
                launcher.start();

                Runtime.getRuntime().addShutdownHook(new Thread() {
                    public void run() {
                        try {
                            launcher.stop();
                            System.out.println("Continer stopped");
                        } catch (final Exception e) {
                            e.printStackTrace();
                        }
                    }
                });

                launcher.deploy(new File(home,"webapps/ode"), "ode");
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            System.out.println("Cannot start the container. Kindly set system property ode.server.home");
        }
    }
}
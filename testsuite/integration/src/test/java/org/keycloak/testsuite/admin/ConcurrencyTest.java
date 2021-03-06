/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.testsuite.admin;

import org.jboss.logging.Logger;
import org.junit.Ignore;
import org.junit.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

import javax.ws.rs.core.Response;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.Assert.*;

/**
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
@Ignore
public class ConcurrencyTest extends AbstractClientTest {

    private static final Logger log = Logger.getLogger(ConcurrencyTest.class);

    private static final int DEFAULT_THREADS = 3;
    private static final int DEFAULT_ITERATIONS = 10;

    // If enabled only one request is allowed at the time. Useful for checking that test is working.
    private static final boolean SYNCHRONIZED = false;

    @Test
    public void createClient() throws Throwable {
        run(new KeycloakRunnable() {
            @Override
            public void run(Keycloak keycloak, RealmResource realm, int threadNum, int iterationNum) {
                String name = "c-" + threadNum + "-" + iterationNum;
                ClientRepresentation c = new ClientRepresentation();
                c.setClientId(name);
                Response response = realm.clients().create(c);
                String id = ApiUtil.getCreatedId(response);
                response.close();

                assertNotNull(realm.clients().get(id));
                boolean found = false;
                for (ClientRepresentation r : realm.clients().findAll()) {
                    if (r.getClientId().equals(name)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    fail("Client " + name + " not found in client list");
                }
            }
        });
    }

    @Test
    public void createRole() throws Throwable {
        run(new KeycloakRunnable() {
            @Override
            public void run(Keycloak keycloak, RealmResource realm, int threadNum, int iterationNum) {
                String name = "r-" + threadNum + "-" + iterationNum;
                RoleRepresentation r = new RoleRepresentation(name, null, false);
                realm.roles().create(r);
                assertNotNull(realm.roles().get(name).toRepresentation());
            }
        });
    }

    @Test
    public void createClientRole() throws Throwable {
        ClientRepresentation c = new ClientRepresentation();
        c.setClientId("client");
        Response response = realm.clients().create(c);
        final String clientId = ApiUtil.getCreatedId(response);
        response.close();

        run(new KeycloakRunnable() {
            @Override
            public void run(Keycloak keycloak, RealmResource realm, int threadNum, int iterationNum) {
                String name = "r-" + threadNum + "-" + iterationNum;
                RoleRepresentation r = new RoleRepresentation(name, null, false);

                ClientResource client = realm.clients().get(clientId);
                client.roles().create(r);

                assertNotNull(client.roles().get(name).toRepresentation());
            }
        });
    }

    private void run(final KeycloakRunnable runnable) throws Throwable {
        run(runnable, DEFAULT_THREADS, DEFAULT_ITERATIONS);
    }

    private void run(final KeycloakRunnable runnable, final int numThreads, final int numIterationsPerThread) throws Throwable {
        final CountDownLatch latch = new CountDownLatch(numThreads);
        final AtomicReference<Throwable> failed = new AtomicReference();
        final List<Thread> threads = new LinkedList<>();
        final Lock lock = SYNCHRONIZED ? new ReentrantLock() : null;

        for (int t = 0; t < numThreads; t++) {
            final int threadNum = t;
            Thread thread = new Thread() {
                @Override
                public void run() {
                    try {
                        if (lock != null) {
                            lock.lock();
                        }

                        Keycloak keycloak = Keycloak.getInstance("http://localhost:8081/auth", "master", "admin", "admin", org.keycloak.models.Constants.ADMIN_CLI_CLIENT_ID);
                        RealmResource realm = keycloak.realm(REALM_NAME);
                        for (int i = 0; i < numIterationsPerThread && latch.getCount() > 0; i++) {
                            log.infov("thread {0}, iteration {1}", threadNum, i);
                            runnable.run(keycloak, realm, threadNum, i);
                        }
                        latch.countDown();
                    } catch (Throwable t) {
                        failed.compareAndSet(null, t);
                        while (latch.getCount() > 0) {
                            latch.countDown();
                        }
                    } finally {
                        if (lock != null) {
                            lock.unlock();
                        }
                    }
                }
            };
            thread.start();
            threads.add(thread);
        }

        latch.await();

        for (Thread t : threads) {
            t.join();
        }

        if (failed.get() != null) {
            throw failed.get();
        }
    }

    interface KeycloakRunnable {

        void run(Keycloak keycloak, RealmResource realm, int threadNum, int iterationNum);

    }

}

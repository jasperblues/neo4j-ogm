/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with
 * separate copyright notices and license terms. Your use of the source
 * code for these subcomponents is subject to the terms and
 *  conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package org.neo4j.ogm.persistence.session;

import org.junit.*;
import org.neo4j.ogm.domain.music.Artist;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;
import org.neo4j.ogm.session.Utils;
import org.neo4j.ogm.testutil.MultiDriverTestClass;
import org.neo4j.ogm.transaction.Transaction;

import java.io.IOException;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @see Issue #130
 *
 * These tests describe the behaviour of the Http Transaction endpoint in the event that there are more
 * concurrent client requests trying to update the same object than there are web server connections available
 * to process them.
 *
 * In this scenario a situation of mutual deadlock occurs: a request would like to commit its change to
 * the shared object, but cannot obtain a server connection to process its request, while the request that would
 * like to update the shared object and so release its connection is waiting on the object to be unlocked. It is
 * when the server times out one or more of the deadlocked requests that their connections are returned to the pool
 * and other requests can make progress.
 *
 * In this test a thread tries to establish a transaction, update a shared object and commit the transaction.
 * Because there many such threads, they are randomly scheduled and their operations interleaved. Every request and
 * response to and from the server is logged. Internally, the log sequences these requests and responses on a
 * per-thread basis so each thread's entire conversation can be easily examined afterwards.
 *
 * The log demonstrates the behaviour described above: when there are more concurrent requests than available
 * server connections, some will randomly fail - either when trying to update or trying to commit. By throttling
 * the number of request threads to some value less than or equal to the number of server connections the problem
 * disappears.
 *
 * By default, the Jetty web server in Neo4j sets the connection pool size to the number of available CPU cores. In
 * the case of hyperthreaded CPUs, this number is twice the number of physical cores.
 *
 * The first of these tests illustrates the deadlock failure scenario when a lock variable is created on the node.
 * The second test modifies the query so that it also reads its own lock variable. The second test doesn't result
 * in a failure scenario.
 *
 * @author vince
 */
public class TransactionSerialisationTest extends MultiDriverTestClass {

    private Session session;

    private static final int numThreads = 20; // set to more threads than available server connections to get failure scenarios


    @Before
    public void init() throws IOException {

        SessionFactory sessionFactory = new SessionFactory("org.neo4j.ogm.domain.music");

        session = sessionFactory.openSession();
        session.purgeDatabase();

    }

    @After
    public void clearDatabase() {
        session.purgeDatabase();
    }

    @Test
    public void shouldFail() throws InterruptedException {

        TreeSet<String> log = new TreeSet();

        Artist theBeatles = new Artist("The Beatles");
        session.save(theBeatles);

        long id = theBeatles.getId();

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);

        // even though this request takes a lock on the object, it's transaction is not serialised and deadlocks occur
        String query = "MATCH (n) where id(n) = " + id + " SET n.lock = 1";

        for (int i = 0; i < numThreads; i++) {
            executor.submit(new QueryRunner(latch, query, log));
        }
        latch.await(); // pause until the count reaches 0 (all transaction have committed or been timed out and rolled back)

        // force termination of all threads
        executor.shutdownNow();

        synchronized (log) {
            for (String entry : log) {
                System.out.println(entry);
            }
        }

    }

    @Test
    public void shouldPass() throws InterruptedException {

        TreeSet<String> log = new TreeSet();

        Artist theStones = new Artist("The Rolling Stones");
        session.save(theStones);

        long id = theStones.getId();

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);

        // by forcing the request to read its own lock variable, the transactions are serialised correctly
        String query = "MATCH (n) where id(n) = " + id + " SET n.lock = n.lock + 1 REMOVE n.lock";

        for (int i = 0; i < numThreads; i++) {
            executor.submit(new QueryRunner(latch, query, log));
        }
        latch.await(); // pause until the count reaches 0 (all transaction have committed or been timed out and rolled back)

        // force termination of all threads
        executor.shutdownNow();

        synchronized (log) {
            for (String entry : log) {
                System.out.println(entry);
            }
        }

    }

    class QueryRunner implements Runnable {

        private final CountDownLatch latch;
        private final String query;
        private final TreeSet<String> log;

        public QueryRunner(CountDownLatch latch, String query, TreeSet<String> log) {
            this.query = query;
            this.latch = latch;
            this.log = log;
        }

        @Override
        public void run() {

            boolean committed = false;
            Transaction tx = null;

            try {

                tx = session.beginTransaction();

                synchronized (log) {
                    log.add(msg("0. begin transaction"));
                }

                synchronized (log) {
                    log.add(msg("1. request: " + query));
                }

                session.query(query, Utils.map());

                synchronized (log) {
                    log.add(msg("2. request ok"));
                }
                try {
                    tx.commit();
                    committed = true;
                    synchronized (log) {
                        log.add(msg("3. commit ok"));
                    }
                } catch (Exception e) {
                    synchronized (log) {
                        log.add(msg("3. commit failed " + e.getLocalizedMessage()));
                    }
                }
            } catch (Exception e) {
                synchronized (log) {
                    log.add(msg("2. request failed " + e.getLocalizedMessage()));
                }
            } finally {

                latch.countDown();

                try {
                    if (tx != null && !committed) {
                        tx.rollback();
                        log.add(msg("3. rollback ok"));
                    }
                } catch (Exception e) {
                    synchronized (log) {
                        log.add(msg("3. rollback failed " + e.getLocalizedMessage()));
                    }
                }

                synchronized (log) {
                    log.add(msg("4. ----------"));
                }
            }
        }
    }

    public static String msg(String message) {
        StringBuilder sb = new StringBuilder();
        sb.append(Thread.currentThread().getId());
        sb.append(",");
        sb.append(System.currentTimeMillis() / 1000L);
        sb.append(",");
        sb.append(message);
        return sb.toString();
    }

}

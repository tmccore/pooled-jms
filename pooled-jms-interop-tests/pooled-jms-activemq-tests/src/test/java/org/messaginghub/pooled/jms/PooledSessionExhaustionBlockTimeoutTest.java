/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.messaginghub.pooled.jms;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.junit.After;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PooledSessionExhaustionBlockTimeoutTest extends ActiveMQJmsPoolTestSupport {

    private final Logger LOG = LoggerFactory.getLogger(PooledSessionExhaustionBlockTimeoutTest.class);

    private static final String QUEUE = "FOO";
    private static final int NUM_MESSAGES = 500;

    private JmsPoolConnectionFactory pooledFactory;
    private int numReceived = 0;
    private final List<Exception> exceptionList = new ArrayList<Exception>();

    @Override
    public void setUp() throws Exception {
        super.setUp();

        pooledFactory = createPooledConnectionFactory();
        pooledFactory.setMaxConnections(1);
        pooledFactory.setBlockIfSessionPoolIsFull(true);
        pooledFactory.setBlockIfSessionPoolIsFullTimeout(500);
        pooledFactory.setMaxSessionsPerConnection(1);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        try {
            pooledFactory.stop();
        } catch (Exception ex) {
            // ignored
        }

        super.tearDown();
    }

    class TestRunner implements Runnable {

        CyclicBarrier barrier;
        CountDownLatch latch;
        TestRunner(CyclicBarrier barrier, CountDownLatch latch) {
            this.barrier = barrier;
            this.latch = latch;
        }

        @Override
        public void run() {
            try {
                barrier.await();
                sendMessages(pooledFactory);
                this.latch.countDown();
            } catch (Exception e) {
                exceptionList.add(e);
                throw new RuntimeException(e);
            }
        }
    }

    public void sendMessages(ConnectionFactory connectionFactory) throws Exception {
        for (int i = 0; i < NUM_MESSAGES; i++) {
            Connection connection = connectionFactory.createConnection();
            try {
                connection.start();

                Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                Destination destination = session.createQueue(QUEUE);
                MessageProducer producer = session.createProducer(destination);

                String msgTo = "hello";
                TextMessage message = session.createTextMessage(msgTo);
                producer.send(message);
            } finally {
                connection.close();
            }
            LOG.debug("sent " + i + " messages using " + connectionFactory.getClass());
        }
    }

    @Test(timeout = 60000)
    public void testCanExhaustSessions() throws Exception {
        final int totalMessagesExpected =  NUM_MESSAGES * 2;
        final CountDownLatch latch = new CountDownLatch(2);
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                Connection connection = null;
                try {
                    ConnectionFactory connectionFactory = new ActiveMQConnectionFactory(connectionURI);
                    connection = connectionFactory.createConnection();
                    connection.start();

                    Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                    Destination destination = session.createQueue(QUEUE);
                    MessageConsumer consumer = session.createConsumer(destination);
                    for (int i = 0; i < totalMessagesExpected; ++i) {
                        Message msg = consumer.receive(5000);
                        if (msg == null) {
                            return;
                        }
                        numReceived++;
                        if (numReceived % 20 == 0) {
                            LOG.debug("received " + numReceived + " messages ");
                            System.runFinalization();
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    if (connection != null) {
                        try {
                            connection.close();
                        } catch (JMSException ex) {}
                    }
                }
            }
        });
        thread.start();

        ExecutorService threads = Executors.newFixedThreadPool(2);
        final CyclicBarrier barrier = new CyclicBarrier(2, new Runnable() {

            @Override
            public void run() {
                LOG.trace("Starting threads to send messages!");
            }
        });

        threads.execute(new TestRunner(barrier, latch));
        threads.execute(new TestRunner(barrier, latch));

        latch.await(2, TimeUnit.SECONDS);
        thread.join();

        assertEquals(totalMessagesExpected, numReceived);
    }
}

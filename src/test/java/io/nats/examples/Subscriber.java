/*
 *  Copyright (c) 2015-2016 Apcera Inc. All rights reserved. This program and the accompanying
 *  materials are made available under the terms of the MIT License (MIT) which accompanies this
 *  distribution, and is available at http://opensource.org/licenses/MIT
 */

package io.nats.examples;


import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.MessageHandler;
import io.nats.client.Nats;
import io.nats.client.Subscription;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class Subscriber {
    static final Logger log = LoggerFactory.getLogger(Subscriber.class);

    private String url = Nats.DEFAULT_URL;
    private String subject;
    private String qgroup;
    private int count;

    static final String usageString =
            "\nUsage: java Subscriber [-s <server>] [-q <group>] <subject>\n\nOptions:\n"
                    + "    -s <url>            NATS server URLs, separated by commas (default: "
                    + Nats.DEFAULT_URL + ")\n"
                    + "    -q <name>           Queue group\n"
                    + "    -n <num>            Number of messages to receive";

    public Subscriber(String[] args) {
        parseArgs(args);
        if (subject == null) {
            usage();
        }
    }

    static void usage() {
        System.err.println(usageString);
    }

    public void run() throws Exception {
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger delivered = new AtomicInteger();

        try (final Connection nc = Nats.connect(url)) {
            // System.out.println("Connected successfully to " + cf.getNatsUrl());
            try (final Subscription sub = nc.subscribe(subject, qgroup, new MessageHandler() {
                @Override
                public void onMessage(Message m) {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    System.out.printf("[#%d] Received on [%s]: '%s'\n", delivered.incrementAndGet(),
                            m.getSubject(), m);
                    if (delivered.get() == count) {
                        done.countDown();
                    }
                }
            })) {
                Thread hook = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        System.err.println("\nCaught CTRL-C, shutting down gracefully...\n");
                        try {
                            sub.unsubscribe();
                            nc.close();
                        } catch (Exception e) {
                            log.error("Problem unsubscribing", e);
                        }
                        done.countDown();
                    }
                });
                Runtime.getRuntime().addShutdownHook(hook);
                System.out.printf("Listening on [%s]\n", subject);
                start.countDown();
                done.await();
                Runtime.getRuntime().removeShutdownHook(hook);
            }
        }
    }

    public void parseArgs(String[] args) {
        if (args == null || args.length < 1) {
            throw new IllegalArgumentException("must supply at least a subject name");
        }

        List<String> argList = new ArrayList<String>(Arrays.asList(args));

        // The last arg should be subject
        // get the subject and remove it from args
        subject = argList.remove(argList.size() - 1);

        // Anything left is flags + args
        Iterator<String> it = argList.iterator();
        while (it.hasNext()) {
            String arg = it.next();
            switch (arg) {
                case "-s":
                case "--server":
                    if (!it.hasNext()) {
                        throw new IllegalArgumentException(arg + " requires an argument");
                    }
                    it.remove();
                    url = it.next();
                    it.remove();
                    continue;
                case "-q":
                case "--qgroup":
                    if (!it.hasNext()) {
                        throw new IllegalArgumentException(arg + " requires an argument");
                    }
                    it.remove();
                    qgroup = it.next();
                    it.remove();
                    continue;
                case "-n":
                case "--count":
                    if (!it.hasNext()) {
                        throw new IllegalArgumentException(arg + " requires an argument");
                    }
                    it.remove();
                    count = Integer.parseInt(it.next());
                    it.remove();
                    continue;
                default:
                    throw new IllegalArgumentException(String.format("Unexpected token: '%s'",
                            arg));
            }
        }
    }

    /**
     * Subscribes to a subject.
     *
     * @param args the subject, cluster info, and subscription options
     */
    public static void main(String[] args) throws Exception {
        try {
            new Subscriber(args).run();
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            Subscriber.usage();
            throw e;
        }
    }
}

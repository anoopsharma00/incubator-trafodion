/**
* @@@ START COPYRIGHT @@@
*
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*   http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*
* @@@ END COPYRIGHT @@@
**/

package org.apache.hadoop.hbase.coprocessor.transactional;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class SsccTableClient7 {
    // ------ for concurrent ------
    private static CountDownLatch cdl = new CountDownLatch(2);
    private static CountDownLatch cdl2 = new CountDownLatch(2);
    private static Lock lock = new ReentrantLock();
    private static Condition t1Condition = lock.newCondition();
    private static Condition t2Condition = lock.newCondition();
    protected final Log log = LogFactory.getLog(getClass());

    private boolean first = false;
    private static int successNum = 0;

    /**
     * concurrency writes
     * 
     * @param args
     * @throws Exception
     */
    static public void main(String[] args) throws Exception {
        SsccTableClient7 cilent = new SsccTableClient7();

        cilent.concurrencyWrites1();
        cilent.concurrencyWrites2();

        System.out.println("=========================================");
        System.out.println(" ");
        System.out.println("TOTAL : 2 . SUCCESS : " + successNum + " FAILURE : " + (2 - successNum));
        System.out.println(" ");
        System.out.println("=========================================");

    }

    /**
     * main--put[v1]<br/>
     * t1-----------beginTrans---get[v1]-------------------------------------get
     * [v1]---commit<br/>
     * t2------------------------------------beginTrans---put[v2]---commit<br/>
     * main---get[v2]
     */
    private void concurrencyWrites1() {
        try {
            System.out.println("Starting SsccTableClient7: concurrencyWrites1");
            System.out.println("main--put[v1]");
            System.out
                    .println("t1-----------beginTrans---get[v1]-------------------------------------get[v1]---commit");
            System.out.println("t2------------------------------------beginTrans---put[v2]---commit");
            System.out.println("main---get[v2]");
            SsccTableClientUtils.initialize();
            putValue();

            final P2Sscc01 t1 = new P2Sscc01();
            final P2Sscc02 t2 = new P2Sscc02();

            new Thread(new Runnable() {
                @Override
                public void run() {
                    first = t1.doWork();
                    System.out.println("Sscc1 finished");
                    cdl.countDown();
                }
            }, "Sscc1").start();

            // to make sure t2 is later than t1
            try {
                Thread.sleep(1000);

            } catch (InterruptedException e1) {
                e1.printStackTrace();
            }

            new Thread(new Runnable() {
                @Override
                public void run() {
                    t2.doWork();
                    System.out.println("Sscc2 finished");
                    cdl.countDown();
                }
            }, "Sscc2").start();

            cdl.await();

            SsccTableClientUtils.testSsccBeginTransaction();
            Integer[] result = SsccTableClientUtils.testSsccGet();
            SsccTableClientUtils.testSsccCommitIfPossible();
            System.out.println("Finish SsccTableClient5: concurrencyWrites1");

            if (result.length == 1 && result[0] == 2 && first == true) {
                successNum++;
                System.out.println("=========================================");
                System.out.println(" ");
                System.out.println("SUCCESS");
                System.out.println(" ");
                System.out.println("=========================================");
            } else {
                System.out.println("=========================================");
                System.out.println(" ");
                System.out.println("FAILURE");
                System.out.println(" ");
                System.out.println("=========================================");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void putValue() throws IOException {
        SsccTableClientUtils.testSsccBeginTransaction();
        SsccTableClientUtils.testSsccPut(SsccTableClientUtils.VALUE1);
        SsccTableClientUtils.testSsccCommitIfPossible();

    }

    static class P2Sscc01 {
        protected final Log log = LogFactory.getLog(getClass());

        public boolean doWork() {
            boolean first = false;
            try {
                lock.lock();

                // start transaction
                SsccTableClientUtils.testSsccBeginTransaction();
                Integer[] r1 = SsccTableClientUtils.testSsccGet();
                if (r1.length == 1 && r1[0] == 1) {
                    first = true;
                }
                System.out
                        .println("Sscc1 had begun transaction & read v1 and waits for Sscc2 to begin transaction & write v2");

                t2Condition.signal();
                t1Condition.await();
                Integer[] r2 = SsccTableClientUtils.testSsccGet();
                if (r2.length != 1 || r2[0] != 1 || first != true) {
                    first = false;
                }
                // commit
                SsccTableClientUtils.testSsccCommitIfPossible();

            } catch (Exception e) {
                System.out.println("Error in Sscc1: ");
                e.printStackTrace();
            } finally {
                lock.unlock();
            }

            return first;
        }
    }

    static class P2Sscc02 {
        protected final Log log = LogFactory.getLog(getClass());

        public void doWork() {
            try {
                lock.lock();

                // start transaction
                SsccTableClientUtils.testSsccBeginTransaction();
                SsccTableClientUtils.testSsccPut(SsccTableClientUtils.VALUE2);
                // commit
                SsccTableClientUtils.testSsccCommitIfPossible();
                System.out.println("Sscc2 had begun transaction & wroten v2 & commited and waits for Sscc1 to commit");

            } catch (Exception e) {
                System.out.println("Error in Sscc2: ");
                e.printStackTrace();
            } finally {
                t1Condition.signal();
                lock.unlock();
            }
        }
    }

    /**
     * main--put[v1]<br/>
     * t1-----------beginTrans---get[v1]-------------------------------------get
     * [v1]---commit<br/>
     * t2------------------------------------beginTrans---put[v2]---abort<br/>
     * main---get[v1]
     */
    private void concurrencyWrites2() {
        try {
            System.out.println("Starting SsccTableClient7: concurrencyWrites2");
            System.out.println("main--put[v1]");
            System.out
                    .println("t1-----------beginTrans---get[v1]-------------------------------------get[v1]---commit");
            System.out.println("t2------------------------------------beginTrans---put[v2]---abort");
            System.out.println("main---get[v1]");
            SsccTableClientUtils.initialize();
            putValue();

            final P2Sscc11 t1 = new P2Sscc11();
            final P2Sscc12 t2 = new P2Sscc12();

            new Thread(new Runnable() {
                @Override
                public void run() {
                    first = t1.doWork();
                    System.out.println("Sscc1 finished");
                    cdl2.countDown();
                }
            }, "Sscc1").start();

            // to make sure t2 is later than t1
            try {
                Thread.sleep(1000);

            } catch (InterruptedException e1) {
                e1.printStackTrace();
            }

            new Thread(new Runnable() {
                @Override
                public void run() {
                    t2.doWork();
                    System.out.println("Sscc2 finished");
                    cdl2.countDown();
                }
            }, "Sscc2").start();

            cdl2.await();

            SsccTableClientUtils.testSsccBeginTransaction();
            Integer[] result = SsccTableClientUtils.testSsccGet();
            SsccTableClientUtils.testSsccCommitIfPossible();
            System.out.println("Finish SsccTableClient5: concurrencyWrites1");

            if (result.length == 1 && result[0] == 1 && first == true) {
                successNum++;
                System.out.println("=========================================");
                System.out.println(" ");
                System.out.println("SUCCESS");
                System.out.println(" ");
                System.out.println("=========================================");
            } else {
                System.out.println("=========================================");
                System.out.println(" ");
                System.out.println("FAILURE");
                System.out.println(" ");
                System.out.println("=========================================");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static class P2Sscc11 {
        protected final Log log = LogFactory.getLog(getClass());

        public boolean doWork() {
            boolean first = false;
            try {
                lock.lock();

                // start transaction
                SsccTableClientUtils.testSsccBeginTransaction();
                Integer[] r1 = SsccTableClientUtils.testSsccGet();
                if (r1.length == 1 && r1[0] == 1) {
                    first = true;
                }
                System.out
                        .println("Sscc1 had begun transaction & read v1 and waits for Sscc2 to begin transaction & write v2");

                t2Condition.signal();
                t1Condition.await();

                Integer[] r2 = SsccTableClientUtils.testSsccGet();
                if (r2.length != 1 || r2[0] != 1 || first != true) {
                    first = false;
                }
                // commit
                SsccTableClientUtils.testSsccCommitIfPossible();

            } catch (Exception e) {
                System.out.println("Error in Sscc1: ");
                e.printStackTrace();
            } finally {
                lock.unlock();
            }
            return first;
        }
    }

    static class P2Sscc12 {
        protected final Log log = LogFactory.getLog(getClass());

        public void doWork() {
            try {
                lock.lock();

                // start transaction
                SsccTableClientUtils.testSsccBeginTransaction();
                SsccTableClientUtils.testSsccPut(SsccTableClientUtils.VALUE2);
                // commit
                SsccTableClientUtils.testSsccAbortTransaction();
                System.out
                        .println("Sscc2 had begun transaction & wroten v2 & abort and waits for Sscc1 read v1 & commit");

            } catch (Exception e) {
                System.out.println("Error in Sscc2: ");
                e.printStackTrace();
            } finally {
                t1Condition.signal();
                lock.unlock();
            }
        }
    }

}
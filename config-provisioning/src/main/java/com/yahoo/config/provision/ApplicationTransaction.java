// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.config.provision;

import com.yahoo.transaction.Mutex;
import com.yahoo.transaction.NestedTransaction;

import java.io.Closeable;
import java.util.Objects;

/**
 * A transaction which exists while holding the application provision lock.
 *
 * @author bratseth
 */
public class ApplicationTransaction implements Closeable {

    private final ProvisionLock lock;
    private final NestedTransaction transaction;

    public ApplicationTransaction(ProvisionLock lock, NestedTransaction transaction) {
        this.lock = Objects.requireNonNull(lock);
        this.transaction = Objects.requireNonNull(transaction);
    }

    public ApplicationId application() { return lock.application(); }

    /** Returns the lock held by this */
    // Usage of this might indicate we are writing changes directly (outside of the transaction) and should probably be avoided
    public ProvisionLock lock() { return lock; }

    /** Returns the NestedTransaction of this */
    public NestedTransaction nested() { return transaction; }

    @Override
    public void close() {
        try {
            transaction.commit();
        }
        finally {
            lock.close();
        }
    }

}
